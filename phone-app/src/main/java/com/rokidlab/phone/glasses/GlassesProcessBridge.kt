package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「过程时间线上眼镜」的手机侧下行桥（v4.7 新增）。
 *
 * 把「过程」全局汇聚点（[com.rokidlab.phone.domain.AiConversationService.setAgentTraceSink]，
 * App 启动时由 `LabApplication.setCxrL` 注册）收到的每个 [AgentStep] 转成紧凑帧，
 * 经 CXR 自定义频道下行到眼镜端，由 RokidLink 的 `ProcessTimelineController`
 * 渲染成贴底小卡（单步聚焦 / 同 key 覆盖 / 确认态高亮 / 轮次结束收起）。
 *
 * ## 与既有两条通道的分工（改这条链前必读，三者的边界是刻意的）
 * 1. **Ai 频道单行进度帧**（`AiConversationService.sendGlassesProgress`，形如
 *    `→ 正在查询天气…`）：混在官方对话正文里的旧形态。**眼镜端带
 *    [LinkProtocol.Cap.PROCESS_TIMELINE] 时手机侧不再发它** —— 全部 5 个 AI 过程
 *    发出点统一走 `AiConversationService.sendGlassesProcessLine` 门禁（2026-10-14
 *    用户实拍反馈「√ 获取位置」仍混在正文里，根因是当时只堵了 runToolWorker 一处），
 *    旧眼镜端（无该 Cap 位）不收本文件的 topic ⇒ 继续吃单行帧，**旧路径零改动**。
 * 2. **手机端过程面板**（`ChatStateHolder.upsertTrace`）：数据面与本桥**完全同源**
 *    （同一个 sink lambda 转发，见 `LabApplication.setCxrL`），不存在第二套采集逻辑。
 *    步骤标题也保持同源：本桥 [labelOf] 直接取 phone-app 的 `R.string.chat_trace_*`
 *    （手机 UI 同一资源），且与 ChatBubble 一样**整体剔除 PLAN 步骤**。
 * 3. **本桥**：只做「序列化 + 节流 + 下行」，不做任何显示决策。
 *
 * ## 节流模型（为什么是 120ms 批量窗口）
 * 步骤帧的真正高频源是 `AgentStep.statusProgress`（拍照答题 OCR 百分比，key 恒为
 * `status:auto`）与自省/框架步骤连发。固定窗口 + **同 key 在窗口内覆盖**（map 语义）
 * 把任意突发合并为 ≤1 帧/120ms：蓝牙串行纪律（16KB 图表突发曾挤丢 tts_play 的教训）
 * 要求下行小且匀速，这里每帧 ≤6 字段、总量 <300B。
 * 代价是步骤显示最多延迟 120ms —— 相对工具执行以秒计，用户无感。
 *
 * ## 顺序保证
 * [finish]（轮次结束）必须先于它 flush 掉在途帧再发 done 帧：两者都排到同一单线程
 * executor，FIFO 即顺序；[finish] 内部显式 flushNow 覆盖「forward 的延迟 flush 还没跑」
 * 的窗口 ⇒ done 帧恒为本轮最后一帧。
 *
 * ## 门禁（三态，缺任一不发）
 * - `GlassesHandshake.supports(Cap.PROCESS_TIMELINE) != true`：眼镜端旧版/未握手 ——
 *   **静默跳过**。过程帧是增强体验，绝不能像 chart 推送那样同步 sleep 等握手
 *   （那会阻塞 AI 执行线程）；冷启头几秒的丢帧由「用户还没开始对话」天然覆盖。
 * - `liveSession()` 为空 / `cxrLink` 为空：没连眼镜 —— 同样静默。
 *   注意这与「没连眼镜就没有过程显示」是同一件事，不存在「说了没显示」：
 *   眼镜端根本没在场。
 */
object GlassesProcessBridge {
    private const val TAG = "GlassesProcessBridge"

    /**
     * 批量窗口：同 key 覆盖 + 定时 flush。
     *
     * 2026-10-14 用户实拍反馈「文字刷新不是实时的」⇒ 300ms 砍到 120ms：
     * 原值下 RUNNING→OK 的状态切换要等一个完整窗口，用户视角就是"跳一拍"。
     * 仍保留窗口是因为步骤帧的真正高频源是 `AgentStep.statusProgress`
     * （拍照答题 OCR 百分比，key 恒为 `status:auto`）—— 逐帧直发会把蓝牙
     * 串行链路打爆（16KB 图表突发曾挤丢 tts_play 的教训）。120ms 对以秒计的
     * 工具执行无感知损失，对状态切换已接近实时。
     */
    private const val FLUSH_DELAY_MS = 120L

    /** detail 手机侧截断长度：窄卡一行 ≈7 个汉字，眼镜端还会按状态再截（见 ProcessTimelineController） */
    private const val MAX_DETAIL = 40

    /**
     * 待发帧（key → 帧；同 key 在窗口内覆盖）。forward 多线程调用，全部访问走 [pendingLock]。
     *
     * ⚠️ **必须是有序 map（LinkedHashMap）**，这不是随手选的容器：
     * [flushNow] 按 `values` 的迭代序**逐条发送**，而同一 120ms 窗口里常有多帧
     * （思考完毕 + 工具启动 / 多个并行工具）。[HashMap] 的迭代序由哈希定、与到达序
     * 无关（工具 key 还是 UUID ⇒ 每轮顺序都不同）⇒ 窗口内帧被**乱序下发**，眼镜端
     * 单槽卡片后到先显：用户视角是过程卡「跳回」上一步，且可能**停在旧步骤**直到
     * 下一个窗口才被纠正（快轮次里就是永久显示错的那一步）。
     * 有序 map 保证「先进先发 = 到达序即发送序」；同 key 覆盖时 put 保留原插入位，
     * 语义不变（窗口内只发最后一态）。
     */
    private val pending = LinkedHashMap<String, AiChannel.ProcessStepFrame>()
    private val pendingLock = Any()

    private val flushScheduled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "glasses-process-bridge").apply { isDaemon = true }
    }

    private var ctx: Context? = null

    /** App 级注册一次（`LabApplication.setCxrL`）。context 仅用于工具名→显示名解析。 */
    fun attach(context: Context) {
        ctx = context.applicationContext
    }

    /**
     * 转发一条过程步骤（由 `LabApplication` 的 trace sink lambda 调用，可能在任意线程）。
     *
     * 门禁失败（旧眼镜端 / 未连接）时静默返回 —— 旧眼镜端继续走 Ai 频道单行帧，
     * 两条通道不会同时亮（详见类注释分工）。
     */
    fun forward(step: AgentStep) {
        if (GlassesHandshake.supports(LinkProtocol.Cap.PROCESS_TIMELINE) != true) return
        val frame = toFrame(step) ?: return
        synchronized(pendingLock) {
            // 同 key 覆盖：RUNNING→OK、进度百分比原地更新，窗口内只发最后一态
            pending[frame.key] = frame
        }
        // AWAITING_CONFIRM（等待用户点头/摇头，可能阻塞 40s）立即 flush：
        // 它是唯一"用户必须在场决策"的帧，等窗口=让用户对着一秒前的旧状态做决定。
        if (frame.state == AgentStep.State.AWAITING_CONFIRM.name) {
            executor.execute { flushNow() }
        } else {
            scheduleFlush()
        }
    }

    /**
     * 轮次结束（由 trace finish sink 调用）。先 flush 在途帧，再发 done 帧，
     * 眼镜端据此把时间线标记终态并延迟收起。
     *
     * @param failed true = 本轮有失败/被取消（done 帧带 "1"，眼镜端头部文案据此变化）
     */
    fun finish(failed: Boolean) {
        if (GlassesHandshake.supports(LinkProtocol.Cap.PROCESS_TIMELINE) != true) return
        executor.execute {
            flushNow()
            send(AiChannel.TOPIC_PROCESS_DONE, AiChannel.encodeProcessDone(failed))
            Log.i(TAG, "process done sent (failed=$failed)")
        }
    }

    private fun toFrame(step: AgentStep): AiChannel.ProcessStepFrame? {
        // PLAN 与手机端过程卡片保持一致：ChatBubble 把 PLAN 步骤从过程时间线整体剔除
        // （计划另有输入框上方 PlanChip 入口，见 ChatBubble.kt 的 traceSteps 过滤），
        // 眼镜端也不显示 —— 否则同一件计划在两端的「过程」里一有一无。
        if (step.kind == AgentStep.Kind.PLAN) return null
        val label = labelOf(step)
        if (label.isEmpty()) return null
        return AiChannel.ProcessStepFrame(
            key = step.key,
            label = label,
            detail = detailOf(step),
            state = step.state.name,
            // 步骤类型：眼镜端据此画过程卡片里的类型图标（与手机过程卡片同一套字形）。
            // 传枚举名而非序号 —— 两端各映射字形，枚举增删不会让对端读到错位的类型。
            kind = step.kind.name,
        )
    }

    /**
     * 步骤 → 眼镜端第二行摘要（[AgentStep.detail] 的**精选**，不是原样透传）。
     *
     * ⚠️ 为什么必须精选：detail 的设计目标是「手机端过程卡片标题下的一行预览」，
     * 各 kind 往里灌的内容对眼镜 overlay 全是噪声——
     * - THINKING 灌的是**模型推理文本**（思维链前几句），透传上去就是半句内心独白；
     * - TOOL 终态灌的是 `参数 → 结果`，**结果是给模型的原文**（JSON/长文本）；
     * - MEMORY / REFLECT 灌的是注入摘要与自省长文。
     *
     * 眼镜端单绿屏一行 ≈34 列，只放得下「正在操作什么」：
     * - TOOL 仅 RUNNING（= 参数摘要，结果还没产生）与 AWAITING_CONFIRM（= 完整参数，
     *   点头前必须看见）带 detail；终态打 √ 即可，结果句留给手机端点开看；
     * - FRAMEWORK 是全表唯一 title 为空的 kind（App 本地流程状态），没 detail 只剩
     *   「系统处理」四个字 ⇒ 保留；
     * - PLAN 的 detail 是 `2/5 · 当前步骤` 进度摘要，短且正是时间线要的 ⇒ 保留；
     * - 其余 kind（THINKING/KNOWLEDGE/COMPRESS/SUMMARY/MEMORY/REFLECT）一律不带。
     */
    private fun detailOf(step: AgentStep): String = when (step.kind) {
        AgentStep.Kind.TOOL -> when (step.state) {
            AgentStep.State.RUNNING, AgentStep.State.AWAITING_CONFIRM ->
                step.detail.take(MAX_DETAIL)
            else -> ""
        }
        AgentStep.Kind.FRAMEWORK, AgentStep.Kind.PLAN -> step.detail.take(MAX_DETAIL)
        else -> ""
    }

    /**
     * 步骤 → 眼镜端标题（2026-10-14 用户要求：与手机端过程卡片**同一套标题**）。
     *
     * 手机端 `ChatBubble.stepText/stepLabel` 的映射原样复刻，且文案直接取
     * phone-app 的 `R.string.chat_trace_*`（与手机 UI 同一资源 —— 改文案不会两端漂移）；
     * ctx 未附着（仅理论可能：`attach` 在 App 启动即执行）时退回硬编码兜底。
     *
     * - TOOL：`ToolRegistry.displayNameOf`（工具设置页同一套名字；查不到回退 wire name，
     *   宁可显示英文工具名也不编一个中文动作）。标题恒非空 ⇒ 任何状态都显示工具名，
     *   与手机端一致（确认/取消态靠 detail 与圆点表达，不改写标题）。
     * - PLAN：**不进帧**（手机端过程卡片也剔除，见 [toFrame]）。
     */
    private fun labelOf(step: AgentStep): String {
        val c = ctx
        if (step.kind == AgentStep.Kind.TOOL) {
            // 防御性分支：title 按约定恒非空（AgentStep.tool），空则与手机端
            // stepLabel 的兜底文案一致（chat_trace_tool_running）
            if (step.title.isBlank() || c == null) {
                return str(c, R.string.chat_trace_tool_running, "执行中…")
            }
            return runCatching {
                com.rokidlab.phone.ai.ToolRegistry.displayNameOf(step.title, c)
            }.getOrDefault(step.title)
        }
        return when (step.kind) {
            AgentStep.Kind.THINKING ->
                if (step.state == AgentStep.State.RUNNING) str(c, R.string.chat_trace_thinking, "正在思考")
                else str(c, R.string.chat_trace_thought, "思考完毕")
            AgentStep.Kind.KNOWLEDGE ->
                if (step.state == AgentStep.State.RUNNING) str(c, R.string.chat_trace_kb_running, "正在检索知识库")
                else str(c, R.string.chat_trace_kb_done, "已检索知识库")
            AgentStep.Kind.COMPRESS -> str(c, R.string.chat_trace_compress_done, "已压缩早期对话以继续回答")
            AgentStep.Kind.SUMMARY ->
                if (step.state == AgentStep.State.RUNNING) str(c, R.string.chat_trace_summary_running, "正在收尾写入文件")
                else str(c, R.string.chat_trace_summary_done, "收尾完成")
            AgentStep.Kind.FRAMEWORK -> str(c, R.string.chat_trace_framework, "框架处理")
            AgentStep.Kind.MEMORY -> str(c, R.string.chat_trace_memory, "已注入上下文")
            AgentStep.Kind.REFLECT -> str(c, R.string.chat_trace_reflect, "自我检查")
            // TOOL 在上方已提前 return；保留分支只为 when 穷尽（防御性不可达）
            AgentStep.Kind.TOOL, AgentStep.Kind.PLAN -> ""
        }
    }

    /** 取手机端过程卡片的同源文案；ctx 缺失（仅理论可能）时用硬编码兜底。 */
    private fun str(c: Context?, res: Int, fallback: String): String =
        c?.getString(res) ?: fallback

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            executor.schedule({
                flushScheduled.set(false)
                flushNow()
            }, FLUSH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 取出当前窗口内全部帧（同 key 已覆盖）并逐条发送。
     *
     * 收集序 = 发送序 = 到达序（[pending] 是有序 map；不变式由
     * `GlassesProcessBridgeOrderTest` 钉住 —— 换回无序 map 该测试必红）。
     */
    private fun flushNow() {
        for (f in collectPending()) {
            Log.i(TAG, "step↓ key=${f.key} state=${f.state} label=${f.label} detail=${f.detail}")
            send(AiChannel.TOPIC_PROCESS_STEP, AiChannel.encodeProcessStep(f.key, f.label, f.detail, f.state, f.kind))
        }
    }

    /**
     * 取出并清空窗口内全部帧。单测经此 seam 断言「到达序即发送序」——
     * 这是 [pending] 必须用有序 map 的唯一可观测出口。
     */
    internal fun collectPending(): List<AiChannel.ProcessStepFrame> =
        synchronized(pendingLock) {
            if (pending.isEmpty()) emptyList()
            else ArrayList(pending.values).also { pending.clear() }
        }

    /**
     * 单帧下行。与 AI 主链路（TTS_Result / tts_play / 单行进度帧）共用
     * [CxrLHiRokidSession.aiCmdLock] 串行 —— 锁顺序纪律见该字段注释：
     * 本桥只拿这一把锁，无嵌套，不会死锁。
     */
    private fun send(topic: String, fields: List<String>) {
        val session = GlassToolConfirmChannel.global.liveSession()
        if (session == null) {
            Log.d(TAG, "send skipped: no live session")
            return
        }
        val link = session.cxrLink
        if (link == null) {
            Log.d(TAG, "send skipped: cxrLink null")
            return
        }
        runCatching {
            synchronized(session.aiCmdLock) {
                val caps = Caps()
                for (f in fields) caps.write(f)
                session.rawSendCustomCmd(link, topic, caps)
            }
        }.onFailure { Log.w(TAG, "send $topic failed: ${it.message}") }
    }
}
