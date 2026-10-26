package com.rokidlab.phone.glasses

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokid.cxr.Caps
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import com.rokidlab.phone.ai.ToolRegistry
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HUD bind 数据中枢（docs/hud-dsl.md「数据绑定」；第一批系统级 key）。
 *
 * 采集系统状态、变化即事件驱动经 `AiChannel.TOPIC_HUD_DATA` 下行小包（只发变化的键，
 * 400ms 合并节流）。眼镜端存 dataMap、更新即重绘；手机预览卡直接取 [current] 保证
 * 预览=眼镜所见。
 *
 * 采集点（新增 key 时在此登记 + 同步 HudDsl 白名单）：
 *  - `battery_phone`：attach() 注册 ACTION_BATTERY_CHANGED 粘性广播（注册即收到当前值）
 *  - `battery_glass`：惰性经眼镜 ADB `dumpsys battery` 刷新（TTL 10min，随其他 flush
 *    搭车；ADB 不可得则保持无数据，渲染端空态兜底）
 *  - `link`：CxrLHiRokidSession.notifyConnectionChanged()（连接状态总漏斗）
 *  - `ai_state`：LabApplication agent trace sink（有步骤进行中 = busy，轮次结束 = idle）
 *  - `timer`：TimerScheduler 任一倒计时任务变化（只发"剩余秒数"锚点，走秒由渲染端本地算）
 *  - `calendar_next`：CalendarBriefing.refreshHud()（30 分钟自检链 + App 启动首刷；只发
 *    "距下一日程秒数"锚点，走秒由渲染端本地算。取舍：刷新粒度 30 分钟 + 本地走秒补齐，
 *    2 小时窗口内无日程 = 清数据；HUD 只做氛围提示，精确日程看 get_calendar 工具结果）
 *  - `unread`：NotificationDigest 缓冲变更点（待摘要通知数；消息摘要开关关闭 = 完全不采集，
 *    与通知正文同一条隐私边界；0 = 清数据）
 *  - `tool_result`：hud_display 工具写入（AI 显式控制，非自动推送 —— 避免隐私/噪音，
 *    仅当 HUD 场景绑定了该键的 text 元素才上屏）
 *  - `gps_speed`/`gps_bearing`：GPS LocationListener（5s/5m；km/h 与度，2026-10-25 P1-2）
 *  - `steps`：TYPE_STEP_COUNTER 按本地日切基线（prefs 存 baseline+day）
 *  - `music`：AudioManager.isMusicActive 轮询（10s 一拍）
 *
 * ⚠️ P1-2 采集器启停（gps/steps/music）：HUD 场景**绑定**这些键 ⇒ 常开（setCollectorDemand）；
 * 未绑定则靠活动窗口兜底（glass_hud 下发/hud_display 写入后续期 10 分钟， hudHidden 或
 * 空闲超时即停并清数据 = HUD 显示空态 "--"，不留过期值误导）。GPS 是耗电大户故按需启停；
 * 计步/音乐轮询廉价但同窗管理，行为一致可预期。权限缺失（定位/身体活动）时静默跳过对应
 * 采集器 —— HUD 是氛围展示，不弹权限框；用户经其他途径授权后下次窗口自动续上。
 *
 * ⚠️ 取舍记账：battery_glass 不做常驻轮询（ADB 查询重且耗电），TTL 搭车策略下最长
 * 10 分钟陈旧 —— HUD 电量弧仅是氛围元素，精确读数应看 get_glasses_status 工具结果。
 * 官方表盘已有电量显示，bind 默认别用（docs/hud-dsl.md 已标注）。
 */
object HudDataHub {
    private const val TAG = "HudDataHub"

    /** 合并节流窗口：事件驱动低频源，400ms 合并足够（蓝牙串行纪律：小包匀速） */
    private const val MIN_FLUSH_INTERVAL_MS = 400L

    private const val GLASS_BATTERY_TTL_MS = 10 * 60_000L

    // ── P1-2 采集器（gps_speed/gps_bearing/steps/music）启停参数 ──
    /** HUD 活动窗口：最近一次 HUD 交互（glass_hud/hud_display）后的采集宽限期 */
    private const val HUD_IDLE_STOP_MS = 10 * 60_000L
    /** 采集器巡检周期（空闲关停判定；只在采集器开着时做事，开销可忽略） */
    private const val COLLECTOR_TICK_MS = 60_000L
    /** GPS 订阅参数：5s / 5m（HUD 氛围刷新率，再快眼镜端也看不出且耗电） */
    private const val GPS_INTERVAL_MS = 5_000L
    private const val GPS_DISTANCE_M = 5f
    /** 音乐状态轮询周期（isMusicActive 廉价，10s 一拍足够） */
    private const val MUSIC_POLL_MS = 10_000L
    /** 需要采集器供数的 bind 键（场景绑定其中任一 ⇒ 采集器常开） */
    private val COLLECTOR_KEYS = setOf("gps_speed", "gps_bearing", "steps", "music")
    private const val PREFS_NAME = "hud_data_hub"
    private const val KEY_STEP_BASE = "step_baseline"
    private const val KEY_STEP_DAY = "step_day"

    /** 当前值（key→字符串，与 TOPIC_HUD_DATA 载荷同格式；value 可为空串=清数据） */
    private val data = ConcurrentHashMap<String, String>()

    /** 各 key 值到达锚点（elapsedRealtime）：timer 键据此本地走秒 */
    private val anchors = ConcurrentHashMap<String, Long>()

    /** 待发键集合（合并窗口） */
    private val dirty = ConcurrentHashMap.newKeySet<String>()

    private val flushScheduled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hud-data-hub").apply { isDaemon = true }
    }

    private var ctx: Context? = null
    private var lastFlushAt = 0L

    @Volatile
    private var lastGlassBatteryAt = 0L

    // ── P1-2 采集器状态（gps/steps/music）──
    /** 采集器是否在跑（GPS 监听 / 步数传感器 / 音乐轮询的总开关） */
    private val collectorsOn = AtomicBoolean(false)
    /** 当前场景是否绑定采集键（常驻主题/下发场景；决定空闲后是否立即停） */
    private val collectorDemand = AtomicBoolean(false)
    /** 最近一次 HUD 活动时刻（elapsedRealtime；活动窗口判据） */
    @Volatile
    private var lastHudActivityAt = 0L
    private var locationListener: LocationListener? = null
    private var stepListener: SensorEventListener? = null
    private var musicFuture: ScheduledFuture<*>? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                update("battery_phone", (level * 100 / scale).toString())
            }
        }
    }

    /** App 级注册一次（`LabApplication.setCxrL`）。 */
    fun attach(context: Context) {
        ctx = context.applicationContext
        runCatching {
            context.applicationContext.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
        }.onFailure { Log.w(TAG, "register battery receiver failed: ${it.message}") }
        // 采集器空闲巡检（P1-2）：60s 一拍，采集器关着时直接返回
        executor.scheduleWithFixedDelay(
            { collectorTick() }, COLLECTOR_TICK_MS, COLLECTOR_TICK_MS, TimeUnit.MILLISECONDS,
        )
        // 冷启动对齐：常驻主题若绑定采集键，直接进入采集态（否则眼镜留陈旧值直到下次交互）
        executor.execute { restoreDemandForActiveTheme() }
    }

    // ── P1-2 采集器启停（gps_speed/gps_bearing/steps/music）────────────────

    /**
     * 声明当前 HUD 场景对采集键的需求（glass_hud 下发 / 主题应用时调用，携场景 DSL 原文）：
     * 绑定 gps_speed/gps_bearing/steps/music ⇒ 采集器常开；未绑定则只靠活动窗口兜底。
     * 解析失败/空 DSL ⇒ 无需求（不猜）。同时刷新活动窗口。
     */
    fun setCollectorDemand(dslJson: String?) {
        val keys = if (dslJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { HudDsl.parse(dslJson).elements.mapNotNull { it.bindKey } }
                .getOrDefault(emptyList())
        }
        collectorDemand.set(keys.any { it in COLLECTOR_KEYS })
        touchHudActivity()
    }

    /** HUD 活动信号（hud_display 写入等）：刷新活动窗口，确保采集器在跑（不改需求） */
    fun touchHudActivity() {
        lastHudActivityAt = SystemClock.elapsedRealtime()
        ensureCollectors()
    }

    /** HUD 收起（hide）：清需求 + 活动窗口置枯，下个巡检拍停采集并清数据（眼镜上已无绑定元素） */
    fun hudHidden() {
        collectorDemand.set(false)
        lastHudActivityAt = SystemClock.elapsedRealtime() - HUD_IDLE_STOP_MS
    }

    private fun ensureCollectors() {
        if (collectorsOn.get()) return
        val warm = SystemClock.elapsedRealtime() - lastHudActivityAt < HUD_IDLE_STOP_MS
        if (!warm && !collectorDemand.get()) return
        if (collectorsOn.compareAndSet(false, true)) startCollectors()
    }

    private fun collectorTick() {
        if (!collectorsOn.get()) return
        val idle = SystemClock.elapsedRealtime() - lastHudActivityAt >= HUD_IDLE_STOP_MS
        if (idle && !collectorDemand.get()) stopCollectors()
    }

    private fun startCollectors() {
        val context = ctx ?: run {
            collectorsOn.set(false)
            return
        }
        // GPS：权限缺失/无定位服务时静默跳过（氛围展示不弹权限框；授权后下次窗口自动续上）
        if (hasLocationPermission(context)) startGps(context)
        // 计步：API 29+ 需 ACTIVITY_RECOGNITION（minSdk=29 恒需；manifest 已声明）
        if (hasActivityPermission(context)) startSteps(context)
        startMusicPoll()
        Log.i(TAG, "hud collectors started (gps/steps/music)")
    }

    private fun stopCollectors() {
        if (!collectorsOn.compareAndSet(true, false)) return
        val context = ctx
        if (context != null) {
            stopGps(context)
            stopSteps(context)
        }
        stopMusicPoll()
        // 清数据 ⇒ 眼镜端渲染空态 "--"，不留过期值误导（空串即清，与 updateToolResult("") 同路径）
        update("gps_speed", "")
        update("gps_bearing", "")
        update("steps", "")
        update("music", "")
        Log.i(TAG, "hud collectors stopped (idle)")
    }

    // ── GPS（gps_speed km/h / gps_bearing 度）──

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasActivityPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startGps(context: Context) {
        if (locationListener != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // speed m/s → km/h 取整；bearing 度归一 0..359；字段缺失不更新（保留上一次有效值）
                if (location.hasSpeed()) {
                    update("gps_speed", (location.speed * 3.6f).toInt().coerceAtLeast(0).toString())
                }
                if (location.hasBearing()) {
                    val deg = ((location.bearing.toInt() % 360) + 360) % 360
                    update("gps_bearing", deg.toString())
                }
            }
        }
        try {
            // 显式主线程 Looper：调用线程（hub executor）无 Looper
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, GPS_DISTANCE_M,
                listener, Looper.getMainLooper(),
            )
            locationListener = listener
        } catch (e: Exception) {
            // 权限竞争/ provider 不可用等：本期静默（HUD 显示 "--"），不炸数据链
            Log.w(TAG, "gps listener register failed: ${e.message}")
        }
    }

    private fun stopGps(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationListener?.let { l -> runCatching { lm.removeUpdates(l) } }
        locationListener = null
    }

    // ── 计步（steps：今日步数，按本地日切基线）──

    private fun startSteps(context: Context) {
        if (stepListener != null) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) onStepCounter(event.values[0])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val ok = try {
            // 事件投递必须显式主线程 Handler（调用线程无 Looper）
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "step counter register failed: ${e.message}")
            false
        }
        stepListener = if (ok) listener else null
    }

    private fun stopSteps(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        stepListener?.let { l -> runCatching { sm.unregisterListener(l) } }
        stepListener = null
    }

    /**
     * TYPE_STEP_COUNTER 报的是「自开机累计」：按本地日切基线（prefs 存 baseline + day），
     * steps = 当前累计 - 基线。跨日或计数器回绕（重启后 total < baseline）⇒ 基线重置。
     */
    private fun onStepCounter(total: Float) {
        val context = ctx ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = dayIndex(System.currentTimeMillis())
        val baseDay = prefs.getLong(KEY_STEP_DAY, -1L)
        var base = prefs.getFloat(KEY_STEP_BASE, -1f)
        if (baseDay != today || base < 0f || total < base) {
            base = total
            prefs.edit().putLong(KEY_STEP_DAY, today).putFloat(KEY_STEP_BASE, base).apply()
        }
        update("steps", (total - base).toLong().coerceAtLeast(0).toString())
    }

    private fun dayIndex(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
    }

    // ── 音乐（music：playing/paused，10s 轮询）──

    private fun startMusicPoll() {
        if (musicFuture != null) return
        musicFuture = executor.scheduleWithFixedDelay({
            if (!collectorsOn.get()) return@scheduleWithFixedDelay
            val context = ctx ?: return@scheduleWithFixedDelay
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@scheduleWithFixedDelay
            update("music", if (am.isMusicActive) "playing" else "paused")
        }, MUSIC_POLL_MS, MUSIC_POLL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopMusicPoll() {
        musicFuture?.cancel(false)
        musicFuture = null
    }

    /** 冷启动对齐：常驻主题绑定采集键 ⇒ 直接恢复采集（否则要等下一次 HUD 交互才供数） */
    private fun restoreDemandForActiveTheme() {
        val context = ctx ?: return
        val id = HudThemeStore.activeId(context) ?: return
        val theme = HudThemeStore.list(context).firstOrNull { it.id == id } ?: return
        setCollectorDemand(theme.dsl)
    }

    // ── 采集入口（任意线程；同值幂等不发）──

    fun updateBatteryGlass(pct: Int) = update("battery_glass", pct.coerceIn(0, 100).toString())

    fun updateLink(connected: Boolean) =
        update("link", if (connected) "connected" else "disconnected")

    fun updateAiState(busy: Boolean) = update("ai_state", if (busy) "busy" else "idle")

    /** 计时器剩余秒数；≤0 = 无进行中的计时器（清数据） */
    fun updateTimer(remainingSec: Long) = update("timer", remainingSec.coerceAtLeast(0).toString())

    /** 距下一日程开始的秒数；≤0 = 无即将开始的日程（清数据） */
    fun updateCalendarNext(secondsUntilBegin: Long) =
        update("calendar_next", if (secondsUntilBegin > 0) secondsUntilBegin.toString() else "")

    /** 待摘要通知数；≤0 = 无（清数据） */
    fun updateUnread(count: Int) =
        update("unread", if (count > 0) count.coerceAtMost(99).toString() else "")

    /** hud_display 工具写入的短文本（≤20 字符，超出截断）；空串 = 清除 */
    fun updateToolResult(text: String) = update("tool_result", text.trim().take(HudDsl.MAX_TEXT_CHARS))

    /** 预览卡取实时数据（key→原始值）；[currentAnchors] 供 timer 走秒 */
    fun current(): Map<String, String> = HashMap(data)

    fun currentAnchors(): Map<String, Long> = HashMap(anchors)

    /**
     * 重连后全量重发：眼镜端每次新会话 dataMap 从空开始，纯增量事件会让它缺失
     * 断连期间更新的键（含 tool_result 等）。由连接建立点（CxrLHiRokidSession.
     * notifyConnectionChanged）调用；节流合并保证最多一包。
     */
    fun resendAll() {
        if (data.isEmpty()) return
        dirty.addAll(data.keys)
        scheduleFlush()
    }

    private fun update(key: String, value: String) {
        val old = data.put(key, value)
        if (old == value && anchors.containsKey(key)) return
        anchors[key] = SystemClock.elapsedRealtime()
        dirty.add(key)
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            executor.execute { flush() }
        }
    }

    /** 合并节流：窗口内多次变化合并为一包（只带变化的键）。 */
    private fun flush() {
        try {
            val wait = MIN_FLUSH_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastFlushAt)
            if (wait > 0) {
                executor.schedule({ flush() }, wait, TimeUnit.MILLISECONDS)
                return
            }
            lastFlushAt = SystemClock.elapsedRealtime()
            flushNow()
        } finally {
            flushScheduled.set(false)
        }
        // flushNow 期间又来的变化 → 再排一轮
        if (dirty.isNotEmpty()) scheduleFlush()
    }

    // ── 数据变更监听（HudThemeScheduler 情景联动订阅；flush 成功后回调，携全量 data）──

    fun interface OnDataChanged {
        fun onDataChanged(data: Map<String, String>)
    }

    private val dataListeners = java.util.concurrent.CopyOnWriteArrayList<OnDataChanged>()

    fun addDataListener(l: OnDataChanged) {
        dataListeners.add(l)
    }

    fun removeDataListener(l: OnDataChanged) {
        dataListeners.remove(l)
    }

    private fun flushNow() {
        val keys = dirty.toList()
        if (keys.isEmpty()) return
        if ("battery_glass" in keys) {
            lastGlassBatteryAt = SystemClock.elapsedRealtime()
        } else {
            maybeRefreshGlassBattery()
        }
        val entries = keys.mapNotNull { k -> data[k]?.let { k to it } }
        if (entries.isEmpty()) return
        dirty.removeAll(entries.map { it.first }.toSet())
        Log.i(TAG, "hud data↓ ${entries.joinToString { "${it.first}=${it.second}" }}")
        send(entries)
        // 监听在 send 之后回调（眼镜已收到新值；监听回调里做联动决策时数据面是一致的）
        if (dataListeners.isNotEmpty()) {
            val snapshot = HashMap(data)
            dataListeners.forEach { l -> runCatching { l.onDataChanged(snapshot) } }
        }
    }

    /**
     * battery_glass 惰性刷新：随其他 flush 搭车（TTL 内不重查）。查询走眼镜 ADB
     * `dumpsys battery`（与 get_glasses_status 工具同源）；ADB 不可得则静默保持无数据。
     */
    private fun maybeRefreshGlassBattery() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGlassBatteryAt < GLASS_BATTERY_TTL_MS) return
        lastGlassBatteryAt = now
        val context = ctx ?: return
        executor.execute {
            runCatching {
                synchronized(ToolRegistry.adbLock) {
                    val client = ToolRegistry.adbClient(context) ?: return@execute
                    val raw = client.getBatteryInfo()
                    val level = Regex("level: (\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                    if (level != null) updateBatteryGlass(level)
                }
            }.onFailure { Log.d(TAG, "glass battery refresh skipped: ${it.message}") }
        }
    }

    /** 下行门禁三态与 GlassesProcessBridge.send 同款：Cap.HUD + liveSession + aiCmdLock。 */
    private fun send(entries: List<Pair<String, String>>) {
        if (GlassesHandshake.supports(LinkProtocol.Cap.HUD) != true) return
        val session = GlassToolConfirmChannel.global.liveSession() ?: return
        val link = session.cxrLink ?: return
        runCatching {
            synchronized(session.aiCmdLock) {
                val caps = Caps()
                for (f in AiChannel.encodeHudData(entries)) caps.write(f)
                session.rawSendCustomCmd(link, AiChannel.TOPIC_HUD_DATA, caps)
            }
        }.onFailure { Log.w(TAG, "send hud data failed: ${it.message}") }
    }
}
