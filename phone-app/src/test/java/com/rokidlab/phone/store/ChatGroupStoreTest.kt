package com.rokidlab.phone.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组索引（[ChatGroupStore]）序列化 round-trip 单测 —— 覆盖二期新增的三份收窄集。
 *
 * 为什么值得钉：收窄集是**安全相关配置**（分组把危险工具关掉），序列化丢字段
 * 的表现是"设了没生效"且**完全静默**（丢一个字段不会报错，只是少拦一个工具）。
 * 同时锁两条兼容口径：
 *  - 空集不落盘 ⇒ 一期文件与"没收窄的分组"在磁盘上完全一致；
 *  - 旧文件缺字段 ⇒ 按空集解析（升级用户的分组不会因为多了字段就坏）。
 */
class ChatGroupStoreTest {

    @Test
    fun `三份收窄集 round-trip 后逐个保真`() {
        val groups = listOf(
            ChatGroupMeta(
                id = "g1",
                name = "写代码",
                createdAt = 100L,
                systemPrompt = "你是资深 Kotlin 工程师",
                disabledTools = setOf("run_shell", "call_phone"),
                disabledMcpServers = setOf("srv-a", "srv-b"),
                disabledSkills = setOf("aiui-dev"),
            )
        )
        val parsed = ChatGroupStore.parseGroupsOrNull(ChatGroupStore.toGroupsJson(groups))!!
        assertEquals(1, parsed.size)
        val g = parsed[0]
        assertEquals("g1", g.id)
        assertEquals("写代码", g.name)
        assertEquals("你是资深 Kotlin 工程师", g.systemPrompt)
        assertEquals(setOf("run_shell", "call_phone"), g.disabledTools)
        assertEquals(setOf("srv-a", "srv-b"), g.disabledMcpServers)
        assertEquals(setOf("aiui-dev"), g.disabledSkills)
    }

    @Test
    fun `空收窄集不落盘_磁盘与一期格式一致`() {
        val json = ChatGroupStore.toGroupsJson(
            listOf(ChatGroupMeta(id = "g1", name = "闲聊", createdAt = 1L))
        )
        assertFalse("空集不该落字段", json.contains("disabledTools"))
        assertFalse("空集不该落字段", json.contains("disabledMcpServers"))
        assertFalse("空集不该落字段", json.contains("disabledSkills"))
    }

    @Test
    fun `旧版分组索引缺收窄字段按空集解析`() {
        val legacy = """[{"id":"g1","name":"老分组","createdAt":1,"systemPrompt":"老人设"}]"""
        val parsed = ChatGroupStore.parseGroupsOrNull(legacy)!!
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].disabledTools.isEmpty())
        assertTrue(parsed[0].disabledMcpServers.isEmpty())
        assertTrue(parsed[0].disabledSkills.isEmpty())
        assertEquals("老人设", parsed[0].systemPrompt)
    }

    @Test
    fun `收窄数组里的空串与空白元素被滤掉`() {
        val json =
            """[{"id":"g1","name":"x","createdAt":1,"disabledTools":["run_shell","","  "]}]"""
        val parsed = ChatGroupStore.parseGroupsOrNull(json)!!
        assertEquals(
            "空串元素会污染集合相等性（缓存键与 GroupToolScope.equals 都按内容比）",
            setOf("run_shell"),
            parsed[0].disabledTools,
        )
    }

    @Test
    fun `损坏与缺失仍按既有口径区分`() {
        assertEquals(emptyList<ChatGroupMeta>(), ChatGroupStore.parseGroupsOrNull(""))
        assertEquals(emptyList<ChatGroupMeta>(), ChatGroupStore.parseGroupsOrNull("   "))
        assertEquals(null, ChatGroupStore.parseGroupsOrNull("not-json"))
        assertEquals(null, ChatGroupStore.parseGroupsOrNull("{}"))
    }

    @Test
    fun `缺 id 的条目照旧跳过`() {
        val json = """[{"name":"没有 id","createdAt":1}]"""
        assertTrue(ChatGroupStore.parseGroupsOrNull(json)!!.isEmpty())
    }
}
