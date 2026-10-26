package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.GroupToolScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GroupScopeGuard]（分组收窄闸门）单测（2026-10-19 二期）。
 *
 * 这是「分组 = 一套独立 agent」在**审批侧**的落点：装配侧不下发 Schema 只省 token，
 * 模型仍可能从历史对话里复述出被分组收窄的工具名（尤其在别的分组里用过它）——
 * 执行侧不查，分组开关就只是提示词级约定。
 *
 * 锁住四类不变式：
 *  1. 收窄 ⇒ 单调拒绝，origin = [DecisionOrigin.GROUP_SCOPE]（排查时一眼看出"谁拒的"）；
 *  2. 未收窄 / 调用方没带上下文 ⇒ **不表态**（null 不是拒绝，别把"没问到"当"被关掉"）；
 *  3. 来源只覆盖对话与 AIUI 页面 —— 头动规则是用户自配的确定性动作，
 *     不该被"当前聊天停在哪个分组"连坐；
 *  4. **顺序**：紧邻全局开关闸门、排在限流之前 —— 被分组拒掉的调用不烧限流配额。
 */
class GroupScopeGuardTest {

    private fun ctx(groupDisabled: Boolean?): ToolCallContext = ToolCallContext(
        source = ToolSource.CONVERSATION,
        name = "get_current_time",
        args = JSONObject(),
        enabled = true,
        groupDisabled = groupDisabled,
    )

    /** 固定返回某个判定的探针（记录求值次数，用于验证短路） */
    private class Probe(
        override val id: String,
        private val fixed: ToolDecision?,
    ) : ToolGuard {
        var calls = 0
        override fun evaluate(ctx: ToolCallContext): ToolDecision? {
            calls++
            return fixed
        }
    }

    @Test
    fun `分组收窄时单调拒绝且 origin 是 GROUP_SCOPE`() {
        val r = GroupScopeGuard().evaluate(ctx(groupDisabled = true))
        assertTrue("被分组收窄必须拒绝", r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.GROUP_SCOPE, (r as ToolDecision.Deny).origin)
        assertTrue("拒绝理由要点出去哪里放宽", r.reason.contains("分组"))
    }

    @Test
    fun `未被分组收窄时不表态`() {
        assertNull(GroupScopeGuard().evaluate(ctx(groupDisabled = false)))
    }

    @Test
    fun `调用方没带分组上下文时不表态`() {
        // null = 不知道，不是"被关掉"（与 ToolCallContext.enabled 的 null 语义一致）
        assertNull(GroupScopeGuard().evaluate(ctx(groupDisabled = null)))
    }

    @Test
    fun `只对对话与 AIUI 页面来源生效_头动规则不受分组收窄影响`() {
        val sources = GroupScopeGuard().sources
        assertEquals(setOf(ToolSource.CONVERSATION, ToolSource.AIUI_PAGE), sources)
        assertFalse(
            "头动规则是用户自配动作，与当前聊天分组无关",
            ToolSource.MOTION_RULE in sources,
        )
    }

    @Test
    fun `default 中分组闸门紧邻全局开关闸门且排在限流之前`() {
        val ids = ToolGuards.default().map { it.id }
        val toolDisabled = ids.indexOf("tool-disabled")
        val groupScope = ids.indexOf("group-scope")
        val rateLimit = ids.indexOf("rate-limit")
        assertTrue("分组闸门必须注册进 default", groupScope >= 0)
        assertTrue(
            "两层开关要挨着：先全局后分组，拒绝理由才指向正确的地方",
            groupScope == toolDisabled + 1,
        )
        assertTrue(
            "被分组拒掉的调用不该烧限流配额（限流在纯判定之后）",
            groupScope < rateLimit,
        )
    }

    @Test
    fun `经 compose 合成时分组拒绝短路后续判定`() {
        val ask = Probe("ask", ToolDecision.Ask(DecisionOrigin.RISK_CONFIRMATION, "确认？"))
        val r = ApprovalGate.compose(ctx(groupDisabled = true), listOf(GroupScopeGuard(), ask))
        assertTrue("分组拒绝必须短路，模型不该看到确认询问", r is ToolDecision.Deny)
        assertEquals("短路后后续 guard 不得被求值", 0, ask.calls)
    }

    @Test
    fun `与全局开关分层_全局关着时先由开关闸门拒绝`() {
        val enabledGuard = ToolEnabledGuard()
        val r = ApprovalGate.compose(
            ctx(groupDisabled = true).copy(enabled = false),
            listOf(enabledGuard, GroupScopeGuard()),
        )
        assertTrue(r is ToolDecision.Deny)
        assertEquals(
            "全局关闭优先于分组收窄（用户该去设置页，不是分组设置）",
            DecisionOrigin.TOOL_DISABLED,
            (r as ToolDecision.Deny).origin,
        )
    }

    @Test
    fun `解析范围命中即拒绝_装配审批同源的最小闭环演示`() {
        // preExecute 的结算逻辑（scope.excludes(name) → groupDisabled）与 guard 的接力：
        // 不依赖 Context 的部分在这里走一遍真值
        val scope = GroupToolScope("g1", setOf("get_current_time"))
        val decision = ApprovalGate.compose(
            ctx(groupDisabled = scope.excludes("get_current_time")),
            listOf(GroupScopeGuard()),
        )
        assertTrue(decision is ToolDecision.Deny)
    }
}
