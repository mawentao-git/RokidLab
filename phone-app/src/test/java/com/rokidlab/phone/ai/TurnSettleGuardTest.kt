package com.rokidlab.phone.ai

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnSettleGuard] 的不变式测试。
 *
 * 每个用例都钉一条类文档里写的不变式；重点是「能红」——
 * 去掉 guard 的 finally / compareAndSet，这些断言必须失败。
 */
class TurnSettleGuardTest {

    /** 不变式 3：body（调用方回调）先于 onLastSettled（排水） */
    @Test
    fun `body 先于 onLastSettled 执行`() {
        val counter = AtomicInteger(1)
        val order = mutableListOf<String>()
        val guard = TurnSettleGuard(counter) { order += "drain" }
        guard.settle { order += "body" }
        assertEquals(listOf("body", "drain"), order)
    }

    /** 不变式 2（核心）：body 抛异常时计数仍归零、排水仍触发 */
    @Test
    fun `body 抛异常时计数归零且排水仍触发`() {
        val counter = AtomicInteger(1)
        var drained = false
        val guard = TurnSettleGuard(counter) { drained = true }
        val e = assertThrows(IllegalStateException::class.java) {
            guard.settle { throw IllegalStateException("onResult boom") }
        }
        assertEquals("onResult boom", e.message)
        assertEquals(0, counter.get())
        assertTrue("body 抛异常不能吞掉排水", drained)
    }

    /** 不变式 1：重复 settle 被丢弃 —— 不多 decrement、不重放 body */
    @Test
    fun `重复 settle 被构造性丢弃`() {
        val counter = AtomicInteger(1)
        var bodyRuns = 0
        var drains = 0
        val guard = TurnSettleGuard(counter) { drains++ }
        guard.settle { bodyRuns++ }
        guard.settle { bodyRuns++ }
        guard.settle { bodyRuns++ }
        assertEquals(1, bodyRuns)
        assertEquals(1, drains)
        assertEquals(0, counter.get())
    }

    /** 不变式 4：重叠回合只在最后一个 settle 时排水 */
    @Test
    fun `重叠回合只在最后一个 settle 时排水`() {
        val counter = AtomicInteger(0)
        var drains = 0
        val onLast: () -> Unit = { drains++ }
        val g1 = TurnSettleGuard(counter, onLast)
        val g2 = TurnSettleGuard(counter, onLast)
        counter.incrementAndGet()
        counter.incrementAndGet()
        g1.settle { }
        assertEquals("第一个落地不能排水（还有回合在飞）", 0, drains)
        assertEquals(1, counter.get())
        g2.settle { }
        assertEquals(1, drains)
        assertEquals(0, counter.get())
    }

    /** 边界：计数从 0 开始时 settle 不会把计数压成负（防御性观测） */
    @Test
    fun `计数为零时 settle 仍触发排水且不压负`() {
        val counter = AtomicInteger(0)
        var drains = 0
        val guard = TurnSettleGuard(counter) { drains++ }
        guard.settle { }
        assertEquals(1, drains)
        assertEquals(0, counter.get())
        assertFalse(counter.get() < 0)
    }
}
