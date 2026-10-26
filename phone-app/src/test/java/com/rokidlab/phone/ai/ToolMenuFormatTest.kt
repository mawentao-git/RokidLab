package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 菜单分组渲染与 when_to_use 抽取（2026-10-23，菜单结构化改造）。
 *
 * 锁三类不变式：① 分组/保序/确定性（同输入 ⇒ 逐字节同输出，前缀缓存纪律）；
 * ② when_to_Use 取首句而非定长截断（信号密度）；③ 空值不产出孤号。
 */
class ToolMenuFormatTest {

    // ── whenToUseOf ──────────────────────────────────────────────────────

    @Test
    fun `when-to-use takes the first sentence, not a fixed-length cut`() {
        // 定长 take(40) 会砍出半句话；首句在 1..maxLen 内时优先整句
        val desc = "检索本地知识库文档。检索词给文档标题里的词或专有名词（原文用词），不要口语化转述"
        assertEquals("检索本地知识库文档", ToolRegistry.whenToUseOf(desc))
    }

    @Test
    fun `when-to-use falls back to length cut when no early sentence break`() {
        val desc = "一".repeat(80)
        assertEquals(60, ToolRegistry.whenToUseOf(desc).length)
        assertEquals(10, ToolRegistry.whenToUseOf(desc, maxLen = 10).length)
    }

    @Test
    fun `when-to-use recognizes ascii and halfwidth sentence breaks`() {
        assertEquals("Read a document from Drive", ToolRegistry.whenToUseOf("Read a document from Drive. Returns full text"))
        assertEquals("查询文件列表", ToolRegistry.whenToUseOf("查询文件列表; 支持 scope 参数"))
        assertEquals("Done", ToolRegistry.whenToUseOf("Done! Next step"))
    }

    @Test
    fun `when-to-use does not split on dots inside versions or numbers`() {
        // 半角 . 仅后随空格/串尾才算边界：v1.2、3.14、file.txt 都不能误切
        assertEquals("支持 v1.2 协议", ToolRegistry.whenToUseOf("支持 v1.2 协议。后续说明"))
        assertEquals("计算 3.14 的值", ToolRegistry.whenToUseOf("计算 3.14 的值。然后四舍五入"))
    }

    @Test
    fun `when-to-use is empty for blank description`() {
        assertEquals("", ToolRegistry.whenToUseOf(""))
        assertEquals("", ToolRegistry.whenToUseOf("   \n  "))
    }

    @Test
    fun `when-to-use never yields an empty signal sentence`() {
        // 首字符就是句读（cut == 0，畸形描述）不视为有效边界 —— 落定长截断全取。
        // 空串信号句（菜单里出现"- name："孤号）比带前导句读的全取更糟。
        assertEquals("。abcdef", ToolRegistry.whenToUseOf("。abcdef"))
        assertEquals("a", ToolRegistry.whenToUseOf("a"))
    }

    // ── renderMenu ───────────────────────────────────────────────────────

    @Test
    fun `render groups by domain and preserves input order`() {
        val text = ToolRegistry.renderMenu(
            listOf(
                ToolRegistry.MenuEntry("search_web", ToolRegistry.DOMAIN_WEB, "联网搜索"),
                ToolRegistry.MenuEntry("get_current_time", ToolRegistry.DOMAIN_INFO, "当前时间"),
                ToolRegistry.MenuEntry("fetch_webpage", ToolRegistry.DOMAIN_WEB, "读网页原文"),
            ),
        )
        assertEquals(
            "【联网检索】\n- search_web：联网搜索\n- fetch_webpage：读网页原文\n" +
                "【基础信息】\n- get_current_time：当前时间",
            text,
        )
    }

    @Test
    fun `render is deterministic for the same input`() {
        val entries = listOf(
            ToolRegistry.MenuEntry("a", "g1", "x"),
            ToolRegistry.MenuEntry("b", "g2", "y"),
            ToolRegistry.MenuEntry("c", "g1", "z"),
        )
        // 缓存前缀纪律：同输入必须逐字节同输出（菜单文本每轮重算，漂移即缓存 miss）
        assertEquals(ToolRegistry.renderMenu(entries), ToolRegistry.renderMenu(entries))
    }

    @Test
    fun `render omits the colon for empty when-to-use`() {
        val text = ToolRegistry.renderMenu(
            listOf(ToolRegistry.MenuEntry("mystery_tool", ToolRegistry.DOMAIN_MCP, "")),
        )
        assertEquals("【外部工具（MCP）】\n- mystery_tool", text)
    }

    @Test
    fun `render falls back to the raw domain id for unknown groups`() {
        // 新增域忘登记 MENU_GROUP_LABELS 时兜底用原值 —— 静默丢组比显示域 id 糟
        val text = ToolRegistry.renderMenu(
            listOf(ToolRegistry.MenuEntry("t", "some_future_domain", "用")),
        )
        assertTrue(text, text.startsWith("【some_future_domain】"))
    }

    @Test
    fun `render of empty list is empty`() {
        assertEquals("", ToolRegistry.renderMenu(emptyList()))
    }
}
