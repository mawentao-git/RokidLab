package com.rokidlab.phone.ai.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelCapabilityProbe.looksLikeImageRejection] 的护栏测试。
 *
 * 锁的是 2026-10-17 的误判修复：错误正文**提到图** ≠ **说不支持图**。
 * 火山方舟错误码表（docs/82379/1299023）里有一大批正文带 image/图像 但与
 * "模型能不能吃图"无关的报错 —— 旧版单条件分类把它们全读成 Rejected，
 * 落盘 false 并自动关掉图像理解开关，表现为 DOUBAO-SEED-2.1-LITE
 * 「检测不通过、不能使用图像理解」（该模型实为多模态，检查是误判）。
 *
 * 不变式：**只有服务端明确说出"不支持（图像）"，才允许下否定结论**；
 * 其余一律 Inconclusive（保持原判断）—— 判错方向的代价不对称。
 */
class ModelCapabilityProbeTest {

    // ═══════════ 带图字但不是"不支持"：必须判否（走 Inconclusive） ═══════════

    @Test
    fun `限流审核与图片URL类报错不得判为模型不支持图像`() {
        // 429 IPM 限流："Images Per Minute" 是图太多，不是不能吃图
        assertFalse(
            "限流报错不得判为不支持图像",
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"code":"ModelAccountIpmRateLimitExceeded","message":"The request has exceeded the IPM (Images Per Minute) limit of the model."}}""",
            ),
        )
        // 图像内容审核：模型支持图像，只是这张图没过审
        assertFalse(
            "图像审核拒绝不得判为模型不支持图像",
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"code":"InputImageSensitiveContentDetected","message":"The request failed because the input image may contain sensitive information."}}""",
            ),
        )
        // 探测图自身的 base64 问题：与模型能力无关
        assertFalse(
            "base64 解析失败不得判为不支持图像",
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"code":"InvalidImageURL.InvalidFormat","message":"Invalid base64 image url"}}""",
            ),
        )
        // 图+文超上下文：上下文问题，不是能力问题
        assertFalse(
            "上下文超限不得判为不支持图像",
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"code":"OutofContextError","message":"Total tokens of image and text exceed max message tokens."}}""",
            ),
        )
        // 连图字都没有的报错（密钥/参数）自然判否
        assertFalse(
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"code":"AuthenticationError","message":"The API key in the request is missing or invalid."}}""",
            ),
        )
        assertFalse(ModelCapabilityProbe.looksLikeImageRejection(""))
    }

    @Test
    fun `只说不支持但没提图的报错同样不判否定`() {
        // "only supports text" 没提图 —— 按不对称取舍保持未知（开关仍可用，失败回退 OCR），
        // 而不是冒险把"text"语境误读成图像能力结论。此用例锁定这个方向。
        assertFalse(
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"message":"This model only supports text input."}}""",
            ),
        )
    }

    // ═══════════ 明确的"不支持图像输入"：才允许下否定结论 ═══════════

    @Test
    fun `明确说不支持图像输入的报错判为不支持`() {
        // OpenAI 系措辞（"image_url ... not supported"）
        assertTrue(
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"message":"Invalid parameter: messages with content array type 'image_url' are not supported by this model."}}""",
            ),
        )
        // 中文措辞（聚合站/代理常见）
        assertTrue(
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"message":"当前模型不支持图片输入"}}""",
            ),
        )
        // unsupported 措辞
        assertTrue(
            ModelCapabilityProbe.looksLikeImageRejection(
                """{"error":{"message":"Multimodal input is unsupported for this model"}}""",
            ),
        )
    }
}
