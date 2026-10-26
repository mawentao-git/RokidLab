package com.rokidlab.phone.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentInbox] 的不变式。
 *
 * 相位是**全局**的（见类文档），所以每个用例自带 @After 复位；sessionId 全部取唯一值，
 * 不依赖用例执行顺序。
 */
class AgentInboxTest {

    @After
    fun tearDown() {
        forceIdle()
        listOf("s1", "s2", "sA", "sB", "s-old", "s-new", "s-multi").forEach { AgentInbox.reset(it) }
    }

    private fun forceIdle() {
        AgentInbox.onTurnSettled()
        AgentInbox.onMaintenanceEnd()
    }

    @Test
    fun `RUNNING 期间排队的 TURN 项不排水，回合落地后才排`() {
        val ran = mutableListOf<String>()
        AgentInbox.onTurnStart()
        AgentInbox.enqueueTurn("s1", 1) { ran += "a" }
        // 回合在飞：不排水（这正是"不撞场"的判据）
        assertNull(AgentInbox.drainTurn("s1").run)
        assertTrue(ran.isEmpty())
        AgentInbox.onTurnSettled()
        val drained = AgentInbox.drainTurn("s1")
        assertNotNull(drained.run)
        drained.run?.invoke()
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `同箱 TURN 项 FIFO 出水`() {
        forceIdle()
        val order = mutableListOf<Int>()
        repeat(3) { i -> AgentInbox.enqueueTurn("s1", 1) { order += i } }
        repeat(3) { AgentInbox.drainTurn("s1").run?.invoke() }
        assertEquals(listOf(0, 1, 2), order)
        assertNull(AgentInbox.drainTurn("s1").run)
    }

    @Test
    fun `MAINTENANCE 期间到达的 TURN 项 latch 到 IDLE 才放行`() {
        val ran = mutableListOf<String>()
        AgentInbox.onMaintenanceStart()
        AgentInbox.enqueueTurn("s1", 1) { ran += "x" }
        // wake latch：维护窗口内不排水
        assertNull(AgentInbox.drainTurn("s1").run)
        assertTrue(ran.isEmpty())
        AgentInbox.onMaintenanceEnd()
        val drained = AgentInbox.drainTurn("s1")
        assertNotNull(drained.run)
        drained.run?.invoke()
        assertEquals(listOf("x"), ran)
    }

    @Test
    fun `cancel 清空两条泳道且只影响目标会话`() {
        AgentInbox.onTurnStart()
        AgentInbox.enqueueStep("s1") { }
        AgentInbox.enqueueTurn("s1", 1) { }
        AgentInbox.enqueueTurn("sB", 1) { }
        assertEquals(2, AgentInbox.cancel("s1"))
        assertEquals(0, AgentInbox.pendingCount("s1"))
        assertFalse(AgentInbox.hasPendingTurns("s1"))
        // 别的会话不受影响
        assertTrue(AgentInbox.hasPendingTurns("sB"))
        // cancel 之后排水为空（用户叫停的语义：什么都别做）
        AgentInbox.onTurnSettled()
        assertNull(AgentInbox.drainTurn("s1").run)
    }

    @Test
    fun `过期会话条目构造性丢弃且计数上报`() {
        val ran = mutableListOf<String>()
        AgentInbox.enqueueTurn("s-old", 1) { ran += "old" }
        val drained = AgentInbox.drainTurn("s-new")
        assertEquals(1, drained.droppedStale)
        assertNull(drained.run)
        // 丢弃 = 不执行（排队项消失必须留痕，这里由 droppedStale 上报给调用方记日志）
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `STEP 项只在 RUNNING 取走，非 RUNNING 入队时提升为 TURN`() {
        val ran = mutableListOf<String>()
        // ① 非 RUNNING 入队 ⇒ 提升为 TURN，回合落地后照常跑
        forceIdle()
        AgentInbox.enqueueStep("s1") { ran += "idle-step" }
        assertTrue(AgentInbox.takeStepSteering("s1").isEmpty()) // 没有在跑的回合，取不到
        val drained = AgentInbox.drainTurn("s1")
        assertNotNull(drained.run)
        drained.run?.invoke()
        assertEquals(listOf("idle-step"), ran)
        // ② RUNNING 入队 ⇒ 步边界取走，取完即空
        AgentInbox.onTurnStart()
        AgentInbox.enqueueStep("s1") { ran += "running-step" }
        val steering = AgentInbox.takeStepSteering("s1")
        assertEquals(1, steering.size)
        steering[0]()
        assertTrue(AgentInbox.takeStepSteering("s1").isEmpty())
        // ③ 相位不对时 takeStepSteering 不清队列（迟到一点但仍送达）
        AgentInbox.enqueueStep("s1") { ran += "late-step" }
        AgentInbox.onTurnSettled() // IDLE：取不到，但也不丢
        assertTrue(AgentInbox.takeStepSteering("s1").isEmpty())
        assertEquals(1, AgentInbox.pendingCount("s1"))
        AgentInbox.onTurnStart()
        assertEquals(1, AgentInbox.takeStepSteering("s1").size)
        AgentInbox.onTurnSettled()
        assertEquals(listOf("idle-step", "running-step"), ran)
    }

    @Test
    fun `顺延预算尽时 enqueueTurn 返回 false`() {
        assertTrue(AgentInbox.enqueueTurn("s1", 1) { })
        assertTrue(AgentInbox.enqueueTurn("s1", 2) { })
        assertTrue(AgentInbox.enqueueTurn("s1", 3) { })
        // > MAX_DEFERRALS(3)：调用方必须自行抢场，不能静默丢弃
        assertFalse(AgentInbox.enqueueTurn("s1", 4) { })
    }

    @Test
    fun `相位翻转幂等且回合进行中不降级到维护`() {
        assertEquals(AgentInbox.Phase.IDLE, AgentInbox.phase())
        AgentInbox.onTurnStart()
        AgentInbox.onTurnStart()
        assertEquals(AgentInbox.Phase.RUNNING, AgentInbox.phase())
        // 回合进行中：维护启动不得降级
        AgentInbox.onMaintenanceStart()
        assertEquals(AgentInbox.Phase.RUNNING, AgentInbox.phase())
        AgentInbox.onTurnSettled()
        assertEquals(AgentInbox.Phase.IDLE, AgentInbox.phase())
        // IDLE 上重复 settled / MAINTENANCE 上 start 回合：不制造假相位
        AgentInbox.onTurnSettled()
        assertEquals(AgentInbox.Phase.IDLE, AgentInbox.phase())
        AgentInbox.onMaintenanceStart()
        AgentInbox.onMaintenanceStart()
        assertEquals(AgentInbox.Phase.MAINTENANCE, AgentInbox.phase())
        // 维护中起回合：RUNNING（维护被回合接管）
        AgentInbox.onTurnStart()
        assertEquals(AgentInbox.Phase.RUNNING, AgentInbox.phase())
        AgentInbox.onTurnSettled()
        assertEquals(AgentInbox.Phase.IDLE, AgentInbox.phase())
    }

    @Test
    fun `reset 清箱且空闲空排水不造事件`() {
        AgentInbox.enqueueTurn("s1", 1) { }
        AgentInbox.reset("s1")
        assertFalse(AgentInbox.hasPendingTurns("s1"))
        val drained = AgentInbox.drainTurn("s1")
        assertNull(drained.run)
        assertEquals(0, drained.droppedStale)
    }

    @Test
    fun `hasPendingTurns 与 pendingCount 反映两条泳道`() {
        assertFalse(AgentInbox.hasPendingTurns("s1"))
        assertEquals(0, AgentInbox.pendingCount("s1"))
        AgentInbox.onTurnStart()
        AgentInbox.enqueueStep("s1") { }
        AgentInbox.enqueueTurn("s1", 1) { }
        assertEquals(2, AgentInbox.pendingCount("s1"))
        assertTrue(AgentInbox.hasPendingTurns("s1"))
        // 只有 step 项时 hasPendingTurns 为 false（它只答 TURN 队列）
        AgentInbox.cancel("s1")
        AgentInbox.enqueueStep("s1") { }
        assertFalse(AgentInbox.hasPendingTurns("s1"))
        assertEquals(1, AgentInbox.pendingCount("s1"))
        AgentInbox.onTurnSettled()
    }
}
