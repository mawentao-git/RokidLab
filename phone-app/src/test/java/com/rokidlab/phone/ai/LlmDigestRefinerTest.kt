package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LlmDigestRefiner.buildDigestPrompt] 的七段式契约（2026-10-23）。
 *
 * 为什么值得单测一个 prompt：七段式是**结构性要求**（"该记什么"不再靠模型自行发挥），
 * 它防的失效模式是"待办/下一步被模型平均分配预算时挤掉" —— 这类退化不报错、不崩，
 * 只是用户追问时助手像失忆。prompt 文本就是这条要求的唯一载体，必须有用例钉住。
 */
class LlmDigestRefinerTest {

    @Test
    fun `prompt 覆盖七段式全部侧面`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("user" to "帮我把首页改成深色", "assistant" to "已经改了 index.ink"),
            budget = 800,
        )
        listOf(
            "最初要什么",
            "文件或资源",
            "坑与错误",
            "还没做完的事",
            "正在做什么",
            "下一步做什么",
            "关键上下文",
        ).forEach { facet ->
            assertTrue("prompt 缺七段侧面: $facet", prompt.contains(facet))
        }
    }

    @Test
    fun `待办与下一步被硬性要求保留`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("user" to "继续"),
            budget = 500,
        )
        assertTrue(prompt.contains("待办与下一步一条都不能丢"))
        assertTrue(prompt.contains("优先保 ④⑥"))
    }

    @Test
    fun `既有摘要必须保留且要求新旧合并`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("system" to "· 旧结论：接口用 v2", "user" to "继续"),
            budget = 500,
        )
        assertTrue(prompt.contains("【既有摘要】"))
        assertTrue(prompt.contains("必须保留其中的要点"))
        assertTrue(prompt.contains("合并"))
    }

    @Test
    fun `对话按角色标注且不把既有摘要谎报成 AI`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("user" to "问题", "assistant" to "回答", "system" to "旧摘要"),
            budget = 300,
        )
        assertTrue(prompt.contains("用户：问题"))
        assertTrue(prompt.contains("AI：回答"))
        assertTrue(prompt.contains("【既有摘要】\n旧摘要"))
        assertTrue(!prompt.contains("AI：旧摘要"))
    }

    @Test
    fun `预算透传进 prompt`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("user" to "x"),
            budget = 777,
        )
        assertTrue(prompt.contains("777"))
    }

    @Test
    fun `输出格式要求不变_要点行仍以点开头`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(
            listOf("user" to "x"),
            budget = 300,
        )
        // 解析层按 "· "/"-"/"•" 收行，格式要求改了解析就会静默失效
        assertTrue(prompt.contains("每行以「· 」开头"))
    }

    @Test
    fun `空输入也产出合法 prompt`() {
        val prompt = LlmDigestRefiner.buildDigestPrompt(emptyList(), budget = 100)
        assertTrue(prompt.contains("对话记录："))
        assertEquals('\n', prompt.last())
    }
}
