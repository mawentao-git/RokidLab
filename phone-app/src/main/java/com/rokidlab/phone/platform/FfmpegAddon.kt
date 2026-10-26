package com.rokidlab.phone.platform

import android.content.Context
import android.os.Build
import android.util.Log
import com.rokidlab.phone.network.ApkDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * FFmpeg 组件（ffmpeg + ffprobe）的**下载 → 校验 → 解压 → 就位 → 验证**全流程。
 *
 * ## 为什么走下载（静态二进制）而不是 `apt install ffmpeg`
 * 三条理由，与 [NodeAddon] 同源，但 ffmpeg 这条更极端：
 *  1. **体积**：apt 走 `--no-install-recommends` 也要拉进约 30 个 deb（libavcodec /
 *     libavformat / libx264 / libx265 / libass / libdav1d…），装后约 180 MB；
 *     静态二进制是两个各 51 MB 的可执行文件，合计约 102 MB，**一半都不到**。
 *  2. **版本**：noble 源里是 ffmpeg 6.1.1，静态构建直接给 7.0.2（含 libx265 / dav1d /
 *     libvpx 新版）。rootfs 的 apt 源虽然含 universe（见 [ProotShell.ensureGuestConfig]），
 *     但"能装"不等于"该装"——为一条命令请进半个多媒体依赖树不划算。
 *  3. **位置**：与 Node.js 一样装在 rootfs **之外**的 `files/proot/extras/`，
 *     重装 rootfs 不丢（见 [ProotShell.extrasDir]）。
 *
 * ## 为什么是 `.gz` 单文件而不是 `.tar.gz` / `.tar.xz`
 * 上游 release 直接发布**gzip 压缩的裸二进制**（`ffmpeg-linux-arm64.gz`）：
 *  - 不需要 tar：解压就是 `GZIPInputStream → copyTo`，JVM 自带，**不依赖设备 toybox**
 *    （它既不会调外部 `xz`，多文件归档也平添一层解析，见 [ProotInstaller] 的同名注释）；
 *  - 也不需要先进 guest——rootfs 可能正被重装，组件安装不该依赖它能跑。
 *
 * ## 校验为什么是三道，不是一道
 *  1. 下载器对 **gz** 做 sha256（[ApkDownloader]，与 rootfs / node 同一机制）；
 *  2. 解压后钉**精确字节数**（51,134,160 / 50,994,160）——防"gz 对但内容被换"；
 *  3. 读 **ELF 头**：magic + 64 位 + 小端 + `e_machine == 183`（aarch64）。
 *     之后还要在 guest 里真跑一次 `ffmpeg -version`（见 [installFromArchives]）——
 *     "文件摆对了"和"proot 加载得动"是两回事，后者只能跑一遍才知道。
 *
 * ## 锁边界（与 [NodeAddon] / [ProotInstaller] 逐字同构）
 * 下载是 suspend 的，**绝不能在锁内**（写锁必须由持锁线程释放，协程挂起点之后可能换线程
 * ⇒ 锁永久泄漏）。只有"解压 → 就位 → 验证 → 写标记"这段阻塞调用取独占锁。
 *
 * ## 镜像顺序（2026-10-22 实测）
 * 三个 GitHub 代理前置 HEAD 全 200，且逐字节与官方一致（同一 sha256）；官方直连殿后
 * （OkHttp 跟随 302 → objects.githubusercontent.com）。⚠️ 代理类镜像**会跑路**，
 * 失效时按 NodeAddon 的先例剔除并补注实测日期，不要静默留着。
 *
 * ## ⚠️ 法务（与 rootfs 同口径）
 * 二进制是 GPLv3 静态构建（johnvansickle），但走**运行期下载**、不经我们分发 ⇒
 * 法务面与 rootfs 一致（最干净的那一档）。
 *
 * ## ⚠️ 已知限制（上游 README 明示）
 * 静态链接 glibc 会失去 DNS 解析 ⇒ **不要拿 http(s) URL 当 ffmpeg 输入**
 * （NSS 的 libnss_dns 不在静态二进制里）。先用 curl/wget 落到 /mnt/lab 再处理。
 */
object FfmpegAddon {

    private const val TAG = "FfmpegAddon"

    /** ffmpeg / ffprobe 的真实版本。⚠️ release 的 tag 是 `b6.1.1`，但 arm64 资产实际是 7.0.2（上游就地更新）。 */
    const val VERSION = "7.0.2"

    /** 上游 release 地址（tag 与资产名分开写：改版本时两处都要动） */
    private const val RELEASE_BASE =
        "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1"

    /** 一个要落盘的二进制：gz 资产名、解压后的二进制名、gz 的 sha256、解压后精确字节数 */
    private data class Artifact(
        val archive: String,
        val binary: String,
        val sha256: String,
        val bytes: Long,
    )

    private val ARTIFACTS = listOf(
        Artifact(
            archive = "ffmpeg-linux-arm64.gz",
            binary = "ffmpeg",
            // 25,568,691 B；解压 51,134,160 B（ffmpeg 7.0.2-static，aarch64，全静态 ELF）
            sha256 = "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0",
            bytes = 51_134_160L,
        ),
        Artifact(
            archive = "ffprobe-linux-arm64.gz",
            binary = "ffprobe",
            // 25,493,573 B；解压 50,994,160 B
            sha256 = "2ab6aba60ee84412dff9188720703376cb4e7aaf7e0b5e43aa8249f2acae5bf8",
            bytes = 50_994_160L,
        ),
    )

    /**
     * 镜像顺序（2026-10-22 实测：三个代理 HEAD 均 200，逐字节与官方同 sha256）。
     * 国内优先：GitHub release 直连在国内常被限速；代理前置，官方殿后（必然可达的那条）。
     */
    private val MIRRORS = listOf(
        "https://ghfast.top/$RELEASE_BASE",
        "https://gh-proxy.com/$RELEASE_BASE",
        "https://ghproxy.net/$RELEASE_BASE",
        RELEASE_BASE,
    )

    /**
     * 安装所需可用空间：两个 gz 合计 51 MB（cacheDir）+ 两个二进制合计 102 MB（extras）
     * ⇒ 400 MB 有充裕余量（与 [NodeAddon] 同档）。
     */
    private const val REQUIRED_FREE_BYTES = 400L * 1024 * 1024

    /** 等 rootfs 独占锁的上限（超时即如实拒绝，不硬等） */
    private const val LOCK_WAIT_SEC = 5L

    /** guest 内验证命令的超时（`ffmpeg -version` 是秒级活，给 60s 是给慢存储留量） */
    private const val VERIFY_TIMEOUT_SEC = 60L

    private const val MARKER_NAME = "ffmpeg.version"

    // ═══════════════════ 状态查询 ═══════════════════

    /**
     * 是否已安装就绪。三个条件缺一不可：
     *  1. `bin/ffmpeg` 与 `bin/ffprobe` 都在位（[ProotShell.ffmpegReady] / [ProotShell.ffprobeReady]）；
     *  2. 版本标记在位且 == [VERSION] —— 只认二进制的话，"只装了一半"（ffmpeg 落盘、
     *     ffprobe 没下完）会被判成已安装，而设置页的"安装"按钮只在未就绪分支里出现，
     *     用户就**永远修不好**（与 [NodeAddon] / [ProotInstaller] 同一条判据）。
     */
    fun isInstalled(ctx: Context): Boolean =
        ProotShell.ffmpegReady(ctx) && ProotShell.ffprobeReady(ctx) && installedVersion(ctx) == VERSION

    /** 已安装的版本号（读版本标记）；未安装或标记缺失返回 null */
    fun installedVersion(ctx: Context): String? =
        markerFile(ctx).takeIf { it.exists() }?.let {
            runCatching { it.readLines().firstOrNull { l -> l.startsWith("version=") }?.substringAfter('=') }.getOrNull()
        }

    /** 占用空间（本组件目录，字节）。设置页展示"这东西占了多大"用。 */
    fun diskUsageBytes(ctx: Context): Long = dirSize(ProotShell.ffmpegDir(ctx))

    // ═══════════════════ 安装 ═══════════════════

    /**
     * 安装 FFmpeg 组件。**幂等**：已就绪时直接返回成功。
     *
     * 全程在 IO 线程，[onProgress] 从 IO 线程回调，阶段复用 [ProotInstaller.Stage]
     * —— 与 rootfs / Node.js 安装是同一套安装语汇，UI 侧共用同一份阶段文案映射。
     *
     * 失败一律以 [Capability.Unavailable] 返回原因（不抛异常）：这些原因都是要展示给用户的。
     */
    suspend fun install(
        ctx: Context,
        onProgress: (ProotInstaller.Stage, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Capability<File> = withContext(Dispatchers.IO) {
        onProgress(ProotInstaller.Stage.CHECK, -1)

        if (isInstalled(ctx)) {
            onProgress(ProotInstaller.Stage.DONE, 100)
            return@withContext Capability.Available(ProotShell.ffmpegDir(ctx))
        }
        // 附加组件挂在 rootfs 之外，但**运行**要经 guest —— 没环境时装了也跑不起来、也没法验证
        if (!ProotInstaller.isInstalled(ctx)) {
            return@withContext Capability.Unavailable("请先安装 Linux 环境")
        }
        // ABI：静态二进制是 aarch64；别的 ABI 直接说清，别让用户白下 51 MB
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        if (primaryAbi != "arm64-v8a") {
            return@withContext Capability.Unavailable("FFmpeg 组件暂只支持 arm64-v8a（当前 $primaryAbi）")
        }

        val free = ProotInstaller.freeBytes(ctx.filesDir)
        if (free in 0 until REQUIRED_FREE_BYTES) {
            return@withContext Capability.Unavailable(
                "存储空间不足：安装约需 ${REQUIRED_FREE_BYTES / 1024 / 1024} MB，当前可用 ${free / 1024 / 1024} MB",
            )
        }
        if (free < 0) Log.w(TAG, "读不到可用空间，跳过预检继续安装")

        // ── 下载（多镜像依次试；下载器内部已做 sha256 校验 + 单镜像重试 2 次）──
        // ⚠️ suspend 区间，绝不在锁内（见 KDoc）。
        onProgress(ProotInstaller.Stage.DOWNLOAD, 0)
        val downloader = ApkDownloader(ctx)
        val archives = ArrayList<File>(ARTIFACTS.size)
        var lastError = "未知错误"
        for (artifact in ARTIFACTS) {
            var file: File? = null
            for ((index, mirror) in MIRRORS.withIndex()) {
                if (isCancelled()) {
                    archives.forEach { runCatching { it.delete() } }
                    return@withContext Capability.Unavailable("已取消")
                }
                try {
                    Log.i(TAG, "下载 ${artifact.binary}（${index + 1}/${MIRRORS.size}）：$mirror")
                    file = downloader.download(
                        url = "$mirror/${artifact.archive}",
                        label = artifact.archive,
                        expectedSha256 = artifact.sha256,
                        onProgress = { onProgress(ProotInstaller.Stage.DOWNLOAD, it) },
                        isCancelled = isCancelled,
                    )
                    break
                } catch (e: CancellationException) {
                    archives.forEach { runCatching { it.delete() } }
                    return@withContext Capability.Unavailable("已取消")
                } catch (e: Exception) {
                    lastError = "${e.javaClass.simpleName}: ${e.message}"
                    Log.w(TAG, "镜像失败（${index + 1}/${MIRRORS.size}）：$lastError")
                }
            }
            val ok = file
                ?: run {
                    archives.forEach { runCatching { it.delete() } }
                    return@withContext Capability.Unavailable(
                        "下载 ${artifact.binary} 失败（${MIRRORS.size} 个镜像都不可用）：$lastError",
                    )
                }
            archives += ok
        }

        // ── 破坏性区间（删旧 bin、写新二进制）⇒ 必须独占；本段全是阻塞调用，同一线程收尾 ──
        if (!ProotShell.tryLockRootfsExclusive(LOCK_WAIT_SEC)) {
            archives.forEach { runCatching { it.delete() } }
            return@withContext Capability.Unavailable(
                "本机执行环境正忙（有命令正在跑），本次安装没有开始，临时归档已清理，请稍后重试",
            )
        }
        try {
            installFromArchives(ctx, archives, onProgress)
        } catch (e: Exception) {
            deleteTree(ProotShell.ffmpegDir(ctx))
            archives.forEach { runCatching { it.delete() } }
            Log.e(TAG, "安装中断：${e.javaClass.simpleName}: ${e.message}", e)
            Capability.Unavailable("安装中断：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            ProotShell.unlockRootfsExclusive()
        }
    }

    /**
     * 解压 → 就位 → guest 内验证 → 写标记。
     *
     * ⚠️ 调用方必须已持有 rootfs **独占锁**，且本函数内**不得出现挂起点**
     * （锁要靠同一线程释放）；本函数全是阻塞调用，天然满足。
     *
     * 验证为什么非要在 guest 里真跑一次：二进制"摆对了"不等于"proot 加载得动"——
     * 前者是文件系统事实，后者要过 ptrace + loader，只能跑一遍才知道（同
     * [ProotShell.selftest] 的判据）。验证不过就**整体清掉**：留着一半能跑的 ffmpeg
     * 会让 [isInstalled] 与真实能力长期不一致，而用户看到的"未就绪 + 安装按钮"会引导他
     * 重装——那是自愈路径，比半成品状态好。
     */
    private fun installFromArchives(
        ctx: Context,
        archives: List<File>,
        onProgress: (ProotInstaller.Stage, Int) -> Unit,
    ): Capability<File> {
        onProgress(ProotInstaller.Stage.EXTRACT, -1)

        val target = ProotShell.ffmpegDir(ctx)
        val binDir = File(target, "bin")
        deleteTree(target)
        if (!binDir.exists() && !binDir.mkdirs()) {
            archives.forEach { runCatching { it.delete() } }
            return Capability.Unavailable("无法创建目录：${binDir.absolutePath}")
        }

        for ((index, artifact) in ARTIFACTS.withIndex()) {
            val gz = archives[index]
            val out = File(binDir, artifact.binary)
            if (!gunzipVerified(gz, out, artifact)) {
                deleteTree(target)
                archives.forEach { runCatching { it.delete() } }
                return Capability.Unavailable(
                    "解压或校验失败：${artifact.binary}（gz 的 sha256 由下载器保证，" +
                        "这里复核对解压产物的字节数与 ELF 头）",
                )
            }
            runCatching { gz.delete() }
        }

        onProgress(ProotInstaller.Stage.CONFIGURE, -1)
        // 真跑一次：取的是同线程读锁（RRWL 允许写锁降级，见 ProotShell.ensureCoreUtils 同款用法）
        val check = ProotShell.runCommand(
            ctx,
            "ffmpeg -hide_banner -version | head -1",
            timeoutSec = VERIFY_TIMEOUT_SEC,
        )
        if (!check.ok || !check.stdout.contains(VERSION)) {
            deleteTree(target)
            archives.forEach { runCatching { it.delete() } }
            return Capability.Unavailable(
                "安装后验证失败（ffmpeg -version 跑不通）：${check.stderrTail(3).ifBlank { check.summary() }}",
            )
        }

        writeMarker(ctx)
        onProgress(ProotInstaller.Stage.DONE, 100)
        Log.i(
            TAG,
            "FFmpeg 就绪：${target.absolutePath}（ffmpeg/ffprobe $VERSION，占用 ${diskUsageBytes(ctx) / 1024 / 1024} MB）",
        )
        return Capability.Available(target)
    }

    // ═══════════════════ 内部 ═══════════════════

    /**
     * gz → 裸二进制，落盘前复核对**精确字节数**与 **ELF 头**（aarch64/64 位/小端）。
     *
     * 先写 `.tmp` 再 rename：就位动作是原子的，要么是完整的二进制，要么什么都没有
     * （与 [NodeAddon] 的 staging 同一条判据，只是这里只有单文件、用不上目录级 staging）。
     *
     * @return true = 已就位；false = 任一校验不过（tmp 已清）
     */
    private fun gunzipVerified(gz: File, out: File, artifact: Artifact): Boolean {
        val tmp = File(out.parentFile, "${out.name}.tmp")
        runCatching {
            GZIPInputStream(gz.inputStream().buffered()).use { input ->
                tmp.outputStream().buffered().use { sink -> input.copyTo(sink) }
            }
        }.onFailure {
            Log.w(TAG, "解压 ${artifact.binary} 失败：${it.javaClass.simpleName}: ${it.message}")
            runCatching { tmp.delete() }
            return false
        }
        if (tmp.length() != artifact.bytes) {
            Log.w(TAG, "${artifact.binary} 字节数不对：${tmp.length()} != ${artifact.bytes}")
            runCatching { tmp.delete() }
            return false
        }
        val header = ByteArray(20)
        val read = runCatching { tmp.inputStream().use { it.read(header) } }.getOrDefault(-1)
        val elfOk = read == 20 &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4] == 2.toByte() &&  // EI_CLASS = ELFCLASS64
            header[5] == 1.toByte() &&  // EI_DATA = little endian
            (header[18].toInt() and 0xff) == 183  // e_machine = EM_AARCH64（小端，低位在前）
        if (!elfOk) {
            Log.w(TAG, "${artifact.binary} 不是 aarch64 的 64 位 ELF（read=$read）")
            runCatching { tmp.delete() }
            return false
        }
        // proot 经 loader mmap 加载 guest 二进制，不依赖宿主 x 位（NodeAddon 同样不设）——
        // 补上只是 Belt & Braces：某些排查路径（adb shell file 查看）会看这个位。
        runCatching { tmp.setExecutable(true) }
        if (!tmp.renameTo(out)) {
            Log.w(TAG, "无法就位：${out.absolutePath}")
            runCatching { tmp.delete() }
            return false
        }
        return true
    }

    private fun markerFile(ctx: Context): File = File(ProotShell.extrasDir(ctx), MARKER_NAME)

    private fun writeMarker(ctx: Context) {
        runCatching {
            ProotShell.extrasDir(ctx).mkdirs()
            markerFile(ctx).writeText(
                buildString {
                    appendLine("version=$VERSION")
                    ARTIFACTS.forEach { appendLine("${it.binary}.sha256=${it.sha256}") }
                    appendLine("installed=${System.currentTimeMillis()}")
                },
            )
        }.onFailure { Log.w(TAG, "写版本标记失败：${it.message}") }
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        ProcessRun.runTool("rm", listOf("-rf", dir.absolutePath), 120L)
    }

    /**
     * 目录占用统计。**不跟随符号链接**（与 [NodeAddon] 同款判据：
     * 跟随会在有链接的目录上算重；链接自身按 0 计）。
     */
    private fun dirSize(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
