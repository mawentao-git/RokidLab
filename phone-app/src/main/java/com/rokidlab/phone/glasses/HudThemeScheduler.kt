package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.tools.GlassHudToolProvider
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * HUD 主题定时 / 情景联动（2026-10-25 P0-3；**纯手机端**，协议零改，眼镜端只是接收方）。
 *
 * 两类规则（按序评估，**state 优先于 time**，先命中先应用）：
 *  - `time`：每日时间窗口 `[startMin, endMin)`（分钟；`start > end` = 跨零点，如 22:00→07:00）
 *  - `state`：bindOn 同款条件（`ai_state:busy` / `battery_phone:<=20`），数据满足即应用
 *
 * 设计取舍（留痕）：
 *  - **边沿触发 + 防抖**：只有「期望主题发生变化」才下发；无规则命中时**不动**
 *    （用户手动应用/对话里 apply 的主题保持，调度器不擅自 hide）。
 *    用户手动切主题后，只要期望值没变，调度器不再抢 —— 手动优先直到规则翻转。
 *  - 应用走 [GlassHudToolProvider.applyDsl]（设置页同款路径：先回灌主题图再 apply），
 *    **不往对话流插预览卡**（自动切换不是用户请求，插卡会刷屏）。
 *  - 主题被删/改名、条件写错 → 静默跳过（规则指向不存在的主题无害；保存前校验另见 [validate]）。
 *  - 默认关闭（opt-in）：没有任何规则命中前不会有任何自动行为。
 */
internal object HudThemeScheduler {
    private const val TAG = "HudThemeScheduler"
    private const val PREFS = "hud_theme_scheduler_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_RULES = "rules"
    private const val TICK_SEC = 30L

    const val KIND_TIME = "time"
    const val KIND_STATE = "state"

    data class Rule(
        val id: String,
        val kind: String,
        val themeName: String,
        val startMin: Int = 0,
        val endMin: Int = 0,
        val cond: String = "",
    )

    // ── 纯函数（单测锚点；无 Context 依赖） ────────────────────────

    /** 每日窗口命中（分钟 0..1439；start==end = 空窗口永不命中；start>end = 跨零点） */
    fun inWindow(nowMin: Int, startMin: Int, endMin: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> nowMin in startMin until endMin
        else -> nowMin >= startMin || nowMin < endMin // 跨零点：22:00→07:00
    }

    /** state 规则条件命中（条件解析失败/无数据 → false，容错：坏规则不炸 ticker） */
    fun stateHit(cond: String, data: Map<String, String>): Boolean {
        if (cond.isBlank()) return false
        return runCatching {
            val c = HudDsl.HudBind.parseCond(cond)
            c.match(data[c.key])
        }.getOrDefault(false)
    }

    /** 期望主题名：state 规则按序优先，其次 time 规则；null = 无命中（调用方保持现状） */
    fun desiredTheme(rules: List<Rule>, nowMin: Int, data: Map<String, String>): String? {
        rules.firstOrNull { it.kind == KIND_STATE && stateHit(it.cond, data) }?.let { return it.themeName }
        rules.firstOrNull { it.kind == KIND_TIME && inWindow(nowMin, it.startMin, it.endMin) }
            ?.let { return it.themeName }
        return null
    }

    /** 分钟 → "HH:mm"（UI 展示） */
    fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    /** 保存前校验（返回 null = 合法；非 null = 用户可读原因） */
    fun validate(context: Context, rule: Rule): String? {
        if (rule.themeName.isBlank()) return "先选主题"
        if (HudThemeStore.find(context, rule.themeName) == null) {
            return "没有叫「${rule.themeName}」的主题，先用对话保存或从主题库选择"
        }
        return when (rule.kind) {
            KIND_TIME -> null
            KIND_STATE -> runCatching { HudDsl.HudBind.parseCond(rule.cond) }.fold(
                onSuccess = { null },
                onFailure = { "条件格式不对：${it.message}" },
            )
            else -> "规则类型只支持 time/state"
        }
    }

    // ── 存储（SharedPreferences + JSON；单写者：管理页 + 本调度器） ──

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(K_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(K_ENABLED, value).apply()
        if (value) ctx?.let { executor.execute { tick(it) } }
    }

    fun rules(context: Context): List<Rule> = runCatching {
        val arr = JSONArray(prefs(context).getString(K_RULES, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toRule() }
    }.getOrDefault(emptyList())

    fun saveRule(context: Context, rule: Rule) {
        val list = rules(context).filter { it.id != rule.id } + rule
        prefs(context).edit()
            .putString(K_RULES, JSONArray(list.map { it.toJson() }).toString())
            .apply()
    }

    fun deleteRule(context: Context, id: String) {
        val list = rules(context).filter { it.id != id }
        prefs(context).edit()
            .putString(K_RULES, JSONArray(list.map { it.toJson() }).toString())
            .apply()
    }

    fun newRuleId(): String = "r${System.currentTimeMillis()}"

    private fun Rule.toJson() = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("themeName", themeName)
        put("startMin", startMin)
        put("endMin", endMin)
        put("cond", cond)
    }

    private fun JSONObject.toRule() = Rule(
        id = optString("id").ifEmpty { newRuleId() },
        kind = optString("kind", KIND_TIME),
        themeName = optString("themeName").trim(),
        startMin = optInt("startMin").coerceIn(0, 1439),
        endMin = optInt("endMin").coerceIn(0, 1439),
        cond = optString("cond").trim(),
    )

    // ── 运行时（App 级注册一次；30s tick + 数据变更即评估） ─────────

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hud-theme-sched").apply { isDaemon = true }
    }
    private val lock = Any()
    private var ctx: Context? = null
    private var attached = false

    /** 调度器最近应用的主题名（null = 未应用过）。进程内记忆；冷启动时按当前 active 主题对齐 */
    private var lastApplied: String? = null

    private val dataListener = HudDataHub.OnDataChanged { ctx?.let { executor.execute { tick(it) } } }

    /** App 级注册（与 [HudDataHub.attach] 同点调用）：立即评估一次 + 30s tick + 订阅数据变更 */
    fun attach(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (attached) return
            attached = true
            ctx = app
        }
        executor.execute { tick(app) }
        executor.scheduleAtFixedRate({ tick(app) }, TICK_SEC, TICK_SEC, TimeUnit.SECONDS)
        HudDataHub.addDataListener(dataListener)
        Log.i(TAG, "attached")
    }

    /**
     * 单次评估：期望主题变化才下发。
     *  - 期望 == 调度器已应用 → 跳过（防抖，含进程重启后按 active 主题对齐的情况）
     *  - 期望 == 眼镜当前 active 主题 → 只记账不下发（用户手动应用过同款）
     *  - 主题不存在 / 下发失败 → 跳过（下次 tick 自然重试）
     */
    internal fun tick(context: Context) {
        if (!enabled(context)) return
        val rs = rules(context)
        if (rs.isEmpty()) return
        val desired = desiredTheme(rs, currentMinute(), HudDataHub.current())
        if (desired.isNullOrBlank()) return
        if (desired == lastApplied) return
        val activeName = HudThemeStore.activeId(context)
            ?.let { id -> HudThemeStore.list(context).firstOrNull { it.id == id }?.name }
        if (desired == activeName) {
            lastApplied = desired
            return
        }
        val theme = HudThemeStore.find(context, desired) ?: return
        val err = GlassHudToolProvider.applyDsl(context, theme.dsl, theme.id)
        if (err == null) {
            lastApplied = desired
            Log.i(TAG, "theme auto-applied: ${theme.name}")
        } else {
            Log.w(TAG, "theme auto-apply skipped (${theme.name}): $err")
        }
    }

    private fun currentMinute(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }
}
