package com.rokidlab.rokidlink

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.cxr.Caps

/**
 * 「过程时间线」眼镜端渲染器（v5.0；2026-10-15 用户实拍反馈三连修）：
 * ① **单行卡**——label 与 detail 同行（不再 `\n` 分行），窗口宽 WRAP_CONTENT 随文字变长，
 *    maxLines=1 + 省略号封顶（TEXT_MAX_WIDTH_DP）保证绝不折行；
 * ② **对齐修偏**——多行块会让行内居中的圆点/图标对到两行中点；单行后三者天然同中线；
 *    且去掉行距 1.15（其末行下方留空会让文字整体偏上）；
 * ③ **框尺寸贴合内容**——内容变化后 `updateViewLayout` 强制重测（overlay 窗口内容缩小时
 *    不自动收缩，实拍表现为「框比内容矮半个字」），底边距 6→9dp 兜住字形。
 *
 * 手机侧把「过程」汇聚点（[com.rokidlab.phone.glasses.GlassesProcessBridge]）的每个
 * AgentStep 转成紧凑帧经 [AiChannel.TOPIC_PROCESS_STEP] 下行，本控制器渲染成
 * **贴在官方对话页底部功能行上方的小卡**：
 *  - **单状态圆点**（随 state 着色）+ **步骤类型图标**（随 kind 着色）+ **当前步 label**
 *    （9sp 亮）+ detail（8sp 暗，同行跟随，截断防长 token）；
 *  - **确认态**：面板变暗绿底，主行下展开参数行 +「[点头]允许  [摇头]取消」提示行
 *    —— 点头之前先看见自己要批什么；
 *  - 标题与手机端过程卡片**同源**：手机侧 labelOf 取 phone-app 的
 *    `R.string.chat_trace_*`（同一资源），PLAN 步骤两端都不显示。
 *
 * ## 步骤类型图标（v4.9）
 * 与手机端过程卡片 `ChatBubble.kt` 用**同一套 Material Outlined 字形**（手机 Compose
 * `Icons.Outlined.X`，本APK 打包子集字体 `res/font/mat_step_icons.ttf`，2660 字节 / 9 字形）。
 * 字体与字形映射见 [STEP_ICON_CODEPOINTS] 与 [stepIconCodepoint]。
 * ⚠️ kind 是协议**尾部可选**字段：旧手机端不下发时退化为纯文字卡（不画图标，不报错）。
 *
 * ## 位置（用户 2026-10-14 实拍指定）
 * 坐在官方对话页底部功能行（「滑动翻页 / 双击退出」，uiautomator 实测
 * `bounds=[16,471][141,490]`，屏高 640）**上方** 6px；左对齐距左 10dp。
 * ⚠️ 曾错放屏幕左下角（贴系统状态栏）——那不是用户要的位置。
 *
 * ## 与既有悬浮层的关系（单槽位问题的一期处置）
 * 复用歌词/图片/工具确认同一套 `SYSTEM_ALERT_WINDOW` 授权与overlay 机制，
 * 但**独立窗口实例**。⚠️ 图片层（居中）与图表全屏查看器仍可能视觉重叠 ——
 * 统一 overlay 调度（优先级+队列）是已知待办，本期不做。
 *
 * ## 状态
 * 单状态圆点（label 左侧）随帧 state 着色：RUNNING 亮实心 / AWAITING_CONFIRM
 * 亮圆环 / OK 暗实心 / FAILED·CANCELLED 暗圆环。轮次结束（done 帧）全卡变暗、
 * 延迟收起。不显示步数（不编造总数）。
 */
internal class ProcessTimelineController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 轮次结束到自动收起的延迟：让用户看清最终态，又不长期占屏 */
        private const val COLLAPSE_DELAY_MS = 3_000L

        // 单绿屏配色：绿字黑底（与歌词/确认层同一授权体系与视觉语言）。
        // ⚠️ 2026-10-14 实拍教训：底**必须不透明**——半透明会把官方对话正文透印上来。
        private const val COLOR_TEXT = 0xFF46FF86.toInt()
        private const val COLOR_DIM = 0xFF9FE8B8.toInt()
        private const val COLOR_TRACK = 0xFF14301D.toInt()
        /** 面板底：纯黑不透明 */
        private const val COLOR_BG = 0xFF000000.toInt()
        /** 确认态面板底：暗绿 */
        private const val COLOR_AWAIT_BG = 0xFF14401F.toInt()
        private const val COLOR_TRANSPARENT = 0x00000000

        /**
         * 主状态圆点直径（dp）：label 左侧，随帧 state 着色（见 statusDotDrawable）。
         * v5.2（2026-10-15 实拍反馈）：5.4 → 4.5。
         */
        private const val STATUS_DOT_DP = 4.5f
        /**
         * 官方对话页底部功能行（「滑动翻页 / 双击退出」）顶边实测 px：
         * uiautomator dump 2026-10-14 实测 bounds=[16,471][141,490]（屏高 640）。
         * 卡片坐在该行上方 —— 用户指定位置。
         */
        private const val PROMPT_ROW_TOP_PX = 471
        /** 卡片底边与功能行顶边的间距（px） */
        private const val PROMPT_ROW_GAP_PX = 6
        /** 面板左边距（dp） */
        private const val PANEL_X_DP = 10f
        /** 面板圆角（dp） */
        private const val PANEL_RADIUS_DP = 7f
        /** 面板描边（dp）：单绿屏上 1dp 几乎不可见（2026-10-14 实拍教训），1.2dp 起 */
        private const val PANEL_STROKE_DP = 1.2f
        /** label 字号（sp）：用户反馈 15sp/13sp 都太大，12sp 在 0.24 宽卡里又太挤 → 9sp */
        private const val LABEL_SP = 9f
        /** detail 字号（sp） */
        private const val DETAIL_SP = 8f
        /**
         * 面板内边距（dp）：v5.2（2026-10-15 实拍反馈）「框矮一点，比字体大一点、
         * 不显得拥挤就行」——上 3 / 下 5，总高 ≈ 文字行盒 12dp + 8dp，视觉贴字不裁字形。
         */
        private const val PANEL_PAD_H_DP = 7f
        private const val PANEL_PAD_TOP_DP = 3f
        private const val PANEL_PAD_BOTTOM_DP = 5f
        /**
         * 状态圆点与文字的间距（dp）：v5.2（2026-10-15 实拍反馈）「图标跟后边的文字
         * 空 5dp」。
         */
        private const val DOT_TEXT_GAP_DP = 5f
        /**
         * detail 眼镜端二次截断（字符）：v5.0 detail 与 label **同行**（用户要求不换行），
         * 截断是唯一的长度防线——普通步骤截 7 字，确认态参数是点头前的决策依据放宽到 11。
         */
        private const val MAX_DETAIL_COLS = 7
        private const val MAX_DETAIL_COLS_CONFIRM = 11
        /** 文字区最大宽度（dp）：单行卡随文字变宽的封顶，超过即省略号（不出屏） */
        private const val TEXT_MAX_WIDTH_DP = 150f

        // ── 步骤类型图标（Material Outlined 子集字体，2026-10-15 新增）──

        /**
         * 步骤类型 → Material Outlined 字形 codepoint。
         *
         * 与手机端过程卡片 `ChatBubble.kt` 的 `Kind → Icons.Outlined.X` **逐项对应**，
         * 改一处必须改另一处（两端各映射字形、不共享枚举，靠这张表 + 手机端那张 when 对齐）。
         * PLAN 不进帧（手机端 [AiChannel.ProcessStepFrame] 侧已过滤），此处保留仅为穷尽防御。
         *
         * codepoint 取自官方 `MaterialIconsOutlined-Regular`（upem 512），已子集化进
         * `res/font/mat_step_icons.ttf`（**只含这 9 个字形，2660 字节**）。
         * ⚠️ 子集外不得新增条目 —— 字形不在字体里会画出豆腐块（空框），
         * 宁可 [stepIconCodepoint] 返回 null 不画。
         */
        private val STEP_ICON_CODEPOINTS = mapOf(
            "THINKING" to 0xEA4A,   // psychology   思考
            "KNOWLEDGE" to 0xEA19,  // menu_book    知识库
            "TOOL" to 0xE869,       // build        工具
            "COMPRESS" to 0xE94D,   // compress     压缩
            "PLAN" to 0xE6B1,       // checklist    计划
            "SUMMARY" to 0xE153,    // flag         收尾
            "FRAMEWORK" to 0xE8B8,  // settings     框架
            "MEMORY" to 0xE1DB,     // storage      上下文注入
            "REFLECT" to 0xE160,    // report       自省
        )

        /**
         * 图标字号（sp）：v5.1（2026-10-15 实拍反馈）「图标改大一点」——比 label 9sp
         * 大一档到 11sp，Material Outlined 字形全高绘制，视觉上明显大于文字。
         */
        private const val ICON_SP = 11f
        /** 图标最小宽度（dp）：给线性布局一个稳定占位（随 ICON_SP 同步放大），避免文字抖动 */
        private const val ICON_WIDTH_DP = 11f
        /** 图标与状态圆点的间距（dp）：v5.2「圆点跟图标空 8dp」 */
        private const val DOT_ICON_GAP_DP = 8f

        /**
         * 步骤类型名 → 图标 codepoint；未知/空类型返回 null（**不画图标**，不猜兜底图标）。
         *
         * 放companion（与 [STEP_ICON_CODEPOINTS] 同域）以便单测直接静态断言这张表，
         * 不必构造 [KeyButtonService] 依赖。
         *
         * 空 kind 是**正常**情况：旧版手机端下发的帧没有第 7 字段（协议尾部可选，见
         * [AiChannel.decodeProcessStep]），此时卡片退化成 v4.8 的纯文字形态，不报错、不显示豆腐块。
         */
        fun stepIconCodepoint(kind: String?): Int? =
            if (kind.isNullOrBlank()) null else STEP_ICON_CODEPOINTS[kind]
    }

    private var box: View? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var statusDot: View? = null
    private var iconView: TextView? = null
    private var textView: TextView? = null
    private var confirmView: TextView? = null
    private var wm: WindowManager? = null

    /**
     * 子集字体懒加载缓存（`res/font/mat_step_icons.ttf`，9 个 Material Outlined 字形）。
     * 首次用到时才 `resources.getFont`：Typeface 是重量级对象，且创建面板时未必已知 kind。
     * 加载失败 → null → 图标退化为不显示（文字照常），绝不因此崩掉过程卡片。
     */
    private var stepIconTypeface: android.graphics.Typeface? = null
    private var stepIconTypefaceLoaded = false

    private val collapseRunnable = Runnable { hide() }
    private val density = service.resources.displayMetrics.density

    // ──────────────────────────────────────────────
    //  下行入口（CxrBridgeCoordinator 订阅回调，binder 线程）
    // ──────────────────────────────────────────────

    /** 一条过程步骤：解码 → 主线程展示。非法载荷整体丢弃（不猜字段）。 */
    fun handleStep(args: Caps?) {
        val frame = AiChannel.decodeProcessStep(capsToStrings(args)) ?: run {
            Log.w(TAG, "process_step: rejected invalid payload (size=${args?.size()})")
            return
        }
        core.mainHandler.post { showStep(frame) }
    }

    /** 轮次结束：延迟收起。 */
    fun handleDone(args: Caps?) {
        val failed = AiChannel.decodeProcessDone(capsToStrings(args)) ?: run {
            Log.w(TAG, "process_done: rejected invalid payload (size=${args?.size()})")
            return
        }
        core.mainHandler.post { markDone(failed) }
    }

    /** 服务销毁：停收起任务并移除面板。 */
    fun release() {
        core.mainHandler.removeCallbacks(collapseRunnable)
        core.mainHandler.post { hide() }
    }

    // ──────────────────────────────────────────────
    //  渲染
    // ──────────────────────────────────────────────

    private fun showStep(frame: AiChannel.ProcessStepFrame) {
        if (box == null && !ensurePanel()) return
        core.mainHandler.removeCallbacks(collapseRunnable)

        // 面板轮廓：确认态最高优先级（暗绿底 + 亮描边），其余纯黑
        box?.background = panelBackground(frame.state == "AWAITING_CONFIRM")
        // 单状态圆点随帧 state 着色（RUNNING 亮实心 / 确认态亮圆环 / 其余暗）
        statusDot?.background = statusDotDrawable(frame.state)
        // 步骤类型图标：圆点与文字之间。未知 kind / 字体缺失 → 隐藏（不留空占位）。
        applyStepIcon(frame.kind, frame.state)
        textView?.text = bodyText(frame)

        if (frame.state == "AWAITING_CONFIRM") {
            // 确认态：展开完整参数 + 操作提示（点头前的决策依据；两行都是完整短行，不折行）
            confirmView?.let { v ->
                v.text = confirmText(frame)
                v.visibility = View.VISIBLE
            }
        } else {
            confirmView?.visibility = View.GONE
        }
        // 内容（宽度/高度）变了，强制 WindowManager 重测——治「框比内容矮半个字」
        relayoutWindow()
        Log.i(TAG, "process step: ${frame.key} state=${frame.state}")
    }

    private fun markDone(failed: Boolean) {
        if (box == null) return
        // 轮次结束：状态圆点落终态（OK 暗实心 / FAILED 暗圆环），随后延迟收起。
        // done 帧不带 label，不编造"共 N 步"。
        statusDot?.background = statusDotDrawable(if (failed) "FAILED" else "OK")
        core.mainHandler.postDelayed(collapseRunnable, COLLAPSE_DELAY_MS)
        Log.i(TAG, "process timeline: done (failed=$failed), collapse in ${COLLAPSE_DELAY_MS}ms")
    }

    // ──────────────────────────────────────────────
    //  步骤类型图标
    // ──────────────────────────────────────────────

    /**
     * 把当前步骤的类型图标写进圆点后的图标位。
     *
     * 三种「不画」的情况都收敛到 [View.GONE]（不留空白、也不留豆腐块）：
     * 1. [kind] 为空/未知（[stepIconCodepoint] 返回 null）；
     * 2. 子集字体加载失败（装机漏打包 res/font 等）；
     * 3. codepoint 恰好不在子集内（加表时忘了重跑子集化）——**宁可少画，不画错**。
     *
     * 颜色随 [state]：运行中/确认态亮绿，完成与失败暗绿（与状态圆点同一套语义）。
     */
    private fun applyStepIcon(kind: String, state: String) {
        val v = iconView ?: return
        val cp = stepIconCodepoint(kind)
        val tf = if (cp != null) loadStepIconTypeface() else null
        if (cp == null || tf == null) {
            v.visibility = View.GONE
            return
        }
        v.typeface = tf
        v.text = String(Character.toChars(cp))
        v.setTextColor(if (state == "RUNNING" || state == "AWAITING_CONFIRM") COLOR_TEXT else COLOR_DIM)
        v.visibility = View.VISIBLE
    }

    /** 懒加载并缓存子集字体；失败只告警一次（不刷日志、不抛）。 */
    private fun loadStepIconTypeface(): android.graphics.Typeface? {
        if (stepIconTypefaceLoaded) return stepIconTypeface
        stepIconTypefaceLoaded = true
        stepIconTypeface = runCatching {
            @Suppress("DiscouragedApi")
            service.resources.getFont(R.font.mat_step_icons)
        }.onFailure {
            Log.e(TAG, "step icon font load failed; icons disabled", it)
        }.getOrNull()
        return stepIconTypeface
    }

    /**
     * 文字内容（v5.0 **单行**，用户 2026-10-15 要求「不要换行，框根据文字变长」）：
     * label（亮 9sp）+ 两空格 + detail（暗 8sp，截断防长 token）。
     * 两级颜色与字号用 Spannable 分段设——单色单字号 TextView 做不出层级。
     *
     * ⚠️ 不再用 `\n` 把 detail 另起一行：多行块会让行内居中的圆点/图标对到
     * **两行的中点**而不是 label 行（即用户实拍的「圆点图标字错位」）；
     * 单行后三者天然同一条中线，与手机端排版一致。
     */
    private fun bodyText(f: AiChannel.ProcessStepFrame): CharSequence {
        val label = f.label.ifEmpty { "处理中" }
        val ss = SpannableStringBuilder(label)
        ss.setSpan(ForegroundColorSpan(COLOR_TEXT), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.setSpan(AbsoluteSizeSpan(LABEL_SP.toInt(), true), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (f.detail.isNotEmpty()) {
            val limit = if (f.state == "AWAITING_CONFIRM") MAX_DETAIL_COLS_CONFIRM else MAX_DETAIL_COLS
            val detail = "  " + f.detail.take(limit)
            val start = ss.length
            ss.append(detail)
            ss.setSpan(ForegroundColorSpan(COLOR_DIM), start, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(AbsoluteSizeSpan(DETAIL_SP.toInt(), true), start, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ss
    }

    /** 确认态独立行：完整参数 + 操作提示（与 ToolConfirmController 同一套手势文案）。 */
    private fun confirmText(f: AiChannel.ProcessStepFrame): CharSequence {
        val ss = SpannableStringBuilder()
        if (f.detail.isNotEmpty()) {
            ss.append("  " + f.detail.take(MAX_DETAIL_COLS_CONFIRM))
            ss.setSpan(ForegroundColorSpan(COLOR_DIM), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(AbsoluteSizeSpan(DETAIL_SP.toInt(), true), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val hint = "\n  [点头]允许  [摇头]取消"
        val start = ss.length
        ss.append(hint)
        ss.setSpan(ForegroundColorSpan(COLOR_DIM), start, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        ss.setSpan(AbsoluteSizeSpan(DETAIL_SP.toInt(), true), start, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return ss
    }

    // ──────────────────────────────────────────────
    //  视图构建
    // ──────────────────────────────────────────────

    /**
     * 主状态圆点（label 左侧）：
     * - RUNNING：实心亮圆；
     * - AWAITING_CONFIRM：亮圆环（空心）——全场最高优先级；
     * - OK：实心暗圆（已完成退场）；
     * - FAILED / CANCELLED：暗圆环。
     */
    private fun statusDotDrawable(state: String): GradientDrawable {
        val (solid, color) = when (state) {
            "RUNNING" -> true to COLOR_TEXT
            "AWAITING_CONFIRM" -> false to COLOR_TEXT
            "OK" -> true to COLOR_DIM
            else -> false to COLOR_DIM
        }
        return trackDotDrawable(solid, color, STATUS_DOT_DP)
    }

    /** 步点/状态点统一构造：实心 or 圆环。 */
    private fun trackDotDrawable(solid: Boolean, color: Int, sizeDp: Float = STATUS_DOT_DP): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (solid) {
                setColor(color)
            } else {
                setColor(COLOR_TRANSPARENT)
                setStroke(dp(1.5f).toInt(), color)
            }
            setSize(dp(sizeDp).toInt(), dp(sizeDp).toInt())
        }

    /** 面板底：确认态暗绿，其余纯黑；统一圆角 + 亮绿描边（边框不能少）。 */
    private fun panelBackground(await: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(PANEL_RADIUS_DP)
        setColor(if (await) COLOR_AWAIT_BG else COLOR_BG)
        setStroke(dp(PANEL_STROKE_DP).toInt(), COLOR_TEXT)
    }

    private fun dp(v: Float): Float = v * density

    /**
     * 建面板（首次步骤到达时）。授权自检与 GlassesOverlayController 同一模式：
     * `TYPE_APPLICATION_OVERLAY` 必须先授予 `SYSTEM_ALERT_WINDOW`（手机端经
     * ADB appops 下发），失败**高声告警**而非静默 —— 历史教训是未授权时
     * addView 被 runCatching 吞掉，表现为「过程不显示但日志无错」。
     */
    private fun ensurePanel(): Boolean {
        val ok = runCatching { android.provider.Settings.canDrawOverlays(service) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "process timeline overlay NOT available: SYSTEM_ALERT_WINDOW not granted " +
                "to ${service.packageName} (手机端应经 adb appops set " +
                "${service.packageName} android:system_alert_window allow)")
            return false
        }
        val wmSafe = wm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
            ?.also { wm = it } ?: return false

        val dot = View(service).apply {
            background = statusDotDrawable("RUNNING")
            layoutParams = LinearLayout.LayoutParams(
                dp(STATUS_DOT_DP).toInt(),
                dp(STATUS_DOT_DP).toInt(),
            )
        }
        // 步骤类型图标：Material Outlined 子集字形；初始 GONE（首个帧到达才按 kind 显示）。
        // ⚠️ 放在 GONE 而非 INVISIBLE：无图标时完全不占宽（空 kind 是正常情况，见 applyStepIcon）。
        val icon = TextView(service).apply {
            textSize = ICON_SP
            includeFontPadding = false
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                dp(ICON_WIDTH_DP).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(DOT_ICON_GAP_DP).toInt() }
        }
        val text = TextView(service).apply {
            textSize = LABEL_SP
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(COLOR_TEXT)
            // v5.0 单行卡：宽度随文字增长（窗口 WRAP_CONTENT），封顶防出屏、超宽省略号。
            // ⚠️ maxLines=1 是「不换行」的硬保证——Spannable 文本里即使混入控制字符也不会折行。
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(TEXT_MAX_WIDTH_DP).toInt()
            // ⚠️ 单行**不设行距**：lineSpacing 会在字形下方留空（含末行），
            // 行内居中时文字整体偏上——正是「圆点/图标/字错位」的来源之一。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(DOT_TEXT_GAP_DP).toInt() }
        }
        val mainRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(PANEL_PAD_TOP_DP).toInt(), 0, 0)
            addView(dot)
            addView(icon)
            addView(text)
        }
        val confirm = TextView(service).apply {
            textSize = DETAIL_SP
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(COLOR_DIM)
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
            // 缩进对齐主行**文字起点**：圆点 + 圆点-图标字位 + 图标位 + 图标-文字字位
            // （v5.1 图标常态可见，按图标可见的起点算；无图标帧缩进略深，视觉可接受）
            setPadding(
                (dp(STATUS_DOT_DP + DOT_ICON_GAP_DP + ICON_WIDTH_DP + DOT_TEXT_GAP_DP)).toInt(),
                dp(2f).toInt(), 0, 0,
            )
        }
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(false)
            setPadding(
                dp(PANEL_PAD_H_DP).toInt(), dp(PANEL_PAD_TOP_DP).toInt(),
                dp(PANEL_PAD_H_DP).toInt(), dp(PANEL_PAD_BOTTOM_DP).toInt(),
            )
            addView(mainRow)
            addView(confirm)
        }
        // 位置：坐在官方对话页底部功能行（「滑动翻页 / 双击退出」实测 top=471px）上方。
        // Gravity.BOTTOM 的 y 是「距屏幕底边的像素偏移」= 屏高 - 卡片底边。
        // v5.0：宽高都 WRAP_CONTENT——宽随文字增长（用户「框根据文字变长」），高随行数；
        // 底边锚定不动 ⇒ 顶边随内容自动上下。⚠️ 内容变化后必须 [relayoutWindow] 强制重测：
        // overlay 窗口在内容缩小时不自动收缩，表现为「框比内容矮/宽半个字」（2026-10-15 实拍）。
        val screenH = service.resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.LEFT or Gravity.BOTTOM
            x = dp(PANEL_X_DP).toInt()
            y = (screenH - PROMPT_ROW_TOP_PX + PROMPT_ROW_GAP_PX).coerceAtLeast(0)
        }
        val added = runCatching { wmSafe.addView(panel, params) }
        added.onFailure { Log.e(TAG, "addView(process timeline) failed", it) }
        if (added.isFailure) return false
        box = panel
        windowParams = params
        statusDot = dot
        iconView = icon
        textView = text
        confirmView = confirm
        Log.i(TAG, "process timeline panel created")
        return true
    }

    /**
     * 内容变化后强制重测窗口（v5.0）。
     *
     * 为什么必须：`TYPE_APPLICATION_OVERLAY` + WRAP_CONTENT 的窗口在视图内容**缩小**时
     * 不会自动收缩（Android overlay 已知行为），上一帧的窗口尺寸会残留 —— 实拍表现为
     * 「框比内容矮半个字 / 宽一截」。updateViewLayout 用同一份 params 触发 WindowManager
     * 重新 measure，尺寸立即对齐内容。
     */
    private fun relayoutWindow() {
        val panel = box ?: return
        val p = windowParams ?: return
        runCatching { wm?.updateViewLayout(panel, p) }
            .onFailure { Log.w(TAG, "updateViewLayout(process timeline) failed: ${it.message}") }
    }

    private fun hide() {
        core.mainHandler.removeCallbacks(collapseRunnable)
        box?.let { panel -> runCatching { wm?.removeView(panel) } }
        box = null
        windowParams = null
        statusDot = null
        iconView = null
        textView = null
        confirmView = null
        Log.i(TAG, "process timeline panel hidden")
    }
}
