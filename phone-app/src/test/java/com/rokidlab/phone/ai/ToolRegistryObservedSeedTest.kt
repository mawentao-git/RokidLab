package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolRegistry.observedSeedDomains（观测域桶装配种子）纯函数单测（2026-10-15 Phase 1）。
 *
 * 锁定契约：
 *  - 观测为空 → 返回 **null**（调用方必须回退全量，绝不返回"只剩兜底域"的退化结果 ——
 *    否则会重演"能力凭空消失"）；
 *  - 观测非空 → 观测域 ∪ [ToolRegistry.OBSERVED_SEED_FALLBACK_DOMAINS]；
 *  - 兜底域 info/knowledge 恒在结果里。
 */
class ToolRegistryObservedSeedTest {

    @Test
    fun `观测为空时返回 null 以触发调用方回退全量`() {
        assertNull(
            "空观测必须返回 null（回退全量），不能退化成只剩兜底域",
            ToolRegistry.observedSeedDomains(emptySet()),
        )
    }

    @Test
    fun `观测非空时并入兜底域`() {
        val seed = ToolRegistry.observedSeedDomains(setOf(ToolRegistry.DOMAIN_FILES))
        assertEquals(
            setOf(ToolRegistry.DOMAIN_FILES) + ToolRegistry.OBSERVED_SEED_FALLBACK_DOMAINS,
            seed,
        )
        assertTrue("兜底域 info 必须恒在", seed!!.contains(ToolRegistry.DOMAIN_INFO))
        assertTrue("兜底域 knowledge 必须恒在", seed.contains(ToolRegistry.DOMAIN_KNOWLEDGE))
    }

    @Test
    fun `观测域本身不得丢失`() {
        val observed = setOf(ToolRegistry.DOMAIN_AIUI, ToolRegistry.DOMAIN_SHELL)
        val seed = ToolRegistry.observedSeedDomains(observed)!!
        assertTrue("上一轮动过手的域必须原样保留", seed.containsAll(observed))
    }
}
