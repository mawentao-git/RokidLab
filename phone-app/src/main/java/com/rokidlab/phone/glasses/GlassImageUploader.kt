package com.rokidlab.phone.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 本地图片 → 眼镜通道规格的**纯编码**管线（零模型参与）。
 *
 * ## 只编码，不负责发送
 * 2026-10-14 用户改需求：选图当下**不直接推**；图由眼镜端在「助手页进前台」时上行
 * `custom_img_req`、手机端才回（见 [GlassCustomImage]）。所以这里只负责把本地 Uri
 * 变成一段可下发的 Base64；是否发、什么时候发由状态层决定。
 *
 * ## 格式固定死 jpeg / webp / png 三层过滤
 * 1. 选图器（SAF OpenDocument）MIME 白名单：系统选择器里就只列这三种；
 * 2. 解码后 [BitmapFactory.Options.outMimeType] 二次校验（API 26+ 公开）——
 *    扩展名/MIME 都可伪造（把 GIF 改名 .png），这层是真正的闸门；
 * 3. BitmapFactory 本身能解 GIF 首帧/BMP 等，不校验就会显示——所以第 2 层不能省。
 *
 * ## 两条编码路径（2026-10-15 用户报「webp 动图不能播放」后新增动画路径）
 * - **静态图**（jpeg/png/静态 webp）：inSampleSize 降采样 → EXIF 校正 → contain 缩放进
 *   眼镜显示盒子 → JPEG q82 → Base64。协议字段 `animated=false`。
 * - **动画 WebP**（RIFF VP8X chunk 的 ANIMATION 标志位）且体积 ≤ [MAX_ANIM_BYTES]：
 *   **原始字节原样 Base64 透传，不解码不缩放不转码**。协议字段 `animated=true`，
 *   眼镜端用 AnimatedImageDrawable 播放。
 *   为什么必须原样：BitmapFactory 解 WebP 只出首帧，一进 JPEG 管线动画就死了；
 *   Android 也没有内置 WebP 重编码器，重编码等于掉帧。
 *
 * ## 动画 WebP 超限 → 降级首帧（2026-10-21 用户实测：QQ 存的 436KB 动画 webp
 * 选进「自定义图」直接弹「保存失败」，用户以为手机坏了——其实只是超了通道护栏）
 * 超 [MAX_ANIM_BYTES] 时**不再直接失败**：BitmapFactory 解动画 WebP 本来就只出首帧，
 * 落静态 JPEG 管线即可，用户至少拿到一张常驻图（[EncodeResult.Ok.degradedFromAnim]）。
 * Base64 后 4*ceil(n/3) 必须 ≤ [MAX_B64_CHARS]（Binder 单事务安全线，与
 * DisplayToolProvider / ChartGlassPush 同一口径），所以 [MAX_ANIM_BYTES] = 300000
 * 正好顶线——**提高上限前先算 base64**，否则通道侧直接炸。
 * 代价是体积不压，所以卡上限；超限的唯一挽救就是降级首帧。
 *
 * ## 尺寸与压缩（与眼镜端显示规格同源，改这里必须同步 GlassesOverlayController）
 * 眼镜单绿屏 480×640；2026-10-14 用户实拍「图太大了」→ 显示盒子由 336×200 缩到
 * 224×133，所以静态路径按同规格压：传得多余的像素只会拖慢蓝牙、眼镜端还要再缩一次。
 */
internal object GlassImageUploader {
    private const val TAG = "GlassImageUploader"

    /** 与眼镜端 GlassesOverlayController.MAX_IMAGE_W/MAX_IMAGE_H 同源（改一处必须同步另一处） */
    const val MAX_W = 224
    const val MAX_H = 133

    /** JPEG 输出质量：82 是体积与观感的平衡点（单绿屏上 JPEG 块效应不可见） */
    private const val JPEG_QUALITY = 82

    /** Base64 载荷上限（与 DisplayToolProvider / ChartGlassPush 同一口径：Binder 单事务安全线） */
    internal const val MAX_B64_CHARS = 400_000

    /**
     * 动画 WebP 原始字节上限：Base64 长度 = 4*ceil(n/3)，300000 字节 → 正好 400000 chars。
     * 超过走**降级首帧**（见类注释），不再直接拒绝。
     */
    internal const val MAX_ANIM_BYTES = 300_000

    /** 允许的图片 MIME（固定死三种，见类注释第 2 层过滤）；image/jpg 是部分 ROM 对 JPEG 的非标写法 */
    private val ALLOWED_MIME = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

    /**
     * 动画 WebP 是否走原样透传（≤上限）；超限返回 false，调用方降级首帧。
     * 提成纯函数供单测锁定边界（用户实测文件 436766 字节必须落降级分支）。
     */
    internal fun shouldPassThroughAnimWebp(size: Int): Boolean = size <= MAX_ANIM_BYTES

    /** 编码结果：坏格式与失败必须分开（用户要看到"不支持这种格式"而不是"保存失败"） */
    sealed interface EncodeResult {
        data class Ok(
            val base64: String,
            val width: Int,
            val height: Int,
            /** true = [base64] 是动画 WebP 原始字节；false = 静态 JPEG 字节 */
            val animated: Boolean = false,
            /**
             * true = 动画 WebP 超上限、已降级为**首帧 JPEG**（[animated] 必为 false）。
             * 调用方应如实告知用户"动画太大，存的是首帧"，别让用户以为存了整段动画。
             */
            val degradedFromAnim: Boolean = false,
        ) : EncodeResult
        data object BadFormat : EncodeResult
        /** [reason] 是给用户看的原因（读取失败/解码失败/体积超通道上限），别吞掉 */
        data class Failed(val reason: String) : EncodeResult
    }

    /**
     * 把本地图片编码成眼镜通道规格载荷。**阻塞调用，必须在 IO 线程执行**。
     *
     * 动画 WebP 走原样透传（见类注释），超上限降级首帧，其余走「降采样 → EXIF → 缩放 → JPEG」静态路径。
     */
    fun encode(ctx: Context, uri: Uri): EncodeResult {
        // ── 1. 读全量字节（静态路径的 EXIF 校正本来也要读，索性一次读够）──
        val rawBytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.onFailure { Log.w(TAG, "read failed: ${it.message}") }.getOrNull()
            ?: return EncodeResult.Failed("无法读取该文件")

        // ── 2. 格式校验（真闸门）：outMimeType 不认三种之一直接拒 ──
        // ⚠️ 归一化后比对（2026-10-21 用户报 WEBP 不认）：outMimeType 写法由各 ROM 解码器
        //   决定——大小写不稳、JPEG 有返回非标 "image/jpg" 的、有的带 "; ..." 参数尾巴。
        //   字面严格匹配会把合法图误判 BAD_FORMAT。文件扩展名大小写与本闸门无关。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val mime = bounds.outMimeType?.trim()?.lowercase()?.substringBefore(';')
        if (mime == null || mime !in ALLOWED_MIME || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.i(TAG, "reject: mime=$mime size=${bounds.outWidth}x${bounds.outHeight}")
            return EncodeResult.BadFormat
        }

        // ── 3. 动画 WebP：≤上限原样透传；超上限降级首帧（BitmapFactory 只出首帧，落静态管线）──
        var degradedFromAnim = false
        if (mime == "image/webp" && isAnimatedWebp(rawBytes)) {
            if (shouldPassThroughAnimWebp(rawBytes.size)) {
                val (w, h) = webpCanvasSize(rawBytes)
                val b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                Log.i(TAG, "animated webp passthrough: ${w}x${h} ${rawBytes.size} bytes -> b64=${b64.length}")
                return EncodeResult.Ok(b64, w, h, animated = true)
            }
            Log.w(TAG, "animated webp too large: ${rawBytes.size} bytes > $MAX_ANIM_BYTES, degrade to first frame")
            degradedFromAnim = true
        }

        // ── 4. 降采样解码（按目标盒子算 inSampleSize，避免解原图 OOM）──
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_W, MAX_H)
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                rawBytes, 0, rawBytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull() ?: return EncodeResult.Failed("图片解码失败")

        // ── 5. EXIF 方向校正（BitmapFactory 不读 EXIF，竖拍照片不校正会横躺）──
        val oriented = applyExifOrientation(rawBytes, decoded)

        // ── 6. contain 缩放进眼镜显示盒子 ──
        val ratio = minOf(MAX_W.toFloat() / oriented.width, MAX_H.toFloat() / oriented.height, 1f)
        val w = (oriented.width * ratio).toInt().coerceAtLeast(1)
        val h = (oriented.height * ratio).toInt().coerceAtLeast(1)
        val scaled = if (w == oriented.width && h == oriented.height) {
            oriented
        } else {
            runCatching { Bitmap.createScaledBitmap(oriented, w, h, true) }.getOrNull() ?: oriented
        }

        // ── 7. 压缩 + Base64（降质阶梯兜底）──
        var b64 = compressToBase64(scaled, JPEG_QUALITY)
        if (b64.length > MAX_B64_CHARS) {
            b64 = compressToBase64(scaled, 60)
        }
        if (b64.length > MAX_B64_CHARS) {
            val half = runCatching {
                Bitmap.createScaledBitmap(scaled, (scaled.width / 2).coerceAtLeast(1), (scaled.height / 2).coerceAtLeast(1), true)
            }.getOrNull()
            if (half != null) b64 = compressToBase64(half, 60)
        }
        if (b64.length > MAX_B64_CHARS) {
            Log.w(TAG, "image too large for glasses channel (${b64.length} chars)")
            return EncodeResult.Failed("图片体积超出眼镜通道上限")
        }
        Log.i(TAG, "encoded ${scaled.width}x${scaled.height} mime=$mime b64=${b64.length} chars degradedFromAnim=$degradedFromAnim")
        return EncodeResult.Ok(b64, scaled.width, scaled.height, degradedFromAnim = degradedFromAnim)
    }

    /**
     * WebP 动画判定：`RIFF????WEBPVP8X` 头 + VP8X flags 的 bit 4（0x10 = ANIMATION）。
     *
     * 简单 WebP（VP8/VP8L chunk）不带动画；动画 WebP 规范上必带 VP8X 扩展头。
     * 逐字节手解而不是引库：只需要读一个标志位。
     */
    internal fun isAnimatedWebp(bytes: ByteArray): Boolean {
        if (bytes.size < 32) return false
        fun ascii(off: Int) = String(bytes, off, 4, Charsets.US_ASCII)
        if (ascii(0) != "RIFF" || ascii(8) != "WEBP" || ascii(12) != "VP8X") return false
        return (bytes[20].toInt() and 0x10) != 0
    }

    /** VP8X 扩展头里的画布尺寸（3 字节 LE 的 (尺寸-1)，动画 WebP 必有；拿不到退回 0） */
    internal fun webpCanvasSize(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < 30) return 0 to 0
        fun le24(off: Int) =
            (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8) or ((bytes[off + 2].toInt() and 0xFF) shl 16)
        return (le24(24) + 1) to (le24(27) + 1)
    }

    /** 2 的幂降采样：解码到「不小于目标盒子的 2 倍」以内，再交给精确缩放（Bitmap 官方配方）。 */
    private fun computeInSampleSize(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (w / sample >= reqW * 2 || h / sample >= reqH * 2) sample *= 2
        return sample
    }

    private fun applyExifOrientation(raw: ByteArray, bmp: Bitmap): Bitmap = runCatching {
        val orientation = ExifInterface(raw.inputStream()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }.getOrDefault(bmp)

    private fun compressToBase64(bmp: Bitmap, quality: Int): String {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
