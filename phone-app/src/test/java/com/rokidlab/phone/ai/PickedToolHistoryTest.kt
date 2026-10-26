package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配集合冻结的纯逻辑（2026-10-23，PickedToolHistory）。
 *
 * 三条不变式各自至少一条"能红"的断言：有界 / 只增且幂等 / 按会话重置。
 */
class PickedToolHistoryTest {

    private fun historyOf(vararg names: String) = LinkedHashSet(names.toList())

    @Test
    fun `add unions within cap and preserves insertion order`() {
        val h = historyOf("search_web")
        val added = PickedToolHistory.addWithinCap(h, setOf("list_files", "save_code_file"), cap = 24)
        assertEquals(2, added)
        // 确定性：插入序 = 调用序（picks 源自保序 filter ⇒ 同输入同集合同缓存前缀）
        assertEquals(listOf("search_web", "list_files", "save_code_file"), h.toList())
    }

    @Test
    fun `add is idempotent for existing names`() {
        val h = historyOf("a", "b")
        val added = PickedToolHistory.addWithinCap(h, setOf("b", "a"), cap = 24)
        assertEquals(0, added)
        assertEquals(listOf("a", "b"), h.toList())
    }

    @Test
    fun `add stops at cap and never shrinks`() {
        val h = historyOf("a", "b", "c")
        // cap=3：已满 ⇒ 新名字一个都进不去；已存在的可以重复 add（幂等不计增长）
        val added = PickedToolHistory.addWithinCap(h, setOf("d", "e", "a"), cap = 3)
        assertEquals(0, added)
        assertEquals(listOf("a", "b", "c"), h.toList())

        // cap=4：只放得进一个，且是第一个（按传入序，确定性）
        val h2 = historyOf("a", "b", "c")
        assertEquals(1, PickedToolHistory.addWithinCap(h2, setOf("d", "e"), cap = 4))
        assertEquals(listOf("a", "b", "c", "d"), h2.toList())
    }

    @Test
    fun `reset only when the bound session differs`() {
        assertFalse("同会话不重置（否则每轮清空 = 冻结失效）", PickedToolHistory.shouldReset("s1", "s1"))
        assertTrue("换会话必须重置（旧会话点名不带偏新会话）", PickedToolHistory.shouldReset("s1", "s2"))
        assertFalse(
            "首轮（未绑定）不重置 —— 那时 history 必然空，清空是空操作",
            PickedToolHistory.shouldReset(null, "s1"),
        )
    }
}
