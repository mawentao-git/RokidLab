package com.rokidlab.phone.mirror

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「截屏/投屏」系统授权的交接点 —— 工具线程 ↔ `MainActivity` 之间唯一的通道。
 *
 * ## 为什么需要这么一个东西
 *
 * MediaProjection 的授权框只能由 **Activity** 弹出（`createScreenCaptureIntent` +
 * `registerForActivityResult`），而工具是在 `ai-turn-worker` 线程上跑的、手上只有
 * `Context`。于是把"弹框"这件事按方向拆开：
 *
 * ```
 * 工具线程 ──requestXxxConsent()──▶ 主线程 launch 系统授权框 ──▶ 用户点按
 *         ◀────结果（resultCode + Intent）──────────────────── ActivityResult 回调
 * ```
 *
 * `MainActivity` 在 `onStart` 挂上发起器、`onStop` 摘掉 —— 挂载窗口就是"界面能弹框"的
 * 窗口，因此 `canRequestXxxConsent()` 为 false 时工具可以直接如实回报"请先打开 App 界面"，
 * 而不是干等超时。
 *
 * ## 为什么是两条独立通道（2026-10-17 二轮审计补）
 *
 * 「截屏」与「投屏」的授权**各是各的**：截屏拿一次性 token 抓一帧就停
 * （`ScreenCaptureService`）；投屏的 token 归 `PhoneMirrorService` 长期持有
 * （targetSdk 34 起一个 MediaProjection 只允许 createVirtualDisplay 一次）。
 * 两条链路各有独立的 launcher 与回调 ⇒ 各用一条 [ConsentChannel]，
 * 谁也不许把结果投进对方的闩锁（否则截屏的授权会被投屏线程拿去开虚拟屏）。
 */
object ScreenCaptureBroker {
    private const val TAG = "ScreenCaptureBroker"

    /** 单条授权通道：发起器挂载 + 一次"请求-等待-交付" */
    private class ConsentChannel {
        @Volatile
        private var launcher: (() -> Unit)? = null

        private val lock = Any()
        private var latch: CountDownLatch? = null
        private var result: Pair<Int, Intent>? = null

        fun canRequest(): Boolean = launcher != null

        fun attach(launch: () -> Unit) {
            launcher = launch
        }

        fun detach() {
            launcher = null
        }

        /** @return `resultCode to data`；null = 界面不可用 / 用户拒绝 / 超时 / 已有一次在进行 */
        fun request(timeoutMs: Long): Pair<Int, Intent>? {
            val launch = launcher ?: return null
            val wait = CountDownLatch(1)
            synchronized(lock) {
                // 已有一次在等：不叠加（否则两个工具线程会各自等到错的结果）
                if (latch != null) return null
                latch = wait
                result = null
            }
            Handler(Looper.getMainLooper()).post {
                // 界面不在 STARTED 状态时 launch 会抛 IllegalStateException：
                // 当作"没同意"处理并立刻放行，不能让它把工具线程打崩
                runCatching { launch() }.onFailure {
                    Log.w(TAG, "launch consent failed: ${it.message}")
                    synchronized(lock) { latch?.countDown() }
                }
            }
            val delivered = runCatching { wait.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            synchronized(lock) {
                val r = if (delivered) result else null
                latch = null
                result = null
                return r
            }
        }

        /** `MainActivity` 的 ActivityResult 回调入口（主线程） */
        fun deliver(resultCode: Int, data: Intent?) {
            synchronized(lock) {
                result = if (data != null) resultCode to data else null
                latch?.countDown()
            }
        }
    }

    private val capture = ConsentChannel()
    private val mirror = ConsentChannel()

    // ── 截屏通道（原 API 原样保留）──

    /** 现在弹得出系统授权框吗（App 界面不在前台时为 false） */
    fun canRequestConsent(): Boolean = capture.canRequest()

    fun attach(launch: () -> Unit) = capture.attach(launch)

    fun detach() = capture.detach()

    fun requestConsent(timeoutMs: Long): Pair<Int, Intent>? = capture.request(timeoutMs)

    fun deliverConsent(resultCode: Int, data: Intent?) = capture.deliver(resultCode, data)

    // ── 投屏通道（2026-10-17 补，screen_mirror 工具用）──

    fun canRequestMirrorConsent(): Boolean = mirror.canRequest()

    fun attachMirror(launch: () -> Unit) = mirror.attach(launch)

    fun detachMirror() = mirror.detach()

    fun requestMirrorConsent(timeoutMs: Long): Pair<Int, Intent>? = mirror.request(timeoutMs)

    fun deliverMirrorConsent(resultCode: Int, data: Intent?) = mirror.deliver(resultCode, data)
}
