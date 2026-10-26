package com.rokidlab.phone.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier0 常驻工具集纪律（2026-10-23，菜单探测轮常驻面）。
 *
 * 锁三类不变式：① 与注册表逐字对得上（改名漏同步 ⇒ 常驻面静默缺能力）；
 * ② 不含眼镜依赖工具（本机模式白占 Schema）；③ 不含强能力/不可逆动作
 * （点名才给 —— 探测轮就能动手改用户东西是红线）。
 */
class Tier0ToolsTest {

    @Test
    fun `tier0 names exist in the registry`() {
        val known = ToolRegistry.allToolNames()
        val missing = ToolIntentRouter.TIER0_CORE_TOOLS - known
        assertTrue("Tier0 名字与注册表不一致（改名要同步 ToolIntentRouter）：$missing", missing.isEmpty())
    }

    @Test
    fun `tier0 excludes glasses-dependent tools`() {
        val glasses = ToolRegistry.GLASSES_REQUIRED_TOOLS
        val overlap = ToolIntentRouter.TIER0_CORE_TOOLS intersect glasses
        assertTrue("Tier0 含眼镜依赖工具（本机模式白占 Schema）：$overlap", overlap.isEmpty())
    }

    @Test
    fun `tier0 excludes strong or irreversible capabilities`() {
        // 点名才给：shell/补丁/整份覆盖/删除/设备副作用。探测轮常驻它们 =
        // 模型还没确认意图就能改用户的东西（Tier0 字段文档红线）。
        val mustNot = setOf(
            "run_shell", "apply_patch", "edit_text_file", "delete_file",
            "manage_timer", "set_phone_alarm", "control_music", "open_phone_app",
            "call_phone", "send_sms", "install_packages", "uninstall_app",
            "install_glasses_app", "manage_glasses_app",
        )
        val overlap = ToolIntentRouter.TIER0_CORE_TOOLS intersect mustNot
        assertTrue("Tier0 含强能力/不可逆动作（必须点名才给）：$overlap", overlap.isEmpty())
    }

    @Test
    fun `tier0 is a superset of the bootstrap guide tools`() {
        // 菜单轮常驻面替代原引导集；bootstrap 仍用于 picks ∪ 辅助（见主循环点名分支）
        assertTrue(
            "Tier0 必须覆盖 BOOTSTRAP_TOOLS（否则菜单轮能力面倒退）",
            ToolIntentRouter.TIER0_CORE_TOOLS.containsAll(ToolIntentRouter.BOOTSTRAP_TOOLS),
        )
    }

    @Test
    fun `tier0 carries no pseudo tools`() {
        // 伪工具（计划/决策/记忆/技能）不进常驻面：菜单轮保持最小伪工具面，
        // 点名/升级后再给（见 buildTools 的 apply 块注释）
        val pseudo = setOf(
            "update_plan", "ask_user", "manage_memory",
            "load_skill", "load_skill_section", "install_skill", "list_skills", "delete_skill",
        )
        val overlap = ToolIntentRouter.TIER0_CORE_TOOLS intersect pseudo
        assertTrue("Tier0 含伪工具：$overlap", overlap.isEmpty())
    }

    @Test
    fun `tier0 stays small enough to keep the probe round light`() {
        // 容量纪律：Tier0 + 累计点名集（MAX_PICKED_HISTORY）是菜单轮常驻面上限。
        // Tier0 本身超过 12 个就该拆分优先级，而不是无限扩（每 Schema ≈ 240 token）。
        assertTrue(
            "Tier0 膨胀到 ${ToolIntentRouter.TIER0_CORE_TOOLS.size} 个，失去'最小常驻面'意义",
            ToolIntentRouter.TIER0_CORE_TOOLS.size <= 12,
        )
    }
}
