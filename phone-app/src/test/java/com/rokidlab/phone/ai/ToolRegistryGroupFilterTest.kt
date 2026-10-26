package com.rokidlab.phone.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配侧可见性谓词（[ToolRegistry.visibleInAssembly]）单测（2026-10-19 二期）。
 *
 * 这个谓词是「分组 = 一套独立 agent」在**装配侧**的唯一落点：模型看不到的工具
 * 才调不到。它与审批侧的 `GroupScopeGuard` 是一对（一个管"看不见"、一个管"调不动"），
 * 两侧都从 `ChatStateHolder.currentGroupToolScope()` 取同一份范围。
 *
 * 锁住的核心不变式：**分组层只可能收窄，永远不放宽** ——
 * 全局关掉的工具（开关快照为 false），分组范围哪怕"没排除"它也看不见；
 * 结构上不存在"分组把全局关的打开"的代码路径（分组是纯排除表）。
 */
class ToolRegistryGroupFilterTest {

    private fun meta(
        name: String,
        group: String,
        pageOnly: Boolean = false,
    ) = ToolRegistry.ToolMeta(
        name = name,
        group = group,
        displayNameRes = 0,
        descriptionRes = 0,
        pageOnly = pageOnly,
    )

    private val domains = setOf(ToolRegistry.DOMAIN_FILES)
    private val enabled = mapOf("save_code_file" to true, "run_shell" to false)

    @Test
    fun `域内_全局开_无分组收窄时可见`() {
        assertTrue(
            ToolRegistry.visibleInAssembly(
                meta("save_code_file", ToolRegistry.DOMAIN_FILES), enabled, domains, null,
            )
        )
    }

    @Test
    fun `域外工具不进本场景装配`() {
        assertFalse(
            ToolRegistry.visibleInAssembly(
                meta("get_current_time", ToolRegistry.DOMAIN_INFO), enabled, domains, null,
            )
        )
    }

    @Test
    fun `全局关掉的工具不可见_分组也救不回来`() {
        // 不变式本体：分组排除表是"再砍一刀"，不是"重新启用"
        assertFalse(
            ToolRegistry.visibleInAssembly(
                meta("run_shell", ToolRegistry.DOMAIN_SHELL),
                mapOf("run_shell" to false),
                setOf(ToolRegistry.DOMAIN_SHELL),
                GroupToolScope("g1", setOf("call_phone")), // 排除表里没有 run_shell
            )
        )
    }

    @Test
    fun `pageOnly 工具不进主模型装配_与分组无关`() {
        assertFalse(
            ToolRegistry.visibleInAssembly(
                meta("get_cover_image", ToolRegistry.DOMAIN_AIUI, pageOnly = true),
                mapOf("get_cover_image" to true),
                setOf(ToolRegistry.DOMAIN_AIUI),
                null,
            )
        )
    }

    @Test
    fun `分组排除表内的工具不可见`() {
        assertFalse(
            ToolRegistry.visibleInAssembly(
                meta("save_code_file", ToolRegistry.DOMAIN_FILES),
                enabled,
                domains,
                GroupToolScope("g1", setOf("save_code_file")),
            )
        )
    }

    @Test
    fun `分组排除表外的工具不受影响`() {
        assertTrue(
            ToolRegistry.visibleInAssembly(
                meta("save_code_file", ToolRegistry.DOMAIN_FILES),
                enabled,
                domains,
                GroupToolScope("g1", setOf("run_shell")),
            )
        )
    }

    @Test
    fun `开关快照缺名按关处理_不因缺 key 放行`() {
        // 快照里没有这个名字（理论上不会发生：schemasFor 对全量建快照）⇒ 视为不可见，
        // 与 schemasFor 的 snapshot[name] == true 判据一致
        assertFalse(
            ToolRegistry.visibleInAssembly(
                meta("mystery_tool", ToolRegistry.DOMAIN_FILES), emptyMap(), domains, null,
            )
        )
    }
}
