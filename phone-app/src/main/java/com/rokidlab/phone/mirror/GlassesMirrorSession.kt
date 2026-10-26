package com.rokidlab.phone.mirror

import android.content.Context
import android.content.Intent
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 眼镜投屏页（[ScreenMirrorActivity]）与工具层之间的**单点桥**（2026-10-18 审计 M5）。
 *
 * 存在的理由：投屏页的状态机 [com.rokidlab.phone.feature.ScreenMirrorStateHolder] 是
 * **Activity-handle 模式**（每个 Activity 一份），工具跑在后台线程、拿不到实例 ⇒
 * 「AI 说『别投了』」这件事没有出口。本类只解决**页面级**的三个动作：
 * 页面是否开着、拉起它、关掉它。
 *
 * ⚠️ 边界（刻意不做的事）：
 *  - **不暴露串流状态**。`isStreaming` 是 Compose 快照，只在 Activity 内存里，
 *    跨进程/跨线程读它需要把状态机改成单例 —— 那是另一次重构。
 *    本类只答"页面开着没有"，文案里也如实说明这**不等于正在串流**（§见工具分支）。
 *  - **不替代 UI 入口**。用户手动点照样能开能关，两条路并存。
 *  - **不吞失败**。`launch` / `requestClose` 都返回布尔，调用方负责说人话。
 *
 * 与 [com.rokidlab.phone.browser.BrowserSession] 同款形态（弱引用 + 单例），
 * 差别只在关页面用 `finish()` 而不是 `WebView.destroy()`。
 */
object GlassesMirrorSession {

    private const val TAG = "GlassesMirrorSession"

    /**
     * 拉起投屏页后等它真正就绪的上限。
     *
     * 为什么要等：直接 `startActivity` 后返回 true 只说明"请求已发出"，
     * 紧接着报"投屏已开始"就是**把过程说成结果**（2026-10-15「封面显示了」幻觉同款）。
     * 页面 onCreate 里有一次 `mirrorSession.init()`，10 秒足够它置位。
     */
    private const val PAGE_READY_TIMEOUT_MS = 10_000L

    private val pageRef = AtomicReference<WeakReference<ScreenMirrorActivity>?>(null)

    /** 页面是否已经完成 onCreate（比"Activity 对象存在"更接近"能用"） */
    private val readyFlag = AtomicBoolean(false)

    // ═══════════════════ Activity 侧登记 ═══════════════════

    /** 由 [ScreenMirrorActivity] 在 onCreate/onDestroy 调用（弱引用，避免持有 Activity 泄漏） */
    internal fun onPageCreated(activity: ScreenMirrorActivity) {
        pageRef.set(WeakReference(activity))
        readyFlag.set(true)
    }

    internal fun onPageDestroyed(activity: ScreenMirrorActivity?) {
        // ⚠️ 只在"还是自己这一页"时清：重建/多实例时不能被旧实例的 onDestroy 抹掉新实例的登记
        if (pageRef.get()?.get() === activity) {
            readyFlag.set(false)
            pageRef.set(null)
        }
    }

    /** 投屏页当前是否打开（**弱信号**：只代表页面在，不代表连接成功，见类注释） */
    fun isPageOpen(): Boolean = readyFlag.get() && pageRef.get()?.get()?.isFinishing == false

    // ═══════════════════ 工具侧调用 ═══════════════════

    /**
     * 拉起投屏页并等它就位。
     *
     * @return true = 页面已打开并完成 init；false = 打不开或超时（超时也算 false，不谎报）
     */
    fun launch(context: Context): Boolean {
        if (isPageOpen()) return true
        val app = context.applicationContext
        try {
            app.startActivity(
                ScreenMirrorActivity.createIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.e(TAG, "拉起投屏页失败", e)
            return false
        }
        val deadline = System.currentTimeMillis() + PAGE_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (readyFlag.get()) {
                // ⚠️ 路由解析 / ADB 租约 / 解码都由页面自己的 MirrorCoordinator 负责，
                // 这里不往全局塞连接对象（两个 ADB 会话并存会打架，见 RULES §12.11 租约约束）。
                Log.i(TAG, "投屏页已就绪")
                return true
            }
            Thread.sleep(120)
        }
        Log.w(TAG, "投屏页在 ${PAGE_READY_TIMEOUT_MS}ms 内未就绪")
        return false
    }

    /**
     * 请求关闭投屏页。
     *
     * 用 `finish()` 而不是杀进程：页面 onDestroy 里 `onActivityDestroy()` 会释放 ADB 租约与
     * 解码器，直接杀进程会留下**未释放的长期租约**（蓝牙通道被占住，下次连不上）。
     *
     * @return true = 已请求关闭；false = 页面没开或系统拒绝了（调用方如实告知，别报成功）
     */
    fun requestClose(context: Context): Boolean {
        val live = pageRef.get()?.get()
        if (live == null || live.isFinishing) return false
        return runCatching {
            live.runOnUiThread {
                runCatching { live.mirrorSession.disconnectAndFinish() }
                    .onFailure { Log.w(TAG, "disconnectAndFinish 失败: ${it.message}") }
            }
        }.isSuccess
    }
}
