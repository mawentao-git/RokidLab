package com.rokidlab.rokidlink

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log

/**
 * 官方助手页可见性探测器（uiautomator / dumpsys 双实证：助手页与桌面同一 Activity）。
 *
 * 机制：UsageStats 增量区间查询（watermark 推进）轮询 ACTIVITY_RESUMED 事件，
 * 判定 com.rokid.os.sprite.launcher/SpriteMainActivity 是否在前台。
 *
 * 省电（2026-10-24 真机实测驱动）：**灭屏停轮询、亮屏恢复**。
 * 实测息屏待机 35s，本类 2s 轮询占 main 线程 CPU 的 65%（560ms/35s，~33ms/次）——
 * 而助手页可见性是 UI 概念，灭屏时不可能变化，纯浪费。恢复时 watermark 增量查询
 * 会自动补上灭屏期间的事件，不漏状态变化（水位只在 pollOnce 内推进，与亮灭屏无关）。
 *
 * ⚠️ 历史事故（2026-10-24 用户报「自定义图全部情况下都显示」根因之一）：
 * 启动自检原用 `queryEvents().hasNextEvent()` 判权限，把「有权限但 30min 内
 * 没有切页面」（刚开机、戴上眼镜助手页一直开着）误判为无权限 ⇒ 探测降级、
 * 常驻图只剩「对话即显示」且永不收起。现在权威判据是 AppOps 的 MODE_ALLOWED
 * （与 `adb appops set ... allow` 同一份状态），查不了才退回「调用不抛异常」。
 */
internal class AssistantPageWatcher(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val onVisibleChanged: (Boolean) -> Unit,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 官方助手页 = 眼镜桌面表盘页（uiautomator / dumpsys 双实证：助手页与桌面同一 Activity） */
        const val ASSISTANT_PKG = "com.rokid.os.sprite.launcher"
        const val ASSISTANT_ACTIVITY = "SpriteMainActivity"
        /** 轮询间隔：UsageStats 事件本身有秒级聚合延迟，再密也没意义 */
        private const val POLL_MS = 2_000L
        /**
         * 启动回看窗口：RokidLink 刚起来时不该干等下一次切页面才显示图。
         * 取 30min 覆盖「戴上眼镜、助手页一直开着、App 才启动」的常态。
         */
        private const val BACKTRACE_MS = 30 * 60_000L

        /**
         * 官方主界面（桌面表盘页）判定 —— 纯函数，单测锁定（判据⑧：断言必须能红）。
         *
         * 判据：包名精确相等 + 类名**包含** SpriteMainActivity。
         * 同包其他页面（如 `.page.music.MusicPageActivity`）不含该串 ⇒ false。
         * null 安全：UsageStats 事件的 className 可能为 null。
         */
        internal fun isAssistantPage(pkg: String?, cls: String?): Boolean =
            pkg == ASSISTANT_PKG && cls?.contains(ASSISTANT_ACTIVITY) == true

        /**
         * 「自定义图」该不该挂：主界面在前台 **或** 语音助手正在交互（2026-10-24 用户需求）。
         *
         * - [pageVisible]：watcher 对主界面的判定；null = 尚不知道（没有事件/探测降级）。
         * - [assistantActive]：语音助手活动窗口是否热（ASR/TTS 帧刷新，见 CxrBridgeCoordinator）。
         *
         * 二者取并集：主界面常驻显示（原语义）；语音助手对话期间也显示（新需求）；
         * 都否 ⇒ 收起。注意 **watcher 不可用时 [pageVisible] 恒为 null**，只剩窗口一条路 ——
         * 这正是降级语义：对话期间显示、过期收起，而不是"挂上就永不收"。
         */
        internal fun shouldShowCustomImage(pageVisible: Boolean?, assistantActive: Boolean): Boolean =
            pageVisible == true || assistantActive
    }

    /** 上次查到的时间水位（UsageStats 只支持增量区间查询，重复查整段会漏状态变化） */
    private var watermark = 0L
    /** null = 尚不知道（没有事件）；用于抑制重复回调 */
    private var visible: Boolean? = null
    private var broken = false
    private val pollTask = Runnable { step() }

    /** 探测已启用（start 成功、未 stop）。灭屏只暂停轮询，不解除启用 */
    private var armed = false

    /** 轮询循环是否在跑（灭屏暂停 / 未启动时为 false） */
    private var polling = false

    init {
        // 灭屏停轮询、亮屏恢复（见类注释）。注意 addListener 会立即回放当前屏幕态：
        // 灭屏期间被创建时直接进入暂停态，不会先空转一轮。
        ScreenStateHub.addListener { on -> if (on) resumePolling() else pausePolling() }
    }

    /**
     * 开始探测。
     * @return true = 探测可用（会回调状态变化）；false = 无权限/系统不支持，调用方须降级
     */
    fun start(): Boolean {
        if (broken) return false
        val usm = service.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            Log.e(TAG, "assistant page watcher: USAGE_STATS_SERVICE unavailable")
            broken = true
            return false
        }
        // 权限自证（判据见类注释历史事故）：
        // ① AppOps MODE_ALLOWED = 手机端 appops 已下发（权威）；
        // ② 查不了 op（API 28 / 系统服务缺失）⇒ 退回「queryEvents 调用本身不抛异常」。
        // ⚠️ 不要再拿 hasNextEvent 当判据：无权限时它静默返回空，
        //    与「有权限但窗口内无事件」不可区分（误判即功能静默降级）。
        val allowed = usageStatsOpAllowed()
        val ok = allowed ?: runCatching {
            usm.queryEvents(System.currentTimeMillis() - BACKTRACE_MS, System.currentTimeMillis())
            true
        }.getOrDefault(false)
        if (!ok) {
            broken = true
            Log.e(TAG, "assistant page watcher DISABLED: 缺 PACKAGE_USAGE_STATS。" +
                "手机端应经 adb appops set ${service.packageName} android:get_usage_stats allow 下发")
            return false
        }
        watermark = System.currentTimeMillis() - BACKTRACE_MS
        armed = true
        beginPolling()
        Log.i(TAG, "assistant page watcher started (poll ${POLL_MS}ms, backtrace ${BACKTRACE_MS / 60000}min)")
        return true
    }

    /**
     * `android:get_usage_stats` appop 是否已 allow。
     * @return true/false = 权威判定；null = 本机查不了（API < 29 无 OPSTR 常量 / 服务缺失），调用方走兜底判据
     */
    private fun usageStatsOpAllowed(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val am = service.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return null
        val mode = runCatching {
            am.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                service.packageName,
            )
        }.getOrNull() ?: return null
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun stop() {
        armed = false
        endPolling()
    }

    /** 亮屏恢复：仅当探测仍启用且循环已停时才重启（幂等，防重复 post） */
    private fun resumePolling() {
        if (!armed || polling || broken) return
        Log.i(TAG, "assistant page watcher: screen on, resuming poll")
        beginPolling()
    }

    /** 灭屏暂停：撤掉挂起的轮询任务，循环即停（armed 不变，亮屏后恢复） */
    private fun pausePolling() {
        if (!polling) return
        Log.i(TAG, "assistant page watcher: screen off, pausing poll")
        endPolling()
    }

    private fun beginPolling() {
        if (!armed || polling || broken) return
        polling = true
        core.mainHandler.removeCallbacks(pollTask)
        core.mainHandler.post(pollTask)
    }

    private fun endPolling() {
        if (!polling) return
        polling = false
        core.mainHandler.removeCallbacks(pollTask)
    }

    private fun step() {
        if (broken) return
        val usm = service.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm == null) {
            broken = true
            Log.e(TAG, "assistant page watcher: USAGE_STATS_SERVICE vanished, stop polling")
            return
        }
        try {
            pollOnce(usm)
        } finally {
            // ⚠️ 必须无条件排下一轮：本探测的常态就是「大部分区间查不到事件」
            // （UsageStats 只在切页面时产生 ACTIVITY_RESUMED），任何早退分支漏掉
            // 重新 post 都会让轮询永久停摆、助手页常驻图再也不出现。
            // armed=false（stop 过）或灭屏暂停时不排——后者由亮屏恢复路径负责重启。
            if (armed) core.mainHandler.postDelayed(pollTask, POLL_MS) else polling = false
        }
    }

    private fun pollOnce(usm: UsageStatsManager) {
        val now = System.currentTimeMillis()
        var last: Boolean? = null
        var sawEvent = false
        val failure = runCatching {
            val events = usm.queryEvents(watermark, now)
            // ⚠️ API 34 真实签名（javap 实测，别凭记忆写）：
            //    UsageEvents() 构造是包私有的；getNextEvent(Event) **要传参**且返回 boolean；
            //    Event 自身有公开无参构造，字段用 getXxx() 读。
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(ev)) break
                sawEvent = true
                if (ev.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = ev.packageName ?: continue
                val cls = ev.className.orEmpty()
                last = isAssistantPage(pkg, cls)
            }
        }.exceptionOrNull()
        // 水位无论成败都要推进，否则一次异常就会让区间无限增长、越查越慢
        watermark = now
        if (failure != null) {
            Log.e(TAG, "assistant page watcher query failed: ${failure.message}")
            return
        }
        // 本区间无任何事件 = 没切过页面 = 状态未变，安静等下一轮（不刷日志）
        if (!sawEvent) return
        val state = last ?: return
        if (state != visible) {
            Log.i(TAG, "assistant page: ${if (state) "VISIBLE" else "hidden"}")
            visible = state
            onVisibleChanged(state)
        }
    }
}
