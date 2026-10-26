package com.rokidlab.phone.ai

import android.content.Context
import com.rokidlab.phone.store.ChatStateHolder
import org.json.JSONObject

/**
 * 一句话动作/查询直办快路（2026-10-25，结构性省 token 第三刀的**通用化**）。
 *
 * ## 存在的理由（用户定调：「放歌只是个例子，你该看还有哪些能走快路」）
 *
 * 完整 Agent 循环的代价是**两次全价模型往返**（工具轮 + 收尾轮，每轮重发整段
 * system + 历史 + 全量 Schema ≈13~14k input token，方舟等无前缀缓存端点每轮全价）。
 * 有一类输入**根本不需要模型参与判断**：整句就是一个高置信、参数自含的动作或查询。
 * 对它们本地直办 = 0 token、秒回。本路由器把这类句子统一收口：
 *
 *  - [MusicDirectAction]（点播/停止音乐，首个落地件）由本路由器委托；
 *  - 本文件新增：时间/电量/剪贴板**只读查询**（工具结果原文即回复）、
 *    手电筒/找手机/音量/打开应用/计时器**本地轻动作**、纯算式直算。
 *
 * ## 入选纪律（宁缺毋滥，每条边界都要有测试钉住）
 *
 *  1. **整句锚定**：整条消息必须完整命中模式 —— 复合句/任务句/含多余语境一律不命中，
 *     漏放的代价只是落回完整循环（维持原状），误放的代价是「说了 A 却执行了 B」；
 *  2. **只收参数自含的句子**：需要从上下文推断参数的（「今天天气怎么样」→ 哪个城市？
 *     「取消提醒」→ 哪个任务？）一律不收，交给模型补全；
 *  3. **只收低风险工具**：LOCAL 侧效果或只读；`send_sms` 等高危长尾**永远不进快路**
 *     （见 MEMORY §6 误放率纪律）；
 *  4. **执行体与工具链同一份**：经 [ToolRegistry.execute] 统一分发，行为零漂移；
 *  5. **尊重装配门禁**：执行前核对工具总开关（[ToolRegistry.isEnabled]）与聊天分组
 *     收窄（[ChatStateHolder.currentGroupToolScope]）—— 快路不能变成绕过用户关掉
 *     某工具的后门；被门禁挡下时返回 null **落回完整循环**，绝不发空回复。
 *
 * ## 与调用方的分工
 *
 * [plan] 是纯函数（无 Android 依赖，单测钉全部边界）；[execute] 做门禁 + 执行。
 * 调用方（`AiConversationService` 生成体）负责其余门禁（本机/只读/图片/答题轮）、
 * 抢占检查（execute 前 `isSuperseded()`）与回复落链路（onReply/TTS/事件流）。
 */
internal object QuickActionRouter {

    /** 直办计划：目标工具 + 参数。args 由 plan 构造，只按键读取（键序无契约）。 */
    data class Plan(val tool: String, val args: JSONObject)

    /** 与 MusicDirectAction/ChatFastPath 一致：长文本多半是任务描述/粘贴内容，绝不直办 */
    private const val MAX_LEN = 64

    // ── 只读查询（结果原文即回复） ────────────────────────────────

    /** 现在几点/今天几号/星期几 —— get_current_time 的返回同时含日期+星期+时分，全部形态都能答。
     *  ⚠️ 前缀只收 现在/今天（返回的是当下时刻）；「明天星期几」需要推算，交给模型。 */
    private val TIME_QUERY = Regex(
        "^(现在|今天)?(几点了?|几点钟|什么时间|时间|日期|几号|几月几号|星期几|礼拜几)(了|呢|呀|啊)?[?？!！。]?$",
    )

    /**
     * 手机电量 —— get_phone_status 返回电量+音量+亮度全量文本，多给的信息无害。
     * ⚠️ 核心词必须是「电量/多少电」而不能是裸「电」：裸电字会误伤「来电了」「放电」
     * 这类无关句。没电了/充电等语义也不收（交给模型）。
     */
    private val BATTERY_QUERY = Regex(
        "^(手机|电池)?(的)?(电量|还剩多少电|还有多少电|剩多少电|剩多少电了)(怎么样|如何|多少|了吗|了|呢)?[?？!！。]?$",
    )

    /** 读剪贴板（Android 10 后台限制由工具结果如实说明，直办也拿到诚实文案） */
    private val CLIPBOARD_QUERY = Regex(
        "^(请|帮我)?(读一下|读下|看看|看一下|看下)?剪贴板(里|中)?(的)?(内容|东西|有什么|有啥|是什么|是啥)?(吗|呢)?[?？!！。]?$",
    )

    /** 纯算式直算：只收 ASCII 数学字符与 ×÷（中文应用题「378 乘 56」交给模型）；×÷ 归一后透传 */
    private val MATH_EXPR = Regex("^[0-9+\\-*/().,%^×÷\\s]+$")

    /** 「2016-08-15」这类日期形参在算式语义外 —— 命中即弃（当减法算会得出荒谬结果） */
    private val DATE_LIKE = Regex("\\d{1,4}\\s*-\\s*\\d{1,2}\\s*-\\s*\\d{1,4}")

    // ── 本地轻动作 ───────────────────────────────────────────────

    private val FLASHLIGHT_ON = Regex(
        "^(请|帮我|给我)?(把)?手电筒(给我)?(打开|开一下|开起来|开了)(吧|呀)?$" +
            "|^(请|帮我|给我)?(打开|开启|开一下)(一下)?手电筒(吧|呀)?$",
    )
    private val FLASHLIGHT_OFF = Regex(
        "^(请|帮我|给我)?(把)?手电筒(给我)?(关闭|关掉|关一下|关了)(吧|呀)?$" +
            "|^(请|帮我|给我)?(关闭|关掉|关一下)(一下)?手电筒(吧|呀)?$",
    )

    private val FIND_PHONE_START = Regex("^(请|帮我)?找(一下|一找|找)?手机(吧|呀|吗)?$")
    private val FIND_PHONE_STOP = Regex("^(别响了|别找了|停止响铃|停止找手机|不用找了)[吧呀]?[。!！]?$")

    /** 音量：静音(0) / 最大(100) / 显式百分比；「调大一点」这类相对量交给模型 */
    private val VOLUME_MUTE = Regex(
        "^(请|帮我)?(把)?(媒体)?音量(调到|设为|设置成|开到)?(静音|零|0)(吧|呀)?$" +
            "|^(请|帮我)?(把)?(媒体)?静音(吧|呀)?$",
    )
    private val VOLUME_MAX = Regex("^(请|帮我)?(把)?(媒体)?音量(调到|设为|设置成|开到)?(最大|最满|满格|100)(吧|呀)?$")
    private val VOLUME_PCT = Regex("^(请|帮我)?(把)?(媒体)?音量(调到|设为|设置成|开到)?(\\d{1,3})(%|％)?(吧|呀)?$")

    /**
     * 打开应用：rest 作为 appName 交给 open_phone_app（label 模糊匹配由工具做，
     * 匹配不到返回诚实的失败文案）。三类守卫：
     *  - **设备词排除**：「打开蓝牙/打开 WiFi」不是开应用，是系统设置 —— 交给模型走
     *    正确工具（快路硬办只会得到「没有找到应用」的误导性失败）；
     *  - **眼镜排除**：「打开眼镜上的 xx」是眼镜端应用域，不是手机端；
     *  - **应用名长度/句读**：超长或含句读 = 不是应用名。
     */
    private val OPEN_APP = Regex("^(请|帮我|给我)?(打开|启动|开一下|开启)(一下)?(.+?)(这个)?(应用|app|APP)?(吧|呀)?$")
    private val OPEN_APP_DEVICE_WORDS = setOf(
        "蓝牙", "wifi", "wlan", "无线", "热点", "手电筒", "闪光灯", "手电",
        "飞行模式", "音量", "亮度", "静音", "nfc", "定位", "gps", "震动",
    )
    /** 复合句线索（与 MusicDirectAction 同款纪律）：rest 里带连接词 = 不是应用名 */
    private val OPEN_APP_COMPOUND_HINTS = listOf("然后", "接着", "顺便", "还有", "以及", "再帮", "再查", "再设", "对了")
    private const val MAX_APP_NAME_LEN = 16

    /** 计时器：「5分钟后提醒我喝水」「设个10分钟倒计时」（相对时长直办；固定时刻交给模型） */
    private val TIMER_REMIND = Regex("^(请|帮我)?(半|\\d+(?:\\.\\d+)?)个?(小时|分钟|秒)后提醒我(.*)$")
    private val TIMER_COUNTDOWN = Regex(
        "^(请|帮我)?(设|定|设置|设个|定个)(一个|个)?(半|\\d+(?:\\.\\d+)?)个?(小时|分钟|秒)(的)?(倒计时|计时器)(吧|呀)?$",
    )
    private val TIMER_CANCEL_ALL = Regex("^(请|帮我)?取消(所有|全部|所有的)(的)?(计时器|定时任务|提醒)(吧|呀)?[。]?$")

    /** 「1小时30分钟」复合时长换算不自含 → 交给模型；直办只收单一时长 */
    private val COMPOUND_DURATION = Regex("^\\d*\\s*(小时|分钟|秒)")

    // ── 判定（纯函数，单测锚点） ─────────────────────────────────

    /**
     * 这条消息是否直办 + 怎么办。null = 不直办（调用方落回完整 Agent 循环）。
     * 顺序即优先级：先语义动作（音乐→手电筒→找手机→音量→计时器），后查询，
     * rest 最宽的泛匹配（打开应用/算式）放最后 —— 放前面会吞掉具体动作句。
     */
    fun plan(text: String): Plan? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        if (t.contains('\n') || t.contains('\r')) return null

        // 音乐（首个落地件，判据与白名单在自己的类里）
        when (val p = MusicDirectAction.plan(t)) {
            is MusicDirectAction.Plan.Stop ->
                return Plan("control_music", JSONObject().put("action", "stop"))
            is MusicDirectAction.Plan.Play ->
                return Plan(
                    "control_music",
                    // ⚠️ 必须显式包 JSONArray：直接 put Kotlin List 在 Maven org.json
                    // （单测）里不会包成数组，Android 端却会 ⇒ 两端行为分裂
                    JSONObject().put("action", "play").put("songNames", org.json.JSONArray(p.songs)),
                )
            null -> {}
        }

        // 手电筒
        if (FLASHLIGHT_ON.matches(t)) return Plan("set_flashlight", JSONObject().put("enabled", true))
        if (FLASHLIGHT_OFF.matches(t)) return Plan("set_flashlight", JSONObject().put("enabled", false))

        // 找手机
        if (FIND_PHONE_STOP.matches(t)) return Plan("find_phone", JSONObject().put("action", "stop"))
        if (FIND_PHONE_START.matches(t)) return Plan("find_phone", JSONObject().put("action", "start"))

        // 音量
        if (VOLUME_MUTE.matches(t)) return Plan("set_phone_volume", JSONObject().put("volume", 0))
        if (VOLUME_MAX.matches(t)) return Plan("set_phone_volume", JSONObject().put("volume", 100))
        VOLUME_PCT.matchEntire(t)?.let { m ->
            val v = m.groupValues[5].toInt()
            if (v in 0..100) return Plan("set_phone_volume", JSONObject().put("volume", v))
        }

        // 计时器
        if (TIMER_CANCEL_ALL.matches(t)) {
            return Plan("manage_timer", JSONObject().put("action", "cancel").put("all", true))
        }
        TIMER_REMIND.matchEntire(t)?.let { m ->
            val seconds = durationSeconds(m.groupValues[2], m.groupValues[3])
            val content = m.groupValues[4].trim()
            // 复合时长尾（「1小时」后跟「30分钟…」被切成 content）或非法数值 → 参数不自含，弃
            if (seconds <= 0 || COMPOUND_DURATION.matches(content)) return null
            return Plan(
                "manage_timer",
                JSONObject().put("action", "set").put("schedule_type", "countdown")
                    .put("seconds", seconds).put("content", content.ifEmpty { "定时提醒" }),
            )
        }
        TIMER_COUNTDOWN.matchEntire(t)?.let { m ->
            val seconds = durationSeconds(m.groupValues[4], m.groupValues[5])
            if (seconds <= 0) return null
            return Plan(
                "manage_timer",
                JSONObject().put("action", "set").put("schedule_type", "countdown")
                    .put("seconds", seconds).put("content", "倒计时"),
            )
        }

        // 查询类
        if (TIME_QUERY.matches(t)) return Plan("get_current_time", JSONObject())
        if (BATTERY_QUERY.matches(t)) return Plan("get_phone_status", JSONObject())
        if (CLIPBOARD_QUERY.matches(t)) return Plan("read_clipboard", JSONObject())

        // 打开应用（rest 宽，放查询之后）
        OPEN_APP.matchEntire(t)?.let { m ->
            val app = m.groupValues[4].trim()
            if (app.isEmpty() || app.length > MAX_APP_NAME_LEN) return null
            if (app.contains("眼镜")) return null
            // 设备词/复合句守卫用 contains：rest 可能带尾巴（「手电筒然后查天气」）
            if (OPEN_APP_DEVICE_WORDS.any { app.lowercase().contains(it) || app.contains(it) }) return null
            if (OPEN_APP_COMPOUND_HINTS.any { app.contains(it) }) return null
            if (app.any { it in "。！？；;…" }) return null
            return Plan("open_phone_app", JSONObject().put("appName", app))
        }

        // 纯算式（放最后：字符集宽，绝不能抢在语义句之前）；行尾等号/问号先剥再匹配
        val exprSrc = t.trimEnd('=', '＝', '?', '？', '。')
        if (MATH_EXPR.matches(exprSrc) && exprSrc.any { it.isDigit() } &&
            exprSrc.any { it in "+-*/%^×÷" } && !DATE_LIKE.containsMatchIn(exprSrc)
        ) {
            return Plan("calculate", JSONObject().put("expression", exprSrc.replace('×', '*').replace('÷', '/')))
        }

        return null
    }

    /** 「半|数字」+ 单位 → 秒（非法/非正返回 -1，由调用方判弃） */
    private fun durationSeconds(num: String, unit: String): Long {
        val n = if (num == "半") 0.5 else num.toDoubleOrNull() ?: return -1L
        if (n <= 0) return -1L
        return when (unit) {
            "小时" -> (n * 3600).toLong()
            "分钟" -> (n * 60).toLong()
            "秒" -> n.toLong()
            else -> -1L
        }
    }

    // ── 执行（门禁 + 统一分发） ─────────────────────────────────

    /**
     * 执行直办计划，返回**面向用户的回复文案**（= 工具结果原文，不另造话术）。
     *
     * @return null = 不直办（plan 不命中，或被装配门禁挡下）—— 调用方必须落回完整
     *   Agent 循环，**不得**把 null 当空回复发出去。
     *   异常如实变成失败文案，不假装成功（「说做了没动」比报错更糟）。
     */
    fun execute(context: Context, text: String): String? {
        val p = plan(text) ?: return null
        if (!ToolRegistry.isEnabled(context, p.tool)) return null
        if (ChatStateHolder.currentGroupToolScope()?.excludes(p.tool) == true) return null
        return runCatching {
            ToolRegistry.execute(context, p.tool, p.args.toString())
        }.getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}，请稍后再试" }
    }
}
