package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.cxr.Caps
import java.nio.ByteBuffer

/**
 * KeyButtonService 的悬浮层控制器（v3.9 拆分自 KeyButtonService）。
 *
 * 统一管理眼镜端两类悬浮层（复用同一套 SYSTEM_ALERT_WINDOW 授权，
 * 由手机端经 ADB appops 下发）：
 *  1. 歌词/工具确认文本悬浮层（底部半透明黑条，重复调用仅更新文本）
 *  2. 图片悬浮层（贴屏幕顶部居中的小图 + 说明文字；persist 常驻不自动隐藏，否则 12s 自动隐藏）
 */
internal class GlassesOverlayController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 图片悬浮层自动隐藏时间（persist=false 的传统行为：AI 工具推图 / 歌曲封面） */
        private const val IMAGE_OVERLAY_MS = 12_000L

        /**
         * 图片显示盒子（px，与手机端 `GlassImageUploader.MAX_W/MAX_H` 同源，改这里必须同步那里）：
         * 单绿屏 480×640；2026-10-14 用户实拍「太大了」→ 由 336×200 缩到 224×133（约 2/3）。
         */
        private const val MAX_IMAGE_W = 224
        private const val MAX_IMAGE_H = 133

        /**
         * 贴屏幕顶部的边距（px）。
         * ⚠️ 2026-10-14 实拍修正：原先图坐在「上方面板正中」（y=(460-图高)/2）会压住
         * 官方表盘的大字时钟，用户要的是**顶部居中**。
         */
        private const val TOP_MARGIN_PX = 8
    }

    // ──────────────────────────────────────────────
    //  歌词/确认文本悬浮层
    // ──────────────────────────────────────────────

    private var lyricView: TextView? = null
    private var lyricWm: WindowManager? = null

    /**
     * 悬浮层可用性自检：`TYPE_APPLICATION_OVERLAY` 必须已授予 `SYSTEM_ALERT_WINDOW`，
     * 否则 `addView` 抛异常。
     *
     * ⚠️ 历史教训：原实现把 `addView` 包在 `runCatching {}` 里且**不打日志**，
     * 未授权时悬浮层静默加不上，表现为「歌词/对话文字不显示」但日志里查不到任何错误。
     * 现在改为显式检查 + 失败高声告警（授权由手机端经 ADB appops 下发）。
     */
    private fun canShowOverlay(): Boolean {
        val ok = runCatching { android.provider.Settings.canDrawOverlays(service) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "overlay NOT available: SYSTEM_ALERT_WINDOW not granted to ${service.packageName} " +
                "(手机端应经 adb appops set ${service.packageName} android:system_alert_window allow)")
        }
        return ok
    }

    /** 在眼镜端显示一个半透明悬浮歌词层，文本居中偏下。重复调用仅更新文本。 */
    fun showLyricOverlay(text: String) {
        val view = lyricView ?: run {
            if (!canShowOverlay()) return
            val wmSafe = lyricWm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
                .also { lyricWm = it } ?: return
            val tv = TextView(service).apply {
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                setPadding(28, 18, 28, 18)
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 90
            }
            val added = runCatching { wmSafe.addView(tv, params) }
            added.onFailure { Log.e(TAG, "addView(lyric overlay) failed", it) }
            if (added.isFailure) return
            tv.also { lyricView = it }
        }
        view.text = text
        Log.i(TAG, "lyric overlay: ${text.take(40)}")
    }

    /** 移除悬浮歌词层（文本为空/停止播放时调用）。 */
    fun hideLyricOverlay() {
        lyricView?.let { tv ->
            runCatching { lyricWm?.removeView(tv) }
            lyricView = null
            lyricWm = null
        }
        Log.i(TAG, "lyric overlay hidden")
    }

    // ──────────────────────────────────────────────
    //  图片悬浮层（手机端对话里的图片 → 眼镜端）
    // ──────────────────────────────────────────────

    private var imageContainer: LinearLayout? = null
    private var imageView: ImageView? = null
    private var imageCaption: TextView? = null
    private var imageWm: WindowManager? = null
    private val hideImageRunnable = Runnable { hideImage() }

    /**
     * 图片层显隐回调（主线程触发）：true=图片层挂上、false=图片层收起。
     * HUD 悬浮层据此挂起/恢复（HUD 与图片在 top-center 区域重叠，图片必须盖住 HUD，
     * 见 2026-10-20 用户需求「显示图片的时候能正确覆盖这些 hud」）。
     */
    var onImageVisibilityChanged: ((Boolean) -> Unit)? = null

    /**
     * 手机端下发图片（[AiChannel.TOPIC_SHOW_IMAGE]）：
     * - 静态 JPEG（animated=false）：Base64 → Bitmap → 悬浮层（老路径）；
     * - 动画 WebP（animated=true）：**原始字节透传**，ImageDecoder 解成
     *   [AnimatedImageDrawable] 播放（2026-10-15 用户报「webp 动图不能播放」：
     *   旧链路 BitmapFactory 只出首帧、再压 JPEG，动画必死）。
     *
     * persist=true 常驻不自动隐藏（自定义图）/ false 12s 自动隐藏（AI 工具链路）；
     * 重复下发只换图不重建视图。复用歌词/工具确认同一套悬浮层授权
     * （`SYSTEM_ALERT_WINDOW`，由手机端经 ADB appops 下发）。
     */
    fun handleShowImage(args: Caps?) {
        try {
            val payload = AiChannel.decodeShowImage(capsToStrings(args))
            if (payload == null) {
                Log.w(TAG, "handleShowImage: rejected invalid payload (size=${args?.size()})")
                return
            }
            Log.i(
                TAG,
                "Received show_image: base64Len=${payload.base64.length} " +
                    "caption='${payload.caption.take(30)}' persist=${payload.persist} animated=${payload.animated}",
            )
            core.mainHandler.post {
                val bytes = runCatching { Base64.decode(payload.base64, Base64.DEFAULT) }.getOrNull()
                if (bytes == null) {
                    Log.e(TAG, "handleShowImage: base64 decode failed (len=${payload.base64.length})")
                    return@post
                }
                if (payload.animated) {
                    val drawable = runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
                    }.getOrNull()
                    val anim = drawable as? AnimatedImageDrawable
                    if (anim != null) {
                        showImageOverlay(anim, anim.intrinsicWidth, anim.intrinsicHeight, payload.caption, payload.persist)
                        return@post
                    }
                    // 解不出动画（坏字节/旧 ROM 不支持）→ 退回静态首帧，不白屏
                    Log.w(TAG, "animated webp decode failed, fallback to static first frame")
                }
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bmp == null) {
                    Log.e(TAG, "handleShowImage: bitmap decode failed (bytes=${bytes.size})")
                    return@post
                }
                showImageOverlay(bmp.toDrawable(), bmp.width, bmp.height, payload.caption, payload.persist)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleShowImage error", e)
        }
    }

    /** Bitmap → 常驻 Drawable（不缓存进 resources，避免复用同一张图时状态串） */
    private fun Bitmap.toDrawable(): Drawable =
        android.graphics.drawable.BitmapDrawable(service.resources, this)

    /**
     * 显示/更新悬浮图片层（同一实例复用；贴顶居中，y 固定 [TOP_MARGIN_PX]）。
     *
     * @param drawable 静态 BitmapDrawable 或动画 [AnimatedImageDrawable]（后者会 start()）
     * @param w/h drawable 的固有尺寸（动画 WebP 的画布尺寸可能远大于显示盒子，
     *   必须由本函数按比例缩出 view 尺寸，不能信 intrinsic 直接上屏）
     */
    private fun showImageOverlay(drawable: Drawable, w: Int, h: Int, caption: String, persist: Boolean) {
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "showImageOverlay: bad intrinsic size ${w}x${h}")
            return
        }
        // contain 缩放进显示盒子（224×133，不铺满）：手机端已按同规格压过静态图，这里是兜底；
        // 动画路径透传不压，全靠这一步把 view 框进盒子（ImageView FIT_CENTER 再缩绘制）。
        val ratio = minOf(MAX_IMAGE_W.toFloat() / w, MAX_IMAGE_H.toFloat() / h, 1f)
        val vw = (w * ratio).toInt().coerceAtLeast(1)
        val vh = (h * ratio).toInt().coerceAtLeast(1)
        val container = imageContainer ?: run {
            if (!canShowOverlay()) return
            val wmSafe = imageWm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
                .also { imageWm = it } ?: return
            val iv = ImageView(service).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val tv = TextView(service).apply {
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 0)
            }
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                // 背景全透明（用户 2026-10-15：「把图的背景改成透明色」）：撤掉原先
                // 95% 不透明黑底板，让图片（含 webp 自带 alpha）直接浮在屏幕上；
                // caption 文字靠 shadowLayer 保证在绿屏上可读。padding 一并撤掉——
                // 透明后内边距只会把图推离中心。
                setPadding(0, 0, 0, 0)
                addView(iv)
                addView(tv)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = TOP_MARGIN_PX
            }
            val added = runCatching { wmSafe.addView(box, params) }
            added.onFailure { Log.e(TAG, "addView(image overlay) failed", it) }
            if (added.isFailure) return
            imageView = iv
            imageCaption = tv
            box.also { imageContainer = it }
        }
        imageView?.apply {
            // 换图（换 drawable / 换尺寸）都必须重摆 layoutParams：动画的 intrinsic 尺寸
            // 与静态图不同，不重摆会留着上一张的 view 大小把图拉伸
            layoutParams = LinearLayout.LayoutParams(vw, vh)
            setImageDrawable(drawable)
        }
        (drawable as? android.graphics.drawable.Animatable)?.start()
        imageCaption?.apply {
            text = caption
            visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        }
        // 位置重报（y 固定；换图后容器内容尺寸可能变化，update 一次兜底）
        val update = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = TOP_MARGIN_PX
        }
        runCatching { imageWm?.updateViewLayout(container, update) }
            .onFailure { Log.w(TAG, "updateViewLayout(image overlay) failed: ${it.message}") }
        // 生命周期：persist = 不自动隐藏（自定义图，只在「主界面离开且语音助手窗口过期」、
        // 或收到 image_hide 时收起，见 CxrBridgeCoordinator.syncCustomImageVisibility）；
        // 否则 12s 自动隐藏（AI 工具推图、歌曲封面链路的老行为，零变化）。
        core.mainHandler.removeCallbacks(hideImageRunnable)
        if (!persist) {
            core.mainHandler.postDelayed(hideImageRunnable, IMAGE_OVERLAY_MS)
        }
        onImageVisibilityChanged?.invoke(true)
        Log.i(
            TAG,
            "image overlay: ${vw}x${vh} (intrinsic ${w}x${h}, animated=${drawable is AnimatedImageDrawable}) " +
                "y=${update.y} persist=$persist caption='${caption.take(30)}'",
        )
    }

    /** dp → px（本类内部小工具，避免每个调用点都写 resources.displayMetrics） */
    private fun dpPx(v: Float): Float = v * service.resources.displayMetrics.density

    /**
     * 移除悬浮图片层。
     *
     * 对外公开两个用途：① 手机端开关关掉时经 `image_hide` 指令收起；
     * ② 主界面离开前台**且**语音助手活动窗口过期时收起（决策见
     *    `CxrBridgeCoordinator.syncCustomImageVisibility`，判定纯函数在
     *    `AssistantPageWatcher.shouldShowCustomImage`）。
     * persist 常驻图**没有无脑超时兜底**（用户明确不要 60s 闪没），收起只认这两类显式信号。
     */
    fun hideImage() {
        core.mainHandler.removeCallbacks(hideImageRunnable)
        // 先停动画再摘视图：AnimatedImageDrawable 不 stop 的话，removeView 后帧回调仍会跑
        (imageView?.drawable as? android.graphics.drawable.Animatable)?.stop()
        imageContainer?.let { box ->
            runCatching { imageWm?.removeView(box) }
            imageContainer = null
            imageView = null
            imageCaption = null
            imageWm = null
            onImageVisibilityChanged?.invoke(false)
            Log.i(TAG, "image overlay hidden")
        }
    }

    /**
     * 当前是否正挂着图。
     *
     * 眼镜端据此决定要不要再向手机端要一次图：助手页每次进入前台都会发请求，
     * 但图已经挂着时重复要图 = 白白重发几十 KB Base64。
     */
    fun isImageVisible(): Boolean = imageContainer != null

    /** 服务销毁时移除全部悬浮层 */
    fun release() {
        hideLyricOverlay()
        hideImage()
    }
}
