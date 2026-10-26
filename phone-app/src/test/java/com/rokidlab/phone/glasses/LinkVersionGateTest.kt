package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 眼镜端版本比对（2026-10-24 启动引导 redesign）—— 锁死三条不变式：
 *
 *  1. **数值比较**：`"5.10" > "5.9"`（字符串比较会判反，`"10" < "9"`）；
 *  2. **后缀忽略**：debug/release 同号是常态（`"1.0-debug"` vs `"1.0"` 必须判"一致"），
 *     否则每次debug包都误报"眼镜端落后"；
 *  3. **三态宁漏勿扰**：任一側版本缺失 → null（调用方按"不打扰"处理），
 *     误拦比漏拦更烦（这是 redesign 的核心动机）。
 */
class LinkVersionGateTest {

    // ── 1. 分段数值比较 ──

    @Test
    fun `数值比较而非字符串比较 —— 5_10 大于 5_9`() {
        assertTrue(LinkVersionGate.compare("5.10", "5.9") > 0)
        assertTrue(LinkVersionGate.compare("5.9", "5.10") < 0)
    }

    @Test
    fun `段数不齐补零 —— 5_0 小于 5_0_1`() {
        assertTrue(LinkVersionGate.compare("5.0", "5.0.1") < 0)
        assertTrue(LinkVersionGate.compare("5.0.1", "5.0") > 0)
        assertEquals(0, LinkVersionGate.compare("5.0", "5.0.0"))
    }

    @Test
    fun `相同版本判零`() {
        assertEquals(0, LinkVersionGate.compare("1.2.3", "1.2.3"))
        assertEquals(0, LinkVersionGate.compare("1.2.3", " 1.2.3 "))
    }

    // ── 2. 后缀忽略（debug/release 同号不能误报）──

    @Test
    fun `debug 后缀与纯版本号判为一致`() {
        assertEquals(0, LinkVersionGate.compare("1.0-debug", "1.0"))
        assertEquals(0, LinkVersionGate.compare("1.0", "1.0-debug"))
        assertEquals(0, LinkVersionGate.compare("0-debug", "0"))
    }

    @Test
    fun `段首取数字 —— 12abc 视为 12`() {
        assertTrue(LinkVersionGate.compare("12abc", "9xyz") > 0)
        assertEquals(0, LinkVersionGate.compare("1.0-debug", "1.0-release"))
    }

    // ── 3. needsUpdate 三态 ──

    @Test
    fun `内置更新才提醒`() {
        assertTrue(LinkVersionGate.needsUpdate("5.11", "5.10") == true)
        assertTrue(LinkVersionGate.needsUpdate("1.0", "0.9.9") == true)
    }

    @Test
    fun `一致或眼镜端反超都不提醒`() {
        assertFalse(LinkVersionGate.needsUpdate("5.10", "5.10") == true)
        assertFalse(LinkVersionGate.needsUpdate("1.0-debug", "1.0") == true)
        assertFalse(LinkVersionGate.needsUpdate("5.9", "5.10") == true)
    }

    @Test
    fun `任一側版本缺失返回 null —— 宁漏勿扰`() {
        assertNull(LinkVersionGate.needsUpdate(null, "5.10"))
        assertNull(LinkVersionGate.needsUpdate("5.10", null))
        assertNull(LinkVersionGate.needsUpdate("", "5.10"))
        assertNull(LinkVersionGate.needsUpdate("5.10", "  "))
        assertNull(LinkVersionGate.needsUpdate(null, null))
    }
}
