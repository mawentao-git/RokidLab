package com.rokidlab.phone.ai

import java.util.concurrent.atomic.AtomicInteger

/**
 * 回合收尾守卫（2026-10-23 审计补）：让「忙闲计数归位」不依赖调用方回调的品行。
 *
 * ## 修的真实缺陷
 *
 * `AiConversationService.sendAiTextMessage` 的收尾回调改造前是这样：
 *
 * ```
 * traceFinishSink?.invoke(!success)
 * onResult?.invoke(success, err)          // ← 调用方代码（UI / TimerScheduler latch）
 * if (turnInFlight.decrementAndGet() == 0) { onTurnSettled(); drainInbox() }
 * ```
 *
 * `onResult` / `traceFinishSink` 都是**外部代码**：它们抛一次异常，`decrementAndGet`
 * 就永不执行 ⇒ 忙闲计数泄漏 ⇒ `AgentInbox` 相位永停 RUNNING ⇒ 之后所有后台到达
 * （定时自主任务）排队后**永不排水**——inbox 被单次回调异常永久楔死，且现场无任何日志。
 * 同时 `sendAiTextViaLink` 内 `onResult` 出口有多条，万一重复 invoke 还会多 decrement，
 * 在飞回合未结束就提前排水。
 *
 * ## 不变式（本类存在的原因，单测逐条钉住）
 *
 *  1. **收尾只认一次**：重复 settle 被构造性丢弃（不多 decrement、不重放回调）；
 *  2. **finally 归位**：body 抛异常时计数照常归零、"最后一个落地"回调照常触发；
 *  3. **顺序**：body（调用方回调）先于 onLastSettled（排水）—— 排水要看到调用方已得知结果；
 *  4. **共享计数**：多个回合重叠时，只有最后一个 settle 触发 onLastSettled；
 *  5. **计数不压负**：收尾多于派发（上游错位）时钳在 0 并照常触发排水，绝不出现负计数。
 *
 * 零 Android 依赖 ⇒ JVM 单测全覆盖（见 `TurnSettleGuardTest`）。
 */
internal class TurnSettleGuard(
    private val counter: AtomicInteger,
    private val onLastSettled: () -> Unit,
) {
    private val settled = AtomicInteger(0)

    /**
     * 收尾一个回合。[body] 里执行调用方回调（onResult 等）。
     *
     * 重复调用直接返回（不变式 1）—— 同一次派发只允许收尾一次。
     *
     * 计数用 [AtomicInteger.updateAndGet] 钳位递减而不是裸 `decrementAndGet`：
     * 上游计数错位（收尾多于派发）时宁可停在 0 并照常触发 [onLastSettled]
     * （排水幂等，多排无害），也不能压成负数 —— 负计数会让之后每个真实回合的
     * "最后一个落地"判定（值归 0）永远不成立，inbox 相位再次被楔死。
     */
    fun settle(body: () -> Unit) {
        if (!settled.compareAndSet(0, 1)) return
        try {
            body()
        } finally {
            // 不变式 2：计数归位与"最后一个落地"判定放在 finally ——
            // body（外部代码）抛什么都不能让它们被跳过。
            val after = counter.updateAndGet { cur -> if (cur > 0) cur - 1 else cur }
            if (after == 0) {
                onLastSettled()
            }
        }
    }
}
