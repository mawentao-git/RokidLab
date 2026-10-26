package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LabFileOutputs
import com.rokidlab.phone.ai.LabMediaScan
import com.rokidlab.phone.ai.ProducedFileSink
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.platform.ExecResult
import com.rokidlab.phone.platform.FfmpegAddon
import com.rokidlab.phone.platform.ProotShell
import org.json.JSONObject
import java.io.File

/**
 * FfmpegToolProvider —— FFmpeg 组件（ffmpeg / ffprobe）在聊天里的**配套工具**。
 *
 * ## 为什么给 ffmpeg 包一层结构化工具，而不是让模型直接 `run_shell ffmpeg …`
 * ffmpeg 的命令行是全表最反人类的之一（几十个开关、流映射、滤镜图），模型凭记忆拼
 * 十次有八次要跑歪。本组工具把 80% 的真实需求（转格式 / 压体积 / 裁剪 / 抽音频 /
 * 探信息）收成**结构化参数**：输入输出路径 + 几个可选项，参数校验在 Kotlin 侧完成，
 * 拼装时才过 [ProotShell.shellQuote] —— 模型没有机会把任意 shell 片段塞进命令行
 * （与 [ShellToolProvider.TOOL_INSTALL] 只收包名是同一条纪律）。
 * 剩余 20%（滤镜链、多输入拼接、复杂 map）仍然走 `run_shell` 直接调 ffmpeg ——
 * 组件装上后容器 PATH 里就有 `ffmpeg` / `ffprobe`（见 [ProotShell.guestExtraPath]），
 * 两条路是同一份能力，不是两套。
 *
 * ## 路径边界：只认 `/mnt/lab` 与 `/mnt/download`
 * 与 run_shell 的挂载事实对齐（容器里只有这两个口子通向手机存储，见
 * [ProotShell] 的 GUEST_SHARE / GUEST_DOWNLOAD）。不猜 `/sdcard`、不放行相对路径、
 * 拦住 `..` 穿越 —— 校验是纯函数（[normalizeMediaPath]），单测直接锁。
 *
 * ## 风险档定档
 *  - `ffmpeg_probe` = [ToolRisk.READ_ONLY]：只读元数据，与 read_code_file 同级；
 *  - `ffmpeg_convert` = [ToolRisk.LOCAL_SIDE_EFFECT] + `sideEffect=true`：判据同 run_shell
 *    （"最坏能坏成什么"）—— 最坏是在 /mnt/lab 里产出一个大文件或半截文件，用户删掉即可，
 *    不碰系统状态、不下外网流量（输入输出都在本机）。`sideEffect=true` 是因为**重放不幂等**：
 *    失败重试会再跑一遍完整编码并覆盖输出，串行泳道 + 不自动重试是对的姿态。
 *  - 两个工具的 `contentTrust` 都是 [ToolContentTrust.UNTRUSTED_EXTERNAL]：媒体文件的
 *    元数据（容器名、编码器名）是**文件作者写进去的**，与拍照 OCR 同类 —— 结构隔离，
 *    不赌"媒体文件里不会藏话术"。
 *
 * ## 组件没装时的姿态
 * 与 run_shell 缺 rootfs 时逐字同构：**不自动下载**（51 MB 外网流量 + 用户存储只能由用户决定），
 * 如实返回引导文案，让模型转告用户去「设置 → 本机执行环境」装。绝不假装转换成功。
 */
internal object FfmpegToolProvider : ToolProvider {
    private const val TAG = "FfmpegToolProvider"

    const val TOOL_PROBE = "ffmpeg_probe"
    const val TOOL_CONVERT = "ffmpeg_convert"

    override val toolNames = setOf(TOOL_PROBE, TOOL_CONVERT)

    /** 转换默认超时：手机编码慢，给 10 分钟；再长交给 run_shell background */
    private const val DEFAULT_TIMEOUT_SEC = 600
    private const val MAX_TIMEOUT_SEC = 1800

    /** 探测超时：ffprobe 读元数据是秒级活 */
    private const val PROBE_TIMEOUT_SEC = 60L

    /** 输出文件已存在且 overwrite=false 时脚本的退出码（与 bash 侧约定，勿改） */
    private const val EXIT_OUTPUT_EXISTS = 42

    // ═══════════════════ 参数校验（纯函数，单测直接锁）═══════════════════

    /** 容器内可读写的两个挂载点前缀（与 run_shell 的口子一致，见 [ProotShell]） */
    private val MEDIA_PATH_PREFIXES = listOf("/mnt/lab/", "/mnt/download/")

    /**
     * 归一化并校验媒体路径；不合法返回 null。
     *
     * 四条规则，每条都对应一种真实攻击/事故形状：
     *  1. 必须绝对路径 —— 相对路径的 cwd 语义在两个工具间会漂；
     *  2. 路径分段里不许出现 `..` —— `/mnt/lab/../../etc/passwd` 穿越；
     *  3. 必须以两个挂载点前缀开头 —— `/mnt/labX/` 这类同前缀诈欺由 `startsWith` 天然挡住；
     *  4. 不许控制字符 —— 会带崩脚本拼接（值最终都要过 [ProotShell.shellQuote]，
     *     但引号内的换行仍能让脚本多出一行，从源头禁掉比转义更稳）。
     */
    internal fun normalizeMediaPath(raw: String): String? {
        val p = raw.trim()
        if (p.isEmpty()) return null
        if (!p.startsWith("/")) return null
        if (p.split('/').any { it == ".." }) return null
        if (MEDIA_PATH_PREFIXES.none { p.startsWith(it) }) return null
        if (p.any { it.code < 0x20 }) return null
        return p
    }

    /** 编码器名白名单字符；禁 `-` 开头（选项注入）。copy / none 是保留语义值。 */
    private val CODEC_RE = Regex("^[A-Za-z0-9_.+-]{1,40}$")

    internal fun normalizeCodec(raw: String): String? {
        val c = raw.trim()
        if (c.isEmpty()) return null
        if (c.startsWith("-")) return null
        if (!CODEC_RE.matches(c)) return null
        return c
    }

    /** `1280x720` → scale 值 `1280:720`；`720` → `-2:720`（短边 720，自动保比例取偶高） */
    internal fun normalizeResolution(raw: String): String? {
        val s = raw.trim()
        val wh = Regex("^(\\d{2,5})x(\\d{2,5})$").find(s)
        if (wh != null) return "${wh.groupValues[1]}:${wh.groupValues[2]}"
        val short = Regex("^(\\d{2,4})$").find(s)
        if (short != null) return "-2:${short.groupValues[1]}"
        return null
    }

    /** 起始/时长：`10`、`1:30`、`00:01:30`、`1:30.5`（秒 / 分:秒 / 时:分:秒，可带小数） */
    private val TIME_RE = Regex("^(\\d{1,3}:)?(\\d{1,2}:)?\\d{1,2}(\\.\\d{1,3})?$")

    internal fun normalizeTime(raw: String): String? {
        val t = raw.trim()
        if (!TIME_RE.matches(t)) return null
        return t
    }

    /** 码率：`1M` / `128k` / `2500k` / `500`（ffmpeg 语法，不带 B 后缀） */
    private val BITRATE_RE = Regex("^\\d{1,5}(\\.\\d{1,2})?[kKmMgG]?$")

    internal fun normalizeBitrate(raw: String): String? {
        val b = raw.trim()
        if (!BITRATE_RE.matches(b)) return null
        return b
    }

    // ═══════════════════ 工具声明 ═══════════════════

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_PROBE,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_ffmpeg_probe_name,
            descriptionRes = R.string.ai_tool_ffmpeg_probe_desc,
            risk = ToolRisk.READ_ONLY,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在读取媒体信息…",
            schema = toolSchema(
                name = TOOL_PROBE,
                description = "读取一个媒体文件的详细信息：容器/图片格式、时长、大小、总码率、" +
                    "每条流的编码/分辨率/帧率/采样率/声道数。**音视频与图片都支持**" +
                    "（jpg/png/webp/gif/bmp/tiff 等；图片没有时长和码率，但宽高、像素格式可用）。" +
                    "**转换或剪辑之前先探测再定参数**，不要凭文件名猜格式。输入是容器内绝对路径，" +
                    "只支持 /mnt/lab/ 与 /mnt/download/ 前缀（用户把手机文件放进「下载/Lab」后从这里读）。" +
                    "只读，不改文件。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf(
                            "type" to "string",
                            "description" to "媒体文件的容器内绝对路径，必须以 /mnt/lab/ 或 /mnt/download/ 开头。例如 /mnt/lab/视频.mp4",
                        ),
                    ),
                    "required" to listOf("path"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_CONVERT,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_ffmpeg_convert_name,
            descriptionRes = R.string.ai_tool_ffmpeg_convert_desc,
            // 定档理由见类注释：最坏是 /mnt/lab 里多/半截一个文件，不碰系统、不下流量。
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 重放不幂等（重跑=完整编码一遍并覆盖输出）⇒ 失败不重试 + 串行泳道
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在转换媒体文件…",
            schema = toolSchema(
                name = TOOL_CONVERT,
                description = "用 FFmpeg 转换/压缩/剪辑/抽取音视频，**也支持图片转格式与缩放**，" +
                    "输入输出都是容器内绝对路径" +
                    "（只支持 /mnt/lab/ 与 /mnt/download/ 前缀；输出写 /mnt/lab/ 用户在手机「下载/Lab」直接拿到）。" +
                    "典型用法：① 转格式——mp4→mp3（videoCodec=none + audioCodec=libmp3lame，输出 .mp3）、" +
                    "mp4→gif、mov→webm；② 压缩——resolution=720 + crf=28；③ 裁剪——start+duration" +
                    "（配 videoCodec=copy、audioCodec=copy 最快，不重编码）；④ 抽音频——videoCodec=none；" +
                    "⑤ 静音——audioCodec=none；" +
                    "⑥ 图片——jpg↔png↔webp 转格式（只传 input/output，扩展名决定输出格式）、" +
                    "缩放（resolution=\"1280x720\" 或 \"1280\"）、压体积（转 jpg 时降低 resolution 最有效）。" +
                    "⚠️ 图片场景**不要传 videoCodec/audioCodec/crf/fps**（这些参数不适用于单张图，会报错）；" +
                    "裁剪/旋转/翻转/水印/加字/调色等图片几何与特效操作，直接用 run_shell 调 ffmpeg（PATH 里已有）。" +
                    "⚠️ 输入**不要用 http(s) URL**（静态构建没有 DNS 解析）：" +
                    "先用 curl/wget 下到 /mnt/lab 再处理。⚠️ crf 与 videoBitrate 二选一（都传时 crf 优先）；" +
                    "crf 越大体积越小画质越低（默认 23，28 已明显压缩，35 是强压缩）。" +
                    "⚠️ videoCodec=libx264 时会自动加 -preset veryfast 提速（手机编码慢）；" +
                    "需要滤镜链/多输入/流映射等高级用法时，直接用 run_shell 调 ffmpeg（PATH 里已有）。" +
                    "⚠️ overwrite 默认 true：输出文件已存在会被覆盖。⚠️ 长任务（超过 timeoutSec）" +
                    "改用 run_shell 并传 background=true 跑 ffmpeg，再用 read_run_output 看进度。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "input" to mapOf(
                            "type" to "string",
                            "description" to "输入文件（容器内绝对路径，/mnt/lab/ 或 /mnt/download/ 开头）。支持音视频与图片（jpg/png/webp/gif/bmp/tiff）",
                        ),
                        "output" to mapOf(
                            "type" to "string",
                            "description" to "输出文件（容器内绝对路径）。扩展名决定格式：音视频 .mp4/.mkv/.webm/.mov/.mp3/.m4a/.wav/.gif，图片 .jpg/.png/.webp/.bmp/.tiff",
                        ),
                        "start" to mapOf(
                            "type" to "string",
                            "description" to "起始时间点，如 \"10\"、\"00:01:30\"、\"1:30.5\"。省略=从开头",
                        ),
                        "duration" to mapOf(
                            "type" to "string",
                            "description" to "持续时长，格式同 start。省略=到结尾",
                        ),
                        "videoCodec" to mapOf(
                            "type" to "string",
                            "description" to "视频编码器：libx264（推荐，自动加 veryfast 提速）/ libx265 / vp9 / av1 / copy（不重编码，裁剪时最快）/ none（去掉视频，抽音频用）。省略=按容器选默认编码器",
                        ),
                        "audioCodec" to mapOf(
                            "type" to "string",
                            "description" to "音频编码器：aac / libmp3lame / libopus / copy（不重编码）/ none（去掉声音）。省略=按容器选默认编码器",
                        ),
                        "resolution" to mapOf(
                            "type" to "string",
                            "description" to "输出分辨率：\"720\"（短边 720，自动保持宽高比）或 \"1280x720\"（精确宽高）。省略=保持原分辨率",
                        ),
                        "fps" to mapOf(
                            "type" to "integer",
                            "description" to "输出帧率 1–240。省略=保持源帧率",
                            "minimum" to 1,
                            "maximum" to 240,
                        ),
                        "crf" to mapOf(
                            "type" to "integer",
                            "description" to "质量系数 0–51：越大体积越小、画质越低（23 默认，28 明显压缩，35 强压缩）。与 videoBitrate 二选一",
                            "minimum" to 0,
                            "maximum" to 51,
                        ),
                        "videoBitrate" to mapOf(
                            "type" to "string",
                            "description" to "视频目标码率，如 \"1M\"、\"2500k\"。与 crf 二选一（都传时 crf 优先）",
                        ),
                        "audioBitrate" to mapOf(
                            "type" to "string",
                            "description" to "音频目标码率，如 \"128k\"",
                        ),
                        "overwrite" to mapOf(
                            "type" to "boolean",
                            "description" to "输出文件已存在时是否覆盖，默认 true",
                        ),
                        "timeoutSec" to mapOf(
                            "type" to "integer",
                            "description" to "超时秒数，默认 $DEFAULT_TIMEOUT_SEC，上限 $MAX_TIMEOUT_SEC。超长任务改用 run_shell(background=true)",
                            "minimum" to 60,
                            "maximum" to MAX_TIMEOUT_SEC,
                        ),
                    ),
                    "required" to listOf("input", "output"),
                ),
            ),
        ),
    )

    // ═══════════════════ 执行 ═══════════════════

    override fun execute(context: Context, name: String, args: JSONObject): String {
        // 执行前的共享目录快照：跑完与它做 diff，差集就是这次真正写出来的文件
        // （与 run_shell 同一套机制，见 ShellToolProvider.execute 的注释）
        val before = if (name == TOOL_CONVERT) LabFileOutputs.snapshot(context) else emptyMap()
        val result = when (name) {
            TOOL_PROBE -> probe(context, args)
            TOOL_CONVERT -> convert(context, args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
        if (name == TOOL_CONVERT) {
            // 容器写 /mnt/lab 走内核路径（不经 MediaProvider）⇒ 补扫一次媒体库，
            // 让 list_files(downloads) 与聊天里的媒体卡片指向同一份东西
            LabMediaScan.scanLabOutputs(context)
            LabFileOutputs.publish(context, before)
        }
        return result
    }

    /** 组件缺失时的统一引导文案（与 run_shell 缺 rootfs 同构：不自动下载、不假装成功） */
    private fun missingAddon(action: String): String =
        "FFmpeg 组件还没安装（容器里没有 ffmpeg/ffprobe），没有${action}任何文件。" +
            "请如实告诉用户：需要到「设置 → 本机执行环境」安装 FFmpeg 组件" +
            "（约 51 MB 下载，一次性；装好后聊天里就能直接处理音视频）。" +
            "现在不要假装已经${action}过，也不要凭空编造输出。"

    private fun probe(context: Context, args: JSONObject): String {
        if (!FfmpegAddon.isInstalled(context)) return missingAddon("读取")
        val path = normalizeMediaPath(args.optString("path"))
            ?: return "path 不受支持：只接受 /mnt/lab/ 或 /mnt/download/ 开头的容器内绝对路径" +
                "（不要相对路径、不要 .. 穿越）。用户给的手机文件请先放进「下载/Lab」。"
        val r = ProotShell.runCommand(
            context,
            "ffprobe -v error -show_format -show_streams -of json ${ProotShell.shellQuote(path)}",
            timeoutSec = PROBE_TIMEOUT_SEC,
        )
        if (!r.launched) {
            return "ffprobe 没能启动（环境本身有问题）：${r.failure}。" +
                "请如实告知用户，并建议他到「设置 → 本机执行环境」跑一次自检。"
        }
        if (r.code != 0) {
            val tail = r.stderr.trim().take(300)
            return if (tail.contains("No such file", ignoreCase = true)) {
                "文件不存在：$path。先用 run_shell 的 ls 确认实际位置（注意输入要放在 /mnt/lab 或 /mnt/download 下）。"
            } else {
                "ffprobe 读取失败（退出码 ${r.code}）：${tail.ifBlank { "（无错误输出，可能不是音视频/图片文件）" }}"
            }
        }
        return runCatching { renderProbe(r.stdout, path) }
            .getOrElse { e ->
                Log.w(TAG, "ffprobe 输出解析失败：${e.javaClass.simpleName}: ${e.message}")
                "ffprobe 输出解析失败，原始输出（前 500 字）：\n${r.stdout.take(500)}"
            }
    }

    /** ffprobe 的 JSON → 紧凑摘要（原始 JSON 有几十个字段，全量回填纯浪费 token） */
    private fun renderProbe(json: String, path: String): String {
        val root = JSONObject(json)
        val fmt = root.optJSONObject("format")
        val streams = root.optJSONArray("streams")
        val sb = StringBuilder()
        sb.append("文件：").append(path).append('\n')
        if (fmt != null) {
            sb.append("容器：").append(fmt.optString("format_name").ifBlank { "未知" })
            fmt.optString("duration").toDoubleOrNull()?.let { sb.append(" · 时长 ").append(formatDuration(it)) }
            fmt.optString("size").toLongOrNull()?.let { sb.append(" · 大小 ").append(formatBytes(it)) }
            fmt.optString("bit_rate").toLongOrNull()?.let { sb.append(" · 总码率 ").append(formatBitrate(it)) }
            sb.append('\n')
        }
        if (streams == null || streams.length() == 0) {
            sb.append("（没有流信息）")
        } else {
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val type = s.optString("codec_type")
                val codec = s.optString("codec_name").ifBlank { "?" }
                val profile = s.optString("profile").takeIf { it.isNotBlank() && it != "unknown" }
                sb.append("流 #").append(s.optInt("index", i))
                    .append(' ').append(streamTypeLabel(type)).append(' ')
                    .append(codec)
                if (profile != null) sb.append('(').append(profile).append(')')
                when (type) {
                    "video" -> {
                        val w = s.optInt("width")
                        val h = s.optInt("height")
                        if (w > 0 && h > 0) sb.append(' ').append(w).append('x').append(h)
                        s.optString("r_frame_rate").takeIf { it.isNotBlank() }?.let { fr ->
                            formatFrameRate(fr)?.let { sb.append(' ').append(it).append("fps") }
                        }
                        s.optString("pix_fmt").takeIf { it.isNotBlank() }?.let { sb.append(' ').append(it) }
                    }
                    "audio" -> {
                        s.optString("sample_rate").takeIf { it.isNotBlank() }?.let { sb.append(' ').append(it).append("Hz") }
                        s.optInt("channels").takeIf { it > 0 }?.let {
                            sb.append(' ').append(it).append("ch").append(
                                s.optString("channel_layout").takeIf { l -> l.isNotBlank() }?.let { "($it)" } ?: "",
                            )
                        }
                    }
                }
                s.optString("bit_rate").toLongOrNull()?.let { sb.append(" · ").append(formatBitrate(it)) }
                sb.append('\n')
            }
        }
        return sb.toString().trim()
    }

    private fun convert(context: Context, args: JSONObject): String {
        if (!FfmpegAddon.isInstalled(context)) return missingAddon("转换")

        val input = normalizeMediaPath(args.optString("input"))
            ?: return "input 不受支持：只接受 /mnt/lab/ 或 /mnt/download/ 开头的容器内绝对路径。"
        val output = normalizeMediaPath(args.optString("output"))
            ?: return "output 不受支持：只接受 /mnt/lab/ 或 /mnt/download/ 开头的容器内绝对路径（写到 /mnt/lab 用户才能在「下载/Lab」拿到）。"
        if (input == output) {
            return "输入与输出不能是同一个文件（会把源文件覆盖掉）。换个输出路径，例如 ${input}.out.mp4。"
        }

        val videoCodec = args.optString("videoCodec").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeCodec(it) ?: return "videoCodec 含不支持的字符（只允许字母数字与 . _ + -，且不许 - 开头）。" }
        val audioCodec = args.optString("audioCodec").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeCodec(it) ?: return "audioCodec 含不支持的字符（只允许字母数字与 . _ + -，且不许 - 开头）。" }
        val start = args.optString("start").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeTime(it) ?: return "start 时间格式不对：用 \"10\"、\"00:01:30\" 或 \"1:30.5\" 这类写法。" }
        val duration = args.optString("duration").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeTime(it) ?: return "duration 时间格式不对：用 \"10\"、\"00:01:30\" 或 \"1:30.5\" 这类写法。" }
        val resolution = args.optString("resolution").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeResolution(it) ?: return "resolution 格式不对：用 \"720\"（短边）或 \"1280x720\"（宽x高）。" }

        val fps = if (args.has("fps")) {
            val v = args.optInt("fps", 0)
            if (v !in 1..240) return "fps 超出范围（1–240）。" else v
        } else null
        val crf = if (args.has("crf")) {
            val v = args.optInt("crf", -1)
            if (v !in 0..51) return "crf 超出范围（0–51；23 默认，28 明显压缩）。" else v
        } else null
        val videoBitrate = args.optString("videoBitrate").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeBitrate(it) ?: return "videoBitrate 格式不对：用 \"1M\"、\"2500k\" 这类写法。" }
        val audioBitrate = args.optString("audioBitrate").trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeBitrate(it) ?: return "audioBitrate 格式不对：用 \"128k\" 这类写法。" }

        val overwrite = args.optBoolean("overwrite", true)
        val timeout = args.optInt("timeoutSec", DEFAULT_TIMEOUT_SEC).coerceIn(60, MAX_TIMEOUT_SEC).toLong()

        // 组合合法性：缩放/帧率/质量参数都要求"这一路真的要重编码"
        val videoReEncodes = videoCodec == null || (videoCodec != "copy" && videoCodec != "none")
        if (!videoReEncodes && (resolution != null || fps != null)) {
            return "resolution/fps 需要重新编码视频，不能与 videoCodec=copy 同用；" +
                "要改分辨率/帧率就不要传 videoCodec（或显式给 libx264）。"
        }

        // ── 拼装脚本：所有外部值都过 shellQuote；布尔开关由本函数按上表校验后决定 ──
        val q = ProotShell::shellQuote
        val sb = StringBuilder()
        if (!overwrite) {
            // 预检存在性：不让 ffmpeg 自己弹交互提问（非 tty 下它会直接失败，错误码还不友好）
            sb.append("if [ -e ").append(q(output)).append(" ]; then echo '[输出已存在]'; exit ")
                .append(EXIT_OUTPUT_EXISTS).append("; fi\n")
        }
        sb.append("ffmpeg -hide_banner -nostats -v error -progress pipe:1")
        if (overwrite) sb.append(" -y")
        if (start != null) sb.append(" -ss ").append(q(start))
        if (duration != null) sb.append(" -t ").append(q(duration))
        sb.append(" -i ").append(q(input))
        val filters = buildList {
            if (resolution != null) add("scale=$resolution")
            if (fps != null) add("fps=$fps")
        }
        if (filters.isNotEmpty()) sb.append(" -vf ").append(q(filters.joinToString(",")))
        if (videoCodec != null) {
            if (videoCodec == "none") sb.append(" -vn") else sb.append(" -c:v ").append(q(videoCodec))
        }
        if (audioCodec != null) {
            if (audioCodec == "none") sb.append(" -an") else sb.append(" -c:a ").append(q(audioCodec))
        }
        // libx264/x265 在手机上默认 preset 太慢：显式给 veryfast（两个编码器都认这个档位）
        if (videoCodec == "libx264" || videoCodec == "libx265") sb.append(" -preset veryfast")
        if (crf != null && videoReEncodes) sb.append(" -crf ").append(crf)
        if (videoBitrate != null && crf == null && videoReEncodes) sb.append(" -b:v ").append(q(videoBitrate))
        if (audioBitrate != null && audioCodec != "copy" && audioCodec != "none") {
            sb.append(" -b:a ").append(q(audioBitrate))
        }
        sb.append(' ').append(q(output))
        // 成功后补一行输出大小（&& 保证失败时不执行，退出码即 ffmpeg 的）
        sb.append(" && echo \"[输出大小] $(wc -c < ").append(q(output)).append(") 字节\"\n")

        Log.i(TAG, "ffmpeg_convert: $input -> $output（超时 ${timeout}s）")
        // 输出直播：串行泳道上主循环装了 sink ⇒ 把 ffmpeg 的 -progress 行译成人类可读进度
        // （并行泳道 sink 为空 ⇒ 退化为只收最终结果，无害）
        val live = ShellToolProvider.takeLiveOutputLine()
        val r = ProotShell.runCommand(
            context,
            sb.toString(),
            timeoutSec = timeout,
            onStdoutLine = if (live != null) { line -> parseProgressLine(line)?.let(live) } else null,
            onStderrLine = live,
        )
        if (r.code == EXIT_OUTPUT_EXISTS) {
            return "输出文件已存在：$output。确认要覆盖就把 overwrite 传 true，或换一个输出路径。"
        }
        if (r.code == 0) {
            // 产物芯片（过程行）：容器路径 → 宿主真实路径。映射不到就不记 ——
            // 宁可少一个芯片，也不给一个点了打不开的路径（与 AgentStep.ARTIFACT_SPECS 同判据）。
            hostPathOf(context, output)?.let { host ->
                ProducedFileSink.record(host, output.substringAfterLast('/'))
            }
        }
        return formatConvertResult(r, output) + ShellToolProvider.shareHint(context)
    }

    /**
     * 容器内路径 → 宿主真实绝对路径（产物芯片用）。
     *
     * 只映射两个**已挂载**的点，且按授权现状判据，不是猜：
     *  - `/mnt/lab/<rel>` ↔ [ProotShell.shareStatus] 的 hostPath（公共「下载/Lab」或 App 私有降级目录）；
     *  - `/mnt/download/<rel>` ↔ 公共「下载」目录，**仅限已授予「所有文件访问」**（否则 guest 里那个挂载点
     *    根本不存在，映射过去就是假路径）。
     */
    private fun hostPathOf(ctx: Context, containerPath: String): String? {
        val rel = when {
            containerPath.startsWith("/mnt/lab/") -> containerPath.removePrefix("/mnt/lab/")
            containerPath.startsWith("/mnt/download/") -> containerPath.removePrefix("/mnt/download/")
            else -> return null
        }
        if (rel.isEmpty()) return null
        val base: File = if (containerPath.startsWith("/mnt/download/")) {
            if (!ProotShell.canUsePublicDownload()) return null
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(ProotShell.shareStatus(ctx).hostPath)
        }
        return File(base, rel).absolutePath
    }

    /** ffmpeg `-progress pipe:1` 的 `out_time_ms=<微秒→毫秒>` → "已处理 00:01:23.45"；非进度行返回 null */
    private fun parseProgressLine(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("out_time_ms=")) return null
        val ms = trimmed.removePrefix("out_time_ms=").trim().toLongOrNull() ?: return null
        if (ms <= 0) return null
        return "[ffmpeg] 已处理 ${formatDuration(ms / 1000.0)}"
    }

    /**
     * 转换结果 → 回填文本。
     *
     * stdout 里的 `-progress` 键值行全部滤掉（每行都是 `frame=…/speed=…` 这类机器行，
     * 回填给模型纯噪音）；只保留 `[输出大小]` 这类标记行与错误。
     */
    private fun formatConvertResult(r: ExecResult, output: String): String {
        if (r.timedOut) {
            return "转换被超时中断：${r.failure}。已按失败处理，但**这不是环境故障** —— " +
                "是这条命令自己没有在超时内跑完（大文件/高分辨率编码在手机上可能就是慢）。" +
                "要么调大 timeoutSec（上限 $MAX_TIMEOUT_SEC），要么用 run_shell 传 background=true " +
                "直接跑 ffmpeg 再用 read_run_output 看进度。"
        }
        if (!r.launched) {
            return "ffmpeg 没能拉起来（环境本身有问题）：${r.failure}。" +
                "请如实告知用户，并建议他到「设置 → 本机执行环境」跑一次自检。"
        }
        val useful = r.stdout.lineSequence()
            .filterNot { PROGRESS_LINE.matches(it.trim()) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val err = r.stderr.trim()
        val sb = StringBuilder()
        if (r.code == 0) {
            sb.append("转换完成：").append(output)
            if (useful.isNotBlank()) sb.append('\n').append(useful)
            if (err.isNotBlank()) sb.append("\n\n[标准错误]\n").append(err.take(1_000))
        } else {
            sb.append("转换失败（退出码 ").append(r.code).append("）")
            if (err.isNotBlank()) {
                sb.append("\n\n[标准错误]\n").append(err.take(2_000))
            } else {
                sb.append("（ffmpeg 没有给出错误输出）")
            }
        }
        return sb.toString()
    }

    // ═══════════════════ 展示格式 ═══════════════════

    /** `frame=…` / `speed=…` / `progress=continue` 这类 ffmpeg 键值行 */
    private val PROGRESS_LINE = Regex("^[a-z_0-9]+=.*")

    private fun streamTypeLabel(type: String): String = when (type) {
        "video" -> "视频"
        "audio" -> "音频"
        "subtitle" -> "字幕"
        "data" -> "数据"
        else -> type.ifBlank { "?" }
    }

    /** 秒 → `HH:MM:SS.cc`（时长与进度共用） */
    internal fun formatDuration(sec: Double): String {
        if (sec < 0) return "未知"
        val total = sec.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val cs = ((sec - total) * 100).toInt().coerceIn(0, 99)
        return "%02d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / 1024 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / 1024 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes.toDouble() / 1024)
        else -> "$bytes B"
    }

    /** ffmpeg 的 bit_rate 单位是 bit/s */
    internal fun formatBitrate(bps: Long): String =
        if (bps >= 1_000_000) "%.1f Mbps".format(bps.toDouble() / 1_000_000)
        else "%.0f kbps".format(bps.toDouble() / 1_000)

    /** `30/1` → `30`；`0/0`（未知）→ null */
    internal fun formatFrameRate(raw: String): String? {
        val parts = raw.split('/')
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        if (den == 0.0) return null
        val v = num / den
        if (v <= 0) return null
        // 整数帧率（30/1 → 30）不带小数；带小数的（2997/100 → 29.97）保留有效数字、去尾零。
        // 注意 %g 会把 30.0 渲染成 "30.0"、把 29.97 舍入成 "30.0"，故这里手动裁尾零。
        return if (v >= 100) "%.0f".format(java.util.Locale.US, v)
        else "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
    }
}
