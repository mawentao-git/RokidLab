package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.GlassesRecordChannel
import com.rokidlab.phone.ai.LabFileOutputs
import com.rokidlab.phone.ai.LabMediaScan
import com.rokidlab.phone.ai.ProducedFileSink
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import com.rokidlab.phone.platform.ProotShell
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GlassesRecordToolProvider —— 眼镜摄像头录像（Cap.RECORD，2026-10-22）。
 *
 * ## 它是什么
 * 真正的"说开始录像、说停止录像"：手机端下发 [LinkProtocol.TOPIC_RECORD_CTRL]，
 * 眼镜端 RokidLink 的 CameraRecordingController 用 Camera1+MediaRecorder 录 mp4
 * （SDK 无录像接口、系统 RecordingService 是 system 私有 ⇒ 只能自建，探查结论见其文档）。
 * stop 成功后凭眼镜端回执里的 path 走 **ADB sync**（[com.rokidlab.phone.adb.AdbShellClient.pullFile]，
 * 无 8MB base64 限制——那是 [GlassesToolProvider] 的 shell 通道限制）把文件拉回
 * 「下载/Lab」= 容器 `/mnt/lab`，随后 `ffmpeg_convert` / `ffmpeg_probe` 直接可用。
 *
 * ## 风险档
 * 对齐 `listen_ambient`：[ToolRisk.LOCAL_SIDE_EFFECT]——物理打开眼镜摄像头，
 * 不应出现在无人值守的定时自主任务里（那里只装配 READ_ONLY 工具）。
 * 另标 `sideEffect = true`（重放会**再录一段**，用户可见的重复，非幂等）。
 *
 * ## 等待模型
 * start/status 短等待（5s）；stop 要等眼镜端 MediaRecorder finalize（几百 ms~2s）
 * + 上行，给 15s。等待经 [GlassesRecordChannel]（latch，无 pending 时回执被静默丢弃）。
 */
internal object GlassesRecordToolProvider : ToolProvider {

    private const val TAG = "GlassesRecordToolProvider"

    const val TOOL_RECORD = "record_glasses_video"

    /** start/status 回执等待：发指令 + 眼镜端几百 ms 内回执，5s 足够宽裕 */
    private const val SHORT_WAIT_MS = 5_000L

    /** stop 回执等待：MediaRecorder finalize（SIG 不涉及，正常 stop 完成即回执）+ 蓝牙传输 */
    private const val STOP_WAIT_MS = 15_000L

    override val toolNames = setOf(TOOL_RECORD)

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_RECORD,
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_record_glasses_name,
            descriptionRes = R.string.ai_tool_record_glasses_desc,
            // 用户视角归「眼镜」：这条能力对用户就是"眼镜帮我拍视频"（与 listen_ambient 同类）
            category = ToolRegistry.ToolCategory.GLASSES,
            // LOCAL_SIDE_EFFECT 而不是 READ_ONLY：它会**物理打开眼镜摄像头**，不应出现在
            // 无人值守的定时自主任务里（那里只装配 READ_ONLY 工具）；sideEffect=true：
            // 重放会再录一段（用户可见的重复），失败不重试。
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在控制眼镜摄像头…",
            schema = toolSchema(
                name = TOOL_RECORD,
                description = "用眼镜摄像头录制一段视频（mp4，带声音——眼镜端已授权麦克风时）。" +
                    "用户说「开始录像」「帮我拍段视频」时 action=start；说「停止录像/好了」时 action=stop。" +
                    "start 只开启录制并立即返回；stop 才结束录制并把视频传回手机" +
                    "（存到「下载/Lab」，之后可用 ffmpeg_convert / ffmpeg_probe 直接处理）。" +
                    "start 后用户久久不说停时，最长录 15 分钟自动停。" +
                    "⚠️ 需要眼镜在线：不可用时如实说明「需要连接眼镜才能录像」。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "description" to "\"start\" 开始录像 / \"stop\" 停止并把视频传回手机 / \"status\" 查询是否在录",
                        ),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        if (name != TOOL_RECORD) throw IllegalArgumentException("未知工具: $name")
        val app = context.applicationContext as? LabApplication
            ?: return "操作失败：应用上下文不可用，请稍后重试"
        if (!app.hasCxrL()) {
            return "录像失败：眼镜链路尚未初始化。请告诉用户「需要先连接眼镜才能录像」"
        }
        val session = runCatching { app.cxrL }.getOrNull()
            ?: return "录像失败：当前没有连接到眼镜。请告诉用户「需要先连接眼镜才能录像」"
        // 旧版眼镜端（已握手且确认无该能力位）快速降级，避免空下发
        if (GlassesHandshake.supports(LinkProtocol.Cap.RECORD) == false) {
            Log.i(TAG, "$name: glasses does not support RECORD cap")
            return "录像失败：眼镜端版本过旧，不支持摄像头录像。请告诉用户需要先更新眼镜端（RokidLink）。"
        }
        val link = session.cxrLink
            ?: return "录像失败：眼镜链路未就绪（CXRL 未连接）。请稍后重试或建议用户重新连接眼镜。"
        if (!session.cxrlConnected || !session.glassBtConnected) {
            return "录像失败：眼镜链路未就绪。请稍后重试或建议用户重新连接眼镜。"
        }

        // ── 产物可见性：stop 才产出文件，故只在这一支做前后快照 ──
        // ⚠️ 这一步不是可选的：只调 ProducedFileSink 只能让视频出现在「过程」行芯片里，
        // 而芯片点开走 readPreview（媒体扩展名 ⇒ "这里不预览"）—— 用户要的「气泡里的
        // 视频卡片」由 [LabFileOutputs.publish] → ChatScreen 的 sink → ChatStateHolder.addFile
        // → ChatFileCard 分流到 ChatMediaCard（内嵌 VideoView 播放器）这条链路产出。
        if (args.optString("action").trim().lowercase(Locale.US) == "stop") {
            val before = LabFileOutputs.snapshot(context)
            return stopAndFetch(context, session, link).also {
                // 拉回成功与否都要 publish：成功则新增 mp4 出卡，失败则 diff 为空、静默
                runCatching { LabFileOutputs.publish(context, before) }
            }
        }

        return when (val action = args.optString("action").trim().lowercase(Locale.US)) {
            "start" -> startRecording(session, link)
            "stop" -> stopAndFetch(context, session, link)
            "status" -> queryStatus(session, link)
            else -> "action 不支持：$action（只接受 start / stop / status）"
        }
    }
    // ── start ──

    private fun startRecording(
        session: com.rokidlab.phone.glasses.CxrLHiRokidSession,
        link: com.rokid.cxr.link.CXRLink,
    ): String {
        if (!sendCtrl(session, link, "start")) {
            return "录像下发失败：眼镜链路不可用。请告诉用户这次没能开始录像，可稍后重试。"
        }
        // 等 started/error 回执：眼镜端 Camera.open 被占用（assistserver 抢占）或缺权限
        // 都会立即 error 上行 —— 收到错误当场翻译，不要让模型对着"已开始"的成功假象继续演。
        val r = GlassesRecordChannel.awaitResult(setOf("started", "error"), SHORT_WAIT_MS)
        return when {
            r == null ->
                "录像指令已下发，但 ${SHORT_WAIT_MS / 1000} 秒内没收到眼镜端回执。" +
                    "请用 action=status 确认是否真的在录，不要向用户断言已开始。"
            r.first == "started" ->
                "已开始录像（眼镜摄像头）。告诉用户可以说「停止录像」结束并把视频传到手机。"
            else -> translateError(r.second, verb = "开始录像")
        }
    }

    // ── stop + 拉回 ──

    private fun stopAndFetch(
        context: Context,
        session: com.rokidlab.phone.glasses.CxrLHiRokidSession,
        link: com.rokid.cxr.link.CXRLink,
    ): String {
        if (!sendCtrl(session, link, "stop")) {
            return "停止指令下发失败：眼镜链路不可用。请让用户稍后重试" +
                    "（眼镜端有失联看门狗，3 分钟收不到手机消息会自动停录，不会一直录下去）。"
        }
        val r = GlassesRecordChannel.awaitResult(setOf("stopped", "error"), STOP_WAIT_MS)
        val remotePath = when {
            r == null -> return "停止指令已下发，但没收到眼镜端完成回执。" +
                "请用 action=status 查询；若确认已停，可让用户在眼镜文件里找「LabRecord」目录。"
            r.first == "stopped" -> {
                val path = runCatching { JSONObject(r.second).optString("path") }.getOrNull().orEmpty()
                if (path.isBlank()) {
                    return "录像已停止，但眼镜端没回传文件路径，无法自动取回。请如实告诉用户。" +
                        "（文件在眼镜端 LabRecord 目录，可稍后重试）"
                }
                path
            }
            else -> return translateError(r.second, verb = "停止录像")
        }

        // ── 拉回手机：ADB sync 直传（无 8MB 限制），落「下载/Lab」= 容器 /mnt/lab ──
        val fileName = "glasses_rec_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val hostBase = ProotShell.shareStatus(context).hostPath
        val local = File(hostBase, fileName)
        val pulled = synchronized(ToolRegistry.adbLock) {
            val client = ToolRegistry.adbClient(context)
                ?: return "录像已完成（眼镜端 $remotePath），但手机与眼镜的 ADB 连接不可用，" +
                    "暂时无法把视频传回手机。请如实告诉用户，稍后可重新尝试传输。"
            // sync RECV 直传（与 adb pull 同通路，无 8MB 限制）。expectedSize 从远端 stat 对账：
            // 蓝牙隧道下 sync 流可能送一半就收掉（既无 DONE 也无 FAIL），必须以字节数对账为收尾凭证。
            runCatching {
                val expected = runCatching { client.getRemoteFileSize(remotePath) }.getOrDefault(-1L)
                if (!client.pullFile(remotePath, local.absolutePath, expected)) {
                    error("pullFile returned false")
                }
                true
            }.getOrElse {
                Log.w(TAG, "pullFile failed: ${it.message}")
                false
            }
        }
        if (!pulled || !local.exists() || local.length() <= 0L) {
            local.delete()
            return "录像已完成（眼镜端 $remotePath），但传输到手机失败（ADB 链路中断或文件不可读）。" +
                "眼镜端文件已保留，可让用户稍后重试 action=stop 之外的取回，或重新录一段。"
        }
        // 产物回报两通道：媒体库补扫 + 过程行芯片（与 zip_files / download_glasses_file 同判据）
        runCatching { LabMediaScan.scanLabOutputs(context) }
        ProducedFileSink.record(local.absolutePath, fileName)
        val sizeHint = formatSize(local.length())
        // 眼镜端文件保留：删除属于不可恢复操作，且用户可能还要用；LabRecord 目录在
        // app 卸载时才清（getExternalFilesDir），不会无限膨胀（单段 ≤15min 上限）。
        return "录像完成，视频已传回手机：「下载/Lab/$fileName」（$sizeHint）。" +
            "容器内路径 /mnt/lab/$fileName —— 可直接用 ffmpeg_probe 查看信息，" +
            "或 ffmpeg_convert 转格式/压缩（例如转 webp）。" +
            "告诉用户可以在手机文件管理器的「下载/Lab」里找到它。"
    }

    // ── status ──

    private fun queryStatus(
        session: com.rokidlab.phone.glasses.CxrLHiRokidSession,
        link: com.rokid.cxr.link.CXRLink,
    ): String {
        if (!sendCtrl(session, link, "status")) {
            return "查询失败：眼镜链路不可用。"
        }
        val r = GlassesRecordChannel.awaitResult(setOf("status", "error"), SHORT_WAIT_MS)
        return when {
            r == null -> "查询超时：眼镜端没有应答。请稍后重试。"
            r.first == "status" -> {
                val obj = runCatching { JSONObject(r.second) }.getOrNull()
                if (obj?.optBoolean("recording", false) == true) {
                    val dur = obj.optDouble("durationSec", 0.0)
                    "眼镜摄像头正在录像（已录约 ${dur.toInt()} 秒）。"
                } else {
                    "眼镜摄像头当前空闲，没有在录像。"
                }
            }
            else -> translateError(r.second, verb = "查询录像状态")
        }
    }

    // ── 发送与辅助 ──

    /** 下发控制帧（与 DeviceControlService.sendImuControl 同一发送路径：公网 API 直发 + 锁保护） */
    private fun sendCtrl(
        session: com.rokidlab.phone.glasses.CxrLHiRokidSession,
        link: com.rokid.cxr.link.CXRLink,
        cmd: String,
    ): Boolean {
        return try {
            val caps = Caps()
            caps.write(cmd)
            val r = synchronized(session.aiCmdLock) {
                link.sendCustomCmd(LinkProtocol.TOPIC_RECORD_CTRL, caps)
            } ?: -3
            Log.i(TAG, "sendCtrl($cmd) -> $r")
            r == 0
        } catch (e: Exception) {
            Log.e(TAG, "sendCtrl($cmd) failed", e)
            false
        }
    }

    /** 眼镜端 error 载荷 → 用户可读文案（与 CameraRecordingController 的错误码一一对应） */
    private fun translateError(payloadJson: String, verb: String): String {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
        return when (obj?.optString("error")) {
            "already_recording" -> "$verb 失败：眼镜已经在录像中了。先 action=stop 结束当前录像，或用 action=status 查看。"
            "no_camera_permission" -> "$verb 失败：眼镜端没有授予摄像头权限。" +
                "请告诉用户：在眼镜上打开一次 RokidLink 应用（或重连眼镜），" +
                "眼镜屏幕会弹出权限确认框，用镜腿触控板点「允许」后重试。"
            "camera_busy" -> "$verb 失败：眼镜摄像头被其他应用占用。请告诉用户稍等几秒再试。"
            // 策略拒绝 ≠ 被占用：这不是"稍等再试"能解决的，必须让用户显式授权/让应用到前台
            "camera_policy_denied" -> "$verb 失败：眼镜系统策略拒绝了摄像头访问（不是被别的应用占用）。" +
                "请让用户在眼镜上打开一次 RokidLink 应用到前台，并确认摄像头权限为「允许」，然后重试。" +
                "（技术细节：${obj?.optString("message")?.take(120) ?: ""}）"
            "start_failed" -> "$verb 失败：眼镜端启动录制出错（${obj?.optString("message") ?: "未知"}）。请稍后重试。"
            "record_failed" -> "$verb 失败：眼镜端录制收尾出错（本次录像没有成功保存）。请重新录一段。"
            else -> "$verb 失败：眼镜端返回未知错误（${payloadJson.take(120)}）。"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(Locale.US, bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.0f KB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }
}
