package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUD 主题定时 / 情景联动（2026-10-25 P0-3）纯函数护栏。
 *
 * 锁定契约：
 *  1. inWindow：普通窗口 / 跨零点窗口 / 空窗口（start==end 永不命中）/ 边界（含头不含尾）；
 *  2. desiredTheme：state 优先于 time、同类型按列表序、无命中 null；
 *  3. stateHit：bindOn 同款语义（enum 相等 / 数值阈值）、无数据→false、坏条件容错→false；
 *  4. fmtMin：分钟 → HH:mm。
 *
 * 存储（SharedPreferences）与下发（applyDsl）需 Context/设备，不在本单测范围
 * （编译期 + 人工验收覆盖）。
 */
class HudThemeSchedulerTest {

    private fun timeRule(id: String, name: String, start: Int, end: Int) =
        HudThemeScheduler.Rule(id, HudThemeScheduler.KIND_TIME, name, startMin = start, endMin = end)

    private fun stateRule(id: String, name: String, cond: String) =
        HudThemeScheduler.Rule(id, HudThemeScheduler.KIND_STATE, name, cond = cond)

    // ── 1. inWindow ──

    @Test
    fun `普通时间窗口含头不含尾`() {
        // 9:00–17:00 = 540..1020
        assertFalse(HudThemeScheduler.inWindow(539, 540, 1020))
        assertTrue(HudThemeScheduler.inWindow(540, 540, 1020))
        assertTrue(HudThemeScheduler.inWindow(1019, 540, 1020))
        assertFalse(HudThemeScheduler.inWindow(1020, 540, 1020))
    }

    @Test
    fun `跨零点窗口两侧都命中`() {
        // 22:00–07:00 = 1320..420（跨夜）
        assertTrue(HudThemeScheduler.inWindow(1320, 1320, 420))
        assertTrue(HudThemeScheduler.inWindow(0, 1320, 420))
        assertTrue(HudThemeScheduler.inWindow(419, 1320, 420))
        assertFalse(HudThemeScheduler.inWindow(420, 1320, 420))
        assertFalse(HudThemeScheduler.inWindow(720, 1320, 420))
    }

    @Test
    fun `空窗口永不命中`() {
        assertFalse(HudThemeScheduler.inWindow(0, 600, 600))
        assertFalse(HudThemeScheduler.inWindow(600, 600, 600))
    }

    // ── 2. desiredTheme 优先级 ──

    @Test
    fun `state 优先于 time 且同类按列表序`() {
        val rules = listOf(
            timeRule("t1", "夜间", 1320, 420),
            stateRule("s1", "告警", "battery_phone:<=20"),
            stateRule("s2", "省电", "ai_state:busy"),
        )
        // 两条 state 都命中 → 列表序前者（告警）
        assertEquals(
            "告警",
            HudThemeScheduler.desiredTheme(rules, 1300, mapOf("battery_phone" to "15", "ai_state" to "busy")),
        )
        // 只有第二条 state 命中 → 省电
        assertEquals(
            "省电",
            HudThemeScheduler.desiredTheme(rules, 1300, mapOf("battery_phone" to "80", "ai_state" to "busy")),
        )
        // state 全不中、时间窗内 → time 规则
        assertEquals(
            "夜间",
            HudThemeScheduler.desiredTheme(rules, 60, mapOf("battery_phone" to "80", "ai_state" to "idle")),
        )
        // 全不中 → null（调用方保持现状）
        assertNull(HudThemeScheduler.desiredTheme(rules, 720, mapOf("battery_phone" to "80", "ai_state" to "idle")))
    }

    @Test
    fun `无规则与空列表返回 null`() {
        assertNull(HudThemeScheduler.desiredTheme(emptyList(), 0, emptyMap()))
        assertNull(
            HudThemeScheduler.desiredTheme(
                listOf(stateRule("s1", "告警", "battery_phone:<=20")),
                0,
                emptyMap(), // 无数据 → 不命中
            ),
        )
    }

    // ── 3. stateHit（bindOn 同款语义） ──

    @Test
    fun `stateHit enum 相等与数值阈值`() {
        assertTrue(HudThemeScheduler.stateHit("ai_state:busy", mapOf("ai_state" to "busy")))
        assertFalse(HudThemeScheduler.stateHit("ai_state:busy", mapOf("ai_state" to "idle")))
        assertTrue(HudThemeScheduler.stateHit("battery_phone:<=20", mapOf("battery_phone" to "15")))
        assertFalse(HudThemeScheduler.stateHit("battery_phone:<=20", mapOf("battery_phone" to "21")))
        assertTrue(HudThemeScheduler.stateHit("link:disconnected", mapOf("link" to "disconnected")))
    }

    @Test
    fun `stateHit 无数据与坏条件容错`() {
        // 无数据 → false（与 bindOn「数据未到达也隐藏」同语义）
        assertFalse(HudThemeScheduler.stateHit("ai_state:busy", emptyMap()))
        assertFalse(HudThemeScheduler.stateHit("timer:<300", mapOf("timer" to "")))
        // 坏条件不炸 ticker
        assertFalse(HudThemeScheduler.stateHit("battery_phone:80", mapOf("battery_phone" to "80")))
        assertFalse(HudThemeScheduler.stateHit("", mapOf("ai_state" to "busy")))
        assertFalse(HudThemeScheduler.stateHit("unknown_key:x", mapOf("unknown_key" to "x")))
    }

    // ── 4. fmtMin ──

    @Test
    fun `fmtMin 分钟转 HH mm`() {
        assertEquals("00:00", HudThemeScheduler.fmtMin(0))
        assertEquals("07:05", HudThemeScheduler.fmtMin(425))
        assertEquals("22:00", HudThemeScheduler.fmtMin(1320))
        assertEquals("23:59", HudThemeScheduler.fmtMin(1439))
    }
}
