package com.rokidlab.phone.glasses

import com.rokidlab.phone.ai.AgentStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「过程小卡片有几率不正常显示」回归（2026-10-25 用户报障）。
 *
 * 根因：[GlassesProcessBridge] 的待发窗口曾是 [HashMap] —— 迭代序由哈希定、与
 * 到达序无关（工具 key 是 UUID，每轮哈希序都不同）⇒ 同一 120ms 窗口内的多帧被
 * **乱序下发**。眼镜端 ProcessTimelineController 是单槽卡片（同 key 覆盖、后到
 * 先显），乱序的直接后果：
 *  - 用户视角卡片「跳回」上一步；
 *  - 快轮次里终态帧先于 RUNNING 帧到达又被后续 RUNNING 覆盖 ⇒ 卡片**停在旧步骤**
 *    （这就是「有几率不正常显示」——是否命中取决于当轮 UUID 的哈希序恰不巧）。
 *
 * 修法：窗口换成 LinkedHashMap（到达序即发送序）。本测试从 `collectPending`
 * seam 断言「到达序 = 发送序」—— 换回无序 map 这组断言**必然红**（判据⑧：
 * 测试要验证它真的能红，HashMap 下 key 顺序由哈希定，固定 key 集合的断言即会翻车）。
 */
class GlassesProcessBridgeOrderTest {

    @Before
    fun openGate() {
        // 打开 PROCESS_TIMELINE 握手门禁（forward/finish 的三态检查）
        GlassesHandshake.onHello(
            ver = 2,
            capsBitmask = LinkProtocol.Cap.PROCESS_TIMELINE,
            linkVersion = "test",
        )
    }

    @After
    fun closeGate() {
        GlassesHandshake.reset()
    }

    /** 一轮典型时序：思考 → 工具A启动 → 工具B启动 → A完成 → 思考完毕。 */
    @Test
    fun `arrival order equals send order across one window`() {
        val steps = listOf(
            AgentStep.thinking(round = 1),
            AgentStep.tool(callId = "call-a", name = "query_weather", state = AgentStep.State.RUNNING),
            AgentStep.tool(callId = "call-b", name = "play_music", state = AgentStep.State.RUNNING),
            AgentStep.tool(
                callId = "call-a", name = "query_weather", state = AgentStep.State.OK,
                result = "晴 25 度",
            ),
            AgentStep.thinking(round = 1, state = AgentStep.State.OK),
        )
        steps.forEach { GlassesProcessBridge.forward(it) }

        val sent = GlassesProcessBridge.collectPending()
        // 5 帧全在窗口内（key 各不相同或同 key 但尚未覆盖丢失：A running→ok 是覆盖，仍是 5→4 帧）
        // 期望 4 帧：think:1、tool:call-a（覆盖为 OK）、tool:call-b、think:1（OK 覆盖 RUNNING）
        assertEquals(listOf("think:1", "tool:call-a", "tool:call-b"), sent.map { it.key }.distinct())
        // 到达序：think 先于 call-a/call-b；call-a 的终态帧位置 = 它覆盖 RUNNING 帧的原插入位
        assertEquals("think:1", sent.first().key)
        assertTrue(sent.indexOfFirst { it.key == "tool:call-a" } < sent.indexOfFirst { it.key == "tool:call-b" })
        // 同 key 覆盖：窗口内只发最后一态（call-a 只出现一次，且是 OK）
        assertEquals(1, sent.count { it.key == "tool:call-a" })
        assertEquals(AgentStep.State.OK.name, sent.first { it.key == "tool:call-a" }.state)
    }

    /**
     * 乱序危害的最小复现：RUNNING 帧**后于**终态帧到达窗口（上轮工具的迟到更新
     * 混进本轮窗口）时，发送序仍按到达序 —— 眼镜端单槽卡片显示的就是最后到达的帧，
     * 手机侧不再借哈希序随机翻面。
     */
    @Test
    fun `late running frame does not jump ahead of earlier frames`() {
        GlassesProcessBridge.forward(
            AgentStep.tool(callId = "done-first", name = "query_weather", state = AgentStep.State.OK, result = "ok"),
        )
        GlassesProcessBridge.forward(
            AgentStep.tool(callId = "late", name = "play_music", state = AgentStep.State.RUNNING),
        )
        val sent = GlassesProcessBridge.collectPending()
        assertEquals(listOf("tool:done-first", "tool:late"), sent.map { it.key })
    }

    /** 空窗口：collectPending 幂等清空，二次取为空。 */
    @Test
    fun `collect drains window`() {
        GlassesProcessBridge.forward(AgentStep.thinking(round = 2))
        assertTrue(GlassesProcessBridge.collectPending().isNotEmpty())
        assertTrue(GlassesProcessBridge.collectPending().isEmpty())
    }
}
