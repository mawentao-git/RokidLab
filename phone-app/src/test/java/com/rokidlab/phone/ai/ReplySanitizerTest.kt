package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回复正文清洗的回归测试（[ReplySanitizer]）。
 *
 * 背景（2026-09-17 真机事故）：system prompt 用了 `<memories>` / `<skills>` 这类 XML 风格标签，
 * 模型模仿该风格把正文包成 `<answer>西安明天晴，最高 31 度…`，整段原样显示到眼镜上
 * （用户实测「第二次回复为什么有 answer」）。本测试锁住清洗行为，同时锁住"不误伤正文"的边界。
 */
class ReplySanitizerTest {

    @Test
    fun `剥掉整段包裹的 answer 标签`() {
        assertEquals(
            "西安明天晴，最高31度。",
            ReplySanitizer.sanitize("<answer>西安明天晴，最高31度。</answer>"),
        )
    }

    @Test
    fun `模型先吐思考过程时只取最后一个块`() {
        assertEquals(
            "西安明天晴。",
            ReplySanitizer.sanitize("<thinking>先查一下天气工具</thinking><answer>西安明天晴。</answer>"),
        )
    }

    @Test
    fun `带属性的标签也能剥掉`() {
        assertEquals("结论", ReplySanitizer.sanitize("<answer lang=\"zh\">结论</answer>"))
    }

    @Test
    fun `没闭合的开标签也剥掉`() {
        assertEquals("西安明天晴。", ReplySanitizer.sanitize("<answer>西安明天晴。"))
    }

    @Test
    fun `嵌套包裹反复剥`() {
        assertEquals("正文", ReplySanitizer.sanitize("<response><answer>正文</answer></response>"))
    }

    @Test
    fun `正文内部的尖括号不受影响`() {
        assertEquals(
            "带 <b>粗体</b> 的正文",
            ReplySanitizer.sanitize("<answer>带 <b>粗体</b> 的正文</answer>"),
        )
    }

    @Test
    fun `正常回复原样返回`() {
        val normal = "西安明天晴，最高 31 度，比今天热一些，出门注意防晒。"
        assertEquals(normal, ReplySanitizer.sanitize(normal))
    }

    @Test
    fun `正文里出现尖括号但不在开头时不处理`() {
        val s = "用 <answer> 标签包起来的写法不推荐"
        assertEquals(s, ReplySanitizer.sanitize(s))
    }

    @Test
    fun `空串安全`() {
        assertEquals("", ReplySanitizer.sanitize(""))
        assertEquals("", ReplySanitizer.sanitize("   "))
    }

    @Test
    fun `块后还有正文时不丢字`() {
        assertEquals("正文 尾巴", ReplySanitizer.sanitize("<answer>正文</answer>尾巴"))
    }

    // ═══════════ sanitizeForGlass：眼镜通道的 Markdown 剥离 ═══════════

    @Test
    fun `眼镜通道真斜体仍然剥掉星号`() {
        assertEquals(
            "这是重点内容",
            ReplySanitizer.sanitizeForGlass("这是*重点*内容"),
        )
    }

    @Test
    fun `眼镜通道乘号星号不被当斜体误伤`() {
        // 2026-10-17 审计修：`3 * 4 * 5` 的星号是数学乘号（两侧有空格），
        // 旧 ITALIC 正则会把「* 4 *」当斜体挖掉星号，念出来变成"3 4 5"。
        assertEquals(
            "计算结果是 3 * 4 * 5 = 60",
            ReplySanitizer.sanitizeForGlass("计算结果是 3 * 4 * 5 = 60"),
        )
    }
}
