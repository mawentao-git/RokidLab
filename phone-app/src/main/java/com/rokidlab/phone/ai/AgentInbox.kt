package com.rokidlab.phone.ai

/**
 * 回合收件箱（2026-10-23，对标 DSH 持久 inbox 的 Lab 化）：**生成期间到达的消息去哪儿**。
 *
 * ## 解决的真实问题
 *
 * 改造前 Lab 的回合调度是"最后消息赢"：每条 `sendAiTextMessage` 都 bump 代际号，
 * 在飞的回合在下个检查点自弃。交互入口（聊天 / 眼镜语音 / 拍照）要的就是这个 ——
 * 新消息必须立刻抢场。但**后台到达**（定时自主任务）走的也是同一条路：它会在用户
 * 聊得正好时抢场，把用户那条请求打断。`TimerScheduler.runAgentTask` 的 KDoc 把这件事
 * 记为"已知局限：彻底解耦需要独立的后台生成通道，属另一档工程"—— 本类就是那个解耦的
 * 最小实现：后台到达改为**排队**，当前回合落地后才跑，不再撞场。
 *
 * ## 模型（对齐 DSH，按 Lab 现状接线）
 *
 *  - **双泳道**：[Lane.TURN] 排队 prompt（当前回合结束后跑）；[Lane.STEP] 步边界 steer
 *    （在 RUNNING 回合的步骤边界注入）。
 *    ⚠️ STEP 泳道**已实现、未接线**：Lab 的语音插话（`dispatchGlassesAsrText`）本身就是
 *    打断通道，改成 steer 会让"说话停它"失效 —— 那是产品决策，要单独立项。
 *  - **phase 机**：[Phase.IDLE] / [Phase.RUNNING] / [Phase.MAINTENANCE]。TURN 项只在
 *    IDLE 排水；MAINTENANCE 期间到达的 TURN 项走 **wake latch**（等相位回 IDLE 才放行）——
 *    回合间的维护窗口（压缩 / 精炼）不该被新回合抢。相位是**全局**的：代际号本来就是
 *    全局的，回合实质全局串行，分箱只管队列。
 *    ⚠️ MAINTENANCE 同样已实现、未接线（Lab 的压缩发生在回合内部，见 `AgentSessionStore.compact`）。
 *  - **会话隔离 + 迟到丢弃**：队列按 sessionId 分箱；排水时**当前会话**之外的箱内条目
 *    一律构造性丢弃（对齐 DSH：过期消息不值得跑，但丢弃必须留痕 —— 见 [DrainResult.droppedStale]）。
 *  - **cancel 持久清空**：两条泳道一起清（不是只清队首）。
 *  - **顺延预算**：[MAX_DEFERRALS] 防"排队 → 排水 → 又撞上在飞回合 → 再排队"的乒乓；
 *    预算尽时 [enqueueTurn] 返回 false，调用方照改造前的语义抢场执行。
 *
 * ## 为什么是 object、没有落盘
 *
 * 生命周期与一轮生成同长（秒 ~ 分钟级）；进程被杀时在飞的回合本身就没了（历史重建走
 * 事件流），队列没有"必须跨重启存活"的场景。会话隔离用 sessionId 分箱 + 显式
 * [reset]，与 `ToolRepetitionTracker` 同一套手法。零 Android 依赖 ⇒ JVM 单测全覆盖。
 */
internal object AgentInbox {

    /** 回合相位（全局，理由见类文档） */
    enum class Phase { IDLE, MAINTENANCE, RUNNING }

    /** 泳道 */
    enum class Lane { TURN, STEP }

    /** 同一逻辑请求的最大顺延次数（防乒乓；超出后调用方自行抢场） */
    const val MAX_DEFERRALS = 3

    /**
     * 顺延结果标记：排队期间经 onResult 上报的 err 值。
     *
     * 放在这里而不是 AiConversationService 的 private companion —— 消费方
     * （TimerScheduler）要判它，而那个 companion 对外不可见是刻意设计。
     * 语义归属也在此：这个值的产生与消费都在收件箱协议内。
     */
    const val RESULT_DEFERRED = "deferred-by-inbox"

    /** 一次排水的结果：[run] = 可执行项（null = 无可跑项）；[droppedStale] = 被构造性丢弃的过期条目数 */
    data class DrainResult(val run: (() -> Unit)?, val droppedStale: Int)

    private class Item(val sessionId: String, val run: () -> Unit)

    private class Box {
        val turn = ArrayDeque<Item>()
        val step = ArrayDeque<Item>()
    }

    private val lock = Any()
    private val boxes = LinkedHashMap<String, Box>()
    private var phase: Phase = Phase.IDLE

    // ═══════════════════ phase 机（全局） ═══════════════════

    fun phase(): Phase = synchronized(lock) { phase }

    /** 回合开始：IDLE / MAINTENANCE → RUNNING。重复调用幂等（已在 RUNNING 不翻转）。 */
    fun onTurnStart() = synchronized(lock) {
        if (phase == Phase.IDLE || phase == Phase.MAINTENANCE) phase = Phase.RUNNING
    }

    /**
     * 回合落地：RUNNING → IDLE。只在**最后一个**在飞回合收尾时调用（调用方按忙闲计数把关）。
     * 幂等：IDLE 时再调保持 IDLE（不制造假相位）。
     */
    fun onTurnSettled() = synchronized(lock) {
        if (phase == Phase.RUNNING) phase = Phase.IDLE
    }

    /** 进入回合间维护（压缩 / 精炼）：仅 IDLE → MAINTENANCE（回合进行中不降级）。 */
    fun onMaintenanceStart() = synchronized(lock) {
        if (phase == Phase.IDLE) phase = Phase.MAINTENANCE
    }

    /** 维护结束：MAINTENANCE → IDLE。latch 的 TURN 项自此可排水。 */
    fun onMaintenanceEnd() = synchronized(lock) {
        if (phase == Phase.MAINTENANCE) phase = Phase.IDLE
    }

    // ═══════════════════ 入队 ═══════════════════

    /**
     * 排队一个 TURN 项（后台到达专用）。
     *
     * @param deferrals 这个逻辑请求已被顺延几次（首次入队传 1）
     * @return false = 超出 [MAX_DEFERRALS]，调用方应照旧抢场执行，不要静默丢弃
     */
    fun enqueueTurn(sessionId: String, deferrals: Int, run: () -> Unit): Boolean = synchronized(lock) {
        if (deferrals > MAX_DEFERRALS) return false
        boxOf(sessionId).turn.addLast(Item(sessionId, run))
        true
    }

    /**
     * 入队一个 STEP 项（步边界 steer，**未接线**，见类文档）。
     *
     * 相位不是 RUNNING 时**提升为 TURN**：没有在跑的回合可注入，steer 就退化成排队 prompt
     * （对齐 DSH：步边界消息在没有活跃回合时按下一轮处理）。
     */
    fun enqueueStep(sessionId: String, run: () -> Unit) = synchronized(lock) {
        val box = boxOf(sessionId)
        if (phase == Phase.RUNNING) box.step.addLast(Item(sessionId, run)) else box.turn.addLast(Item(sessionId, run))
    }

    // ═══════════════════ 出队 ═══════════════════

    /**
     * 取走当前可注入的步边界 steer（仅 [Phase.RUNNING]；返回的条目已从队列移除）。
     *
     * 相位不对时返回空且**不清队列** —— steer 等着，直到真有回合在跑（或被提升）。
     */
    fun takeStepSteering(sessionId: String): List<() -> Unit> = synchronized(lock) {
        if (phase != Phase.RUNNING) return emptyList()
        val box = boxes[sessionId] ?: return emptyList()
        val out = box.step.map { it.run }
        box.step.clear()
        out
    }

    /**
     * 排水一个 TURN 项（仅 [Phase.IDLE] —— wake latch 的落点）。
     *
     * [currentSessionId] 之外的箱内条目**构造性丢弃**：用户切走了会话，排队项再跑就会
     * 落进错误的会话上下文（显示、记忆、历史全串）。丢弃数通过 [DrainResult.droppedStale]
     * 上报，调用方必须记日志 —— 排队项消失不能无声。
     *
     * @return null 项 = 无可跑项（相位不对 / 队列空 / 刚丢完过期的）
     */
    fun drainTurn(currentSessionId: String): DrainResult = synchronized(lock) {
        var dropped = 0
        boxes.keys.filter { it != currentSessionId }.toList().forEach { sid ->
            val box = boxes.remove(sid)
            if (box != null) dropped += box.turn.size + box.step.size
        }
        val box = boxes[currentSessionId]
        if (phase != Phase.IDLE || box == null || box.turn.isEmpty()) {
            return DrainResult(null, dropped)
        }
        return DrainResult(box.turn.removeFirst().run, dropped)
    }

    // ═══════════════════ 查询 / 清理 ═══════════════════

    fun hasPendingTurns(sessionId: String): Boolean =
        synchronized(lock) { boxes[sessionId]?.turn?.isNotEmpty() == true }

    fun pendingCount(sessionId: String): Int =
        synchronized(lock) { boxes[sessionId]?.let { it.turn.size + it.step.size } ?: 0 }

    /**
     * cancel：两条泳道一起清空（用户叫停 / 会话结束）。返回清了几条。
     *
     * ⚠️ 与 DSH 一致：cancel 是**持久**操作（不是只取消队首）—— 用户叫停的语义是
     * "现在开始什么都别做"，留半截队列会在下一个空闲点自己冒出来。
     */
    fun cancel(sessionId: String): Int = synchronized(lock) {
        val box = boxes[sessionId] ?: return 0
        val n = box.turn.size + box.step.size
        box.turn.clear()
        box.step.clear()
        n
    }

    /** 整箱清理（会话结束 / 测试复位）。相位不动 —— 它是全局的，不属于任何一个会话。 */
    fun reset(sessionId: String): Unit = synchronized(lock) { boxes.remove(sessionId) }

    private fun boxOf(sessionId: String): Box = boxes.getOrPut(sessionId) { Box() }
}
