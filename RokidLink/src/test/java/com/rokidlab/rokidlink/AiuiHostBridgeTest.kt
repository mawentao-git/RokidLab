package com.rokidlab.rokidlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 眼镜端 AIUI 宿主桥的**纯逻辑**回归测试。
 *
 * ## 为什么这些需要单测（它们不在同源覆盖范围内）
 *
 * `AiChannel.kt` 有 phone-app 同源副本，完整协议契约测试在那边（`AiChannelTest` 17 用例）；
 * 但本文件测的三样东西是**眼镜端独有**的，phone-app 里根本没有对应实现，
 * 也就是说它们此前完全没有测试：
 *
 *  1. [AiuiLinkActivity.sanitizeForJs] —— JS 注入前的 U+2028/2029 转义（2026-10-18 审计 M3 新提的共用函数）
 *  2. 工具结果缓存的**判定前置**（type 字段 / cbId 非空）
 *  3. 缓存的取走即删语义（页面轮询 `__lab/tool_result`）
 *
 * ## 判据：这些 bug 的共同特征是「失败完全静默」
 *
 * 三者出错时的用户可见表现都是同一个：**页面转圈直到超时，零报错**。
 * 没有日志、没有异常、没有红字 —— 页面的 Promise 既不 resolve 也不 reject。
 * 这类缺陷不可能靠真机"看着像正常"来判断，只能把语义钉在测试里。
 *
 * ⚠️ 眼镜端测试此前只有 1 个类 / 9 个方法（2026-10-18 审计 M10），
 * 而近 10 个提交全在改眼镜端。这个文件是往回补的第一块。
 */
class AiuiHostBridgeTest {

    // ── sanitizeForJs：U+2028 / U+2029 ──────────────────────────────

    @Test
    fun `U+2028 行分隔符必须被转义`() {
        // 这两个字符是合法 JSON（JSONObject.toString() 不转义），但在 JS 源码里是行终止符。
        // 直接内插进 evaluateJavascript ⇒ 整段脚本语法错误 ⇒ **静默**失败（页面永远等不到回调）。
        val raw = "{\"text\":\"第一段\u2028第二段\"}"
        val safe = AiuiLinkActivity.sanitizeForJsForTest(raw)

        assertFalse("U+2028 不得原样留在 JS 源码里", safe.contains("\u2028"))
        assertTrue("必须转成 JS 字符串内的转义序列", safe.contains("\\u2028"))
        assertTrue("原始文本内容必须保留（转义不是丢弃）", safe.contains("第一段"))
        assertTrue("转义后仍是合法 JSON 文本", JSONObject(safe).optString("text").contains("第二段"))
    }

    @Test
    fun `U+2029 段落分隔符必须被转义`() {
        val safe = AiuiLinkActivity.sanitizeForJsForTest("{\"t\":\"a\u2029b\"}")
        assertFalse("U+2029 也不得原样出现", safe.contains("\u2029"))
        assertTrue(safe.contains("\\u2029"))
    }

    @Test
    fun `两个字符同时出现时都要处理`() {
        val safe = AiuiLinkActivity.sanitizeForJsForTest("{\"t\":\"\u2028x\u2029y\"}")
        assertFalse(safe.contains("\u2028"))
        assertFalse(safe.contains("\u2029"))
        assertEquals(2, safe.split("\\u202").size - 1)
    }

    @Test
    fun `普通 JSON 不被改动`() {
        // 防误伤：中文、emoji、引号、换行都必须原样透传。
        // 📌 若这里被"顺手"做了更多转义，歌词与中文排版会在页面上显示成转义码。
        val raw = """{"caption":"她说\"你好\"","emoji":"🎧","n":42}"""
        assertEquals(raw, AiuiLinkActivity.sanitizeForJsForTest(raw))
    }

    @Test
    fun `空串与无特殊字符的短串`() {
        assertEquals("", AiuiLinkActivity.sanitizeForJsForTest(""))
        assertEquals("{}", AiuiLinkActivity.sanitizeForJsForTest("{}"))
    }

    // ── 工具结果缓存：判定前置 ────────────────────────────────────
    //
    // 页面能不能拿到结果，取决于两道前置：type 必须是 toolResult、cbId 必须非空。
    // 任一道不满足 ⇒ 结果既不进缓存也不唤醒等待者 ⇒ 页面只能等到超时。
    // 这两条判定此前**零覆盖**，而失败模式全是静默的。

    @Test
    fun `toolResult 帧进入缓存并可取走`() {
        val cbId = "bridge-ok-1"
        val frame = JSONObject().put("type", "toolResult").put("cbId", cbId).put("ok", true).toString()

        AiuiLinkActivity.deliverToolResultForTest(frame)

        assertEquals("结果必须能被页面轮询取走", frame, AiuiLinkActivity.cachedToolResultForTest(cbId))
    }

    @Test
    fun `缓存取走即删 不会重复投递`() {
        // 「取走即删」是刻意的：同一 cbId 只该有一次结果，重复投递会让页面收到两次回调。
        val cbId = "bridge-take-once"
        val frame = JSONObject().put("type", "toolResult").put("cbId", cbId).toString()
        AiuiLinkActivity.deliverToolResultForTest(frame)

        assertEquals(frame, AiuiLinkActivity.cachedToolResultForTest(cbId))
        assertNull("第二次取必须是 null", AiuiLinkActivity.cachedToolResultForTest(cbId))
    }

    @Test
    fun `非 toolResult 帧不进缓存`() {
        // 宿主消息（launch / customImgReq 等）走 WebView 投递，不该污染轮询缓存。
        val cbId = "bridge-not-tool-result"
        AiuiLinkActivity.deliverToolResultForTest(
            JSONObject().put("type", "launch").put("cbId", cbId).put("params", JSONObject()).toString(),
        )
        assertNull("launch 帧不得进工具结果缓存", AiuiLinkActivity.cachedToolResultForTest(cbId))
    }

    @Test
    fun `cbId 缺失或空白的帧不进缓存`() {
        // 无 cbId 的结果等于没有地址 —— 进了缓存也没人能取，等于泄漏。
        AiuiLinkActivity.deliverToolResultForTest(JSONObject().put("type", "toolResult").toString())
        assertNull("缺 cbId 的帧不得入缓存", AiuiLinkActivity.cachedToolResultForTest(""))

        val blank = "bridge-blank"
        AiuiLinkActivity.deliverToolResultForTest(
            JSONObject().put("type", "toolResult").put("cbId", "   ").toString(),
        )
        assertNull("空白 cbId 不得入缓存", AiuiLinkActivity.cachedToolResultForTest(blank))
    }

    @Test
    fun `非法 JSON 不抛异常且不入缓存`() {
        // 兜底通道可能在半截写入时拿到残帧；这里必须安静跳过而不是让投递线程崩掉。
        AiuiLinkActivity.deliverToolResultForTest("{not json")
        AiuiLinkActivity.deliverToolResultForTest("")
        assertNull(AiuiLinkActivity.cachedToolResultForTest("bridge-broken"))
    }

    @Test
    fun `失败结果同样要入缓存`() {
        // ⚠️ 这条是本组最容易被改坏的一条：`ok:false` 的结果**必须**回到页面，
        // 否则页面 loading 永转。判据是 type/cbId，不是 ok 的取值 ——
        // 曾经有一版实现按 ok 过滤，失败结果全被丢掉，页面只能等到超时。
        val cbId = "bridge-failed"
        val frame = JSONObject()
            .put("type", "toolResult")
            .put("cbId", cbId)
            .put("ok", false)
            .put("error", "tool timed out")
            .toString()

        AiuiLinkActivity.deliverToolResultForTest(frame)

        val got = AiuiLinkActivity.cachedToolResultForTest(cbId)
        assertTrue("失败结果必须回到页面", got != null)
        assertEquals("tool timed out", JSONObject(got!!).optString("error"))
    }
}