package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.glasses.PhotoQuizFlow
import com.rokidlab.phone.store.ChatStateHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「拍照问 AI」域服务（Phase 3 三轮：从 CxrLHiRokidSession 逐字迁出，session-handle 门面模式）。
 *
 * 持有 PhotoQuizFlow 编排器与拍照请求超时任务；Session 保留 public 门面
 * （setPhotoAskUiCallbacks / takeGlassesPhoto / startPhotoAsk），调用方零改动。
 * TAG 沿用 CxrLHiRokidSession 以保持既有 logcat 过滤不变。
 */
internal class PhotoQuizService(private val session: CxrLHiRokidSession) {
    companion object {
        private const val TAG = "CxrLHiRokidSession"
    }

    private var photoRequestTimeoutJob: Job? = null

    /**
     * 拍照请求在途标志（2026-10-17 审计修）。
     *
     * 改造前 [requestPhotoFromLink] 靠**单实例字段** `photoRequestTimeoutJob` +
     * 单实例 `link.setCXRImageCbk`：两个并发取图请求（答题在途 + AI 工具
     * look_at_view，或镜腿双通道赛过入口检查）会让后到者**覆盖**先到者的
     * 回调与超时任务，先到者永远等不到 onPhoto/onError ⇒ 答题 inFlight 永真
     * ⇒ 生成门禁永久锁死（新建/切换会话全被拦）。CAS 让第二个请求直接拿到
     * 错误，从结构上消灭"后写覆盖先写"。
     */
    private val photoInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 拍照超时任务取消（Session.cleanup 统一清理入口调用） */
    fun cancelPhotoRequestTimeout() {
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = null
        // 会话清理意味着在途请求已被拆走：释放在途标志，避免后续拍照被永久拒绝
        photoInFlight.set(false)
    }

    /** 「拍照问 AI」流程编排器（拍照→OCR→RAG→AI 答题，从本类拆出） */
    private val photoQuiz = PhotoQuizFlow(
        appContext = session.appContext,
        appScope = session.appScope,
        mainHandler = session.mainHandler,
        takePhoto = { w, h, q, onPhoto, onError -> takeGlassesPhoto(w, h, q, onPhoto, onError) },
        sendAiQuestion = { question, contextText, instruction, imageBase64, onResult, onReply, onGeneration ->
            // 本轮抢占代际（OCR 回退前的"我是否还是最新请求"判据，2026-10-17 审计修）：
            // onGeneration 在 bump 之后**同步**回调，返回前 gen 必已就绪。
            // ★ 2026-10-18 复审修：代际同时转发给 Flow 侧 genHolder —— Flow 的 onResult
            //   可能先于本 lambda 返回被主线程执行掉，返回后回填会竞态（见 askWithVision 注释）。
            var gen = 0L
            session.sendAiTextMessage(
                question,
                contextText = contextText,
                // 答题完成后保留眼镜端回复显示：skipTtsAudioFinished=true 不发送
                // TTS_AudioFinished（该消息会触发官方会话 startNewTalk 重置，清掉刚显示的答案）
                skipTtsAudioFinished = true,
                // 注入设置页填写的答题指令（如「只显示答案」「给出解题步骤」）
                instruction = instruction,
                // 一次性问答且带答题指令，不记录到 Agent 会话记忆（避免污染闲聊上下文）
                recordHistory = false,
                // ★ 只读工具档（2026-10-16 用户报障修复：拍照识别后自动拨打识别到的数字）。
                //
                // 根因链：OCR/视认识别出的文字是**照片里的外部内容**，却作为 question
                // 进了完整 Agent 工具循环；照片上出现一串数字（手机号/电话号码）时，
                // 模型会把它当成"拨打指令"调 call_phone —— 而 CALL_PHONE 启动即授、
                // 无确认层，号码被直接 ACTION_CALL 拨出。用户侧现象就是
                // 「拍一下照，手机自己打起电话来」。
                //
                // 判据：这一轮的输入不是用户指令而是**不可信外部内容**，与
                // 「无人值守轮」（TimerScheduler.requestAgentReply 同款）同一性质 ——
                // 副作用动作（拨号/发短信/定时/装机）绝不能由识别内容触发。
                // 拍照问答是"问一句答一句"的一次性语义（recordHistory=false 同源），
                // 查询类工具（查时间/天气/网页/知识库/设备状态）足够覆盖答题场景。
                //
                // 双层兜底：装配侧 schemasUnattended 压根不下发 call_phone 等副作用
                // 工具；执行侧 UnattendedScopeGuard 连"模型凭记忆复述出工具名"也拦。
                readOnlyTools = true,
                onGeneration = { g ->
                    gen = g
                    onGeneration(g)
                },
                onResult = onResult,
                onReply = onReply,
                // 多模态：非空时这一轮的 user 消息带 image_url 分片（图像理解开关开启时才有值）
                imageBase64 = imageBase64,
            )
            gen
        },
        quizInstructionProvider = { session.getAiConfig().quizInstruction },
        // 识别路径决策（llm 接缝）：用户开关 + **当前模型能力**共同决定，每次拍照现读。
        // 改造前这里只传开关的布尔值，于是"开了但模型不支持"这件事在流程里无从表达，
        // 用户开了开关却总是走 OCR，还以为是功能坏了（见 ImageInputDecision 的说明）。
        imageInputDecisionProvider = {
            val enabledByUser = (session.appContext as? com.rokidlab.phone.app.LabApplication)
                ?.chatImageInputEnabled == true
            com.rokidlab.phone.ai.llm.LlmRegistry.imageInputDecision(
                cfg = session.getAiConfig(),
                ctx = session.appContext,
                enabledByUser = enabledByUser,
            )
        },
        // 当前 AI 代际（OCR 回退前的"我是否还是最新请求"判据，2026-10-17 审计修）
        currentAiGeneration = { session.currentAiGeneration() },
        // AI 忙判据（拍照入口互斥：两轮并发会共用同一个过程锚点）
        isAiBusy = { session.isAiBusy() },
    )

    init {
        registerDefaultUiCallbacks()

        // 生成门禁的第二个数据源（H1 补齐）：答题进行中是 App 级事实，
        // 不依赖聊天页的 photoAsking 镜像（组合一销毁镜像就丢，门禁跟着失效）。
        ChatStateHolder.photoQuizActiveProvider = { photoQuiz.isActive() }
    }

    /**
     * 注册 App 级默认 UI 回调（聊天页组合不在时也有落点）。
     *
     * ★ 2026-10-17 修「过程走完但没回复」主根因：旧结构里默认回调是空实现，
     *   过程与答案全靠聊天页注册；而聊天页的 onReply 又包在
     *   rememberCoroutineScope 里 —— 切走聊天 Tab 即取消，scope.launch
     *   静默永不执行。于是镜腿按键在非聊天页触发、或答题中途切走页面时，
     *   **过程卡照常全绿**（trace sink 是 App 级、settledResult 保证收尾），
     *   答案却凭空蒸发。
     *
     *   这里把默认回调落到 App 级（ChatStateHolder 各入口自行切主线程），
     *   与页面是否组合无关；聊天页打开时仍由它注册的增强版覆盖（多一份
     *   photoAsking 镜像与贴底滚动）。
     */
    private fun registerDefaultUiCallbacks() {
        photoQuiz.setUiCallbacks(
            onStage = { resId ->
                val text = session.appContext.getString(resId)
                if (PhotoQuizFlow.isTerminalStage(resId)) {
                    ChatStateHolder.finishStatusTrace(text, ok = false)
                } else {
                    ChatStateHolder.updateStatusTrace(text)
                }
            },
            onText = { text -> ChatStateHolder.add(isUser = true, content = text) },
            onReply = { reply -> ChatStateHolder.finalizeTraceReply(reply) },
            onStageText = { text -> ChatStateHolder.updateStatusTrace(text) },
        )
    }

    /**
     * 恢复 App 级默认回调（2026-10-17 审计修：聊天页离开时还原）。
     *
     * 聊天页注册的增强回调捕获了 Activity 与组合状态，离开页面不还原就会
     * 成为"最后一任"回调 —— 既泄漏一个 Activity，也让"页面级覆盖 App 级"
     * 这个结构残留（今天修掉的答案蒸发 bug 正是这个结构的极端形态）。
     */
    fun resetPhotoAskUiCallbacks() = registerDefaultUiCallbacks()

    /** 注册「拍照问 AI」流程的 UI 回调（乐奇聊天界面进入时调用，按键触发时复用展示） */
    fun setPhotoAskUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) = photoQuiz.setUiCallbacks(onStage, onText, onReply, onStageText)

    /**
     * 远程控制眼镜拍照，通过 IImageStreamCbk 回调获取 JPEG 图片字节。
     * 用于「拍照问 AI」：拍照 → 本地 OCR 识别 → 知识库检索 → DeepSeek 生成答案。
     *
     * @param width/height/quality 拍照参数（推荐 1024/768/80）
     * @param onPhoto 拍照成功，返回 JPEG 字节
     * @param onError 拍照失败原因
     */
    fun takeGlassesPhoto(
        width: Int = 1024,
        height: Int = 768,
        quality: Int = 80,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "takeGlassesPhoto($width,$height,$quality) called. session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}, session.cxrLink=${session.cxrLink != null}")

        // 快速路径: 已有连接直接拍照，拍完保持连接（乐奇聊天可继续使用）
        val link = session.cxrLink
        if (session.cxrlConnected && session.glassBtConnected && link != null) {
            Log.i(TAG, "takeGlassesPhoto: fast path, using existing CXRLink")
            requestPhotoFromLink(link, width, height, quality, onPhoto, onError, cleanupOnDone = false)
            return
        }

        // 慢速路径: 先建立连接再拍照
        Log.i(TAG, "takeGlassesPhoto: no active link, falling back to connectAndRun path")
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "takeGlassesPhoto: missing prerequisites")
            onError("missing prerequisites")
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 20_000,
                timeoutMessage = "photo request timeout",
                bindMessage = "Taking photo",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { l ->
                    // cleanupOnDone=false + resetBusyOnDone=true：拍照完成后保持 CXR 链路复用
                    // （避免每次按键重建连接 5-10s，导致「按键后出答案慢」），同时复位 busy 状态；
                    // 后续按键/聊天直接走 fast path
                    requestPhotoFromLink(l, width, height, quality, onPhoto, onError, cleanupOnDone = false, resetBusyOnDone = true)
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onError("connection failed")
                },
            ),
        )
    }

    /**
     * 同步拍一张眼镜画面（供 AI 工具 `look_at_view` 调用）。
     *
     * ⚠️ **必须在非主线程调用**：拍照结果经 `session.mainHandler.post` 投递，
     * 在**主线程**上 `await` 会自我死锁 —— 所以这里显式拒掉主线程调用而不是"让它卡住"。
     * 工具循环本身跑在 worker 线程，满足条件。
     *
     * 与「拍照问 AI」复用同一条拍照链路（同一个 [takeGlassesPhoto]），因此不会出现
     * 两套取图实现漂移；并发保护沿用 CXRLink 的"一次一个拍照请求"语义。
     *
     * @return JPEG 字节；超时 / 缺前置条件 / 链路异常一律 null（由调用方负责措辞）
     */
    fun capturePhotoBlocking(
        width: Int = 1024,
        height: Int = 768,
        quality: Int = 80,
        timeoutMs: Long = 20_000,
    ): ByteArray? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "capturePhotoBlocking called on main thread, refusing (would deadlock)")
            return null
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        takeGlassesPhoto(
            width, height, quality,
            { jpeg -> out = jpeg; latch.countDown() },
            { err ->
                Log.w(TAG, "capturePhotoBlocking error: $err")
                latch.countDown()
            },
        )
        // latch 的 countDown/await 已经建立了 happens-before，out 的可见性无需额外同步
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "capturePhotoBlocking timeout after ${timeoutMs}ms")
            return null
        }
        return out
    }

    /** 「拍照问 AI」全流程入口（无显式回调，用聊天界面注册的默认回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk() = photoQuiz.start()
    /** 「拍照问 AI」全流程入口（显式回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) = photoQuiz.start(onStage, onText, onReply, onStageText)

    private fun requestPhotoFromLink(
        link: CXRLink,
        width: Int,
        height: Int,
        quality: Int,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        cleanupOnDone: Boolean,
        /**
         * 完成后是否复位 busy（fast path 未设 busy 时为 false，避免多余 UI 刷新；
         * slow path 保持连接时需显式复位 busy）
         */
        resetBusyOnDone: Boolean = cleanupOnDone,
    ) {
        var done = false
        // 在途互斥（2026-10-17 审计修）：第二个并发请求直接拿错误，
        // 不许覆盖先到者的 imageCbk 与 15s 超时任务（那会让先到者永收回调）。
        if (!photoInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "requestPhotoFromLink: another photo request in flight, reject")
            session.mainHandler.post { onError("photo request already in flight") }
            return
        }
        fun finish(onResult: () -> Unit) {
            if (done) return
            done = true
            photoInFlight.set(false)
            photoRequestTimeoutJob?.cancel()
            photoRequestTimeoutJob = null
            session.connection.completeActiveOperation()
            if (cleanupOnDone) {
                session.cleanup()
                session.onBusyChanged(false)
            } else if (resetBusyOnDone) {
                // 保持连接复用（避免每次按键重建 CXR 链路）：复位 busy 但不断开 session.cxrLink
                session.onBusyChanged(false)
            }
            onResult()
        }

        // 超时兜底：takePhoto 返回成功但 onImageReceived/onImageError 永不到达
        // （SDK 静默失败/眼镜端场景被关闭）时，复位标志并回调错误，避免后续拍照被永久跳过
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = session.appScope.launch {
            delay(15_000)
            session.mainHandler.post {
                if (!done) {
                    Log.w(TAG, "requestPhotoFromLink: no image callback within 15s, forcing error")
                    finish { onError("photo timeout") }
                }
            }
        }

        link.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray) {
                Log.i(TAG, "onImageReceived: ${data.size} bytes")
                session.mainHandler.post { finish { onPhoto(data) } }
            }

            override fun onImageError(code: Int, message: String) {
                Log.e(TAG, "onImageError($code): $message")
                session.mainHandler.post { finish { onError("photo error($code): $message") } }
            }
        })

        val ok = link.takePhoto(width, height, quality)
        Log.i(TAG, "takePhoto -> $ok")
        if (!ok) {
            session.mainHandler.post { finish { onError("takePhoto failed") } }
        }
    }
}
