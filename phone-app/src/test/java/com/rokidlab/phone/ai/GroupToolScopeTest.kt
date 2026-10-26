package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupToolScope]（分组级工具收窄范围）纯函数单测（2026-10-19 二期）。
 *
 * 锁住三类契约：
 *  1. **空配置 ⇒ null**：没配收窄的分组走零开销路径（连缓存键都不带分组）；
 *  2. **MCP server 按 lambda 展开**：分组侧按 server 粒度勾选，wire name 的展开
 *     发生在解析层（不在 UI、不在装配侧各写一遍）；
 *  3. **server 暂无工具也要保留意图**：连上之前展开结果是空集，但范围本身非 null ——
 *     否则"先配收窄、后连 server"会静默丢失配置（server 一连上工具全跑出来）。
 */
class GroupToolScopeTest {

    @Test
    fun `两份收窄配置都为空时返回 null`() {
        assertNull(
            "没有收窄配置的分组不该产生范围对象",
            GroupToolScope.resolve("g1", emptySet(), emptySet()) { emptySet() },
        )
    }

    @Test
    fun `只配工具时原样进入排除表`() {
        val scope = GroupToolScope.resolve("g1", setOf("run_shell"), emptySet()) { emptySet() }!!
        assertEquals(setOf("run_shell"), scope.disabledTools)
        assertTrue(scope.excludes("run_shell"))
        assertFalse("没列出的工具不受分组影响", scope.excludes("get_current_time"))
    }

    @Test
    fun `MCP server 按传入 lambda 展开成 wire name`() {
        val byServer = mapOf(
            "srv-a" to setOf("mcp__srv-a__foo", "mcp__srv-a__bar"),
            "srv-b" to setOf("mcp__srv-b__baz"),
        )
        val scope = GroupToolScope.resolve("g1", emptySet(), setOf("srv-a")) {
            byServer[it].orEmpty()
        }!!
        assertEquals(setOf("mcp__srv-a__foo", "mcp__srv-a__bar"), scope.disabledTools)
        assertTrue(scope.excludes("mcp__srv-a__foo"))
        assertFalse("只收了 srv-a，srv-b 的工具不受影响", scope.excludes("mcp__srv-b__baz"))
    }

    @Test
    fun `工具与 MCP 展开结果取并集`() {
        val byServer = mapOf("srv-a" to setOf("mcp__srv-a__foo"))
        val scope = GroupToolScope.resolve(
            "g1",
            disabledTools = setOf("run_shell"),
            disabledMcpServers = setOf("srv-a"),
        ) { byServer[it].orEmpty() }!!
        assertEquals(setOf("run_shell", "mcp__srv-a__foo"), scope.disabledTools)
    }

    @Test
    fun `只配 MCP 且该 server 当前无工具时范围仍非 null`() {
        val scope = GroupToolScope.resolve("g1", emptySet(), setOf("srv-a")) { emptySet() }
        assertNotNull("server 连上之前也要保留收窄意图，否则连上后配置静默丢失", scope)
        assertTrue(scope!!.disabledTools.isEmpty())
    }

    @Test
    fun `groupId 参与相等性判断_缓存键防串组依赖它`() {
        val a = GroupToolScope("g1", setOf("x"))
        val b = GroupToolScope("g2", setOf("x"))
        assertFalse("不同分组即使排除表相同也不是同一个范围", a == b)
        assertEquals(GroupToolScope("g1", setOf("x")), a)
    }
}
