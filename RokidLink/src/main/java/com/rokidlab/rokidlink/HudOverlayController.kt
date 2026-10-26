package com.rokidlab.rokidlink

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.rokid.cxr.Caps
import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成式 HUD 悬浮层控制器（v2：宏实例参数 / 属性表达式 / fall 位移动画 / text 纵排，2026-10-21）。
 *
 * 手机端对话里经 `glass_hud` 工具生成场景 DSL（规范 docs/hud-dsl.md），经
 * [AiChannel.TOPIC_HUD_CTRL] 下发，本控制器解析为元素列表并全屏 overlay 渲染：
 *  - `try`：60s 试戴自动收起；`apply`：常驻直到 hide；`hide`：收起。
 *  - 单槽位：新场景直接顶掉旧场景（与图片层独立，不与歌词/图片层互抢窗口）。
 *  - 全屏 `TYPE_APPLICATION_OVERLAY` 但 NOT_TOUCHABLE/NOT_FOCUSABLE：纯展示，
 *    不改变顶层 resumed 身份（KeyButtonService 类注释铁律）。
 *
 * ⚠️ 解析与绘制逻辑与 phone-app `HudDsl` / `HudRenderer` **同源双副本**（两模块无共享
 * 依赖，docs/hud-dsl.md 是单一事实源）：改字段/护栏/区域表/画法必须两端同步，否则
 * 「手机预览一回事、眼镜渲染另一回事」。
 *
 * 动画模型：最多 48 个动画元素，**单个 ticker**（ValueAnimator 线性空转）驱动
 * [HudView.tMs]，每个元素按 elapsed 自算相位 —— 不给每个元素建 Animator，帧内
 * invalidate 次数恒为 1。无动画场景只画一帧（静态，零持续开销）。
 *
 * 结构说明：绘制函数（[HudOverlayController.Companion.drawEl] 等）放在 companion ——
 * 嵌套类 [HudView]（非 inner）无法访问外部类实例成员，companion 成员对嵌套类可见；
 * phone-app 侧 HudRenderer 是 object 无此问题。
 */
internal class HudOverlayController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    // ── 元素模型（扁平，坐标已换算为屏幕绝对值）──

    /** 单个动画描述（与 phone-app HudDsl.Anim 同源同步；phase 0-360 周期内偏移） */
    private class Anim(
        val type: String,
        val period: Float,
        val phase: Float = 0f,
        val reverse: Boolean = false,
        val orbitR: Float = 100f,
        val amp: Float = 0.3f,
        val speed: Float = 30f,
    )

    private class El {
        var type = ""
        var x = 0f
        var y = 0f
        var x2 = 0f
        var y2 = 0f
        var r = 0f
        /** 椭圆 x/y 半径（circle 专属；缺省 0 = 用 r） */
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
        /** 组合动画（≤2 个，与手机端 HudDsl.El.anims 同源同步）；空 = 无动画 */
        var anims: List<Anim> = emptyList()
        // text 专用（与手机端 HudDsl.El 同源同步）
        var text: String? = null
        var size = 24f
        var align = "left"
        // bind 数据绑定（与手机端同步）
        var bindKey: String? = null
        var bindAs: String? = null
        /** as=count 的 dot 行容量（x → 区域右缘按 step 均分，钳 1..16） */
        var maxDots = 0
        /** as=bar 的 rect 比例填充墨色档（0–3，默认 3；仅 as=bar 生效；与手机端同步） */
        var barFill = 3
        /**
         * 比例映射满量程（as=sweep/count/bar/progress 的 100% 基准，2026-10-25 P1-2）：
         * percent 键恒 100；number 键量程各异（km/h、度、步数）须显式 bind.max；非比例映射 = 0。
         */
        var bindMax = 0f
        // bindOn 条件显隐（解析期拆成 key/op/值三元，渲染端零解析；与手机端 HudDsl.El 同步）：
        //  - 两段 "enum键:值"：op=null 相等比较（link/ai_state）
        //  - 三段 "数值键:op:值"：op ∈ </<=/>/>=/==/!=（percent/number 键）
        var bindOn: String? = null
        var bindOnKey: String? = null
        var bindOnOp: String? = null
        var bindOnVal: String? = null
        var bindOnCmp: Float = 0f
        /** image 元素：登记的资源 id（hud_image 工具下发，缓存见 HudOverlayController.imageOf） */
        var imageId: String? = null
        // image 专属蒙版/混合（2026-10-25 P1-3；仅 image 支持，解析期白名单校验；与手机端同步）
        /** mask="circle"：圆形蒙版（直径=min(w,h)，随 scale 呼吸；占位框仍是矩形） */
        var mask: String? = null
        /** blend="screen"：位图与已绘墨线 Screen 叠印（黑区透出下层线稿，亮区加亮） */
        var blend: String? = null
        // use 组级变换（宏内元素继承，与手机端同步）
        var useRot = 0f
        var useMirror = false
        /** 组变换锚点 = use 定位点（use 自身 zone 原点 + x/y + dx/dy） */
        var pivX = 0f
        var pivY = 0f
        // ── v2（与手机端 HudDsl.El 同源同步）──
        /** 元素所在 zone 宽/高（expr 求值 env 的 w/h；fall 循环位移周期） */
        var zoneW = 0f
        var zoneH = 0f
        /** 元素 base = 区域中心 + use 累计偏移（中心原点坐标系基准；expr 覆盖 x/y 时加回它） */
        var zoneCx = 0f
        var zoneCy = 0f
        /** text dir:"down"：逐字符纵向排列 */
        var dirDown = false
        /** 属性表达式 AST（attr → 编译结果；空 = 无表达式。运行时每帧求值覆盖静态值） */
        var exprs: Map<String, HudExpr.Node> = emptyMap()
    }

    private class Scene(
        val scanline: Float,
        val grid: Float,
        val gridOpacity: Float,
        val elements: List<El>,
    ) {
        /** 是否存在逐帧动画（决定要不要跑 ticker；expr 元素也随 t 变化） */
        val hasAnim: Boolean
            get() = elements.any { it.anims.isNotEmpty() || it.type == "ping" || it.exprs.isNotEmpty() }

        /** 存在走秒绑定（无逐帧动画也需 ticker 按秒重绘：timer/calendar_next 本地走秒） */
        val hasTickingBind: Boolean
            get() = elements.any { it.bindKey == "timer" || it.bindKey == "calendar_next" }
    }

    // ── 解析（与 phone-app HudDsl 同源；解析期护栏全在这里）──

    private object HudSceneParser {
        private val ZONES: Map<String, FloatArray> = mapOf(
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
        // scale 适用元素 / dashFlow 不适用元素（与手机端 HudDsl 同步）
        private val SCALE_TYPES = setOf("circle", "rect", "dot", "polygon")
        private val NO_DASHFLOW_TYPES = setOf("dot", "text", "ping", "image")
        private val ALLOWED_PATH_CMDS = "MLHVQCZ"
        // ── v2 宏实例参数（docs/hud-dsl-framework.md §1；与手机端 HudDsl 同步）──
        /** 单宏定义 p.* 引用处上限 */
        private const val MAX_MACRO_PARAM_REFS = 12
        /** 单个 use 的 p 对象键数上限 */
        private const val MAX_USE_PARAMS = 6
        /** 宏参数引用前缀："p.名字"（use 未提供且无缺省 → 拒绝）或 "p.名字|缺省数字" */
        private const val PARAM_PREFIX = "p."
        /** 宏参数名规则 */
        private val PARAM_NAME = Regex("[a-z][a-zA-Z0-9]{0,11}")
        /** 允许参数化的属性（数值字段 + dash 数组 + anim 对象/数组）；其余字段出现 p.* 解析期拒绝 */
        private val P_SUBST_KEYS = setOf(
            "x", "y", "x2", "y2", "r", "rx", "ry", "w", "h", "opacity", "size", "start", "sweep",
            "stroke", "sides", "width", "radius", "step", "major", "interval", "dx", "dy", "rot",
            "orbitR", "amp", "speed", "period", "phase", "dash", "anim",
        )
        // bind 白名单（docs/hud-dsl.md「数据绑定」同表；与手机端 HudDsl 同步。
        // 非 private：companion 渲染端的 bind 取值解释要读）
        val BIND_PERCENT_KEYS = setOf("battery_glass", "battery_phone")
        // enum 键：on 值 = link:connected / ai_state:busy / music:playing
        val BIND_ENUM_KEYS = setOf("link", "ai_state", "music")
        // number 键：timer/calendar_next = 秒数（锚点本地走秒），unread = 条数（不走秒）；
        // gps_speed = km/h、gps_bearing = 度（0–359）、steps = 今日步数（2026-10-25 P1-2）
        val BIND_NUMBER_KEYS = setOf("timer", "calendar_next", "unread", "gps_speed", "gps_bearing", "steps")
        // text 键：自由短文本（仅 as=text；值由手机端 hud_display 工具写入，≤20 字符）
        val BIND_TEXT_KEYS = setOf("tool_result")
        private val KNOWN_BIND_AS = setOf("sweep", "count", "enum", "text", "bar", "progress")
        /** bindOn 阈值比较符（三段式 "数值键:op:值"；与手机端 HudDsl 同步） */
        private val BIND_ON_OPS = setOf("<", "<=", ">", ">=", "==", "!=")
        /** 可做阈值比较的键 = percent + number（enum 键只支持两段式相等比较） */
        private val BIND_NUMERIC_KEYS = BIND_PERCENT_KEYS + BIND_NUMBER_KEYS

        fun parse(json: String): Scene {
            if (json.length > MAX_JSON_CHARS) {
                throw IllegalArgumentException("场景 DSL 过大（>$MAX_JSON_CHARS 字符）")
            }
            val root = JSONObject(json)
            if (root.optInt("v", 1) != 1) throw IllegalArgumentException("仅支持 DSL v1")
            val macros = HashMap<String, JSONArray>()
            root.optJSONObject("macros")?.let { m ->
                for (key in m.keys()) {
                    macros[key] = m.optJSONArray(key)
                        ?: throw IllegalArgumentException("宏 $key 必须是元素数组")
                }
            }
            // 宏定义静态扫描：p.* 参数名校验 + 每宏引用处数上限（解析期护栏；与手机端同步）
            for ((mname, marr) in macros) scanMacroParams(marr, mname)
            val sceneArr = root.optJSONArray("scene")
                ?: throw IllegalArgumentException("缺 scene 数组")
            val bg = root.optJSONObject("bg")
            val scanline = clampF(bg?.optDouble("scanline", 0.0) ?: 0.0, 0.0, 0.2)
            val grid = clampF(bg?.optDouble("grid", 0.0) ?: 0.0, 0.0, 200.0)
            val gridOpacity = clampF(bg?.optDouble("gridOpacity", 0.1) ?: 0.1, 0.02, 0.3)
            val anims = intArrayOf(0)
            val binds = intArrayOf(0)
            val out = ArrayList<El>()
            for (i in 0 until sceneArr.length()) {
                // 顶层元素也过参数替换（空作用域）：顶层 p.* 引用会被拒绝（参数仅宏内生效）
                expand(
                    substParams(
                        sceneArr.optJSONObject(i) ?: throw IllegalArgumentException("scene[$i] 不是对象"),
                        emptyMap(),
                    ),
                    0, macros, out, anims, binds, 0f, 0f,
                )
            }
            if (out.isEmpty()) throw IllegalArgumentException("scene 为空")
            if (out.size > MAX_ELEMENTS) throw IllegalArgumentException("元素过多 ${out.size}")
            if (anims[0] > MAX_ANIMS) throw IllegalArgumentException("动画过多 ${anims[0]}")
            // 含表达式的元素 ≤48（逐帧 CPU 预算；与手机端同步）
            val exprCount = out.count { it.exprs.isNotEmpty() }
            if (exprCount > HudExpr.MAX_EXPR_ELEMENTS) {
                throw IllegalArgumentException("含表达式的元素过多（$exprCount > ${HudExpr.MAX_EXPR_ELEMENTS}）")
            }
            val imgCount = out.count { it.type == "image" }
            if (imgCount > MAX_IMAGES) throw IllegalArgumentException("image 元素过多（>$MAX_IMAGES，先 hud_image 登记再引用）")
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
            if (type.isEmpty()) throw IllegalArgumentException("元素缺 type")
            if (type == "use") {
                val name = o.optString("use")
                val arr = macros[name] ?: throw IllegalArgumentException("未定义宏 $name")
                if (depth >= MAX_MACRO_DEPTH) throw IllegalArgumentException("宏嵌套过深")
                // ⚠️ 与手机端 HudDsl.expand 同步：use 定位读 x/y（语义同普通元素「相对区域中心的偏移」）+ dx/dy 增量
                val ndx = dx + optF(o, "dx", 0f) + optF(o, "x", 0f)
                val ndy = dy + optF(o, "dy", 0f) + optF(o, "y", 0f)
                // 组级变换：rot/mirror 绕 use 定位点（use 所在 zone 中心 + x/y + dx/dy），
                // 传播给宏内每个元素（与手机端同步；不支持嵌套变换——外层已带 rot/mirror 再带 → 拒绝）
                val rot = optF(o, "rot", 0f).coerceIn(-360f, 360f)
                val mirror = o.optBoolean("mirror", false)
                val hasTransform = rot != 0f || mirror
                if (hasTransform && inGroup) {
                    throw IllegalArgumentException("use 的 rot/mirror 不支持嵌套")
                }
                val zoneName = o.optString("zone", "full").ifBlank { "full" }
                val useZone = ZONES[zoneName] ?: throw IllegalArgumentException("未知区域 $zoneName")
                val pivX = useZone[0] + useZone[2] / 2f + ndx
                val pivY = useZone[1] + useZone[3] / 2f + ndy
                // 宏参数作用域：外层继承、本层 p 同名覆盖（内层优先）；p 值必须为数字
                // （docs/hud-dsl-framework.md §1：替换统一发生在最外层展开时；与手机端同步）
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
            out.add(parseEl(o, type, anims, binds, dx, dy, inMacro, gRot, gMirror, gPivX, gPivY))
        }

        private fun parseEl(
            o: JSONObject,
            type: String,
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
            // use 组级变换（无组变换时全 0/false：绘制端据此跳过 canvas 变换；与手机端同步）
            el.useRot = gRot
            el.useMirror = gMirror
            el.pivX = gPivX
            el.pivY = gPivY
            // ── v2 属性表达式：值字符串以 "expr:" 开头 → 运行时每帧求值（与手机端同步）──
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
            val zone = ZONES[zoneName] ?: throw IllegalArgumentException("未知区域 $zoneName")
            el.zoneW = zone[2]
            el.zoneH = zone[3]
            // ── 坐标空间：中心原点（2026-10-21 翻转；与手机端 HudDsl.parseEl 同步，勿改回左上角）──
            // x/y = 元素定位点相对「区域中心」的偏移：0,0 = 区域中心，+x 右，+y 下；缺省 = 0。
            // 实测两轮模型都按中心原点思维写坐标（x:0,y:0 当画布中心、四角写负数偏移）
            // ⇒ 旧「左上角绝对值」语义下照片扔角上、负数被 clamp 压糊（截图+日志实锤）。
            // 宏内部件缺省 0 = use 定位点（2026-10-20 修复在此语义下自动保持）。
            // zoneCx/zoneCy = base；expr 覆盖 x/y 时结果当偏移加回 base（见 applyExpr）。
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
                    throw IllegalArgumentException("arc 需要 sweep")
                }
                "polygon" -> if (el.sides < 3) throw IllegalArgumentException("polygon 需要 sides>=3")
                "path" -> {
                    val d = o.optString("d").trim()
                    if (d.isEmpty()) throw IllegalArgumentException("path 需要 d")
                    validatePath(d)
                    el.pathData = d
                    // d 为以元素 x,y 为原点的局部坐标（2026-10-21 随中心原点翻转，与手机端同步）
                    el.pathDx = el.x
                    el.pathDy = el.y
                }
                "ruler" -> if (el.w <= 0f && !exprs.containsKey("w")) throw IllegalArgumentException("ruler 需要 w")
                "ping" -> if (el.r <= 0f && !exprs.containsKey("r")) throw IllegalArgumentException("ping 需要 r")
                "text" -> {
                    // \r\n / \r 归一为 \n；trim 只去首尾（与手机端 HudDsl 同步）
                    val c = o.optString("content").replace("\r\n", "\n").replace("\r", "\n").trim()
                    if (c.isEmpty() && !o.has("bind")) {
                        throw IllegalArgumentException("text 需要 content 或 bind")
                    }
                    el.text = c.ifEmpty { null }
                    el.size = optF(o, "size", 24f).coerceIn(8f, 48f)
                    // y 缺省 = 区域中心 + size/3（x,y 语义 = 文本基线锚点；与手机端同步）
                    if (!hasY) el.y = clampCoord(el.zoneCy + el.size / 3f)
                    val al = o.optString("align", "left")
                    if (al != "left" && al != "center") throw IllegalArgumentException("align 只支持 left/center")
                    el.align = al
                    // v2 dir:"down" 纵排：逐字符下排（步进 size*1.2），x,y = 首字符锚点，align 控横向
                    val dir = o.optString("dir", "right")
                    if (dir != "right" && dir != "down") {
                        throw IllegalArgumentException("dir 只支持 right/down（down = 逐字符纵排）")
                    }
                    el.dirDown = dir == "down"
                    // P1-1 多行：content 支持 \n 显式换行（≤4 行，每行 ≤20 字符；中文可用）
                    if (c.isNotEmpty()) {
                        val lines = c.split('\n')
                        if (lines.size > MAX_TEXT_LINES) {
                            throw IllegalArgumentException("text.content 行数过多（${lines.size} > $MAX_TEXT_LINES）")
                        }
                        if (el.dirDown && lines.size > 1) {
                            throw IllegalArgumentException("dir:down 纵排不支持 \\n 多行")
                        }
                        lines.forEachIndexed { i, ln ->
                            if (ln.length > MAX_TEXT_CHARS) {
                                throw IllegalArgumentException("text.content 第 ${i + 1} 行过长（$MAX_TEXT_CHARS 上限）")
                            }
                        }
                    }
                }
                "image" -> {
                    // hud_image 登记的图片资源：w/h = 显示目标框（必填，contain 等比适配居中）
                    val id = o.optString("id").trim()
                    if (id.isEmpty()) throw IllegalArgumentException("image 需要 id（先调 hud_image 登记拿 id）")
                    el.imageId = id
                    el.w = optF(o, "w", 0f)
                    el.h = optF(o, "h", 0f)
                    if ((el.w <= 0f && !exprs.containsKey("w")) || (el.h <= 0f && !exprs.containsKey("h"))) {
                        throw IllegalArgumentException("image 需要 w/h（显示尺寸 px）")
                    }
                    el.w = el.w.coerceIn(8f, SCREEN_W.toFloat())
                    el.h = el.h.coerceIn(8f, SCREEN_H.toFloat())
                    // P1-3 蒙版/混合（仅 image；白名单外值解析期拒绝，与手机端同步）
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
                    // 2026-10-21 修（与手机端同步）：旧实现拿 el.x 当「距屏幕上边距」、
                    // el.r 当臂长，文档字段 len/inset 没解析 ⇒ 模型写 len:16 实画臂长 0。
                    if (o.has("len")) el.r = clampCoord(optF(o, "len", el.r))
                    val bw = if (el.w > 0f) el.w else zone[2].toFloat()
                    val bh = if (el.h > 0f) el.h else zone[3].toFloat()
                    val ins = optF(o, "inset", 0f).coerceAtLeast(0f)
                    el.w = (bw - 2f * ins).coerceAtLeast(2f * el.r + 2f)
                    el.h = (bh - 2f * ins).coerceAtLeast(2f * el.r + 2f)
                }
                else -> Unit
            }
            // anchor：box 元素（image/rect/brackets/ruler）定位语义。默认 center：x,y = 框中心
            // （2026-10-21 起唯一默认，见上方坐标空间说明；历史「左上角」加 anchor:"topleft" opt-out）。
            //   与手机端 HudDsl.parseEl 同源同步，改这里必须双改。
            val anchor = o.optString("anchor", "center")
            if (anchor != "topleft" && anchor != "center") {
                throw IllegalArgumentException("anchor 只支持 topleft/center（默认 center）")
            }
            if (type != "image" && type != "rect" && type != "brackets" && type != "ruler") {
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
                throw IllegalArgumentException("content/size/align/dir 仅 text 元素支持")
            }
            // 专属字段严格性（与手机端 HudDsl 同步）
            if (type != "circle" && (o.has("rx") || o.has("ry"))) {
                throw IllegalArgumentException("rx/ry 仅 circle 元素支持")
            }
            if (type != "rect" && o.has("radius")) {
                throw IllegalArgumentException("radius 仅 rect 元素支持")
            }
            if (type != "image" && o.has("id")) {
                throw IllegalArgumentException("id 仅 image 元素支持（hud_image 登记的资源引用）")
            }
            if (type != "image" && (o.has("mask") || o.has("blend"))) {
                throw IllegalArgumentException("mask/blend 仅 image 元素支持（蒙版与混合只对位图有意义）")
            }
            if (type == "image" && (o.has("stroke") || o.has("fillInk") || o.has("width") || o.has("dash"))) {
                throw IllegalArgumentException("image 不支持 stroke/fillInk/width/dash（墨线体系仅矢量元素）")
            }
            // bind 数据绑定：白名单 + as 映射 + 单场景上限（与手机端 HudDsl 同步）
            o.optJSONObject("bind")?.let { bind ->
                val key = bind.optString("key").trim()
                if (key.isEmpty()) throw IllegalArgumentException("bind 缺 key")
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
                if (asName !in KNOWN_BIND_AS) throw IllegalArgumentException("bind.as 不支持：$asName")
                val asTypeOk = when (asName) {
                    "sweep" -> type == "arc"
                    "count" -> type == "dot"
                    "text" -> type == "text"
                    "bar" -> type == "rect"
                    "progress" -> type == "line"
                    else -> true
                }
                if (!asTypeOk) throw IllegalArgumentException("bind.as=$asName 不适用于 type=$type")
                val asKeyOk = when (asName) {
                    // percent 恒 100 满量程；number 键量程各异须显式 max（2026-10-25 P1-2，与手机端同步）
                    "sweep", "count", "bar", "progress" -> keyType == "percent" || keyType == "number"
                    "enum" -> keyType == "enum"
                    else -> true
                }
                if (!asKeyOk) {
                    throw IllegalArgumentException(
                        "bind.as=$asName 不能绑定 $keyType 类键 $key" +
                            "（sweep/count/bar/progress←percent 或 number+max、enum←enum、text←任意）",
                    )
                }
                el.bindKey = key
                el.bindAs = asName
                binds[0]++
                if (binds[0] > MAX_BIND_ELEMENTS) throw IllegalArgumentException("bind 元素过多")
                // max 满量程（2026-10-25 P1-2，与手机端 HudDsl 同步）：
                //  - percent 键恒 100：显式 max 拒绝（冗余）
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
                    // optString 而非 optDouble：org.json 两实现对数字强转语义有差异，统一 toString+解析
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
                    val right = zone[0] + dx + zone[2]
                    el.maxDots = ((right - el.x) / el.step).toInt().coerceIn(1, 16)
                }
            }
            // as=bar 填充墨色（rect 比例条；轨道用 fillInk，填充用 barFill，默认亮绿 3；与手机端同步）
            if (o.has("barFill")) {
                if (type != "rect" || el.bindAs != "bar") {
                    throw IllegalArgumentException("barFill 仅 as=bar 的 rect 支持（比例填充条）")
                }
                el.barFill = o.optInt("barFill", 3).coerceIn(0, 3)
            }
        // bindOn 条件显隐（与手机端 HudDsl 同步）：
        //  - "enum键:值"：相等比较（link/ai_state/music）
        //  - "数值键:比较符值"（如 timer:<300）：阈值比较（percent/number 键，2026-10-25 P0-1）
        // 解析期拆成 key/op/值，渲染端零解析。
        o.optString("bindOn").trim().takeIf { it.isNotEmpty() }?.let { bo ->
            val k = bo.substringBefore(':').trim()
            // ⚠️ 必须显式传 missingDelimiterValue=""：无冒号时单参版返回整串，"link" 会被当成 link:link 放过
            val rest = bo.substringAfter(':', "").trim()
            // rest 以比较符开头 → 阈值比较（<= 优先于 <：取最长匹配）
            val op = BIND_ON_OPS.filter { rest.startsWith(it) }.maxByOrNull { it.length }
            if (op != null) {
                if (k !in BIND_NUMERIC_KEYS) {
                    throw IllegalArgumentException(
                        "bindOn 阈值比较 \"key:比较符值\" 仅支持数值键（percent：battery_glass/battery_phone；" +
                            "number：timer/calendar_next/unread/gps_speed/gps_bearing/steps），" +
                            "enum 键 link/ai_state/music 用 \"key:值\" 相等比较",
                    )
                }
                val cv = rest.substring(op.length).trim().toFloatOrNull()
                    ?: throw IllegalArgumentException("bindOn 阈值必须是数字：$bo（如 timer:<300）")
                el.bindOn = bo
                el.bindOnKey = k
                el.bindOnOp = op
                el.bindOnCmp = cv
            } else {
                if (k in BIND_NUMERIC_KEYS) {
                    throw IllegalArgumentException(
                        "数值键 $k 要做阈值比较请写 \"key:比较符值\"，如 $k:<300（相等比较仅 enum 键 link/ai_state/music）",
                    )
                }
                if (k !in BIND_ENUM_KEYS || rest.isEmpty()) {
                    throw IllegalArgumentException("bindOn 格式 \"key:值\"，key 仅 enum 键（link/ai_state/music）")
                }
                el.bindOn = bo
                el.bindOnKey = k
                el.bindOnOp = null
                el.bindOnVal = rest
            }
        }
            // 组合动画：单对象或数组（≤2，按实例计数进护栏；与手机端 HudDsl.parseAnims 同源）
            el.anims = parseAnims(o, type, el.dashOn, el.dashOff)
            anims[0] += el.anims.size
            if (type == "ping") anims[0]++
            return el
        }

        /** anim 解析（与手机端 HudDsl.parseAnims 同源）：单对象或数组 ≤2，数值越界钳制、结构性错误拒绝 */
        private fun parseAnims(o: JSONObject, type: String, dashOn: Float, dashOff: Float): List<Anim> {
            val raw = o.opt("anim") ?: return emptyList()
            val items: List<JSONObject> = when (raw) {
                is JSONObject -> listOf(raw)
                is JSONArray -> (0 until raw.length()).map {
                    raw.optJSONObject(it) ?: throw IllegalArgumentException("anim[$it] 不是对象")
                }
                else -> throw IllegalArgumentException("anim 必须是对象或数组")
            }
            if (items.size > 2) throw IllegalArgumentException("组合动画最多 2 个")
            val out = ArrayList<Anim>(items.size)
            for (a in items) {
                val at = a.optString("type")
                if (at !in KNOWN_ANIMS) throw IllegalArgumentException("未知动画 $at")
                if (out.any { it.type == at }) throw IllegalArgumentException("同元素动画类型重复：$at")
                if (at == "scale" && type !in SCALE_TYPES) {
                    throw IllegalArgumentException("scale 仅适用于 circle/rect/dot/polygon")
                }
                if (at == "orbit" && type == "image") {
                    throw IllegalArgumentException("orbit 不适用于 image（orbit 会以圆点替代元素本体）")
                }
                if (at == "glow" && type == "ping") throw IllegalArgumentException("ping 不支持 glow")
                if (at == "dashFlow") {
                    if (dashOn <= 0f || dashOff <= 0f) throw IllegalArgumentException("dashFlow 需要元素带 dash:[on,off]")
                    if (type in NO_DASHFLOW_TYPES) throw IllegalArgumentException("dashFlow 不适用于 $type")
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

        // ── v2 宏实例参数（解析期静态替换；⚠️ 与手机端 HudDsl 同步）──

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
                            throw IllegalArgumentException("宏参数不支持属性 $key（expr 仅支持数值属性）")
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

        /** 宏定义静态扫描：校验 p.* 参数名格式 + 每宏引用处数 ≤ 上限（含 anim/dash 内层；与手机端同步） */
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
                throw IllegalArgumentException("path.d 含不支持指令 '$ch'")
            }
        }

        private fun optF(o: JSONObject, key: String, def: Float): Float =
            o.optDouble(key, def.toDouble()).toFloat()

        private fun optF(v: Double?, def: Float): Float =
            (v ?: def.toDouble()).toFloat()

        private fun clampF(v: Double, min: Double, max: Double): Float = v.coerceIn(min, max).toFloat()

        private fun clampCoord(v: Float): Float = v.coerceIn(-50f, 690f)
    }

    // ── 控制器 ──

    private var hudView: HudView? = null
    private var wm: WindowManager? = null
    private var ticker: ValueAnimator? = null

    /**
     * 灭屏暂停的 ticker 标记（2026-10-24 省电）：灭屏 cancel、亮屏重启；场景消失时清除。
     * 只对「本在跑的 ticker」置位 —— 静态场景/无场景灭屏无动作可做。
     */
    private var screenPausedTicker = false
    private val hideRunnable = Runnable { hide() }

    /** bind 数据源（key→原始值，TOPIC_HUD_DATA 载荷格式）与到达锚点（timer 走秒用） */
    private val dataMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val anchorMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 当前展示是否为常驻场景（suspendForImage 恢复时用） */
    private var activePersist = false

    /**
     * 图片/图表层占用期间的挂起场景（互斥，2026-10-20）。
     * 图片层与 HUD 在 top-center 区域重叠、图表查看器是全屏不透明窗 —— 这两类层
     * 显示时必须盖住 HUD：这里记下当前场景并移除视图，层收起时原样恢复。
     * 用户显式 hide（指令/试戴超时）会清掉挂起场景 —— 显式 hide 后图片收起不应「复活」HUD。
     */
    private var suspendScene: Scene? = null
    private var suspendPersist = false

    init {
        // 灭屏暂停 ticker / 亮屏恢复（2026-10-24 省电实测）：动画场景的帧循环灭屏后没人看，
        // 空转纯耗电。恢复时 tMs 由 elapsedRealtime 重算、相位连续；走秒绑定取 nowMs，不受影响。
        // addListener 立即回放当前屏幕态：灭屏中创建的控制器不会误启 ticker。
        ScreenStateHub.addListener { on ->
            core.mainHandler.post {
                if (on) {
                    if (screenPausedTicker) {
                        screenPausedTicker = false
                        val v = hudView
                        if (v != null && (v.scene.hasAnim || v.scene.hasTickingBind)) {
                            Log.i(TAG, "ticker resumed: screen on")
                            startTicker(v)
                        }
                    }
                } else if (ticker != null) {
                    screenPausedTicker = true
                    ticker?.cancel()
                    ticker = null
                    Log.i(TAG, "ticker paused: screen off")
                }
            }
        }
    }

    /**
     * 图片/图表层显隐通知（ GlassesOverlayController / ChartViewerController 回调）：
     * hidden=true 挂起 HUD 让位，false 恢复。幂等；跨线程安全（post 主线程）。
     */
    fun suspendForOverlays(hidden: Boolean) {
        core.mainHandler.post {
            if (hidden) {
                val view = hudView ?: return@post // 已挂起或本就没显示：幂等
                suspendScene = view.scene
                suspendPersist = activePersist
                hideInternal()
                Log.i(TAG, "hud suspended for image/chart overlay")
            } else {
                val scene = suspendScene ?: return@post
                suspendScene = null
                show(scene, persist = suspendPersist)
                Log.i(TAG, "hud resumed after image/chart overlay hidden")
            }
        }
    }

    /** CXR 订阅回调（binder 线程）：只解析，View 操作一律 post 主线程 */
    fun handleCtrl(args: Caps?) {
        val payload = AiChannel.decodeHudCtrl(capsToStrings(args)) ?: run {
            Log.w(TAG, "hud_ctrl: rejected invalid payload (size=${args?.size()})")
            return
        }
        when (payload.action) {
            "hide" -> core.mainHandler.post { hide() }
            "try", "apply" -> {
                val scene = runCatching { HudSceneParser.parse(payload.dslJson) }
                    .onFailure { Log.w(TAG, "hud_ctrl parse failed: ${it.message}") }
                    .getOrNull() ?: return
                core.mainHandler.post { show(scene, persist = payload.action == "apply") }
            }
            else -> Log.w(TAG, "hud_ctrl unknown action=${payload.action}")
        }
    }

    /**
     * 压测直推入口（HudDebugReceiver → KeyButtonService.debugApplyHud）：与 [handleCtrl] 同路，
     * 但不经 CXR 桥/手机端，供 scripts/hud_stress.py 硬件摸底时用 adb 直接推场景。
     * 测完按数据回填护栏后，本方法与 HudDebugReceiver 可一并删。
     */
    fun debugApply(dsl: String, action: String) {
        when (action) {
            "hide" -> core.mainHandler.post { hide() }
            "try", "apply" -> {
                val t0 = SystemClock.elapsedRealtime()
                val scene = runCatching { HudSceneParser.parse(dsl) }
                    .onFailure { Log.w(TAG, "debug hud parse failed: ${it.message}") }
                    .getOrNull() ?: return
                Log.i(
                    TAG,
                    "debug hud parsed in ${SystemClock.elapsedRealtime() - t0}ms: " +
                        "elements=${scene.elements.size} anim=${scene.hasAnim}",
                )
                core.mainHandler.post { show(scene, persist = action == "apply") }
            }
            else -> Log.w(TAG, "debug hud unknown action=$action")
        }
    }

    /** CXR 数据下行（binder 线程）：更新 bind 数据源，主线程重绘（bind 元素按新值渲染） */
    fun handleData(args: Caps?) {
        val payload = AiChannel.decodeHudData(capsToStrings(args)) ?: run {
            Log.w(TAG, "hud_data: rejected invalid payload (size=${args?.size()})")
            return
        }
        val now = SystemClock.elapsedRealtime()
        for ((k, v) in payload) {
            dataMap[k] = v
            anchorMap[k] = now
        }
        Log.i(TAG, "hud_data: keys=${payload.keys}")
        core.mainHandler.post { hudView?.invalidate() }
    }

    /** CXR 图片资源下行（binder 线程）：hud_image 登记的主视觉图，解码缓存供场景 DSL 按 id 引用 */
    fun handleImage(args: Caps?) {
        val payload = AiChannel.decodeHudImage(capsToStrings(args)) ?: run {
            Log.w(TAG, "hud_image: rejected invalid payload (size=${args?.size()})")
            return
        }
        val bmp = runCatching {
            val bytes = Base64.decode(payload.base64, Base64.NO_WRAP or Base64.NO_CLOSE)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        if (bmp == null) {
            Log.w(TAG, "hud_image: decode failed id=${payload.id}")
            return
        }
        cacheImage(payload.id, bmp)
        Log.i(TAG, "hud_image: cached id=${payload.id} ${bmp.width}x${bmp.height}")
        core.mainHandler.post { hudView?.invalidate() }
    }

    /** 悬浮层可用性自检（与 GlassesOverlayController 同款高声告警，授权由手机端 ADB appops 下发） */
    private fun canShowOverlay(): Boolean {
        val ok = runCatching { Settings.canDrawOverlays(service) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "hud overlay NOT available: SYSTEM_ALERT_WINDOW not granted to ${service.packageName}")
        }
        return ok
    }

    private fun show(scene: Scene, persist: Boolean) {
        if (!canShowOverlay()) return
        hideInternal() // 单槽位：新场景顶掉旧场景
        val wmSafe = wm
            ?: (service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.also { wm = it }
            ?: return
        val view = HudView(service, scene, this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val added = runCatching { wmSafe.addView(view, params) }
        added.onFailure { Log.e(TAG, "addView(hud) failed", it) }
        if (added.isFailure) return
        hudView = view
        activePersist = persist
        // ⚠️ 2026-10-20 修：startTicker 此前从未被调用 ⇒ tMs 恒 0、画面静止（用户实测反馈）。
        //   有动画场景必须启动 ticker 驱动 [HudView.tMs]；无动画场景保持单帧零开销。
        //   灭屏中 apply 的场景不启 ticker（没人看），置 screenPausedTicker 等亮屏重启。
        if (scene.hasAnim || scene.hasTickingBind) {
            if (ScreenStateHub.isScreenOn) startTicker(view) else screenPausedTicker = true
        }
        core.mainHandler.removeCallbacks(hideRunnable)
        if (!persist) core.mainHandler.postDelayed(hideRunnable, TRY_ON_MS)
        Log.i(
            TAG,
            "hud overlay shown: elements=${scene.elements.size} persist=$persist anim=${scene.hasAnim}",
        )
    }

    /** 收起（hide 指令 / 试戴超时 / release 共用） */
    fun hide() {
        core.mainHandler.removeCallbacks(hideRunnable)
        // 显式收起：连挂起场景一起清 —— 用户说 hide 了，图片层收起后 HUD 不应复活
        suspendScene = null
        hideInternal()
    }

    private fun hideInternal() {
        ticker?.cancel()
        ticker = null
        screenPausedTicker = false // 场景没了，亮屏恢复逻辑失去目标
        hudView?.let { v ->
            runCatching { wm?.removeView(v) }
            hudView = null
            Log.i(TAG, "hud overlay hidden")
        }
    }

    /** 服务销毁时移除悬浮层（KeyButtonService.onDestroy 调用） */
    fun release() {
        core.mainHandler.removeCallbacks(hideRunnable)
        suspendScene = null
        hideInternal()
    }

    private fun startTicker(view: HudView) {
        ticker?.cancel()
        val start = SystemClock.elapsedRealtime()
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                view.tMs = (SystemClock.elapsedRealtime() - start).toFloat()
                // ⚠️ 2026-10-20 修：只更新 tMs 不触发重绘 ⇒ 画面永远停在第一帧
                //   （用户实测「眼镜端 HUD 不动」的直接原因）。Animator 回调在主线程，
                //   invalidate() 立即生效；无动画场景不建 ticker，零开销不变。
                view.invalidate()
            }
        }
        a.start()
        ticker = a
    }

    // ── 渲染（与 phone-app HudRenderer 同源；tMs 由 ticker 驱动）──

    private class HudView(
        context: Context,
        val scene: Scene,
        /** 持有控制器引用读 dataMap/anchorMap（嵌套类非 inner，显式传） */
        private val owner: HudOverlayController,
    ) : View(context) {
        var tMs = 0f
        private val bg: Bitmap? = buildBg(scene)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tmpPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            bg?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            val nowMs = SystemClock.elapsedRealtime()
            val data = owner.dataMap
            val anchors = owner.anchorMap
            for ((idx, el) in scene.elements.withIndex()) {
                // v2 expr：每帧求值覆盖静态几何（无表达式的元素零开销直接画；与手机端同步）
                val e = if (el.exprs.isEmpty()) el else applyExpr(el, tMs, idx)
                drawEl(canvas, paint, tmpPaint, e, tMs, data, anchors, nowMs)
            }
        }

        override fun onDetachedFromWindow() {
            bg?.recycle()
            super.onDetachedFromWindow()
        }

        companion object {
            /** 背景（网格+扫描线）静态不变 → 一次成型为位图，逐帧只 drawBitmap */
            private fun buildBg(scene: Scene): Bitmap? = runCatching {
                val bmp = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                if (scene.grid >= 8f) {
                    val cx = SCREEN_W / 2f
                    val cy = SCREEN_H / 2f
                    val maxDist = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
                    val p = Paint().apply { strokeWidth = 1f }
                    var x = scene.grid
                    while (x < SCREEN_W) {
                        val f = 1f - Math.hypot((x - cx).toDouble(), 0.0).toFloat() / maxDist
                        p.color = INK[3]
                        p.alpha = (255 * scene.gridOpacity * f).toInt().coerceIn(0, 255)
                        canvas.drawLine(x, 0f, x, SCREEN_H.toFloat(), p)
                        x += scene.grid
                    }
                    var y = scene.grid
                    while (y < SCREEN_H) {
                        val f = 1f - Math.hypot(0.0, (y - cy).toDouble()).toFloat() / maxDist
                        p.color = INK[3]
                        p.alpha = (255 * scene.gridOpacity * f).toInt().coerceIn(0, 255)
                        canvas.drawLine(0f, y, SCREEN_W.toFloat(), y, p)
                        y += scene.grid
                    }
                }
                if (scene.scanline > 0f) {
                    val p = Paint().apply { color = 0xFF000000.toInt() }
                    p.alpha = (255 * scene.scanline).toInt().coerceIn(0, 60)
                    var y = 0
                    while (y < SCREEN_H) {
                        canvas.drawRect(0f, y.toFloat(), SCREEN_W.toFloat(), (y + 1).toFloat(), p)
                        y += 3
                    }
                }
                bmp
            }.onFailure { Log.w(TAG, "hud bg build failed: ${it.message}") }.getOrNull()
        }
    }

    companion object {
        private const val TAG = KeyButtonService.TAG

        /**
         * 试戴自动收起（2026-10-25 用户要求 12s → 60s：看清动画节拍 + 走开一下再回来看
         * 都够）。⚠️ 与图片悬浮层 IMAGE_OVERLAY_MS（12s）**故意不同值** —— 两者语义
         * 不同：图片是"看一眼就走"的轻提示，HUD 试戴是设计验收，需要时间。
         */
        private const val TRY_ON_MS = 60_000L

        // 墨色 4 档（单绿屏；与 docs/hud-dsl.md 同表）
        private val INK = intArrayOf(
            0xFF1E5C36.toInt(), 0xFF2E7A4B.toInt(), 0xFF9FE8B8.toInt(), 0xFF46FF86.toInt(),
        )

        // ──────────────────────────────────────────────
        // 护栏值（与 HudDsl 同值；解析期强制）。
        // 2026-10-24 硬件摸底（scripts/hud_stress.py，RG-glasses 60Hz 实测）后调档：
        // 原 64/6/16 只吃到 ~23–30% CPU，远低于硬件能力；新值全部落在实测舒适区
        // （128 元素 41% CPU / 48 动画 ≈32% / 48 expr 元素 36%，p95 ≤ 38ms，fps 不塌）。
        // 真正的约束是 12K→16K JSON 预算（模型输出+传输）与 parse 主线程耗时，非渲染性能。
        // ──────────────────────────────────────────────
        private const val MAX_ELEMENTS = 128
        private const val MAX_ANIMS = 48
        private const val MAX_MACRO_DEPTH = 2
        private const val MAX_JSON_CHARS = 16_000
        private const val MAX_BIND_ELEMENTS = 8
        private const val MAX_TEXT_CHARS = 20
        /** text 多行上限（2026-10-25 P1-1：content 支持 \n 显式换行；每行仍 ≤MAX_TEXT_CHARS） */
        private const val MAX_TEXT_LINES = 4
        private const val MAX_IMAGES = 2
        private const val SCREEN_W = 480
        private const val SCREEN_H = 640

        private val TOKEN_REGEX = Regex("[MLHVQCZmlhvcz]|-?\\d+(?:\\.\\d+)?")

        // ── HUD 图片资源缓存（hud_image 工具下发的登记图；2 槽 LRU 轮转）──
        // 存 companion：companion 的 drawEl 渲染 image 元素时读取；资源先于场景 DSL 到达。

        private val imageCache = LinkedHashMap<String, Bitmap>()
        private const val IMAGE_CACHE_SIZE = 2

        private fun cacheImage(id: String, bmp: Bitmap) {
            synchronized(imageCache) {
                imageCache.remove(id)
                imageCache[id] = bmp
                while (imageCache.size > IMAGE_CACHE_SIZE) {
                    imageCache.remove(imageCache.keys.first())
                }
            }
        }

        private fun imageOf(id: String): Bitmap? = synchronized(imageCache) { imageCache[id] }

        /** 正弦波 0..1；phaseDeg = 周期内相位偏移（0–360，错峰呼吸/旋转；与手机端同步） */
        private fun wave(tMs: Float, periodSec: Float, phaseDeg: Float = 0f): Float {
            val phase = (tMs / 1000f / periodSec + phaseDeg / 360f) * 2.0 * Math.PI
            return (0.5 + 0.5 * Math.sin(phase)).toFloat()
        }

        // ── v2 expr 求值挂钩（⚠️ 与手机端 HudRenderer 同步）──

        /**
         * expr 属性逐帧求值，产出元素副本（不改解析结果本体：onDraw 每帧重算，单线程安全）。
         * env：t 播放秒 / i 元素序号 / w,h 元素 zone 宽高 / sw,sh 屏宽高。
         */
        private fun applyExpr(el: El, tMs: Float, idx: Int): El {
            val c = El()
            c.type = el.type
            c.x = el.x; c.y = el.y; c.x2 = el.x2; c.y2 = el.y2
            c.r = el.r; c.rx = el.rx; c.ry = el.ry; c.w = el.w; c.h = el.h
            c.radius = el.radius; c.start = el.start; c.sweep = el.sweep
            c.sides = el.sides; c.pathData = el.pathData; c.pathDx = el.pathDx; c.pathDy = el.pathDy
            c.stroke = el.stroke; c.fillInk = el.fillInk; c.width = el.width
            c.opacity = el.opacity; c.dashOn = el.dashOn; c.dashOff = el.dashOff
            c.step = el.step; c.major = el.major; c.interval = el.interval
            c.anims = el.anims; c.text = el.text; c.size = el.size; c.align = el.align
            c.bindKey = el.bindKey; c.bindAs = el.bindAs; c.maxDots = el.maxDots; c.bindOn = el.bindOn
            c.bindOnKey = el.bindOnKey; c.bindOnOp = el.bindOnOp; c.bindOnVal = el.bindOnVal; c.bindOnCmp = el.bindOnCmp
            c.barFill = el.barFill; c.bindMax = el.bindMax
            c.imageId = el.imageId; c.useRot = el.useRot; c.useMirror = el.useMirror
            c.mask = el.mask; c.blend = el.blend
            c.pivX = el.pivX; c.pivY = el.pivY; c.zoneW = el.zoneW; c.zoneH = el.zoneH; c.dirDown = el.dirDown
            c.zoneCx = el.zoneCx; c.zoneCy = el.zoneCy
            val env = floatArrayOf(
                tMs / 1000f, idx.toFloat(), el.zoneW, el.zoneH,
                SCREEN_W.toFloat(), SCREEN_H.toFloat(),
            )
            for ((k, node) in el.exprs) {
                val v = clampExprAttr(k, HudExpr.eval(node, env))
                when (k) {
                    // x/y/x2/y2 的 expr 结果 = 相对区域中心的偏移（与静态 x/y 同语义），
                    // 加回 base 得画布绝对坐标（2026-10-21 中心原点翻转，与手机端同步）
                    "x" -> c.x = el.zoneCx + v
                    "y" -> c.y = el.zoneCy + v
                    "x2" -> c.x2 = el.zoneCx + v
                    "y2" -> c.y2 = el.zoneCy + v
                    "r" -> c.r = v
                    "rx" -> c.rx = v
                    "ry" -> c.ry = v
                    "w" -> c.w = v
                    "h" -> c.h = v
                    "opacity" -> c.opacity = v
                    "size" -> c.size = v
                    "start" -> c.start = v
                    "sweep" -> c.sweep = v
                }
            }
            return c
        }

        /** 求值结果钳制：x/y 为「相对区域中心的偏移」[-320,320]/[-400,400]（加回 base 后即画布坐标）、
         * 尺寸 [0,690]、opacity [0,1]、size [8,48]（2026-10-21 随中心原点翻转调整 x/y 量纲，与手机端同步） */
        private fun clampExprAttr(k: String, v: Float): Float = when (k) {
            "x", "x2" -> v.coerceIn(-320f, 320f)
            "y", "y2" -> v.coerceIn(-400f, 400f)
            "r", "rx", "ry", "w", "h" -> v.coerceIn(0f, 690f)
            "opacity" -> v.coerceIn(0f, 1f)
            "size" -> v.coerceIn(8f, 48f)
            "start" -> v.coerceIn(-360f, 720f)
            else -> v.coerceIn(0f, 360f) // sweep
        }

        // ── bind 取值解释（与手机端 HudDsl.HudBind 同源；改必须两端同步）──

        /** percent/number 键数值化；enum 键、缺数据、非数值 → null */
        private fun bindNumeric(key: String, value: String?): Double? {
            if (value.isNullOrEmpty()) return null
            return when {
                key in HudSceneParser.BIND_PERCENT_KEYS || key in HudSceneParser.BIND_NUMBER_KEYS ->
                    value.toDoubleOrNull()
                else -> null
            }
        }

        /** enum 键的 on/off（null = 非 enum 键或缺数据）；on = link:connected / ai_state:busy / music:playing */
        private fun bindIsOn(key: String, value: String?): Boolean? {
            if (value.isNullOrEmpty() || key !in HudSceneParser.BIND_ENUM_KEYS) return null
            return when (key) {
                "link" -> value == "connected"
                "ai_state" -> value == "busy"
                "music" -> value == "playing"
                else -> null
            }
        }

        /** bindOn 条件求值（与手机端 HudBind.matchOn 同源）：op=null 相等；否则数值阈值，无数据→false（隐藏） */
        private fun bindOnMatch(key: String, op: String?, cmp: Float, valStr: String?, value: String?): Boolean {
            if (op == null) return value == valStr
            val n = bindNumeric(key, value) ?: return false
            return when (op) {
                "<" -> n < cmp
                "<=" -> n <= cmp
                ">" -> n > cmp
                ">=" -> n >= cmp
                "==" -> n == cmp.toDouble()
                else -> n != cmp.toDouble()
            }
        }

        /** as=text 的显示值；无数据/不可解析一律 "--"（timer/calendar_next 按接收锚点本地走秒） */
        private fun bindDisplayText(key: String, value: String?, anchorMs: Long, nowMs: Long): String {
            if (value.isNullOrEmpty()) return "--"
            return when {
                key in HudSceneParser.BIND_PERCENT_KEYS -> {
                    val pct = value.toDoubleOrNull() ?: return "--"
                    "${pct.toInt().coerceIn(0, 100)}%"
                }
                key == "timer" -> {
                    val left = value.toDoubleOrNull() ?: return "--"
                    val sec = (left - (nowMs - anchorMs) / 1000.0).coerceAtLeast(0.0)
                    String.format(java.util.Locale.US, "%02d:%02d", (sec / 60).toInt(), (sec % 60).toInt())
                }
                // 距下一日程：<1h → "45m"，≥1h → "1h05"（本地走秒，向上取整到分钟；与手机端 HudDsl 同步）
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
                // gps_speed：km/h 取整；gps_bearing：八方位缩写（22.5° 一档）；steps：今日步数
                // （2026-10-25 P1-2，与手机端 HudDsl.HudBind.displayText 同步）
                key == "gps_speed" -> {
                    val v = value.toDoubleOrNull() ?: return "--"
                    v.toInt().coerceAtLeast(0).toString()
                }
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
                key in HudSceneParser.BIND_TEXT_KEYS -> value
                key == "link" -> if (value == "connected") "ON" else "OFF"
                key == "ai_state" -> if (value == "busy") "BUSY" else "IDLE"
                key == "music" -> if (value == "playing") "PLAY" else "PAUSE"
                else -> "--"
            }
        }

        private fun drawEl(
            canvas: Canvas,
            paint: Paint,
            tmpPaint: Paint,
            el: El,
            tMs: Float,
            data: Map<String, String>,
            anchors: Map<String, Long>,
            nowMs: Long,
        ) {
            val bindKey = el.bindKey
            // bindOn 条件显隐：条件不满足（含无数据）→ 整元素不画
            // （enum 相等 or 数值阈值比较，与手机端 HudRenderer.drawEl 同步）
            el.bindOnKey?.let { k ->
                if (!bindOnMatch(k, el.bindOnOp, el.bindOnCmp, el.bindOnVal, data[k])) return
            }
            // bind as=enum：off → 整体置暗 + 关呼吸（与手机端 HudRenderer 同步）
            val enumOff = el.bindAs == "enum" && bindKey != null &&
                (bindIsOn(bindKey, data[bindKey]) ?: false).not()
            val dim = if (enumOff) 0.3f else 1f
            // 效果预扫：glow/scale/dashFlow 不依赖 canvas 变换（与手机端 HudRenderer 同步）
            var glowMul = 1f
            var geoScale = 1f
            var flowOff = -1f // <0 = 无蚂蚁线
            for (a in el.anims) {
                when (a.type) {
                    "glow" -> if (!enumOff) glowMul = 1f - a.amp + 2f * a.amp * wave(tMs, a.period, a.phase)
                    "scale" -> geoScale = 1f + a.amp * (2f * wave(tMs, a.period, a.phase) - 1f)
                    "dashFlow" -> {
                        val dir = if (a.reverse) -1f else 1f
                        val cyc = el.dashOn + el.dashOff
                        flowOff = ((tMs / 1000f * a.speed * dir) % cyc + cyc) % cyc
                    }
                }
            }
            // fall 位移动画：y 向匀速循环位移（(t*speed)%zoneH，出底回顶）——text/ping/常规三分支各自应用
            val fallDy = el.anims.firstOrNull { it.type == "fall" }?.let { (tMs / 1000f * it.speed) % el.zoneH } ?: 0f
            // bind as=text：整条走文本分支（bind 优先于 content；无数据 "--"）
            if (el.type == "text") {
                val display = if (bindKey != null && el.bindAs == "text") {
                    bindDisplayText(bindKey, data[bindKey], anchors[bindKey] ?: nowMs, nowMs)
                } else {
                    el.text ?: "--"
                }
                paint.style = Paint.Style.FILL
                paint.color = INK[el.stroke]
                paint.pathEffect = null
                paint.textSize = el.size
                paint.alpha = (255 * el.opacity * dim * glowMul).toInt().coerceIn(0, 255)
                if (fallDy != 0f) {
                    canvas.save()
                    canvas.translate(0f, fallDy)
                }
                if (el.dirDown) {
                    // v2 纵排：逐字符下排（步进 size*1.2），x,y 为首字符锚点，align 控横向对齐
                    var cy = el.y
                    for (ch in display) {
                        val s = ch.toString()
                        val cx = if (el.align == "center") el.x - paint.measureText(s) / 2f else el.x
                        canvas.drawText(s, cx, cy, paint)
                        cy += el.size * 1.2f
                    }
                } else {
                    // P1-1 多行：display 含 \n 时逐行绘制（行距 size*1.2，与纵排步进一致；align 逐行生效；与手机端同步）
                    val lines = display.split('\n')
                    if (lines.size <= 1) {
                        val tx = if (el.align == "center") el.x - paint.measureText(display) / 2f else el.x
                        canvas.drawText(display, tx, el.y, paint)
                    } else {
                        lines.forEachIndexed { li, ln ->
                            val ty = el.y + li * el.size * 1.2f
                            val tx = if (el.align == "center") el.x - paint.measureText(ln) / 2f else el.x
                            canvas.drawText(ln, tx, ty, paint)
                        }
                    }
                }
                if (fallDy != 0f) canvas.restore()
                return
            }
            if (el.type == "ping") {
                if (fallDy != 0f) {
                    canvas.save()
                    canvas.translate(0f, fallDy)
                }
                val period = el.interval * 1000f
                val phase = (tMs % period) / period
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = INK[el.stroke]
                paint.alpha = ((1f - phase) * el.opacity * 255f).toInt().coerceIn(0, 255)
                paint.pathEffect = null
                canvas.drawCircle(el.x, el.y, 2f + (el.r - 2f) * phase, paint)
                paint.style = Paint.Style.FILL
                paint.alpha = (el.opacity * 255f).toInt().coerceIn(0, 255)
                canvas.drawCircle(el.x, el.y, 2f, paint)
                if (fallDy != 0f) canvas.restore()
                return
            }
            var alphaMul = 1f
            canvas.save()
            // use 整组变换（rot/mirror 绕组定位点）：先组后部件（与手机端 HudRenderer 同步）
            if (el.useRot != 0f || el.useMirror) {
                if (el.useRot != 0f) canvas.rotate(el.useRot, el.pivX, el.pivY)
                if (el.useMirror) canvas.scale(-1f, 1f, el.pivX, el.pivY)
            }
            // fall 位移在组变换后的元素空间内叠加（与 spin/pulse 等组合；与手机端同步）
            if (fallDy != 0f) canvas.translate(0f, fallDy)
            for (a in el.anims) {
                when (a.type) {
                    "spin" -> {
                        val dir = if (a.reverse) -1f else 1f
                        canvas.rotate(tMs / 1000f / a.period * 360f * dir + a.phase, el.x, el.y)
                    }
                    "orbit" -> {
                        val dir = if (a.reverse) -1f else 1f
                        val theta = (tMs / 1000f / a.period + a.phase / 360f) * 2.0 * Math.PI * dir
                        val ox = el.x + a.orbitR * Math.cos(theta).toFloat()
                        val oy = el.y + a.orbitR * Math.sin(theta).toFloat()
                        paint.color = INK[el.stroke]
                        paint.style = Paint.Style.FILL
                        paint.alpha = (255 * el.opacity * glowMul).toInt().coerceIn(0, 255)
                        paint.pathEffect = null
                        canvas.drawCircle(ox, oy, if (el.r > 0f) el.r else 2f, paint)
                        paint.alpha = (120 * el.opacity * glowMul).toInt().coerceIn(0, 255)
                        // ⚠️ 2026-10-20 修：轨迹环必须 STROKE（此前沿用画点留下的 FILL，
                        //   画成半径=orbitR 的巨大实心盘盖住构图；与手机端 HudRenderer 同步）
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 0.6f
                        canvas.drawCircle(el.x, el.y, a.orbitR, paint)
                        canvas.restore()
                        return
                    }
                    "pulse" -> if (!enumOff) alphaMul = 0.35f + 0.65f * wave(tMs, a.period, a.phase)
                    "fade" -> if (!enumOff) alphaMul = 0.2f + 0.8f * wave(tMs, a.period, a.phase)
                }
            }
            // image：hud_image 登记的主视觉图（contain 适配 w×h 框居中；spin/scale/glow/
            // 组变换经 canvas/geoScale/glowMul 天然生效；缓存 miss 画占位虚线框）
            if (el.type == "image") {
                val bmp = imageOf(el.imageId ?: "")
                paint.color = INK[2]
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.pathEffect = null
                if (bmp == null) {
                    paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                    paint.alpha = (255 * el.opacity * dim).toInt().coerceIn(0, 255)
                    // x,y = 框中心（2026-10-21 中心原点；占位框同语义，与手机端同步）
                    canvas.drawRect(
                        el.x - el.w / 2f, el.y - el.h / 2f, el.x + el.w / 2f, el.y + el.h / 2f, paint,
                    )
                    canvas.restore()
                    return
                }
                val fit = minOf(el.w / bmp.width, el.h / bmp.height)
                val bw = bmp.width * fit * geoScale
                val bh = bmp.height * fit * geoScale
                val cx = el.x
                val cy = el.y
                paint.isFilterBitmap = true
                paint.alpha = (255 * el.opacity * alphaMul * glowMul * dim)
                    .toInt().coerceIn(0, 255)
                // P1-3 mask=circle：圆形蒙版（直径=min(w,h)，含 geoScale ⇒ 随呼吸缩放；
                // 与手机端同步）。占位框（bmp==null）仍是矩形虚线框，不做圆形裁剪。
                val clipCircle = el.mask == "circle"
                if (clipCircle) {
                    canvas.save()
                    canvas.clipPath(
                        Path().apply {
                            addCircle(cx, cy, minOf(bw, bh) / 2f, Path.Direction.CW)
                        },
                    )
                }
                // P1-3 blend=screen：位图与已绘内容 Screen 叠印（黑区透出下层墨线、亮区加亮；
                // xfermode 画完必须复位——paint 跨元素复用，泄漏会污染后续所有元素）
                if (el.blend == "screen") {
                    paint.xfermode = android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.SCREEN,
                    )
                }
                canvas.drawBitmap(
                    bmp, null,
                    RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f),
                    paint,
                )
                paint.xfermode = null
                if (clipCircle) canvas.restore()
                canvas.restore()
                return
            }
            alphaMul *= glowMul * dim
            paint.color = INK[el.stroke]
            paint.alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
            paint.strokeWidth = el.width
            paint.pathEffect = if (flowOff >= 0f) {
                // dashFlow：蚂蚁线沿路径流动（与手机端同步）
                DashPathEffect(floatArrayOf(el.dashOn, el.dashOff), -flowOff)
            } else if (el.dashOn > 0f && el.dashOff > 0f) {
                DashPathEffect(floatArrayOf(el.dashOn, el.dashOff), 0f)
            } else {
                null
            }
            paint.style = Paint.Style.STROKE
            // bind as=count：dot → 光点行（值/bindMax×容量；暗点画轨道，无数据全空；与手机端同步）
            if (el.type == "dot" && el.bindAs == "count" && bindKey != null) {
                val v = bindNumeric(bindKey, data[bindKey])
                val count = if (v == null) 0 else ((v / el.bindMax.toDouble()) * el.maxDots).toInt().coerceIn(0, el.maxDots)
                val pr = if (el.r > 0f) el.r else 2f
                paint.style = Paint.Style.FILL
                paint.pathEffect = null
                paint.alpha = (255 * el.opacity * alphaMul * 0.18f).toInt().coerceIn(0, 255)
                for (i in 0 until el.maxDots) canvas.drawCircle(el.x + i * el.step, el.y, pr, paint)
                paint.alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
                for (i in 0 until count) canvas.drawCircle(el.x + i * el.step, el.y, pr, paint)
                canvas.restore()
                return
            }
            // bind as=progress：line 按比例描绘（值/bindMax；从 (x,y) 画到值的比例处，无数据 0；与手机端同步）
            if (el.type == "line" && el.bindAs == "progress" && bindKey != null) {
                val v = bindNumeric(bindKey, data[bindKey])
                val pct = ((v ?: 0.0) / el.bindMax.toDouble()).toFloat().coerceIn(0f, 1f)
                canvas.drawLine(el.x, el.y, el.x + (el.x2 - el.x) * pct, el.y + (el.y2 - el.y) * pct, paint)
                canvas.restore()
                return
            }
            // bind as=sweep：arc 扫角由数据驱动（值/bindMax×360，无数据 0；与手机端同步）
            val sweepOverride = if (el.bindAs == "sweep" && bindKey != null) {
                (bindNumeric(bindKey, data[bindKey]) ?: 0.0).toFloat() / el.bindMax * 360f
            } else {
                null
            }
            if (el.fillInk >= 0) {
                // Paint.set() 返回 void 不能链式；复用 tmpPaint 免每帧分配
                tmpPaint.set(paint)
                tmpPaint.style = Paint.Style.FILL
                tmpPaint.color = INK[el.fillInk]
                tmpPaint.alpha = (255 * el.opacity * alphaMul * 0.55f).toInt().coerceIn(0, 255)
                tmpPaint.pathEffect = null
                drawShape(canvas, tmpPaint, el, sweepOverride, geoScale)
            }
            drawShape(canvas, paint, el, sweepOverride, geoScale)
            // bind as=bar：rect 比例填充条（值/bindMax；clip 到值比例，barFill 墨色；与手机端同步）
            if (el.type == "rect" && el.bindAs == "bar" && bindKey != null) {
                val v = bindNumeric(bindKey, data[bindKey])
                val pct = ((v ?: 0.0) / el.bindMax.toDouble()).toFloat().coerceIn(0f, 1f)
                val bw = el.w * geoScale
                val bh = el.h * geoScale
                val fillW = bw * pct
                if (fillW > 0.5f) {
                    val left = el.x - bw / 2f
                    val top = el.y - bh / 2f
                    canvas.save()
                    canvas.clipRect(left, top, left + fillW, top + bh)
                    tmpPaint.set(paint)
                    tmpPaint.style = Paint.Style.FILL
                    tmpPaint.color = INK[el.barFill]
                    tmpPaint.alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
                    tmpPaint.pathEffect = null
                    if (el.radius > 0f) {
                        canvas.drawRoundRect(left, top, left + bw, top + bh, el.radius, el.radius, tmpPaint)
                    } else {
                        canvas.drawRect(left, top, left + bw, top + bh, tmpPaint)
                    }
                    canvas.restore()
                }
            }
            canvas.restore()
        }

        private fun drawShape(canvas: Canvas, paint: Paint, el: El, sweepOverride: Float? = null, scale: Float = 1f) {
            when (el.type) {
                "circle", "dot" -> {
                    if (el.type == "dot") paint.style = Paint.Style.FILL
                    val sr = if (el.r > 0f) el.r else 2f
                    if (el.type == "circle" && (el.rx > 0f || el.ry > 0f)) {
                        // 椭圆：rx/ry 缺一侧时回退 r（与手机端 HudRenderer 同步）
                        val rx = (if (el.rx > 0f) el.rx else sr) * scale
                        val ry = (if (el.ry > 0f) el.ry else sr) * scale
                        canvas.drawOval(el.x - rx, el.y - ry, el.x + rx, el.y + ry, paint)
                    } else {
                        canvas.drawCircle(el.x, el.y, sr * scale, paint)
                    }
                }
                "arc" -> canvas.drawArc(
                    el.x - el.r, el.y - el.r, el.x + el.r, el.y + el.r,
                    el.start - 90f, sweepOverride ?: el.sweep, false, paint,
                )
                "line" -> canvas.drawLine(el.x, el.y, el.x2, el.y2, paint)
                "rect" -> {
                    // x,y = 框中心；尺寸乘 scale 绕矩形自身中心呼吸（2026-10-21 中心原点，与手机端同步）
                    val w = el.w * scale
                    val h = el.h * scale
                    val left = el.x - w / 2f
                    val top = el.y - h / 2f
                    if (el.radius > 0f) {
                        canvas.drawRoundRect(left, top, left + w, top + h, el.radius, el.radius, paint)
                    } else {
                        canvas.drawRect(left, top, left + w, top + h, paint)
                    }
                }
                "polygon" -> {
                    val path = Path()
                    val seg = 2.0 * Math.PI / el.sides
                    val pr = el.r * scale
                    for (i in 0 until el.sides) {
                        val a = -Math.PI / 2 + seg * i
                        val px = el.x + pr * Math.cos(a).toFloat()
                        val py = el.y + pr * Math.sin(a).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "path" -> canvas.drawPath(buildPath(el.pathData ?: "", el.pathDx, el.pathDy), paint)
                "brackets" -> {
                    // 四角角标框：x,y = 框中心，w/h = 框大小，r = 臂长（2026-10-21 重写，与手机端同步：
                    // 旧实现 inset=el.x、固定在屏幕四角且忽略 w/h/y，模型写的框尺寸全被丢弃）
                    val len = el.r
                    val left = el.x - el.w / 2f
                    val top = el.y - el.h / 2f
                    val right = el.x + el.w / 2f
                    val bottom = el.y + el.h / 2f
                    val path = Path()
                    path.moveTo(left, top + len); path.lineTo(left, top); path.lineTo(left + len, top)
                    path.moveTo(right - len, top); path.lineTo(right, top); path.lineTo(right, top + len)
                    path.moveTo(left, bottom - len); path.lineTo(left, bottom); path.lineTo(left + len, bottom)
                    path.moveTo(right - len, bottom); path.lineTo(right, bottom); path.lineTo(right, bottom - len)
                    canvas.drawPath(path, paint)
                }
                "ruler" -> {
                    val path = Path()
                    var i = 0
                    while (i * el.step <= el.w) {
                        val tall = i % el.major == 0
                        val th = if (tall) 7f else 4f
                        // x,y = 刻度行中心：从 x-w/2 起向右排（2026-10-21 中心原点，与手机端同步）
                        val tx = el.x - el.w / 2f + i * el.step
                        path.moveTo(tx, el.y)
                        path.lineTo(tx, el.y + th)
                        i++
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }

        /** 极简 SVG path → android Path（v1 仅绝对坐标 M/L/H/V/C/Q/Z，非法指令已在解析期拒绝） */
        private fun buildPath(d: String, dx: Float, dy: Float): Path {
            val path = Path()
            val tokens = TOKEN_REGEX.findAll(d).map { it.value }.toList()
            var i = 0
            var cmd = 'M'
            var lastX = 0f
            var lastY = 0f
            fun nextF(): Float = tokens[i++].toFloat()
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.length == 1 && t[0].isLetter()) {
                    cmd = t[0].uppercaseChar()
                    i++
                    if (cmd == 'Z') {
                        path.close()
                        continue
                    }
                }
                when (cmd) {
                    'M' -> { lastX = nextF() + dx; lastY = nextF() + dy; path.moveTo(lastX, lastY) }
                    'L' -> { lastX = nextF() + dx; lastY = nextF() + dy; path.lineTo(lastX, lastY) }
                    'H' -> { lastX = nextF() + dx; path.lineTo(lastX, lastY) }
                    'V' -> { lastY = nextF() + dy; path.lineTo(lastX, lastY) }
                    'C' -> {
                        val x1 = nextF() + dx; val y1 = nextF() + dy
                        val x2 = nextF() + dx; val y2 = nextF() + dy
                        lastX = nextF() + dx; lastY = nextF() + dy
                        path.cubicTo(x1, y1, x2, y2, lastX, lastY)
                    }
                    'Q' -> {
                        val x1 = nextF() + dx; val y1 = nextF() + dy
                        lastX = nextF() + dx; lastY = nextF() + dy
                        path.quadTo(x1, y1, lastX, lastY)
                    }
                }
            }
            return path
        }
    }
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
