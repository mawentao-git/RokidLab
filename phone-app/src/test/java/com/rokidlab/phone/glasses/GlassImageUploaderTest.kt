package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * GlassImageUploader 纯函数单测（字节解析 + 上限决策，不需要 Context/mock）。
 *
 * 夹具来自**用户设备上真实的 QQ 动画 webp**（2026-10-21 用户报「自定义图保存失败」：
 * /sdcard/Pictures/QQ/Image_1792585812436_451.webp，436766 字节，512×512，
 * VP8X flags=0x12 = ALPHA|ANIMATION）——前 32 字节逐字硬编码，防上游格式识别退化。
 */
class GlassImageUploaderTest {

    /** 用户实拍动画 webp 头部（RIFF/WEBP/VP8X，flags 0x12，canvas 512×512） */
    private val realAnimWebpHeader = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, 0x16, 0xAA.toByte(), 0x06, 0x00, 0x57, 0x45, 0x42, 0x50,
        0x56, 0x50, 0x38, 0x58, 0x0A, 0x00, 0x00, 0x00, 0x12, 0x00, 0x00, 0x00,
        0xFF.toByte(), 0x01, 0x00, 0xFF.toByte(), 0x01, 0x00, 0x41, 0x4E,
    )

    /** 用户实拍文件总大小（QQ 保存，选进「自定义图」后原实现直接 Failed） */
    private val realAnimWebpSize = 436766

    @Test
    fun `真实 QQ 动画 webp 头部识别为动画`() {
        assertTrue(GlassImageUploader.isAnimatedWebp(realAnimWebpHeader))
    }

    @Test
    fun `真实动画 webp 画布尺寸 512x512`() {
        assertEquals(512 to 512, GlassImageUploader.webpCanvasSize(realAnimWebpHeader))
    }

    @Test
    fun `VP8X 无 ANIMATION 位判为静态`() {
        // flags 0x12 → 清掉 bit4(0x10) → 0x02（仅 ALPHA）：静态 webp 不透传
        val staticFlags = realAnimWebpHeader.copyOf().also { it[20] = 0x02 }
        assertFalse(GlassImageUploader.isAnimatedWebp(staticFlags))
    }

    @Test
    fun `非 webp 容器判为非动画`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(29)
        assertFalse(GlassImageUploader.isAnimatedWebp(jpeg))
        assertFalse(GlassImageUploader.isAnimatedWebp(ByteArray(8)))
    }

    @Test
    fun `用户实拍 436KB 动画 webp 必须落降级分支而非透传`() {
        assertFalse(GlassImageUploader.shouldPassThroughAnimWebp(realAnimWebpSize))
    }

    @Test
    fun `透传边界取得到上限本身`() {
        assertTrue(GlassImageUploader.shouldPassThroughAnimWebp(GlassImageUploader.MAX_ANIM_BYTES))
        assertTrue(GlassImageUploader.shouldPassThroughAnimWebp(GlassImageUploader.MAX_ANIM_BYTES - 1))
        assertFalse(GlassImageUploader.shouldPassThroughAnimWebp(GlassImageUploader.MAX_ANIM_BYTES + 1))
    }

    @Test
    fun `MAX_ANIM_BYTES 的 base64 不超通道安全线`() {
        // 不变式：动画 webp 原样透传 ⇒ base64 长度 = 4*ceil(n/3) 必须 ≤ MAX_B64_CHARS。
        // 提 MAX_ANIM_BYTES 前先算这条，否则 CXRLink 通道侧直接炸（Binder 单事务安全线）。
        val b64 = 4 * ceil(GlassImageUploader.MAX_ANIM_BYTES / 3.0).toInt()
        assertTrue("MAX_ANIM_BYTES base64=$b64 > MAX_B64_CHARS", b64 <= GlassImageUploader.MAX_B64_CHARS)
    }

    @Test
    fun `用户实拍文件透传的 base64 必然超通道线`() {
        // 反向论证降级的必要性：436766 字节 base64 后 582356 chars > 400000，
        // 不透传是通道约束、不是实现偷懒——降级首帧是唯一挽救路径。
        val b64 = 4 * ceil(realAnimWebpSize / 3.0).toInt()
        assertTrue(b64 > GlassImageUploader.MAX_B64_CHARS)
    }
}
