package com.rokidlab.phone.glasses

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import java.io.File

/**
 * 「自定义图」状态与存储 —— 手机端是唯一真相源。
 *
 * ## 语义（用户 2026-10-14 明确要求，与早先版本不同；2026-10-24 修订显示时机）
 * - 设置页是一个**开关**：打开后可以选一张图存起来；
 * - **选完当下不推、不显示**；
 * - 只有眼镜端**主界面在前台、或语音助手正在交互**时，眼镜端才上行
 *   [AiChannel.TOPIC_CUSTOM_IMG_REQ] 来要图，本类才回一张 `show_image(persist=true)`
 *   （主界面上常驻、无自动隐藏超时；语音助手场景按活动窗口显示）；
 * - 开关关掉时下发 [AiChannel.TOPIC_IMAGE_HIDE] 让眼镜立刻收起（常驻图没有超时兜底，
 *   这条指令是"关开关立刻生效"的唯一路径）。
 *
 * ## 为什么图存在手机而不是眼镜
 * 眼镜端不持久化任何用户数据（换图/重启即丢，且眼镜存储与生命周期都不可控）；
 * 手机端存图 + 眼镜端按需来取，眼镜重启、换图都不用重新配。
 *
 * ## 存储形态
 * app 私有目录下的**原始字节**（不是 Base64 文本——落盘是二进制、每次要发时才编码，
 * 避免长期占空间也避免文本被 prefs 序列化二次处理）。按格式分两个文件名：
 * - [FILE_NAME_JPEG] 静态图（jpeg/png/静态 webp 都压成 JPEG 存；**动画 webp 超通道
 *   上限时也降级存首帧 JPEG**，见 GlassImageUploader 类注释）；
 * - [FILE_NAME_WEBP] 动画 WebP **原始字节**（透传保动画，见 [GlassImageUploader]）。
 *
 * 两者互斥：保存新图成功后删掉另一种格式的旧文件，`hasImage`/取图按现存文件判断。
 */
internal object GlassCustomImage {
    private const val TAG = "GlassCustomImage"
    private const val PREFS_NAME = "glass_custom_image"
    private const val KEY_ENABLED = "enabled"
    private const val FILE_NAME_JPEG = "custom_image.jpg"
    private const val FILE_NAME_WEBP = "custom_image.webp"

    /**
     * 保存结果三态 + 降级态：
     * - [OK] 存好（动画图透传或静态图压缩）；
     * - [OK_DEGRADED] 动画图超通道上限、已降级为**首帧静态图**（用户要被告知"动画没存成"）；
     * - [BAD_FORMAT] 格式不支持；
     * - [FAILED] 读取/解码失败等真异常。
     */
    enum class SaveResult { OK, OK_DEGRADED, BAD_FORMAT, FAILED }

    // ── 开关与图状态 ────────────────────────────────────────────────

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun hasImage(ctx: Context): Boolean = storedFile(ctx) != null

    /** 已存图大小（KB），设置页展示用；没存图返回 0 */
    fun imageSizeKb(ctx: Context): Int {
        val (f, _) = storedFile(ctx) ?: return 0
        return if (f.isFile) (f.length() / 1024L).toInt() else 0
    }

    /**
     * 现存图文件 + 是否动画（互斥两文件里挑存在的那个；都没有返回 null）。
     * 返回 first=文件 second=animated。
     */
    private fun storedFile(ctx: Context): Pair<File, Boolean>? {
        val webp = File(ctx.applicationContext.filesDir, FILE_NAME_WEBP)
        if (webp.isFile && webp.length() > 0L) return webp to true
        val jpg = File(ctx.applicationContext.filesDir, FILE_NAME_JPEG)
        if (jpg.isFile && jpg.length() > 0L) return jpg to false
        return null
    }

    /**
     * 选图 → 编码 → 落盘。**阻塞调用，必须在 IO 线程执行**。
     *
     * 只存不推：显示时机由眼镜端助手页 presence 决定（见类注释）。
     * 动画 WebP 原样落盘（保动画），超上限降级首帧 JPEG；其余压成 JPEG；
     * 成功后删掉另一种格式的旧文件。
     */
    fun saveFromUri(ctx: Context, uri: Uri): SaveResult =
        when (val r = GlassImageUploader.encode(ctx, uri)) {
            is GlassImageUploader.EncodeResult.Ok -> {
                // 动画透传 → webp 文件；降级首帧/静态 → jpg 文件（互斥，见 storedFile）
                val target = File(
                    ctx.applicationContext.filesDir,
                    if (r.animated) FILE_NAME_WEBP else FILE_NAME_JPEG,
                )
                val stale = File(
                    ctx.applicationContext.filesDir,
                    if (r.animated) FILE_NAME_JPEG else FILE_NAME_WEBP,
                )
                val bytes = Base64.decode(r.base64, Base64.DEFAULT)
                val ok = runCatching { target.writeBytes(bytes) }
                    .onFailure { Log.e(TAG, "write image failed: ${it.message}") }
                    .isSuccess
                if (ok) {
                    if (stale.isFile) runCatching { stale.delete() }
                    Log.i(
                        TAG,
                        "custom image saved: ${bytes.size} bytes (${r.width}x${r.height}, " +
                            "animated=${r.animated}, degradedFromAnim=${r.degradedFromAnim})",
                    )
                }
                when {
                    !ok -> SaveResult.FAILED
                    r.degradedFromAnim -> SaveResult.OK_DEGRADED
                    else -> SaveResult.OK
                }
            }
            GlassImageUploader.EncodeResult.BadFormat -> SaveResult.BAD_FORMAT
            is GlassImageUploader.EncodeResult.Failed -> {
                Log.w(TAG, "encode failed: ${r.reason}")
                SaveResult.FAILED
            }
        }

    // ── 对眼镜端应答 ────────────────────────────────────────────────

    /**
     * 眼镜端要图（`custom_img_req`）：开关开着且已存图才回，否则**静默不回**。
     *
     * 静默是对的：眼镜端只是"问了一句"，没图/没开关是正常状态，不是错误，不必回错误码。
     *
     * 眼镜端要图的时机 = 主界面在前台 **或** 语音助手活动窗口热（2026-10-24 起，
     * 见 CxrBridgeCoordinator.syncCustomImageVisibility）；其余界面眼镜端不会来要。
     *
     * @return true = 真的发出去了（便于日志与测试断言）
     */
    fun publishIfEnabled(ctx: Context): Boolean {
        if (!isEnabled(ctx)) {
            Log.i(TAG, "custom image: disabled, not publishing")
            return false
        }
        val (file, animated) = storedFile(ctx) ?: run {
            Log.i(TAG, "custom image: enabled but no image stored, not publishing")
            return false
        }
        val b64 = runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }
            .onFailure { Log.e(TAG, "read image failed: ${it.message}") }
            .getOrNull() ?: return false
        val ok = send(
            ctx,
            AiChannel.TOPIC_SHOW_IMAGE,
            AiChannel.encodeShowImage(b64, "", persist = true, animated = animated),
        )
        Log.i(TAG, "custom image published (persist=true, animated=$animated, b64=${b64.length}) -> $ok")
        return ok
    }

    /**
     * 开关关掉时通知眼镜收起常驻图。
     *
     * ⚠️ 链路不通时只记日志不重试：常驻图最终会被"助手页离开"这条兜底路径收掉，
     * 而为了一个视觉反馈去拉起 RokidLink/重试 ADB 不划算（用户看不到区别，只会等）。
     */
    fun sendHide(ctx: Context) {
        val ok = send(ctx, AiChannel.TOPIC_IMAGE_HIDE, AiChannel.encodeImageHide())
        Log.i(TAG, "custom image hide sent -> $ok")
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun send(ctx: Context, topic: String, fields: List<String>): Boolean {
        val session = GlassToolConfirmChannel.global.liveSession()
        if (session == null) {
            Log.w(TAG, "send($topic) skipped: no live session")
            return false
        }
        val link = session.cxrLink
        if (link == null) {
            Log.w(TAG, "send($topic) skipped: no CXR link")
            return false
        }
        return runCatching {
            val caps = Caps()
            for (f in fields) caps.write(f)
            session.rawSendCustomCmd(link, topic, caps) == 0
        }.onFailure { Log.w(TAG, "send($topic) failed: ${it.message}") }.getOrDefault(false)
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
