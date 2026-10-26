package com.rokidlab.phone.glasses

import android.content.Context
import android.os.Handler
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LocalOcr
import kotlinx.coroutines.CoroutineScope

/**
 * 「拍照问 AI」流程编排器（从 CxrLHiRokidSession 拆出）。
 *
 * 职责：拍照 → **图像理解（多模态）或本地 OCR** → 知识库检索（RAG）→ OpenAI 兼容模型
 * 生成答案 → 答案经会话层发回眼镜显示 + tts_play 播报；维护进行中标志与默认 UI 回调。
 *
 * **两条识别路径**（2026-09-19 新增，由 [imageInputDecisionProvider] 决定）：
 * - 走 OCR（默认，也是视觉路失败时的回退）：本地 OCR（PP-OCRv4）把图转成文字再交给模型。
 *   不依赖模型能力，但只留下文字 —— 版式、图形、颜色全部丢失。
 * - 走视觉：JPEG 以 `data:image/jpeg;base64,…` 直接作为 user 消息的 image_url 分片
 *   发给模型，保留完整视觉信息。**能否生效取决于当前模型是否支持图像输入** ——
 *   服务端拒绝时自动回退 OCR 重试，不让用户卡在失败态。
 *
 * ⚠️ 2026-09-19 `llm` 接缝化：原来这里只拿到一个 `Boolean`（"用户开没开图像理解开关"），
 * 于是「用户没开」和「开了但模型根本不支持」被压成同一件事 —— 后者静默走 OCR，
 * 用户明明开了开关却不知道功能其实用不了。现在改为三态决策
 * （[com.rokidlab.phone.ai.llm.ImageInputDecision]），"模型不支持"这一支会把原因显示出来。
 *
 * 传输（拍照/AI 下行/连接）由会话层注入，本类只编排流程与阶段状态。
 */
internal class PhotoQuizFlow(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val mainHandler: Handler,
    /** 会话层拍照入口（width, height, quality, onPhoto, onError） */
    private val takePhoto: (Int, Int, Int, (ByteArray) -> Unit, (String) -> Unit) -> Unit,
    /** 会话层 AI 问答发送（question, contextText, instruction, imageBase64, onResult, onReply,
     *  onGeneration），固定 skipTtsAudioFinished=true + recordHistory=false（一次性答题语义） */
    private val sendAiQuestion: (
        question: String,
        contextText: String?,
        instruction: String?,
        imageBase64: String?,
        onResult: (Boolean, String?) -> Unit,
        onReply: (String) -> Unit,
        /** 代际 bump 的同步回调（2026-10-18 复审修）：genHolder 的唯一回填时机，见 askWithVision */
        onGeneration: (Long) -> Unit,
    ) -> Long,
    /** 设置页填写的答题指令（如「只显示答案」），空串视为无 */
    private val quizInstructionProvider: () -> String,
    /**
     * 当前 AI 代际（2026-10-17 审计修）。
     *
     * OCR 回退前的"我是否还是最新请求"判据：vision 发送拿到代际 [sendAiQuestion] 的返回值，
     * 回退前比对 `currentAiGeneration() != gen` —— 已被用户更新的请求抢占时**绝不重问**，
     * 否则回退的再次 bump 会把用户刚发起的请求挤掉（"我的消息既没回复也没报错"）。
     */
    private val currentAiGeneration: () -> Long,
    /**
     * AI 是否正在生成（2026-10-17 审计修，拍照入口互斥）。
     *
     * 两轮并发（答题 × 打字/语音）会共用同一个 `traceAnchorId`：过程卡互串、
     * 答案并进别人的气泡。与打字路径的差别：打字是"最新输入赢"，拍照答题是
     * 一次性任务、被抢占后没有"继续"的恢复路径，所以入口直接让位。
     */
    private val isAiBusy: () -> Boolean,
    /**
     * 本次拍照该走哪条识别路径（每次拍照现读，切换即生效）：
     * 由会话层根据「用户开关 + 当前模型能力」在 [com.rokidlab.phone.ai.llm.LlmRegistry] 里问出来。
     */
    private val imageInputDecisionProvider: () -> com.rokidlab.phone.ai.llm.ImageInputDecision =
        { com.rokidlab.phone.ai.llm.ImageInputDecision.SwitchOff },
) {
    companion object {
        private const val TAG = "PhotoQuizFlow"

        /**
         * 拍照答题流程的**终止态**阶段（2026-10-17 收全集合）：
         * 走到这些阶段本轮已结束（失败 / OCR 不可用 / 没识别到文字），
         * UI 侧据此结清进行中标志与过程卡上的转圈点。
         *
         * 为什么必须收全：旧判据（聊天页两处）只认「拍照失败 + 没识别到文字」，
         * 漏掉 native 不可用 / 模型未就绪 —— 那两条路本类已把 [inFlight] 复位，
         * 聊天页的 photoAsking 却永不置回，按钮从此点不动。
         */
        fun isTerminalStage(resId: Int): Boolean =
            resId == R.string.chat_photo_failed ||
                resId == R.string.chat_ocr_empty ||
                resId == R.string.chat_ocr_native_unavailable ||
                resId == R.string.chat_ocr_model_unavailable

        /**
         * 跨入口去重窗（2026-10-17 二轮审计补）：按键答题有两条触发通道
         * （custom cmd PHOTO_ASK_CMD + RFCOMM PHOTO_ASK_MARKER），链路抖动时
         * 两路都可能到达。2.5s ≈ 一次按键事件的自然抖动上界，也小于一次拍照流程的
         * 任何真实间隔 —— 窗口内的第二次触发按"没发生"处理，而不是当错误回。
         */
        private const val DEDUP_WINDOW_MS = 2_500L
    }

    /**
     * 流程进行中标志（防止按键/按钮重复触发）。
     *
     * ★ 2026-10-17 审计修：改用 CAS。改造前是裸 check-then-act
     * （`if (inProgress) return; inProgress = true`）—— 镜腿按键线程与手机
     * 按钮线程可同时进入，双跑（两次拍照、两轮答题、后者把前者的
     * [replyDelivered] 复位）。项目里同类模式（ToolGateway 占位）均已按
     * 「语义不能靠竞态运气」修掉，这里对齐。
     */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次 start 时刻（跨入口去重窗判据，见 [DEDUP_WINDOW_MS]） */
    private var lastStartMs = 0L

    /**
     * 本轮答案是否已通过 onReply 交付（2026-10-17，vision 回退 OCR 的去重判据）。
     *
     * onResult(false) 可能在 onReply **之后**才到（下行断开时模型已吐出答案）——
     * 不看这个标志就会回退 OCR 重问一遍，同一张贴出现两个答案。
     */
    @Volatile
    private var replyDelivered = false

    /** 流程是否进行中（App 级生成门禁的数据源，见 ChatStateHolder.photoQuizActiveProvider） */
    fun isActive(): Boolean = inFlight.get()

    /**
     * 「拍照问 AI」默认 UI 回调（乐奇聊天界面注册）。
     * 镜腿按键 / 自定义指令触发的 start() 不带显式回调，使用此处注册的回调
     * 在聊天界面展示流程气泡、识别文字与最终答案。
     */
    @Volatile
    private var stageCb: (Int) -> Unit = {}
    @Volatile
    private var stageTextCb: (String) -> Unit = {}
    @Volatile
    private var textCb: (String) -> Unit = {}
    @Volatile
    private var replyCb: (String) -> Unit = {}

    fun setUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) {
        stageCb = onStage
        textCb = onText
        replyCb = onReply
        stageTextCb = onStageText
    }

    /**
     * 「拍照问 AI」全流程（镜腿按键 / 手机端按钮共用入口）：
     * 眼镜拍照 → 识别（多模态看图 或 本地 OCR）→ 知识库检索（RAG）→ AI 生成答案
     * → 答案经 Ai 通道发回眼镜显示 + tts_play 语音播报。
     *
     * @param onStage 阶段状态回调（参数为 strings.xml 资源 id，UI 层可展示流程气泡）
     * @param onStageText 动态文本阶段回调（如 OCR 模型下载百分比），UI 层原地更新同一条状态气泡
     * @param onReply 最终答案回调（同时已发送到眼镜显示+播报）
     * @return true = 本轮已启动；false = 被让位（AI 忙 / 已在答题中），**不会**调用任何回调，
     *   调用方据此自行复位自己的"进行中"镜像（2026-10-17：聊天页按钮的 photoAsking
     *   曾经在这里永久卡死 —— start 静默 return 不回调，按钮以为还在答题）。
     */
    fun start(
        onStage: (Int) -> Unit = stageCb,
        onText: (String) -> Unit = textCb,
        onReply: (String) -> Unit = replyCb,
        onStageText: (String) -> Unit = stageTextCb,
    ): Boolean {
        // AI 忙时不让步拍照入口（2026-10-17 审计修）：两轮并发会共用同一个
        // traceAnchorId（过程卡互串、答案并进别人的气泡）。
        if (isAiBusy()) {
            Log.i(TAG, "start: AI busy, skip photo ask")
            return false
        }
        // 跨入口去重（先于 inFlight CAS）：两条触发通道双到达时，第二次在这里静默退场
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastStartMs < DEDUP_WINDOW_MS) {
            Log.i(TAG, "start: dedup window (${nowMs - lastStartMs}ms since last start), skip")
            return false
        }
        lastStartMs = nowMs
        if (!inFlight.compareAndSet(false, true)) {
            Log.i(TAG, "start: already in progress, skip")
            return false
        }
        replyDelivered = false
        // 全链路起点：记录开始时间，各阶段打印相对耗时（拍照/识别/KB/AI），定位「出答案慢」
        val askStartMs = System.currentTimeMillis()
        Log.i(TAG, "start: BEGIN, trigger=photo ask")
        onStage(R.string.chat_photo_status)

        // takePhoto 必须在 try 内：它内部会走 PhotoQuizService → CXRLink.setCXRImageCbk /
        // takePhoto（SDK 状态非法时同步抛异常，那条路径没有任何 try/catch）。
        // 原先它在 try 之外，异常会沿 start() 逃逸到调用线程（AsrBridgeCoordinator 的
        // Thread{ onPhotoAsk() } 或 appScope.launch），成为未捕获异常直接崩进程。
        try {
            takePhoto(
                1024,
                768,
                80,
                { jpeg ->
                    Thread {
                        handlePhoto(jpeg, askStartMs, onStage, onText, onReply, onStageText)
                    }.apply { name = "photo-quiz"; isDaemon = true }.start()
                },
                { err ->
                    Log.e(TAG, "photoAsk photo error: $err")
                    inFlight.set(false)
                    mainHandler.post { onStage(R.string.chat_photo_failed) }
                },
            )
            // 拍照请求已异步发出：归宿是 onPhoto（→ handlePhoto）或 onError，
            // 两路都会复位 inFlight，此处不能提前 return false。
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "startPhotoAsk: takePhoto threw synchronously", e)
            inFlight.set(false)
            mainHandler.post { onStage(R.string.chat_photo_failed) }
            return false
        }
    }

    /**
     * 拿到照片后的分岔：按 [imageInputDecisionProvider] 的结论决定走视觉还是走本地 OCR。
     *
     * 必须捕 Throwable：本线程是未捕获异常的终点（从 takePhoto 回调起的裸 Thread），
     * Error 逃逸即崩进程；且必须复位 [inFlight]，否则拍照答题入口永久失效。
     */
    private fun handlePhoto(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        try {
            when (val decision = imageInputDecisionProvider()) {
                is com.rokidlab.phone.ai.llm.ImageInputDecision.Allow ->
                    askWithImage(jpeg, askStartMs, onStage, onText, onReply, onStageText)

                com.rokidlab.phone.ai.llm.ImageInputDecision.SwitchOff ->
                    // 用户自己没开这个功能，走 OCR 不需要解释
                    askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)

                is com.rokidlab.phone.ai.llm.ImageInputDecision.Unsupported -> {
                    // 用户开了开关，但能力已确认不支持 —— 必须说出来，否则他以为功能坏了。
                    // 先发出这条状态文案再走 OCR（同一位置原地更新，不额外占一条气泡）。
                    Log.i(
                        TAG,
                        "photoAsk: image input unsupported by model " +
                            "(known=${decision.capabilities.imageKnown}), falling back to OCR",
                    )
                    mainHandler.post {
                        onStageText(appContext.getString(R.string.chat_photo_vision_unsupported))
                    }
                    askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "photoAsk failed", e)
            inFlight.set(false)
            mainHandler.post { onStage(R.string.chat_photo_failed) }
        }
    }

    /**
     * 多模态路径：图片直接进模型（跳过 OCR）。
     *
     * 失败时**自动回退 OCR**。回退而不是报错，是因为这里最常见的失败原因是
     * "当前配置的模型不支持图像输入"，而那属于配置问题不是任务失败 ——
     * 回退后用户照样能拿到答案，只是精度退化为纯文字。
     */
    private fun askWithImage(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        Log.i(TAG, "photoAsk: vision path, jpeg=${jpeg.size}B")
        val base64 = runCatching { Base64.encodeToString(jpeg, Base64.NO_WRAP) }.getOrNull()
        if (base64.isNullOrBlank()) {
            // 编码失败极少见，静默退 OCR：此时再弹一次「图像理解失败」对用户没有信息量
            Log.w(TAG, "photoAsk: base64 encode failed, fallback to OCR")
            askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)
            return
        }
        val instruction = quizInstructionProvider().ifBlank { null }
        val question = appContext.getString(R.string.chat_photo_vision_question)
        mainHandler.post { onText(question) }
        mainHandler.post { onStage(R.string.chat_ai_status) }
        val tAi = System.currentTimeMillis()
        // 返回值 = 本轮抢占代际（OCR 回退前的"我是否还是最新请求"判据）。
        // ★ 2026-10-18 复审修：genHolder 改经 onGeneration 回调回填。原实现「调用返回后
        //   立即回填」依赖一个不成立的假设 —— onResult 确实全部走 mainHandler **post**，
        //   但调用方在**后台线程**：post 一旦入队，主线程可能在本调用返回前就把它执行掉
        //   （读 to genHolder[0]=0），被误判成"已被抢占"而跳过 OCR 回退。
        //   onGeneration 在 bump 处**同步**触发，必然早于任何 onResult，竞态就此关闭。
        val genHolder = LongArray(1)
        sendAiQuestion(
            question,
            null,
            instruction,
            base64,
            { success, err ->
                if (success) {
                    inFlight.set(false)
                    Log.i(
                        TAG,
                        "photoAsk: vision send done after ${System.currentTimeMillis() - tAi}ms " +
                            "(total ${System.currentTimeMillis() - askStartMs}ms)"
                    )
                } else if (replyDelivered) {
                    // ★ 2026-10-17（「有几率过程走完没回复」同族缺陷：答案两遍）：
                    //   onReply 可能**先于** onResult(false) 到达 —— 下行抖动时模型
                    //   已把答案吐了出来。此时回退 OCR 重问，同一张贴会拿到两个答案
                    //   （先交付的 + 重问的）。已交付过就不再重问，只收尾。
                    Log.i(TAG, "photoAsk: vision failed after reply delivered, skip OCR fallback")
                    inFlight.set(false)
                } else if (currentAiGeneration() != genHolder[0]) {
                    // ★ 2026-10-17 审计修：已被用户更新的请求抢占 —— 绝不回退 OCR 重问。
                    //   回退会再次 bump 代际，把用户刚发起的请求挤掉（表现为"我的消息
                    //   既没回复也没报错"）。发终态让 UI 自愈（photoAsking 复位 +
                    //   状态行结清），不重问。
                    Log.i(TAG, "photoAsk: vision superseded by newer request (gen=${genHolder[0]}), skip OCR fallback")
                    inFlight.set(false)
                    mainHandler.post { onStage(R.string.chat_photo_failed) }
                } else {
                    Log.w(TAG, "photoAsk: vision failed ($err), falling back to OCR")
                    mainHandler.post {
                        onStageText(appContext.getString(R.string.chat_photo_vision_fallback))
                    }
                    // 回退路径仍在进行中：inFlight 保持 true，由 OCR 那条路负责复位
                    // ★ 2026-10-17 审计修：onResult 在**主线程**回调（AiConversationService
                    //   全部 mainHandler.post），而 OCR 回退含解码/15MB 模型下载/识别/
                    //   检索/生成（runBlocking join 数十秒）—— 必须自切后台线程，
                    //   否则主线程一路阻塞，直接 ANR（与 start() 里 handlePhoto 同款形态）。
                    Thread {
                        askWithOcr(jpeg, System.currentTimeMillis(), onStage, onText, onReply, onStageText)
                    }.apply { name = "photo-quiz-ocr-fallback"; isDaemon = true }.start()
                }
            },
            { reply ->
                replyDelivered = true
                Log.i(TAG, "photoAsk: vision reply (${reply.length} chars)")
                onReply(reply)
            },
            { gen -> genHolder[0] = gen },
        )
    }

    /** 本地 OCR 路径（默认路径，也是多模态失败时的回退路径） */
    private fun askWithOcr(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        Log.i(TAG, "photoAsk: OCR path, jpeg=${jpeg.size}B")
        mainHandler.post { onStage(R.string.chat_ocr_status) }
        // 本地 OCR 识别题目文字（模型不内置 APK，首次使用先从 Gitee 下载约 15.4MB）
        val tOcr = System.currentTimeMillis()
        val text = runCatching {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: return@runCatching ""
            try {
                // 模型缺失时 onProgress 才会回调（模型已存在则静默继续）。
                // 下载不再静默：按 10% 档位把进度推到聊天窗口，原地更新同一条状态气泡，
                // 让用户明确知道「正在下载模型」而不是以为 App 卡死。
                val downloadBucket = java.util.concurrent.atomic.AtomicInteger(-1)
                LocalOcr.ensureInit(appContext) { pct ->
                    val bucket = (pct / 10) * 10
                    if (downloadBucket.getAndSet(bucket) != bucket) {
                        Log.i(TAG, "photoAsk: OCR model downloading $bucket%")
                        mainHandler.post {
                            onStageText(appContext.getString(R.string.chat_ocr_downloading, bucket))
                        }
                    }
                }
                // 发生过下载：上面的「正在识别…」状态气泡已被下载进度覆盖，
                // 下载完成后补回一条识别阶段提示，再开始识别。
                if (downloadBucket.get() >= 0) {
                    mainHandler.post { onStage(R.string.chat_ocr_status) }
                }
                LocalOcr.recognize(appContext, bmp)
            } finally {
                bmp.recycle()
            }
        }.getOrDefault("").trim()
        Log.i(TAG, "photoAsk: OCR done in ${System.currentTimeMillis() - tOcr}ms -> ${text.take(50)}")
        if (text.isEmpty()) {
            // 区分降级原因：设备不支持 native / 模型未就绪（下载失败）/ 确实没识别到文字
            val stageRes = when {
                LocalOcr.nativeUnavailable -> R.string.chat_ocr_native_unavailable
                LocalOcr.modelsUnavailable -> R.string.chat_ocr_model_unavailable
                else -> R.string.chat_ocr_empty
            }
            Log.w(
                TAG,
                "photoAsk: OCR result empty, abort (nativeUnavailable=${LocalOcr.nativeUnavailable}, " +
                    "modelsUnavailable=${LocalOcr.modelsUnavailable})"
            )
            inFlight.set(false)
            mainHandler.post { onStage(stageRes) }
            return
        }
        // 把识别出的文字回调给 UI（作为「用户消息」气泡展示）
        mainHandler.post { onText(text) }
        // 知识库检索相关资料（RAG）
        mainHandler.post { onStage(R.string.chat_kb_status) }
        val tKb = System.currentTimeMillis()
        val kbText = KnowledgeBase
            .searchHits(appContext, text, topK = 3)
            .joinToString("\n\n") { hit ->
                // 来源标注：让答案可溯源（出自哪份文档的哪一块）
                "（《${hit.docName}》第${hit.chunkIdx + 1}块）${hit.text}"
            }
        Log.i(TAG, "photoAsk: KB search done in ${System.currentTimeMillis() - tKb}ms, hits=${kbText.length} chars")
        // 生成答案并发送到眼镜（显示 + 播报）
        mainHandler.post { onStage(R.string.chat_ai_status) }
        val tAi = System.currentTimeMillis()
        sendAiQuestion(
            text,
            kbText.ifBlank { null },
            quizInstructionProvider().ifBlank { null },
            null,
            { success, err ->
                inFlight.set(false)
                Log.i(
                    TAG,
                    "photoAsk: AI send onResult success=$success err=$err after " +
                        "${System.currentTimeMillis() - tAi}ms (total ${System.currentTimeMillis() - askStartMs}ms)"
                )
                if (!success && !replyDelivered) {
                    // 失败且无 onReply：通知 UI 复位 photoAsking（否则拍照问 AI 入口永久失效）。
                    // ★ replyDelivered 时不再报失败（2026-10-17）：答案已在用户眼前，
                    //   晚到的 onResult(false) 再盖一个「拍照失败」是撒谎。
                    mainHandler.post { onStage(R.string.chat_photo_failed) }
                }
            },
            { reply ->
                replyDelivered = true
                Log.i(TAG, "photoAsk: AI reply received (${reply.length} chars) after " +
                    "${System.currentTimeMillis() - askStartMs}ms total")
                onReply(reply)
            },
            { },
        )
    }
}
