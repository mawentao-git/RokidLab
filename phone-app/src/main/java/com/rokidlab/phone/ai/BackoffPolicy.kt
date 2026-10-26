package com.rokidlab.phone.ai

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * LLM 请求的**退避策略**（对齐 deepseek-harness `llm-retry` 的两条核心纪律）。
 *
 * ## 为什么从 OpenAiService 里抽出来
 * 改造前退避是 `chatTurnStream` 里的一行 `(retryBaseDelayMs shl attempt).coerceAtMost(4000L)`
 * —— 纯计算却埋在 3000 行的网络方法里，既没法单测，也看不到"为什么是 4s"。
 * 抽成纯函数对象后：策略可单测、上限/抖动比有名字、`chatTurnStream` 只负责调用。
 *
 * ## 抄自 DSH 的两条（各修一个真机问题）
 *  1. **尊重服务端 `Retry-After`**（429/503）：服务端比我们更清楚自己多久恢复。
 *     但受 [DELAY_CAP_MS] 封顶 —— 超出预算 ⇒ **放弃重试**（[resolveDelay] 返回 null），
 *     等不起的限流不该盲目重试（DSH normal 模式同款：providerRetryAfter > maxDelay 就不重试）。
 *  2. **抖动**：原实现无抖动，同局域网的多个客户端（或多会话并发）会在同一时刻
 *     同步重试，把刚恢复的服务端再打一次（"惊群"）。抖动因子在
 *     `[1-JITTER_RATIO, 1+JITTER_RATIO]` 之间浮动，期望值仍是原值。
 *
 * ## 不抄的
 * DSH 的 retry 状态是 session projection（持久化重试计数）—— Lab 的重试发生在
 * 单次 `chatTurnStream` 调用内，进程内计数足够，持久化没有对应的恢复点。
 */
internal object BackoffPolicy {

    /** 退避硬上限（毫秒）：与服务端 Retry-After 共用。保持改造前的 4s 不变。 */
    const val DELAY_CAP_MS = 4000L

    /** 抖动幅度：最终延迟 = 计算值 × [1-r, 1+r] 内的因子（期望 = 计算值）。 */
    const val JITTER_RATIO = 0.25

    /**
     * 指数退避 + 抖动。
     *
     * @param baseDelayMs 首次重试的基数（调用方的 `retryBaseDelayMs`，默认 500）
     * @param attempt 第几次重试，从 0 起（0 = 首次重试，等 base；1 = 2×base…）
     * @param random 抖动随机源，`[0,1)`；测试传固定值。0.5 ⇒ 因子恰为 1.0（不抖）。
     */
    fun exponentialDelay(baseDelayMs: Long, attempt: Int, random: Double): Long {
        // exponent 封顶 30：attempt 再大 base<<30 也已远超 cap，先钳位避免 Long 溢出
        val exponent = attempt.coerceIn(0, 30)
        val raw = (baseDelayMs shl exponent).coerceIn(0L, DELAY_CAP_MS)
        return (raw * jitterFactor(random)).toLong().coerceIn(0L, DELAY_CAP_MS)
    }

    /** 抖动因子：`random=0.5` 时恰为 1.0（确定性测试的锚点）。 */
    fun jitterFactor(random: Double): Double =
        1 - JITTER_RATIO + 2 * JITTER_RATIO * random.coerceIn(0.0, 1.0)

    /**
     * 解析 `Retry-After` 响应头 → 毫秒。无法解析（缺失/既不是秒数也不是 HTTP-date）返回 null。
     *
     * 两种法定形式都认（RFC 7231）：
     *  - 秒数：`"120"`（最常见，DeepSeek/OpenAI 都用它）；
     *  - HTTP-date：`"Wed, 21 Oct 2026 07:28:00 GMT"`。
     * 过去的时间戳归一为 0（立即可重试），负秒数同样归一为 0。
     */
    fun parseRetryAfterMs(value: String?, nowMs: Long): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1000L }
        val dateMs = runCatching {
            val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("GMT")
            fmt.parse(v)?.time
        }.getOrNull() ?: return null
        return (dateMs - nowMs).coerceAtLeast(0L)
    }

    /**
     * 决定这次重试等多久。
     *
     * @return 等待毫秒数；**null = 放弃重试**（服务端 `Retry-After` 要求的等待超出
     *   [DELAY_CAP_MS] 预算 —— 调用方应直接上报失败，让用户看到真实原因，
     *   而不是把服务端的限流时长原样转嫁给用户）。
     */
    fun resolveDelay(
        baseDelayMs: Long,
        attempt: Int,
        retryAfterHeader: String?,
        nowMs: Long,
        random: Double = Math.random(),
    ): Long? {
        val retryAfterMs = parseRetryAfterMs(retryAfterHeader, nowMs)
        if (retryAfterMs != null) {
            // 服务端明示的等待：尊重它，但不加抖动（抖它就违背了"服务端说等多久"的本意），
            // 且受预算封顶 —— 超预算的限流等不起，放弃重试。
            if (retryAfterMs > DELAY_CAP_MS) return null
            return retryAfterMs
        }
        return exponentialDelay(baseDelayMs, attempt, random)
    }
}
