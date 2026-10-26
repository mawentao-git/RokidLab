package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolRepetitionTracker] 的指纹语义与分级提醒。
 *
 * 指纹测试刻意覆盖两个"顺序/字段无关"性质：JSONObject 键序在真机（LinkedHashMap，
 * 保插入序）与单测（org.json:json 的 HashMap，哈希序）下不同，任何"锚定书写顺序"
 * 的实现在单测里永远命中不了 —— 顺序无关正是这条坑的解法，必须有用例钉住。
 */
class ToolRepetitionTrackerTest {

    @Test
    fun `指纹与参数书写顺序无关`() {
        val a = ToolRepetitionTracker.fingerprint("read_code_file", """{"path":"a.ink","line":10}""")
        val b = ToolRepetitionTracker.fingerprint("read_code_file", """{"line":10,"path":"a.ink"}""")
        assertEquals(a, b)
    }

    @Test
    fun `易变字段不参与指纹`() {
        val a = ToolRepetitionTracker.fingerprint("t", """{"path":"a","ts":111}""")
        val b = ToolRepetitionTracker.fingerprint("t", """{"path":"a","ts":999}""")
        assertEquals(a, b)
    }

    @Test
    fun `参数不同则指纹不同`() {
        val a = ToolRepetitionTracker.fingerprint("read_code_file", """{"path":"a.ink"}""")
        val b = ToolRepetitionTracker.fingerprint("read_code_file", """{"path":"b.ink"}""")
        assertNotEquals(a, b)
    }

    @Test
    fun `空参数归一为无参调用`() {
        assertEquals(
            ToolRepetitionTracker.fingerprint("t", "{}"),
            ToolRepetitionTracker.fingerprint("t", null),
        )
    }

    @Test
    fun `同一指纹在窗口内累计计数`() {
        val sid = "count-test"
        ToolRepetitionTracker.reset(sid)
        try {
            assertEquals(1, ToolRepetitionTracker.noteAndCount(sid, "a()"))
            assertEquals(2, ToolRepetitionTracker.noteAndCount(sid, "a()"))
            assertEquals(3, ToolRepetitionTracker.noteAndCount(sid, "a()"))
        } finally {
            ToolRepetitionTracker.reset(sid)
        }
    }

    @Test
    fun `滑出窗口后计数重新从 1 计`() {
        val sid = "window-test"
        ToolRepetitionTracker.reset(sid)
        try {
            ToolRepetitionTracker.noteAndCount(sid, "x()")
            // 窗口 40：用 60 个别的指纹把 x() 挤出去（长会话里查时间这类天然重复不能误报）
            repeat(60) { ToolRepetitionTracker.noteAndCount(sid, "filler$it()") }
            assertEquals(1, ToolRepetitionTracker.noteAndCount(sid, "x()"))
        } finally {
            ToolRepetitionTracker.reset(sid)
        }
    }

    @Test
    fun `提醒文案按次数分三档且边界值正确`() {
        val soft = ToolRepetitionTracker.reminder("read_code_file", 2)
        val strong = ToolRepetitionTracker.reminder("read_code_file", 4)
        val stop = ToolRepetitionTracker.reminder("read_code_file", 6)
        // 三档互不相同（分级的前提：不能第 2 次和第 9 次同一句话）
        assertNotEquals(soft, strong)
        assertNotEquals(strong, stop)
        assertNotEquals(soft, stop)
        // 温和档：说清第 N 次、不含升级措辞
        assertTrue(soft.contains("第 2 次"))
        assertTrue(!soft.contains("强提醒"))
        assertTrue(!soft.contains("停止"))
        // 强档：出现"强提醒"与"不要再次发起相同调用"
        assertTrue(strong.contains("强提醒"))
        assertTrue(strong.contains("不要再次发起相同调用"))
        // 停手档：出现停手措辞
        assertTrue(stop.contains("停止"))
        // 档位边界：3 仍温和、5 仍强、7 停手。
        // ⚠️ 同档文案不能整串比对 —— "第 $occurrence 次"嵌在文案里，2 和 3 的温和档
        //    本来就不同（本条断言第一版就是这么写红的：实现没错，是断言错了）。
        val soft3 = ToolRepetitionTracker.reminder("read_code_file", 3)
        assertTrue(soft3.contains("第 3 次"))
        assertTrue(!soft3.contains("强提醒") && !soft3.contains("停止"))
        val strong5 = ToolRepetitionTracker.reminder("read_code_file", 5)
        assertTrue(strong5.contains("强提醒"))
        assertTrue(strong5.contains("第 5 次"))
        val stop7 = ToolRepetitionTracker.reminder("read_code_file", 7)
        assertTrue(stop7.contains("停止"))
        assertTrue(stop7.contains("第 7 次"))
        // 阈值常量本身钉住"只升不降"的单调性
        assertTrue(ToolRepetitionTracker.WARN_FROM_OCCURRENCE < ToolRepetitionTracker.STRONG_FROM_OCCURRENCE)
        assertTrue(ToolRepetitionTracker.STRONG_FROM_OCCURRENCE < ToolRepetitionTracker.STOP_FROM_OCCURRENCE)
    }
}
