package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 退避策略单测（2026-10-23，对齐 DSH llm-retry）。
 *
 * 锁两条纪律：① 429/503 的 Retry-After 被尊重但受预算封顶（超预算放弃重试）；
 * ② 指数退避带 ±25% 抖动（防多客户端同步重试的惊群）。
 * 两条都是**行为断言**（给具体输入断言具体输出/放弃），不是"能跑通"型测试。
 */
class BackoffPolicyTest {

    // ── 指数退避 + 抖动 ──

    @Test
    fun `随机源取中点时因子恰为一_退避值等于原值`() {
        // random=0.5 ⇒ jitterFactor = 1 - 0.25 + 2*0.25*0.5 = 1.0（确定性锚点）
        assertEquals(500L, BackoffPolicy.exponentialDelay(500, 0, 0.5))
        assertEquals(1000L, BackoffPolicy.exponentialDelay(500, 1, 0.5))
        assertEquals(2000L, BackoffPolicy.exponentialDelay(500, 2, 0.5))
    }

    @Test
    fun `退避受四秒硬上限封顶`() {
        // attempt 再大也压在 cap 上（且 exponent 钳位防止 Long 溢出）
        assertEquals(BackoffPolicy.DELAY_CAP_MS, BackoffPolicy.exponentialDelay(500, 10, 0.5))
        assertEquals(BackoffPolicy.DELAY_CAP_MS, BackoffPolicy.exponentialDelay(500, 62, 0.5))
    }

    @Test
    fun `抖动区间为加减四分之一且不超上限`() {
        assertEquals(375L, BackoffPolicy.exponentialDelay(500, 0, 0.0)) // ×0.75
        assertEquals(625L, BackoffPolicy.exponentialDelay(500, 0, 1.0)) // ×1.25
        // 封顶值被抖动抬高时必须压回 cap（4000×1.25=5000 ⇒ 4000）
        assertEquals(BackoffPolicy.DELAY_CAP_MS, BackoffPolicy.exponentialDelay(4000, 5, 1.0))
        // 随机源越界时钳位到 [0,1]：2.0 ⇒ 1.0 ⇒ ×1.25
        assertEquals(625L, BackoffPolicy.exponentialDelay(500, 0, 2.0))
        assertEquals(375L, BackoffPolicy.exponentialDelay(500, 0, -1.0))
    }

    // ── Retry-After ──

    @Test
    fun `RetryAfter_秒数形式被尊重`() {
        assertEquals(1000L, BackoffPolicy.parseRetryAfterMs("1", nowMs = 0L))
        assertEquals(120_000L, BackoffPolicy.parseRetryAfterMs("120", nowMs = 0L))
    }

    @Test
    fun `RetryAfter_HTTP_date_形式被解析`() {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val target = 1_800_000_000_000L // 秒对齐的固定时刻
        val header = fmt.format(Date(target))
        assertEquals(2000L, BackoffPolicy.parseRetryAfterMs(header, nowMs = target - 2000L))
    }

    @Test
    fun `RetryAfter_过去的时间归一为零`() {
        assertEquals(0L, BackoffPolicy.parseRetryAfterMs("-5", nowMs = 0L))
        assertEquals(0L, BackoffPolicy.parseRetryAfterMs("0", nowMs = 0L))
    }

    @Test
    fun `RetryAfter_无法解析时返回_null`() {
        assertNull(BackoffPolicy.parseRetryAfterMs(null, nowMs = 0L))
        assertNull(BackoffPolicy.parseRetryAfterMs("", nowMs = 0L))
        assertNull(BackoffPolicy.parseRetryAfterMs("soon", nowMs = 0L))
        assertNull(BackoffPolicy.parseRetryAfterMs("next tuesday", nowMs = 0L))
    }

    // ── resolveDelay：等待多久 / 是否放弃 ──

    @Test
    fun `有_RetryAfter_时按服务端时长等待且不加抖动`() {
        // 服务端说 2s：就是 2s（×1.25 抖动会把它变成 2.5s，违背"服务端说等多久"）
        assertEquals(2000L, BackoffPolicy.resolveDelay(500, 0, "2", nowMs = 0L, random = 1.0))
    }

    @Test
    fun `RetryAfter_超出预算时放弃重试`() {
        // cap=4s，服务端要 10s：等不起 ⇒ null（调用方应直接上报失败）
        assertNull(BackoffPolicy.resolveDelay(500, 0, "10", nowMs = 0L))
        // 恰好等于预算边界：仍等待（封顶判据是 > 而非 >=）
        assertEquals(
            BackoffPolicy.DELAY_CAP_MS,
            BackoffPolicy.resolveDelay(500, 0, "4", nowMs = 0L),
        )
    }

    @Test
    fun `无_RetryAfter_时走带抖动的指数退避`() {
        assertEquals(1000L, BackoffPolicy.resolveDelay(500, 1, null, nowMs = 0L, random = 0.5))
        assertEquals(750L, BackoffPolicy.resolveDelay(500, 1, "garbage", nowMs = 0L, random = 0.0))
    }

    @Test
    fun `RetryAfter_为零立即可重试`() {
        assertEquals(0L, BackoffPolicy.resolveDelay(500, 0, "0", nowMs = 0L))
    }
}
