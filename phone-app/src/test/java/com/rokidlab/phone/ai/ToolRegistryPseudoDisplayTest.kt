package com.rokidlab.phone.ai

import com.rokidlab.phone.ai.approval.PseudoTools
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 伪工具显示名覆盖兜底（2026-10-18 审计修，低危项）。
 *
 * 背景：`PSEUDO_DISPLAY_RES` 曾漏登记 `ask_user`（2026-10-12 新增的伪工具）——
 * 过程卡片把 wire name 直接渲染给用户。这条不变式没有编译期约束，只能靠测试钉住：
 * 每有一个新伪工具（[PseudoTools.names]），就必须在同一次改动里给它配显示资源。
 *
 * 用**键集合相等**而不是 ⊆：反向多出的键同样是腐烂（伪工具被删/改名后
 * 残留的死资源没人清），一并拦掉。
 */
class ToolRegistryPseudoDisplayTest {

    @Test
    fun `every pseudo tool has a display name resource`() {
        assertEquals(
            "新伪工具必须同步登记 PSEUDO_DISPLAY_RES（否则过程卡片显示 wire name）；" +
                "被删/改名的伪工具也要顺手清掉对应条目",
            PseudoTools.names(),
            ToolRegistry.pseudoDisplayResNames(),
        )
    }

    @Test
    fun `ask_user display name is registered`() {
        // 直接钉住这次漏掉的那一个——上面的全等断言覆盖未来，这条覆盖已知事故
        assertEquals(true, ToolRegistry.pseudoDisplayResNames().contains("ask_user"))
    }
}
