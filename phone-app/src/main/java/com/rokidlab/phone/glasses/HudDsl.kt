package com.rokidlab.phone.glasses

import org.json.JSONArray
import org.json.JSONObject

/**
 * HUD 场景 DSL（v2：宏实例参数 / 属性表达式 / fall 位移动画 / text 纵排）解析与护栏
 * —— 规范见 docs/hud-dsl.md（v2 节）。
 *
 * ⚠️ 与 RokidLink `HudOverlayController` 内的解析逻辑**同源双副本**（两模块无共享依赖，
 * docs/hud-dsl.md 是单一事实源）：改字段/护栏/区域表必须两端同步，否则出现
 * 「手机预览能过、眼镜解析拒绝」的两侧行为分叉。
 *
 * 解析在工具线程同步执行；所有错误抛 [IllegalArgumentException] 且 message 面向模型
 * （工具结果直接把它回给模型，让模型自我修正重试）。
 */
internal object HudDsl {

    // 2026-10-24 硬件摸底（scripts/hud_stress.py，RG-glasses 60Hz 实测）后调档：
    const val MAX_ELEMENTS = 128
    const val MAX_ANIMS = 48
    const val MAX_MACRO_DEPTH = 2
    const val MAX_JSON_CHARS = 16_000
    const val MAX_BIND_ELEMENTS = 8
    const val MAX_TEXT_CHARS = 20
    /** text 多行上限（2026-10-25 P1-1：content 支持 \n 显式换行；每行仍 ≤MAX_TEXT_CHARS） */
    const val MAX_TEXT_LINES = 4

    // ── v2 宏实例参数（docs/hud-dsl-framework.md §1：解析期静态替换，零运行时开销）──
    /** 单宏定义 p.* 引用处上限 */
    const val MAX_MACRO_PARAM_REFS = 12
    /** 单个 use 的 p 对象键数上限 */
    const val MAX_USE_PARAMS = 6
    /** 宏参数引用前缀："p.名字"（use 未提供且无缺省 → 拒绝）或 "p.名字|缺省数字" */
    const val PARAM_PREFIX = "p."
    /** 宏参数名规则 */
    private val PARAM_NAME = Regex("[a-z][a-zA-Z0-9]{0,11}")
    /** 允许参数化的属性（数值字段 + dash 数组 + anim 对象/数组）；其余字段出现 p.* 解析期拒绝 */
    private val P_SUBST_KEYS = setOf(
        "x", "y", "x2", "y2", "r", "rx", "ry", "w", "h", "opacity", "size", "start", "sweep",
        "stroke", "sides", "width", "radius", "step", "major", "interval", "dx", "dy", "rot",
        "orbitR", "amp", "speed", "period", "phase", "dash", "anim",
    )

    const val SCREEN_W = 480
    const val SCREEN_H = 640

    /** 区域矩形 [left, top, width, height]（官方表盘页 480x640 实测安全区，规范文档同表） */
    val ZONES: Map<String, FloatArray> = mapOf(
        "top-center" to floatArrayOf(128f, 8f, 224f, 133f),
        "A" to floatArrayOf(8f, 10f, 116f, 180f),
        "B" to floatArrayOf(356f, 10f, 118f, 180f),
        "C" to floatArrayOf(6f, 552f, 468f, 80f),
        "full" to floatArrayOf(0f, 0f, 480f, 640f),
    )

    private val KNOWN_TYPES = setOf(
        "circle", "arc", "line", "rect", "dot", "polygon", "path",
        "use", "brackets", "ruler", "ping", "text", "image",
    )
    private val KNOWN_ANIMS = setOf("spin", "orbit", "pulse", "fade", "scale", "glow", "dashFlow", "fall")
    /** scale 动画适用元素（尺寸呼吸需要 r/w/h 语义） */
    private val SCALE_TYPES = setOf("circle", "rect", "dot", "polygon")
    /** dashFlow 不适用元素（非描边：dot 恒填充、text/ping 有独立分支、image 是位图） */
    private val NO_DASHFLOW_TYPES = setOf("dot", "text", "ping", "image")
    /** box 元素：x,y = 框中心（可加 anchor:"topleft" opt-out 回左上角；见坐标空间说明） */
    private val BOX_TYPES = setOf("image", "rect", "brackets", "ruler")

    /** 单场景 image 元素上限（hud_image 登记的主视觉图） */
    const val MAX_IMAGES = 2
    private val ALLOWED_PATH_CMDS = "MLHVQCZ"

    // ── bind 数据键白名单（docs/hud-dsl.md「数据绑定」同表；键类型决定 as 映射合法性）──
    /** percent 键：0–100 数值（as=sweep/count/text） */
    val BIND_PERCENT_KEYS = setOf("battery_glass", "battery_phone")
    /** enum 键：字符串状态（as=enum/text）；on 值 = link:connected / ai_state:busy / music:playing */
    val BIND_ENUM_KEYS = setOf("link", "ai_state", "music")
    /**
     * number 键：秒数/计数/原始量（as=text；比例映射须显式 max）。
     * timer/calendar_next = 秒数（锚点本地走秒），unread = 条数（不走秒）；
     * gps_speed = km/h、gps_bearing = 度（0–359）、steps = 今日步数（2026-10-25 P1-2）。
     */
    val BIND_NUMBER_KEYS = setOf("timer", "calendar_next", "unread", "gps_speed", "gps_bearing", "steps")
    /** text 键：自由短文本（仅 as=text；值由 hud_display 工具写入，≤20 字符） */
    val BIND_TEXT_KEYS = setOf("tool_result")
    private val KNOWN_BIND_AS = setOf("sweep", "count", "enum", "text", "bar", "progress")
    /** bindOn 阈值比较符（三段式 "数值键:op:值"） */
    private val BIND_ON_OPS = setOf("<", "<=", ">", ">=", "==", "!=")
    /** 可做阈值比较的键 = percent + number（enum 键只支持两段式相等比较） */
    private val BIND_NUMERIC_KEYS = BIND_PERCENT_KEYS + BIND_NUMBER_KEYS

    /**
     * 单个动画描述（组合动画 anims 列表的元素；spin 的 reverse、orbit 的 orbitR 逐动画存）。
     * 相位语义：0–360，t' = t + phase/360*period。
     */
    class Anim(
        val type: String,
        val period: Float,
        val phase: Float = 0f,
        val reverse: Boolean = false,
        val orbitR: Float = 100f,
        val amp: Float = 0.3f,
        val speed: Float = 30f,
    )

    /** 一个可绘制元素（解析/宏展开后的扁平结构；坐标已换算为屏幕绝对值） */
    class El {
        var type = ""
        var x = 0f
        var y = 0f
        var x2 = 0f
        var y2 = 0f
        /** 圆/多边形半径（rx/ry 缺省时用） */
        var r = 0f
        /** 椭圆 x/y 半径（只有显式给 rx/ry 才有别于 r；绘制时与 r 取并集） */
        var rx = 0f
        var ry = 0f
        var w = 0f
        var h = 0f
        /** rect 圆角半径 px */
        var radius = 0f
        var start = 0f
        var sweep = 0f
        var sides = 0
        var pathData: String? = null
        /** path 的绝对平移量（zone 左上角 + use 偏移），绘制时整体 translate */
        var pathDx = 0f
        var pathDy = 0f
        var stroke = 3
        var fillInk = -1
        var width = 1f
        var opacity = 1f
        var dashOn = 0f
        var dashOff = 0f
        var step = 48f
        var major = 3
        var interval = 3f
        /** 组合动画（≤2 个）；空 = 无动画 */
        var anims: List<Anim> = emptyList()
        // text 专属
        var text: String? = null
        var size = 24f
        var align = "left"
        // bind 数据绑定
        var bindKey: String? = null
        var bindAs: String? = null
        /** as=count 的 dot 行容量（x → 区域右缘按 step 均分，钳 1..16）；非 count = 0 */
        var maxDots = 0
        /** as=bar 的 rect 比例填充墨色档（0–3，默认 3；仅 as=bar 的 rect 生效） */
        var barFill = 3
        /**
         * 比例映射满量程（as=sweep/count/bar/progress 的 100% 基准，2026-10-25 P1-2）：
         * percent 键恒 100；number 键量程各异（km/h、度、步数）须显式 bind.max；非比例映射 = 0。
         */
        var bindMax = 0f
        // bindOn 条件显隐（解析期拆成 key/op/值三元，渲染端零解析；与 RokidLink 同源同步）：
        //  - 两段 "enum键:值"：op=null 相等比较（link/ai_state）
        //  - 三段 "数值键:op:值"：op ∈ </<=/>/>=/==/!=（percent/number 键）
        var bindOn: String? = null
        var bindOnKey: String? = null
        var bindOnOp: String? = null
        var bindOnVal: String? = null
        var bindOnCmp: Float = 0f
        /** image 元素：登记的资源 id（hud_image 工具下发） */
        var imageId: String? = null
        // image 专属蒙版/混合（2026-10-25 P1-3；仅 image 支持，解析期白名单校验）
        /** mask="circle"：圆形蒙版（直径=min(w,h)，随 scale 呼吸；占位框仍是矩形） */
        var mask: String? = null
        /** blend="screen"：位图与已绘墨线 Screen 叠印（黑区透出下层线稿，亮区加亮） */
        var blend: String? = null
        /** use 组级变换（宏内元素继承；rot=绕 use 定位点旋转度，mirror=水平镜像） */
        var useRot = 0f
        var useMirror = false
        /** 组变换旋转/镜像锚点 = use 定位点（use 自身 zone 原点 + x/y + dx/dy） */
        var pivX = 0f
        var pivY = 0f
        // ── v2 ──
        /** 元素所在 zone 宽/高（expr 求值 env 的 w/h；fall 循环位移周期） */
        var zoneW = 0f
        var zoneH = 0f
        /** 元素 base = 区域中心 + use 累计偏移（中心原点坐标系的定位基准；expr 覆盖 x/y 时加回它） */
        var zoneCx = 0f
        var zoneCy = 0f
        /** text dir:"down"：逐字符纵向排列 */
        var dirDown = false
        /** 属性表达式 AST（attr → 编译结果；空 = 无表达式。运行时每帧求值覆盖静态值） */
        var exprs: Map<String, HudExpr.Node> = emptyMap()
    }

    class Scene(
        val scanline: Float,
        val grid: Float,
        val gridOpacity: Float,
        val elements: List<El>,
    ) {
        /** 是否存在逐帧动画（决定眼镜端要不要跑 ticker；expr 元素也随 t 变化） */
        val hasAnim: Boolean
            get() = elements.any { it.anims.isNotEmpty() || it.type == "ping" || it.exprs.isNotEmpty() }

        /** 存在走秒绑定（无逐帧动画也需按秒重绘：timer/calendar_next 按接收锚点本地走秒） */
        val hasTickingBind: Boolean
            get() = elements.any { it.bindKey == "timer" || it.bindKey == "calendar_next" }
    }

    /**
     * bind 取值解释（纯函数，渲染端与单测共用）。
     * ⚠️ 与 RokidLink `HudOverlayController` 内的解释逻辑同源双副本，改必须两端同步。
     */
    object HudBind {
        /** percent/number 键数值化；enum 键、缺数据、非数值 → null */
        fun numeric(key: String, value: String?): Double? {
            if (value.isNullOrEmpty()) return null
            return when {
                key in BIND_PERCENT_KEYS || key in BIND_NUMBER_KEYS -> value.toDoubleOrNull()
                else -> null
            }
        }

        /** enum 键的 on/off（null = 非 enum 键或缺数据）；on = link:connected / ai_state:busy / music:playing */
        fun isOn(key: String, value: String?): Boolean? {
            if (value.isNullOrEmpty() || key !in BIND_ENUM_KEYS) return null
            return when (key) {
                "link" -> value == "connected"
                "ai_state" -> value == "busy"
                "music" -> value == "playing"
                else -> null
            }
        }

        /**
         * bindOn 条件求值（渲染端与单测共用；与 RokidLink bindOnMatch 同源）。
         * op=null = enum 相等（value == valStr；无数据不匹配）；否则数值阈值比较
         * （无数据/非数值 → false = 隐藏）。
         */
        fun matchOn(key: String, op: String?, cmp: Float, valStr: String?, value: String?): Boolean {
            if (op == null) return value == valStr
            val n = numeric(key, value) ?: return false
            return when (op) {
                "<" -> n < cmp
                "<=" -> n <= cmp
                ">" -> n > cmp
                ">=" -> n >= cmp
                "==" -> n == cmp.toDouble()
                else -> n != cmp.toDouble()
            }
        }

        /** 解析后的条件（bindOn / 主题联动规则共用；[match] 委托 [matchOn]） */
        class Cond(val key: String, val op: String?, val cmp: Float, val valStr: String?) {
            fun match(value: String?): Boolean = matchOn(key, op, cmp, valStr, value)
        }

        /**
         * 条件解析（bindOn 与 HudThemeScheduler 情景规则的单一语义源；错误消息面向模型/用户自修正）：
         *  - "enum键:值"：相等比较（link/ai_state）
         *  - "数值键:比较符值"（如 battery_phone:<=20）：阈值比较（percent/number 键）
         * 数值键误写相等（如 timer:300）→ 定向提示阈值写法。
         */
        fun parseCond(cond: String): Cond {
            val bo = cond.trim()
            val k = bo.substringBefore(':').trim()
            // ⚠️ 必须显式传 missingDelimiterValue=""：无冒号时单参版返回整串，"link" 会被当成 link:link 放过
            val rest = bo.substringAfter(':', "").trim()
            // rest 以比较符开头 → 阈值比较（<= 优先于 <：取最长匹配）
            val op = BIND_ON_OPS.filter { rest.startsWith(it) }.maxByOrNull { it.length }
            if (op != null) {
                if (k !in BIND_NUMERIC_KEYS) {
                    throw IllegalArgumentException(
                        "阈值比较 \"key:比较符值\" 仅支持数值键（percent：battery_glass/battery_phone；" +
                            "number：timer/calendar_next/unread/gps_speed/gps_bearing/steps），" +
                            "enum 键 link/ai_state/music 用 \"key:值\" 相等比较",
                    )
                }
                val cv = rest.substring(op.length).trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("阈值必须是数字：$bo（如 timer:<300）")
                return Cond(k, op, cv, null)
            }
            if (k in BIND_NUMERIC_KEYS) {
                throw IllegalArgumentException(
                    "数值键 $k 要做阈值比较请写 \"key:比较符值\"，如 $k:<300（相等比较仅 enum 键 link/ai_state/music）",
                )
            }
            if (k !in BIND_ENUM_KEYS || rest.isEmpty()) {
                throw IllegalArgumentException(
                    "条件格式 \"key:值\"，key 仅 enum 键（link/ai_state/music），如 link:connected",
                )
            }
            return Cond(k, null, 0f, rest)
        }

        /** as=text 的显示值；无数据/不可解析一律 "--"（timer/calendar_next 按接收锚点本地走秒） */
        fun displayText(key: String, value: String?, anchorMs: Long, nowMs: Long): String {
            if (value.isNullOrEmpty()) return "--"
            return when {
                key in BIND_PERCENT_KEYS -> {
                    val pct = value.toDoubleOrNull() ?: return "--"
                    "${pct.toInt().coerceIn(0, 100)}%"
                }
                key == "timer" -> {
                    val left = value.toDoubleOrNull() ?: return "--"
                    val sec = (left - (nowMs - anchorMs) / 1000.0).coerceAtLeast(0.0)
                    String.format(java.util.Locale.US, "%02d:%02d", (sec / 60).toInt(), (sec % 60).toInt())
                }
                // 距下一日程：<1h → "45m"，≥1h → "1h05"（本地走秒，向上取整到分钟）
                key == "calendar_next" -> {
                    val left = value.toDoubleOrNull() ?: return "--"
                    val sec = (left - (nowMs - anchorMs) / 1000.0).coerceAtLeast(0.0)
                    val totalMin = kotlin.math.ceil(sec / 60.0).toInt()
                    val h = totalMin / 60
                    if (h > 0) {
                        String.format(java.util.Locale.US, "%dh%02d", h, totalMin % 60)
                    } else {
                        "${totalMin}m"
                    }
                }
                key == "unread" -> {
                    val n = value.toIntOrNull() ?: return "--"
                    n.coerceAtLeast(0).toString()
                }
                // gps_speed：km/h 取整（HUD 氛围显示，精确测速看 get_location 工具结果）
                key == "gps_speed" -> {
                    val v = value.toDoubleOrNull() ?: return "--"
                    v.toInt().coerceAtLeast(0).toString()
                }
                // gps_bearing：八方位缩写（22.5° 一档，罗盘约定；ASCII 短词适配 ≤20 字符上限）
                key == "gps_bearing" -> {
                    val v = value.toDoubleOrNull() ?: return "--"
                    val deg = (((v.toInt() % 360) + 360) % 360)
                    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
                    dirs[(deg + 22.5).toInt() / 45 % 8]
                }
                key == "steps" -> {
                    val n = value.toIntOrNull() ?: return "--"
                    n.coerceAtLeast(0).toString()
                }
                key in BIND_TEXT_KEYS -> value
                key == "link" -> if (value == "connected") "ON" else "OFF"
                key == "ai_state" -> if (value == "busy") "BUSY" else "IDLE"
                key == "music" -> if (value == "playing") "PLAY" else "PAUSE"
                else -> "--"
            }
        }
    }

    /**
     * 解析入口；不合法抛 [IllegalArgumentException]（message 可直接回给模型）。
     *
     * [registeredImages]：hud_image 已登记的资源 id 表（glass_hud 实时下发点传入）——
     * 非空时校验 image 元素的 id 必须已登记；空集跳过校验（主题库等离线场景不含 image）。
     */
    fun parse(json: String, registeredImages: Set<String> = emptySet()): Scene {
        if (json.length > MAX_JSON_CHARS) {
            throw IllegalArgumentException("场景 DSL 过大（>${MAX_JSON_CHARS} 字符），请精简元素")
        }
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("scene 不是合法 JSON: ${e.message}")
        }
        if (root.optInt("v", 1) != 1) throw IllegalArgumentException("仅支持 DSL v1（顶层 \"v\":1）")
        val macros = HashMap<String, JSONArray>()
        root.optJSONObject("macros")?.let { m ->
            for (key in m.keys()) {
                macros[key] = m.optJSONArray(key)
                    ?: throw IllegalArgumentException("宏 $key 必须是元素数组")
            }
        }
        // 宏定义静态扫描：p.* 参数名校验 + 每宏引用处数上限（解析期护栏，docs/hud-dsl-framework.md §1）
        for ((mname, marr) in macros) scanMacroParams(marr, mname)
        val sceneArr = root.optJSONArray("scene")
            ?: throw IllegalArgumentException("缺 scene 数组（顶层 \"scene\":[...]）")
        val bg = root.optJSONObject("bg")
        val scanline = clampF(bg?.optDouble("scanline", 0.0) ?: 0.0, 0.0, 0.2)
        val grid = clampF(bg?.optDouble("grid", 0.0) ?: 0.0, 0.0, 200.0)
        val gridOpacity = clampF(bg?.optDouble("gridOpacity", 0.1) ?: 0.1, 0.02, 0.3)
        val anims = intArrayOf(0)
        val binds = intArrayOf(0)
        val out = ArrayList<El>()
        for (i in 0 until sceneArr.length()) {
            val o = sceneArr.optJSONObject(i)
                ?: throw IllegalArgumentException("scene[$i] 不是对象")
            // 顶层元素也过一遍参数替换（空作用域）：顶层出现 p.* 引用会被拒绝（参数仅宏内生效）
            expand(substParams(o, emptyMap()), 0, macros, out, anims, binds, 0f, 0f)
        }
        if (out.isEmpty()) throw IllegalArgumentException("scene 为空：至少要有一个元素")
        if (out.size > MAX_ELEMENTS) {
            throw IllegalArgumentException("元素过多（展开后 ${out.size} > $MAX_ELEMENTS）")
        }
        if (anims[0] > MAX_ANIMS) {
            throw IllegalArgumentException("动画实例过多（${anims[0]} > $MAX_ANIMS，每元素最多 2 个组合动画）")
        }
        // 含表达式的元素 ≤48（逐帧 CPU 预算，docs/hud-dsl-framework.md §2）
        val exprCount = out.count { it.exprs.isNotEmpty() }
        if (exprCount > HudExpr.MAX_EXPR_ELEMENTS) {
            throw IllegalArgumentException("含表达式的元素过多（$exprCount > ${HudExpr.MAX_EXPR_ELEMENTS}）")
        }
        val imgCount = out.count { it.type == "image" }
        if (imgCount > MAX_IMAGES) {
            throw IllegalArgumentException("image 元素过多（$imgCount > $MAX_IMAGES）")
        }
        if (registeredImages.isNotEmpty()) {
            val missing = out.mapNotNull { it.imageId }.filter { it !in registeredImages }.toSet()
            if (missing.isNotEmpty()) {
                throw IllegalArgumentException(
                    "image.id 未登记：$missing（先用 hud_image 登记图片，再在场景里引用返回的 id）",
                )
            }
        }
        return Scene(scanline, grid, gridOpacity, out)
    }

    private fun expand(
        o: JSONObject,
        depth: Int,
        macros: Map<String, JSONArray>,
        out: MutableList<El>,
        anims: IntArray,
        binds: IntArray,
        dx: Float,
        dy: Float,
        inMacro: Boolean = false,
        gRot: Float = 0f,
        gMirror: Boolean = false,
        gPivX: Float = 0f,
        gPivY: Float = 0f,
        inGroup: Boolean = false,
        pScope: Map<String, Double> = emptyMap(),
    ) {
        val type = o.optString("type")
        if (type.isEmpty()) throw IllegalArgumentException("元素缺 type 字段")
        if (type == "use") {
            val name = o.optString("use")
            val arr = macros[name]
                ?: throw IllegalArgumentException("引用了未定义的宏 $name（须在顶层 macros 里定义）")
            if (depth >= MAX_MACRO_DEPTH) {
                throw IllegalArgumentException("宏嵌套过深（>$MAX_MACRO_DEPTH）：$name")
            }
            // ⚠️ 2026-10-20 修：use 的定位要读 x/y（语义同普通元素的「相对区域中心的偏移」）。
            //   此前只读 dx/dy，"x":70 这类定位被静默丢弃 ⇒ 宏部件全部堆到区域中心。
            val ndx = dx + optF(o, "dx", 0f) + optF(o, "x", 0f)
            val ndy = dy + optF(o, "dy", 0f) + optF(o, "y", 0f)
            // 组级变换：rot/mirror 绕 use 定位点（use 所在 zone 中心 + x/y + dx/dy），
            // 传播给宏内每个元素；不支持嵌套变换（外层已带 rot/mirror 再带 → 拒绝）。
            val rot = optF(o, "rot", 0f).coerceIn(-360f, 360f)
            val mirror = o.optBoolean("mirror", false)
            val hasTransform = rot != 0f || mirror
            if (hasTransform && inGroup) {
                throw IllegalArgumentException("use 的 rot/mirror 不支持嵌套（外层 use 已带变换）")
            }
            val zoneName = o.optString("zone", "full").ifBlank { "full" }
            val useZone = ZONES[zoneName]
                ?: throw IllegalArgumentException("未知区域 $zoneName（可选：${ZONES.keys.joinToString("/")}）")
            val pivX = useZone[0] + useZone[2] / 2f + ndx
            val pivY = useZone[1] + useZone[3] / 2f + ndy
            // 宏参数作用域：外层继承、本层 p 同名覆盖（内层优先）；p 值必须为数字
            // （docs/hud-dsl-framework.md §1：替换统一发生在最外层展开时）
            val merged = HashMap(pScope)
            o.optJSONObject("p")?.let { pj ->
                val keys = pj.keys().asSequence().toList()
                if (keys.size > MAX_USE_PARAMS) {
                    throw IllegalArgumentException("use 参数过多（${keys.size} > $MAX_USE_PARAMS）")
                }
                for (k in keys) {
                    if (!k.matches(PARAM_NAME)) {
                        throw IllegalArgumentException("宏参数名非法：$k（[a-z] 开头，字母数字 ≤12 字符）")
                    }
                    val dv = pj.optDouble(k, Double.NaN)
                    if (dv.isNaN()) throw IllegalArgumentException("宏参数 p.$k 的值必须是数字")
                    merged[k] = dv
                }
            }
            for (i in 0 until arr.length()) {
                val inner = arr.optJSONObject(i)
                    ?: throw IllegalArgumentException("宏 $name 第 $i 项不是对象")
                expand(
                    substParams(inner, merged), depth + 1, macros, out, anims, binds, ndx, ndy, inMacro = true,
                    gRot = if (hasTransform) rot else gRot,
                    gMirror = if (hasTransform) mirror else gMirror,
                    gPivX = if (hasTransform) pivX else gPivX,
                    gPivY = if (hasTransform) pivY else gPivY,
                    inGroup = inGroup || hasTransform,
                    pScope = merged,
                )
            }
            return
        }
        if (type !in KNOWN_TYPES) throw IllegalArgumentException("未知元素类型 $type")
        out.add(parseEl(o, type, macros, anims, binds, dx, dy, inMacro, gRot, gMirror, gPivX, gPivY))
    }

    private fun parseEl(
        o: JSONObject,
        type: String,
        macros: Map<String, JSONArray>,
        anims: IntArray,
        binds: IntArray,
        dx: Float,
        dy: Float,
        inMacro: Boolean,
        gRot: Float,
        gMirror: Boolean,
        gPivX: Float,
        gPivY: Float,
    ): El {
        val el = El()
        el.type = type
        // use 组级变换（无组变换时全 0/false：绘制端据此跳过 canvas 变换）
        el.useRot = gRot
        el.useMirror = gMirror
        el.pivX = gPivX
        el.pivY = gPivY
        // ── v2 属性表达式：值字符串以 "expr:" 开头 → 运行时每帧求值 ──
        // 解析期只做适用性/语法/护栏校验并存 AST；数值字段照常读静态值（运行时被求值结果覆盖）。
        val exprs = HashMap<String, HudExpr.Node>()
        for (key in o.keys()) {
            val v = o.opt(key)
            if (v is String && v.startsWith(HudExpr.PREFIX)) {
                if (key !in HudExpr.EXPR_ATTRS) {
                    throw IllegalArgumentException(
                        "expr: 不适用于 $key（仅 ${HudExpr.EXPR_ATTRS.joinToString("/")}；" +
                            "content/d/type 等结构字段不支持表达式）",
                    )
                }
                exprs[key] = HudExpr.compile(v.substring(HudExpr.PREFIX.length))
            }
        }
        if (exprs.size > HudExpr.MAX_EXPR_PER_EL) {
            throw IllegalArgumentException("单元素 expr 属性过多（${exprs.size} > ${HudExpr.MAX_EXPR_PER_EL}）")
        }
        el.exprs = exprs
        val zoneName = o.optString("zone", "full").ifBlank { "full" }
        val zone = ZONES[zoneName] ?: throw IllegalArgumentException("未知区域 $zoneName（可选：${ZONES.keys.joinToString("/")}）")
        el.zoneW = zone[2]
        el.zoneH = zone[3]
        // ── 坐标空间：中心原点（2026-10-21 翻转；勿改回「左上角绝对值」）──
        // x/y = 元素定位点相对「区域中心」的偏移：0,0 = 区域中心，+x 向右，+y 向下；
        // 缺省 x/y = 0（即区域中心）。旧语义是「相对区域左上角的绝对值」，实测两轮
        // 模型都按中心原点思维写坐标（把 x:0,y:0 当画布中心、四角装饰写 ±152/±236
        // 负数偏移）⇒ 照片被扔到左上角、负数偏移被 clampCoord 压糊在角上
        // （设备截图 + 会话日志实锤，2026-10-21）。翻成中心原点后，模型的自然写法
        // 即正确写法。宏内部件缺省 0 = use 定位点——2026-10-20「眼睛宏圆点堆到
        // 区域中心」的修复在此语义下自动保持（base 即定位点）。
        // zoneCx/zoneCy = base（区域中心 + use 累计偏移 dx/dy）；expr 运行时覆盖
        // x/y 时把结果当偏移加回 base（见 HudRenderer.applyExpr，双端同步）。
        val hasX = o.has("x")
        val hasY = o.has("y")
        el.zoneCx = zone[0] + zone[2] / 2f + dx
        el.zoneCy = zone[1] + zone[3] / 2f + dy
        el.x = clampCoord(el.zoneCx + (if (hasX) optF(o, "x", 0f) else 0f))
        el.y = clampCoord(el.zoneCy + (if (hasY) optF(o, "y", 0f) else 0f))
        el.x2 = clampCoord(el.zoneCx + optF(o, "x2", 0f))
        el.y2 = clampCoord(el.zoneCy + optF(o, "y2", 0f))
        el.r = clampCoord(optF(o, "r", 0f))
        el.rx = clampCoord(optF(o, "rx", 0f))
        el.ry = clampCoord(optF(o, "ry", 0f))
        el.w = clampCoord(optF(o, "w", 0f))
        el.h = clampCoord(optF(o, "h", 0f))
        el.radius = optF(o, "radius", 0f).coerceAtLeast(0f).coerceAtMost(minOf(el.w, el.h) / 2f)
        el.start = optF(o, "start", 0f)
        el.sweep = optF(o, "sweep", 0f)
        el.sides = o.optInt("sides", 0)
        el.stroke = o.optInt("stroke", 3).coerceIn(0, 3)
        el.fillInk = if (o.has("fillInk")) o.optInt("fillInk", -1).coerceIn(0, 3) else -1
        el.width = optF(o, "width", 1f).coerceIn(0.3f, 6f)
        el.opacity = optF(o, "opacity", 1f).coerceIn(0.05f, 1f)
        val dash = o.optJSONArray("dash")
        if (dash != null && dash.length() >= 2) {
            el.dashOn = optF(dash.optDouble(0, 0.0), 0f)
            el.dashOff = optF(dash.optDouble(1, 0.0), 0f)
        }
        el.step = optF(o, "step", 48f).coerceIn(8f, 200f)
        el.major = o.optInt("major", 3).coerceIn(2, 10)
        el.interval = optF(o, "interval", 3f).coerceIn(0.8f, 30f)
        when (type) {
            // expr 驱动的 sweep/w/r 视为已提供（静态值是缺省 0，运行时被求值覆盖）
            "arc" -> if (el.sweep == 0f && !o.has("bind") && !exprs.containsKey("sweep")) {
                throw IllegalArgumentException("arc 需要 sweep（度）")
            }
            "polygon" -> if (el.sides < 3) throw IllegalArgumentException("polygon 需要 sides>=3")
            "path" -> {
                val d = o.optString("d").trim()
                if (d.isEmpty()) throw IllegalArgumentException("path 需要 d 字段")
                validatePath(d)
                el.pathData = d
                // d 为以元素 x,y 为原点的局部坐标（2026-10-21 随中心原点翻转：
                // 旧语义 pathDx=zone 左上角、d 被当画布绝对值，模型按「局部原点」
                // 写 d 全部跑偏——笑脸/爱心全糊在画布角上）
                el.pathDx = el.x
                el.pathDy = el.y
            }
            "ruler" -> if (el.w <= 0f && !exprs.containsKey("w")) throw IllegalArgumentException("ruler 需要 w（总宽 px）")
            "ping" -> if (el.r <= 0f && !exprs.containsKey("r")) throw IllegalArgumentException("ping 需要 r（最大扩散半径）")
            "text" -> {
                // \r\n / \r 归一为 \n（模型可能写转义符或真换行）；trim 只去首尾
                val c = o.optString("content").replace("\r\n", "\n").replace("\r", "\n").trim()
                if (c.isEmpty() && !o.has("bind")) {
                    throw IllegalArgumentException("text 需要 content 或 bind（必有其一）")
                }
                el.text = c.ifEmpty { null }
                el.size = optF(o, "size", 24f).coerceIn(8f, 48f)
                // y 缺省 = 区域中心 + size/3（x,y 语义 = 文本基线锚点）
                if (!hasY) el.y = clampCoord(el.zoneCy + el.size / 3f)
                val al = o.optString("align", "left")
                if (al != "left" && al != "center") {
                    throw IllegalArgumentException("align 只支持 left/center")
                }
                el.align = al
                // v2 dir:"down" 纵排：逐字符下排（步进 size*1.2），x,y = 首字符锚点，align 控横向
                val dir = o.optString("dir", "right")
                if (dir != "right" && dir != "down") {
                    throw IllegalArgumentException("dir 只支持 right/down（down = 逐字符纵排）")
                }
                el.dirDown = dir == "down"
                // P1-1 多行：content 支持 \n 显式换行（≤4 行，每行 ≤20 字符；中文可用——
                // 绿屏美学从「ASCII 短词」扩为「短句」，渲染走系统 Paint，字形以眼镜端字体为准）。
                // 只做显式换行：自动 wrap 依赖 measureText，跨设备有 1 字级差异，双端不一致。
                if (c.isNotEmpty()) {
                    val lines = c.split('\n')
                    if (lines.size > MAX_TEXT_LINES) {
                        throw IllegalArgumentException(
                            "text.content 行数过多（${lines.size} > $MAX_TEXT_LINES；用 \\n 换行，最多 $MAX_TEXT_LINES 行）",
                        )
                    }
                    if (el.dirDown && lines.size > 1) {
                        throw IllegalArgumentException("dir:down 纵排不支持 \\n 多行（纵排本身逐字符下排）")
                    }
                    lines.forEachIndexed { i, ln ->
                        if (ln.length > MAX_TEXT_CHARS) {
                            throw IllegalArgumentException(
                                "text.content 第 ${i + 1} 行过长（${ln.length} > $MAX_TEXT_CHARS，绿屏一行约 20 字）",
                            )
                        }
                    }
                }
            }
            "image" -> {
                // hud_image 登记的图片资源：w/h = 显示目标框（必填，contain 等比适配居中）
                val id = o.optString("id").trim()
                if (id.isEmpty()) {
                    throw IllegalArgumentException("image 需要 id（先调 hud_image 登记图片，用返回的 id）")
                }
                el.imageId = id
                el.w = optF(o, "w", 0f)
                el.h = optF(o, "h", 0f)
                if ((el.w <= 0f && !exprs.containsKey("w")) || (el.h <= 0f && !exprs.containsKey("h"))) {
                    throw IllegalArgumentException("image 需要 w/h（显示尺寸 px）")
                }
                el.w = el.w.coerceIn(8f, SCREEN_W.toFloat())
                el.h = el.h.coerceIn(8f, SCREEN_H.toFloat())
                // P1-3 蒙版/混合（仅 image；白名单外值解析期拒绝，与 RokidLink 同步）
                o.optString("mask").trim().takeIf { it.isNotEmpty() }?.let { m ->
                    if (m != "circle") throw IllegalArgumentException("mask 仅支持 circle（给了 $m）")
                    el.mask = m
                }
                o.optString("blend").trim().takeIf { it.isNotEmpty() }?.let { b ->
                    if (b != "screen") throw IllegalArgumentException("blend 仅支持 screen（给了 $b）")
                    el.blend = b
                }
            }
            "brackets" -> {
                // 四角角标框：x,y = 框中心，w/h = 框大小（缺省 = 所在 zone 大小），
                // r（或速查表字段名 len）= 臂长 px，inset = 框整体内缩 px。
                // 2026-10-21 修：旧实现拿 el.x 当「距屏幕上边距」、el.r 当臂长，
                // 文档字段 len/inset 根本没解析 ⇒ 模型按速查表写 len:16 实画臂长 0，
                // 角标凭空消失（截图实锤：四角什么都没有）。
                if (o.has("len")) el.r = clampCoord(optF(o, "len", el.r))
                val bw = if (el.w > 0f) el.w else zone[2].toFloat()
                val bh = if (el.h > 0f) el.h else zone[3].toFloat()
                val ins = optF(o, "inset", 0f).coerceAtLeast(0f)
                el.w = (bw - 2f * ins).coerceAtLeast(2f * el.r + 2f)
                el.h = (bh - 2f * ins).coerceAtLeast(2f * el.r + 2f)
            }
            "line" -> Unit
            else -> Unit
        }
        // anchor：box 元素（image/rect/brackets/ruler）定位语义。默认 center：x,y = 框中心
        // （2026-10-21 起为唯一默认，见上方坐标空间说明；历史「左上角」行为加
        // anchor:"topleft" opt-out）。与眼镜端 HudOverlayController.parseEl 同源同步。
        val anchor = o.optString("anchor", "center")
        if (anchor != "topleft" && anchor != "center") {
            throw IllegalArgumentException("anchor 只支持 topleft/center（默认 center）")
        }
        if (type !in BOX_TYPES) {
            if (o.has("anchor")) {
                throw IllegalArgumentException("anchor 仅 image/rect/brackets/ruler 支持（$type 的 x,y 语义固定，见速查表）")
            }
        } else if (anchor == "topleft") {
            val badExpr = listOf("x", "y", "w", "h").filter { it in exprs }
            if (badExpr.isNotEmpty()) {
                throw IllegalArgumentException(
                    "anchor:topleft 不能配 ${badExpr.joinToString("/")} 表达式（每帧求值会覆盖锚点归一化）",
                )
            }
            el.x = clampCoord(el.x - el.w / 2f)
            el.y = clampCoord(el.y - el.h / 2f)
        }
        if (type != "text" && (o.has("content") || o.has("size") || o.has("align") || o.has("dir"))) {
            throw IllegalArgumentException("content/size/align/dir 仅 text 元素支持（$type 没有）")
        }
        if (type != "circle" && (o.has("rx") || o.has("ry"))) {
            throw IllegalArgumentException("rx/ry 仅 circle 元素支持（椭圆；$type 没有）")
        }
        if (type != "rect" && o.has("radius")) {
            throw IllegalArgumentException("radius 仅 rect 元素支持（圆角；$type 没有）")
        }
        if (type != "image" && o.has("id")) {
            throw IllegalArgumentException("id 仅 image 元素支持（hud_image 登记的资源引用；$type 没有）")
        }
        if (type != "image" && (o.has("mask") || o.has("blend"))) {
            throw IllegalArgumentException("mask/blend 仅 image 元素支持（蒙版与混合只对位图有意义；$type 没有）")
        }
        if (type == "image" && (o.has("stroke") || o.has("fillInk") || o.has("width") || o.has("dash"))) {
            throw IllegalArgumentException("image 不支持 stroke/fillInk/width/dash（墨线体系仅矢量元素）")
        }
        // bind 数据绑定：白名单 + as 映射 + 单场景上限（docs/hud-dsl.md「数据绑定」）
        o.optJSONObject("bind")?.let { bind ->
            val key = bind.optString("key").trim()
            if (key.isEmpty()) throw IllegalArgumentException("bind 缺 key 字段")
            val keyType = when {
                key in BIND_PERCENT_KEYS -> "percent"
                key in BIND_ENUM_KEYS -> "enum"
                key in BIND_NUMBER_KEYS -> "number"
                key in BIND_TEXT_KEYS -> "text"
                else -> throw IllegalArgumentException(
                    "bind.key 不在白名单：$key（可选：battery_glass/battery_phone/link/ai_state/music/timer/" +
                        "calendar_next/unread/gps_speed/gps_bearing/steps/tool_result）",
                )
            }
            val asName = bind.optString("as").trim().ifEmpty {
                when (type) {
                    "arc" -> "sweep"
                    "dot" -> "count"
                    "text" -> "text"
                    else -> "enum"
                }
            }
            if (asName !in KNOWN_BIND_AS) {
                throw IllegalArgumentException("bind.as 不支持：$asName（可选 sweep/count/enum/text/bar/progress）")
            }
            val asTypeOk = when (asName) {
                "sweep" -> type == "arc"
                "count" -> type == "dot"
                "text" -> type == "text"
                "bar" -> type == "rect"
                "progress" -> type == "line"
                else -> true
            }
            if (!asTypeOk) {
                throw IllegalArgumentException(
                    "bind.as=$asName 不适用于 type=$type（sweep→arc / count→dot / text→text / " +
                        "bar→rect / progress→line / enum→任意图形）",
                )
            }
            val asKeyOk = when (asName) {
                // percent 恒 100 满量程；number 键量程各异须显式 max（2026-10-25 P1-2）
                "sweep", "count", "bar", "progress" -> keyType == "percent" || keyType == "number"
                "enum" -> keyType == "enum"
                else -> true // text：任意键
            }
            if (!asKeyOk) {
                throw IllegalArgumentException(
                    "bind.as=$asName 不能绑定 $keyType 类键 $key（sweep/count/bar/progress←percent 或 number+max、" +
                        "enum←enum、text←任意）",
                )
            }
            el.bindKey = key
            el.bindAs = asName
            binds[0]++
            if (binds[0] > MAX_BIND_ELEMENTS) {
                throw IllegalArgumentException("bind 元素过多（>$MAX_BIND_ELEMENTS）")
            }
            // max 满量程（2026-10-25 P1-2：gps_speed/gps_bearing/steps 等 number 键量程各异）：
            //  - percent 键恒 100：显式 max 拒绝（冗余，且误导模型以为可调）
            //  - number 键比例映射：必须显式 max（钳 2..999999）
            //  - as=text/enum 不按比例：拒绝 max
            val proportional = asName == "sweep" || asName == "count" || asName == "bar" || asName == "progress"
            if (bind.has("max")) {
                if (!proportional) {
                    throw IllegalArgumentException("bind.max 仅比例映射 as=sweep/count/bar/progress 支持（as=$asName 用原值）")
                }
                if (keyType == "percent") {
                    throw IllegalArgumentException("percent 键 $key 满量程恒 100，不需要 bind.max")
                }
                // optString 而非 optDouble：org.json 两实现对数字的 optDouble 强转语义有差异，
                // 统一走 toString+toFloatOrNull（数字/数字串都吃，对象/数组/非法串 → 定向报错）
                val m = bind.optString("max").trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("bind.max 必须是数字（$key 的满量程，如 gps_speed 给 120）")
                if (m < 2f || m > 999999f) {
                    throw IllegalArgumentException("bind.max 须在 2..999999（$key 的满量程，给了 $m）")
                }
                el.bindMax = m
            } else if (proportional) {
                if (keyType == "percent") {
                    el.bindMax = 100f
                } else {
                    throw IllegalArgumentException(
                        "number 键 $key 做比例映射必须显式 bind.max（满量程，如 gps_speed 给 max:120；" +
                            "只要原值显示请用 as=text）",
                    )
                }
            }
            if (asName == "count") {
                // dot 行容量：从 x 到区域右缘按 step 均分（钳 1..16）
                val right = zone[0] + dx + zone[2]
                el.maxDots = ((right - el.x) / el.step).toInt().coerceIn(1, 16)
            }
        }
        // as=bar 填充墨色（rect 比例条的填充色；轨道用 fillInk，填充用 barFill，默认亮绿 3）
        if (o.has("barFill")) {
            if (type != "rect" || el.bindAs != "bar") {
                throw IllegalArgumentException("barFill 仅 as=bar 的 rect 支持（比例填充条；$type/as=${el.bindAs} 没有）")
            }
            el.barFill = o.optInt("barFill", 3).coerceIn(0, 3)
        }
        // bindOn 条件显隐（与 bind as=enum 置暗互补，docs/hud-dsl.md）：
        //  - "enum键:值"：相等比较（link/ai_state/music）
        //  - "数值键:比较符值"（如 timer:<300）：阈值比较（percent/number 键，2026-10-25 P0-1）
        // 解析走 HudBind.parseCond（与主题联动规则同一语义源）；拆成 key/op/值，渲染端零解析。
        o.optString("bindOn").trim().takeIf { it.isNotEmpty() }?.let { bo ->
            val c = HudBind.parseCond(bo)
            el.bindOn = bo
            el.bindOnKey = c.key
            el.bindOnOp = c.op
            el.bindOnCmp = c.cmp
            el.bindOnVal = c.valStr
        }
        // 组合动画：单对象或数组（≤2，按实例计数进护栏）
        el.anims = parseAnims(o, type, el.dashOn, el.dashOff)
        anims[0] += el.anims.size
        if (type == "ping") anims[0]++ // ping 本身是逐帧动画
        return el
    }

    /**
     * anim 解析：单对象或数组（≤2 组合动画，同元素类型不可重复）。
     * amp/phase/speed 等数值越界一律钳制（对模型宽容，省一轮重试）；结构性错误（未知类型、
     * 组合超限、适用元素不符、缺失前提）直接拒绝。
     */
    private fun parseAnims(o: JSONObject, type: String, dashOn: Float, dashOff: Float): List<Anim> {
        val raw = o.opt("anim") ?: return emptyList()
        val items: List<JSONObject> = when (raw) {
            is JSONObject -> listOf(raw)
            is JSONArray -> (0 until raw.length()).map {
                raw.optJSONObject(it) ?: throw IllegalArgumentException("anim[$it] 不是对象")
            }
            else -> throw IllegalArgumentException("anim 必须是对象或数组")
        }
        if (items.size > 2) throw IllegalArgumentException("组合动画最多 2 个（${items.size} 个）")
        val out = ArrayList<Anim>(items.size)
        for ((i, a) in items.withIndex()) {
            val at = a.optString("type")
            if (at !in KNOWN_ANIMS) {
                // 可选清单必须与 KNOWN_ANIMS 逐字对齐（2026-10-25 修：旧文案漏了 fall，
                // 模型拼错时会以为 fall 不合法而主动回避它——错误消息是模型的说明书）
                throw IllegalArgumentException(
                    "anim[$i] 未知动画 $at（可选：spin/orbit/pulse/fade/scale/glow/dashFlow/fall）",
                )
            }
            if (out.any { it.type == at }) throw IllegalArgumentException("同元素动画类型重复：$at")
            if (at == "scale" && type !in SCALE_TYPES) {
                throw IllegalArgumentException("scale 仅适用于 circle/rect/dot/polygon（$type 没有）")
            }
            if (at == "orbit" && type == "image") {
                throw IllegalArgumentException("orbit 不适用于 image（orbit 会以圆点替代元素本体）")
            }
            if (at == "glow" && type == "ping") {
                throw IllegalArgumentException("ping 自带扩散脉冲，不支持 glow")
            }
            if (at == "dashFlow") {
                if (dashOn <= 0f || dashOff <= 0f) {
                    throw IllegalArgumentException("dashFlow 需要元素带 dash:[on,off]")
                }
                if (type in NO_DASHFLOW_TYPES) {
                    throw IllegalArgumentException("dashFlow 不适用于 $type（需描边图形）")
                }
            }
            out.add(
                Anim(
                    type = at,
                    period = optF(a, "period", 10f).coerceIn(1f, 300f),
                    phase = optF(a, "phase", 0f).coerceIn(0f, 360f),
                    reverse = a.optBoolean("reverse", false),
                    orbitR = clampCoord(optF(a, "orbitR", 100f)),
                    amp = optF(a, "amp", if (at == "glow") 0.5f else 0.3f).coerceIn(0f, 0.9f),
                    // fall：y 向匀速循环位移（(t*speed)%zoneH，出底回顶），speed 钳 [10,300]
                    speed = if (at == "fall") optF(a, "speed", 60f).coerceIn(10f, 300f)
                    else optF(a, "speed", 30f).coerceIn(1f, 300f),
                ),
            )
        }
        return out
    }

    // ── v2 宏实例参数（解析期静态替换；与 RokidLink HudSceneParser 同步）──

    /**
     * 宏参数静态替换：把元素 JSON 数值属性里 "p.名字"/"p.名字|缺省" 替换为实际数字。
     * 返回副本 —— 宏定义被多个 use 共享，绝不能原地改；替换后与普通元素无异。
     * expr 字符串内做 "p.名字" 子串替换（"expr:(t*p.v0)%h" → "expr:(t*60)%h"）；
     * 递归覆盖 anim（对象/数组）与 dash 数组；其余字段出现 p.* 一律拒绝（结构字段不可参数化）。
     */
    private fun substParams(o: JSONObject, scope: Map<String, Double>): JSONObject {
        val out = JSONObject()
        for (key in o.keys()) {
            val v = o.opt(key)
            when {
                v is String && v.startsWith(PARAM_PREFIX) -> {
                    if (key !in P_SUBST_KEYS) {
                        throw IllegalArgumentException(
                            "宏参数不支持属性 $key（仅数值字段可参数化；zone/type/content/d 等结构字段不可）",
                        )
                    }
                    out.put(key, resolveParam(v, scope))
                }
                v is String && v.startsWith(HudExpr.PREFIX) -> {
                    if (key !in P_SUBST_KEYS) {
                        throw IllegalArgumentException(
                            "宏参数不支持属性 $key（expr 仅支持数值属性）",
                        )
                    }
                    out.put(key, substExprParams(v, scope))
                }
                key == "anim" && v is JSONObject -> out.put(key, substParams(v, scope))
                key == "anim" && v is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i) ?: throw IllegalArgumentException("anim[$i] 不是对象")
                        arr.put(substParams(item, scope))
                    }
                    out.put(key, arr)
                }
                key == "dash" && v is JSONArray -> {
                    val arr = JSONArray()
                    for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        if (item is String && item.startsWith(PARAM_PREFIX)) arr.put(resolveParam(item, scope))
                        else arr.put(item)
                    }
                    out.put(key, arr)
                }
                else -> out.put(key, v)
            }
        }
        return out
    }

    /** 解析 "p.名字" / "p.名字|缺省数字" → 数值；未提供且无缺省 → 报错并列出名字（回模型自修正） */
    private fun resolveParam(spec: String, scope: Map<String, Double>): Double {
        val body = spec.substring(PARAM_PREFIX.length)
        val name = body.substringBefore('|')
        if (!name.matches(PARAM_NAME)) {
            throw IllegalArgumentException("宏参数名非法：$name（[a-z] 开头，字母数字 ≤12 字符）")
        }
        scope[name]?.let { return it }
        if (body.contains('|')) {
            return body.substringAfter('|').toDoubleOrNull()
                ?: throw IllegalArgumentException("宏参数缺省值必须是数字：$spec")
        }
        throw IllegalArgumentException("宏参数 p.$name 未提供：use 的 p 里给出该参数，或写成 \"$spec|<缺省数字>\"")
    }

    // expr 体内 p.名字 引用：宽松匹配用于校验非法名，严格匹配用于替换（含 |缺省）
    private val P_LOOSE = Regex("p\\.[a-zA-Z0-9_]+")
    private val P_REF_IN_EXPR = Regex("p\\.([a-z][a-zA-Z0-9]{0,11})(\\|-?\\d+(?:\\.\\d+)?)?")

    /** expr 字符串内的宏参数子串替换："expr:(t*p.v0)%h" → "expr:(t*60)%h"；非法名/未提供 → 报错 */
    private fun substExprParams(src: String, scope: Map<String, Double>): String {
        for (m in P_LOOSE.findAll(src)) {
            val name = m.value.substring(2).substringBefore('|')
            if (!name.matches(PARAM_NAME)) {
                throw IllegalArgumentException("宏参数名非法：$name（[a-z] 开头，字母数字 ≤12 字符）")
            }
        }
        return P_REF_IN_EXPR.replace(src) { m ->
            val name = m.groupValues[1]
            val v = scope[name]
            when {
                v != null -> fmtParamNum(v)
                m.groupValues[2].isNotEmpty() -> m.value.substringAfter('|')
                else -> throw IllegalArgumentException(
                    "宏参数 p.$name 未提供：use 的 p 里给出该参数，或写成 \"p.$name|<缺省数字>\"",
                )
            }
        }
    }

    /** Double → 紧凑数字字面量（整数不带小数点；避免科学计数法进表达式） */
    private fun fmtParamNum(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** 宏定义静态扫描：校验 p.* 参数名格式 + 每宏引用处数 ≤ 上限（含 anim/dash 内层） */
    private fun scanMacroParams(arr: JSONArray, macroName: String) {
        var refs = 0
        fun walk(o: JSONObject) {
            for (key in o.keys()) {
                val v = o.opt(key)
                if (v is String && v.startsWith(PARAM_PREFIX)) {
                    val pname = v.substring(PARAM_PREFIX.length).substringBefore('|')
                    if (!pname.matches(PARAM_NAME)) {
                        throw IllegalArgumentException(
                            "宏 $macroName 参数名非法：$pname（[a-z] 开头，字母数字 ≤12 字符）",
                        )
                    }
                    refs++
                    if (refs > MAX_MACRO_PARAM_REFS) {
                        throw IllegalArgumentException(
                            "宏 $macroName 参数引用过多（>$MAX_MACRO_PARAM_REFS），请拆分宏或减少变体",
                        )
                    }
                } else if (v is String && v.startsWith(HudExpr.PREFIX)) {
                    // expr 体内 p.名字 引用同样计数与校验（docs/hud-dsl-framework.md §1）
                    for (mm in P_LOOSE.findAll(v)) {
                        val pname = mm.value.substring(2).substringBefore('|')
                        if (!pname.matches(PARAM_NAME)) {
                            throw IllegalArgumentException(
                                "宏 $macroName 参数名非法：$pname（[a-z] 开头，字母数字 ≤12 字符）",
                            )
                        }
                        refs++
                        if (refs > MAX_MACRO_PARAM_REFS) {
                            throw IllegalArgumentException(
                                "宏 $macroName 参数引用过多（>$MAX_MACRO_PARAM_REFS），请拆分宏或减少变体",
                            )
                        }
                    }
                } else if (key == "anim" && v is JSONObject) {
                    walk(v)
                } else if (key == "anim" && v is JSONArray) {
                    for (i in 0 until v.length()) walk(v.optJSONObject(i) ?: throw IllegalArgumentException("anim[$i] 不是对象"))
                } else if (key == "dash" && v is JSONArray) {
                    for (i in 0 until v.length()) {
                        val item = v.opt(i)
                        if (item is String && item.startsWith(PARAM_PREFIX)) {
                            refs++
                            if (refs > MAX_MACRO_PARAM_REFS) {
                                throw IllegalArgumentException("宏 $macroName 参数引用过多（>$MAX_MACRO_PARAM_REFS）")
                            }
                        }
                    }
                }
            }
        }
        for (i in 0 until arr.length()) {
            walk(arr.optJSONObject(i) ?: throw IllegalArgumentException("宏 $macroName 第 $i 项不是对象"))
        }
    }

    private fun validatePath(d: String) {
        for (ch in d) {
            if (ch.isWhitespace() || ch == ',' || ch == '.' || ch == '-' || ch.isDigit()) continue
            if (ch.uppercaseChar() in ALLOWED_PATH_CMDS && !ch.isLowerCase()) continue
            throw IllegalArgumentException(
                "path.d 含不支持指令 '$ch'（v1 仅支持绝对坐标 M/L/H/V/C/Q/Z）",
            )
        }
    }

    private fun optF(o: JSONObject, key: String, def: Float): Float =
        optF(o.optDouble(key, def.toDouble()), def)

    private fun optF(v: Double?, def: Float): Float =
        (v ?: def.toDouble()).toFloat()

    private fun clampF(v: Double, min: Double, max: Double): Float =
        v.coerceIn(min, max).toFloat()

    private fun clampCoord(v: Float): Float = v.coerceIn(-50f, 690f)
}

// ══EXPR-SYNC-BEGIN══
/**
 * HUD 属性表达式求值器（v2，docs/hud-dsl.md「属性表达式」）。
 *
 * ⚠️ 双端同源段：本对象在 phone-app HudDsl.kt 与 RokidLink HudOverlayController.kt
 * 各持一份逐字节副本，checkProtocolSynced 校验 EXPR-SYNC 标记区间一致 —— 修改必须两端同步。
 *
 * 纯函数、零系统调用、零 import 依赖：跨设备逐位一致优先用 StrictMath（fdlibm 固定算法，
 * JDK 规范保证全平台同结果）；禁止 Math.sin/pow 等 intrinsic 快路径（跨设备 libm 差异会
 * 破坏「双端逐位一致」铁律；floor/abs 是精确定义的 IEEE 运算，Math 与 StrictMath 同结果，允许）。
 * 求值永不抛异常：除零/模零/失败一律取 0（不炸场景）。
 */
internal object HudExpr {

    /** expr: 前缀（属性值字符串以此开头 = 运行时每帧求值） */
    const val PREFIX = "expr:"
    // 2026-10-24 硬件摸底后调档（原 CHARS=48/PER_EL=3/ELEMENTS=16）：48 个 expr 元素
    // 实测 36% CPU、p95 36ms、fps 不塌；单条 48 字符 / 每元素 3 条本就未触顶，维持原值。
    /** 单条表达式长度上限（字符） */
    const val MAX_EXPR_CHARS = 48
    /** 单元素 expr 属性上限 */
    const val MAX_EXPR_PER_EL = 3
    /** 全场景含 expr 元素上限（逐帧 CPU 预算；16→48，实测依据见上） */
    const val MAX_EXPR_ELEMENTS = 48
    /** 数字字面量位数上限 */
    const val MAX_LITERAL_DIGITS = 6
    /** 括号/调用嵌套深度上限 */
    const val MAX_DEPTH = 4
    /** 适用属性白名单（几何 + 透明度 + 弧角；结构字段解析期拒绝） */
    val EXPR_ATTRS = setOf(
        "x", "y", "x2", "y2", "r", "rx", "ry", "w", "h", "opacity", "size", "start", "sweep",
    )

    // 变量与函数索引（AST 内用下标引用；env 数组顺序与此对齐）
    private const val V_T = 0
    private const val V_I = 1
    private const val V_W = 2
    private const val V_H = 3
    private const val V_SW = 4
    private const val V_SH = 5
    private val VARS = mapOf("t" to V_T, "i" to V_I, "w" to V_W, "h" to V_H, "sw" to V_SW, "sh" to V_SH)
    // 函数：0=min 1=max 2=floor 3=abs 4=r01 5=sin 6=cos 7=pow
    private val FUNCS = mapOf("min" to 0, "max" to 1, "floor" to 2, "abs" to 3, "r01" to 4, "sin" to 5, "cos" to 6, "pow" to 7)
    private val FUNC_ARITY = intArrayOf(2, 2, 1, 1, 1, 1, 1, 2)

    // AST 节点（轻量）：kind 决定字段语义；二元/函数用 a/b 两子节点
    internal class Node(val kind: Int, val num: Float = 0f, val id: Int = 0, val a: Node? = null, val b: Node? = null)
    private const val K_NUM = 0
    private const val K_VAR = 1
    private const val K_BIN = 2
    private const val K_NEG = 3
    private const val K_FN = 4

    /**
     * 编译表达式（解析期调用）：语法错误/超护栏抛 IllegalArgumentException（message 回模型自修正）。
     * [src] 为去掉 "expr:" 前缀的表达式体。
     */
    fun compile(src: String): Node {
        if (src.length > MAX_EXPR_CHARS) {
            throw IllegalArgumentException("表达式过长（${src.length} > $MAX_EXPR_CHARS）：$src")
        }
        val p = intArrayOf(0)
        val n = parseExpr(src, p, 0)
        if (p[0] < src.length) {
            throw IllegalArgumentException("表达式有无法识别的内容 '${src[p[0]]}'：$src")
        }
        return n
    }

    /**
     * 求值（每帧调用，纯函数，永不抛）：失败/除零/模零 → 0。
     * env = [t 播放秒, i 元素序号, w zone宽, h zone高, sw 屏宽, sh 屏高]。
     */
    fun eval(n: Node, env: FloatArray): Float = when (n.kind) {
        K_NUM -> n.num
        K_VAR -> env[n.id]
        K_NEG -> -eval(n.a!!, env)
        K_BIN -> {
            val l = eval(n.a!!, env)
            val r = eval(n.b!!, env)
            when (n.id) {
                0 -> l + r
                1 -> l - r
                2 -> l * r
                3 -> if (r == 0f) 0f else l / r
                else -> if (r == 0f) 0f else l % r
            }
        }
        else -> { // K_FN
            val l = eval(n.a!!, env)
            when (n.id) {
                0 -> { val r = eval(n.b!!, env); if (l < r) l else r }
                1 -> { val r = eval(n.b!!, env); if (l > r) l else r }
                2 -> Math.floor(l.toDouble()).toFloat()
                3 -> Math.abs(l)
                5 -> StrictMath.sin(l.toDouble()).toFloat()
                6 -> StrictMath.cos(l.toDouble()).toFloat()
                7 -> {
                    val r = eval(n.b!!, env)
                    val p = StrictMath.pow(l.toDouble(), r.toDouble())
                    if (p.isNaN() || p.isInfinite()) 0f else p.toFloat()
                }
                else -> r01(l, env[V_T])
            }
        }
    }

    /**
     * r01(n)：确定性伪随机 [0,1) = splitmix32(n*2654435761u + floor(t*10))。
     * 纯整数散列（UInt 溢出回绕、shr 逻辑右移均跨设备一致）；时间粒度 100ms（10Hz 变化）。
     */
    private fun r01(n: Float, t: Float): Float {
        val k = Math.floor(n.toDouble()).toLong()
        var z = k.toUInt() * 2654435761u + Math.floor(t * 10.0).toLong().toUInt()
        z += 0x9E3779B9u
        z = (z xor (z shr 16)) * 0x21F0AAADu
        z = (z xor (z shr 15)) * 0x735A2D97u
        z = z xor (z shr 15)
        return (z and 0xFFFFFFu).toFloat() / 16777216f
    }

    // ── 递归下降解析（expr := term (('+'|'-') term)* ；term := unary (('*'|'/'|'%') unary)*）──

    private fun parseExpr(s: String, p: IntArray, depth: Int): Node {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("表达式嵌套过深（>$MAX_DEPTH）：$s")
        var n = parseTerm(s, p, depth)
        while (p[0] < s.length) {
            val c = s[p[0]]
            if (c != '+' && c != '-') break
            p[0]++
            n = Node(K_BIN, id = if (c == '+') 0 else 1, a = n, b = parseTerm(s, p, depth))
        }
        return n
    }

    private fun parseTerm(s: String, p: IntArray, depth: Int): Node {
        var n = parseUnary(s, p, depth)
        while (p[0] < s.length) {
            val c = s[p[0]]
            if (c != '*' && c != '/' && c != '%') break
            p[0]++
            // id 对齐 eval 的 K_BIN 分派：0=+ 1=- 2=* 3=/ 4=%
            n = Node(K_BIN, id = if (c == '*') 2 else if (c == '/') 3 else 4, a = n, b = parseUnary(s, p, depth))
        }
        return n
    }

    private fun parseUnary(s: String, p: IntArray, depth: Int): Node {
        skipWs(s, p)
        if (p[0] < s.length && s[p[0]] == '-') {
            p[0]++
            return Node(K_NEG, a = parseUnary(s, p, depth))
        }
        return parsePrimary(s, p, depth)
    }

    private fun parsePrimary(s: String, p: IntArray, depth: Int): Node {
        skipWs(s, p)
        if (p[0] >= s.length) throw IllegalArgumentException("表达式意外结束：$s")
        val c = s[p[0]]
        if (c == '(') {
            p[0]++
            val n = parseExpr(s, p, depth + 1)
            skipWs(s, p)
            if (p[0] >= s.length || s[p[0]] != ')') throw IllegalArgumentException("表达式缺右括号：$s")
            p[0]++
            return n
        }
        if (c.isDigit()) return parseNumber(s, p)
        if (c.isLetter()) {
            // 标识符含字母+数字（函数名如 r01）；数字开头走 parseNumber，"2abc" 会在残留处报错
            val start = p[0]
            while (p[0] < s.length && (s[p[0]].isLetter() || s[p[0]].isDigit())) p[0]++
            val name = s.substring(start, p[0])
            VARS[name]?.let { return Node(K_VAR, id = it) }
            val fid = FUNCS[name]
                ?: throw IllegalArgumentException(
                    "表达式含未知标识符 '$name'（变量：t/i/w/h/sw/sh；函数：min/max/floor/abs/sin/cos/pow/r01）：$s",
                )
            skipWs(s, p)
            if (p[0] >= s.length || s[p[0]] != '(') throw IllegalArgumentException("函数 $name 后缺 '('：$s")
            p[0]++
            val arg1 = parseExpr(s, p, depth + 1)
            var arg2: Node? = null
            skipWs(s, p)
            if (p[0] < s.length && s[p[0]] == ',') {
                p[0]++
                arg2 = parseExpr(s, p, depth + 1)
            }
            skipWs(s, p)
            if (p[0] >= s.length || s[p[0]] != ')') throw IllegalArgumentException("函数 $name 缺右括号：$s")
            p[0]++
            if (FUNC_ARITY[fid] == 2 && arg2 == null) throw IllegalArgumentException("函数 $name 需要两个参数：$s")
            if (FUNC_ARITY[fid] == 1 && arg2 != null) throw IllegalArgumentException("函数 $name 只接受一个参数：$s")
            return Node(K_FN, id = fid, a = arg1, b = arg2)
        }
        throw IllegalArgumentException("表达式含非法字符 '$c'：$s")
    }

    private fun parseNumber(s: String, p: IntArray): Node {
        val start = p[0]
        var digits = 0
        while (p[0] < s.length && (s[p[0]].isDigit() || s[p[0]] == '.')) {
            if (s[p[0]].isDigit()) digits++
            p[0]++
        }
        val v = s.substring(start, p[0]).toFloatOrNull()
            ?: throw IllegalArgumentException("表达式数字字面量非法：$s")
        if (digits > MAX_LITERAL_DIGITS) {
            throw IllegalArgumentException("表达式数字字面量位数超限（$digits > $MAX_LITERAL_DIGITS）：$s")
        }
        return Node(K_NUM, num = v)
    }

    private fun skipWs(s: String, p: IntArray) {
        while (p[0] < s.length && s[p[0]] == ' ') p[0]++
    }
}
// ══EXPR-SYNC-END══
