package com.rokidlab.rokidlink

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge

/**
 * KeyButtonService 的 CXR 桥接协调器（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 创建/销毁 [CXRServiceBridge]，注册连接状态监听
 *  2. 注册全部下行订阅（config/tts/tool_confirm/quiz/continue/ping/hello/Ai/AIUI/...）
 *  3. 协议握手（LinkProtocol v2）：就绪时主动通告 + 应答 HELLO_REQ
 *  4. 断线自愈：断线武装 → 重连成功启动观察窗口 → 窗口内无下行判定路由 stale →
 *     经 Alarm 自杀重启进程（cxr-service 分发路由 stale 只能靠进程重启恢复）
 */
internal class CxrBridgeCoordinator(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val aiTakeover: AiTakeoverCoordinator,
    private val keyRoutes: KeyRouteCoordinator,
    private val toolConfirm: ToolConfirmController,
    private val overlays: GlassesOverlayController,
    private val chartViewer: ChartViewerController,
    private val ipReporter: GlassesIpReporter,
    private val aiuiHost: AiuiHostController,
    private val configHandlers: ConfigHandlers,
    private val processTimeline: ProcessTimelineController,
    private val hud: HudOverlayController,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 断线重连后的下行观察窗口：手机端 ping 周期 60s，观察 150s 覆盖 ≥2 个周期，避免正常空闲误判 */
        private const val SELF_HEAL_CHECK_DELAY_MS = 150_000L
        /** Alarm 拉活与自杀之间的延迟：留给系统注册 alarm */
        private const val SELF_HEAL_RESTART_DELAY_MS = 3_000L
        /** 自杀后兜底拉起的延迟：给 START_STICKY 留出重启时间，超时未起则由兜底闹钟显式拉起 */
        private const val SELF_HEAL_BOOTSTRAP_DELAY_MS = 20_000L
        /** 常驻下行观测周期（每轮顺带记录下行年龄，便于事后取证） */
        private const val DOWNLINK_WATCH_INTERVAL_MS = 60_000L
        /** 「零下行」阈值：手机在场却连续这么久收不到任何下行 ⇒ 判 cxr-service 路由 stale。
         *  取 180s 覆盖 ≥3 个手机端 ping 周期（60s），避开偶发抖动。 */
        private const val DOWNLINK_SILENT_LIMIT_MS = 180_000L
        /** 自愈最小间隔：防"重启也救不回来"时反复自杀（例如手机端自己没在发 ping） */
        private const val SELF_HEAL_MIN_GAP_MS = 15 * 60_000L
        /** 降级路径（无助手页探测）请求自定义图的最小间隔：对话每轮十几帧，不节流会刷爆链路 */
        private const val CUSTOM_IMG_REQ_THROTTLE_MS = 30_000L

        /**
         * 语音助手活动窗口：ASR/TTS 帧到达后视为「助手正在交互」的时长。
         * 取 90s 覆盖「用户说完→长回复播报→思考→下一轮」的完整间隔；
         * 用户 90s 不出声视为离开助手，自定义图收起（watcher 不可用时的收起依据）。
         * ⚠️ 不能取太长：离开助手去别的界面，窗口还热会让图在那些界面继续挂。
         */
        private const val ASSISTANT_WINDOW_MS = 90_000L

        /**
         * 收图防抖：watcher 判 hidden 后等这么久再收，给语音助手窗口接上的机会
         * （助手打开瞬间主界面先失焦，ASR 帧 ~几百 ms 后才到）。防「图闪一下」。
         */
        private const val HIDE_DEBOUNCE_MS = 2_500L

        /**
         * 本进程启动时刻（[android.os.SystemClock.elapsedRealtime]，单调钟）——
         * 随 HELLO 第 4 字段上报，手机端据此识别「眼镜端进程重启/重装过」。
         *
         * 为什么需要它：行为类开关（连续对话/按键答题）的**真值源在手机端 prefs**，
         * 执行器是眼镜端 prefs。眼镜端 RokidLink 重装（引导/设置页的"先卸后装"）
         * 会清掉它的 prefs ⇒ `chat_continue_dialog` 回落默认 **true** ⇒
         * 手机端开关显示"关"、眼镜端却仍在连续对话（2026-10-24 用户实测）。
         * 开关只在用户点击时推一次，此前没有任何补推机制。
         * 进程重启后该值必然变化 ⇒ 手机端见到新值即重推一遍开关状态。
         *
         * 放 companion 而非实例字段：Coordinator 随 Service 重建，进程内只初始化一次，
         * 语义正好是"进程世代"（旧手机端只读前 3 字段，忽略第 4 字段，无兼容问题）。
         */
        private val PROCESS_START_AT_MS = android.os.SystemClock.elapsedRealtime()
    }

    /** 是否处于「断线武装」状态：onDisconnected 置 true，重连成功后启动观察窗口。
     *  ⚠️ CXR 回调（binder 线程）与主线程都会读写，必须 @Volatile（KeyServiceCore 同款约束） */
    @Volatile
    private var reconnectArmed = false

    /** 环境音监听（官方字幕链路白嫖）：跨断线重连持有会话状态与看门狗，见其文档 */
    private val ambient = AmbientListenController(core)

    /** 眼镜摄像头录像（真 Camera1+MediaRecorder）：SDK 无录像接口、系统 RecordingService
     *  是 system 私有 ⇒ 自建录制（2026-10-22 探查定论，见 CameraRecordingController 文档）。 */
    private val recorder = CameraRecordingController(core)

    /**
     * 官方助手页（眼镜主界面/桌面表盘）在场探测 —— 「自定义图」显示的触发源之一。
     *
     * 语义（用户 2026-10-14 明确，2026-10-24 修订）：开关打开并存好图后，
     * **推图当下不显示**；只有眼镜**主界面**在前台、或**语音助手**正在交互时才显示，
     * 主界面常驻**不设自动隐藏超时**（不要 60s 闪没）；两个场景都不满足就收起。
     *
     * 显示决策统一走 [syncCustomImageVisibility]（并集判定见
     * [AssistantPageWatcher.shouldShowCustomImage]）：
     * - 主界面：watcher 回调驱动；
     * - 语音助手：ASR/TTS 帧刷新的「活动窗口」驱动（官方助手界面没有可靠的
     *   Activity 类名可匹配，用户开口/助手说话是唯一可得的在场证据）。
     *
     * 探测不可用（缺 `PACKAGE_USAGE_STATS`，手机端 appops 没下发）时 watcher 回调
     * 永不到来 ⇒ 只剩活动窗口一条路：对话期间显示、过期收起（降级语义，
     * 不再"挂上就永不收"——那是 2026-10-24 用户报「全部界面都显示」的根因）。
     */
    private val assistantPage = AssistantPageWatcher(service, core) { visibleNow ->
        assistantPageVisible = visibleNow
        // hidden 不立即收：语音助手打开的瞬间主界面先失焦（AiuiActivity 盖上来），
        // ASR 窗口随后就到，立即收会造成"图闪一下再回来"。防抖留给窗口接上。
        syncCustomImageVisibility(debounceHideMs = HIDE_DEBOUNCE_MS)
    }

    /** watcher 只启动一次（initCxrBridge 可能被重连路径重复调用） */
    private var assistantPageStarted = false

    /** 主界面可见性（watcher 回调，仅主线程读写；null = 尚不知道） */
    private var assistantPageVisible: Boolean? = null

    /** custom_img_req 节流时刻（仅主线程读写，见 requestCustomImageThrottled） */
    private var lastCustomImgReqAt = 0L

    /**
     * 语音助手活动窗口截止时刻（仅主线程读写）：
     * 眼镜端收到官方 ASR 文字 / TTS 播报帧时刷新到 now + [ASSISTANT_WINDOW_MS]。
     * 过期 = 助手不在交互 ⇒ 收起自定义图（watcher 不可用时的唯一收起路径）。
     */
    private var assistantActiveUntilMs = 0L

    /** 语音助手窗口过期检查任务（到期重跑 sync；ASR 帧会重排它） */
    private val assistantWindowExpire = Runnable { syncCustomImageVisibility() }

    /** 收图防抖任务（watcher 判 hidden 时延后收，见 assistantPage 回调注释） */
    private val hideImageDebounced = Runnable { syncCustomImageVisibility() }

    /** 重连成功时刻：观察窗口起点，窗口内无下行则判定路由失效 */
    @Volatile
    private var reconnectAtMs = 0L

    /** 自愈检查任务引用（主线程 Handler），cancel 用 */
    @Volatile
    private var selfHealCheck: Runnable? = null

    /** B1：去重断连重建任务，避免堆叠导致订阅倍发 */
    @Volatile
    private var pendingReconnect: Runnable? = null

    /** 常驻下行观测任务引用（主线程 Handler），cancel 用 */
    @Volatile
    private var downlinkWatch: Runnable? = null

    /** 观测基准时刻：此刻起算「零下行」（收到下行或本轮跳过判定时前移） */
    @Volatile
    private var downlinkBaseMs = 0L

    /** 上次自愈时刻（限流用） */
    @Volatile
    private var lastSelfHealMs = 0L

    fun init() {
        try {
            core.bridge = CXRServiceBridge()
            core.bridge?.setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connected: name=$name, address=$address, type=$type")
                    core.bridgeConnected = true
                    // 常驻观测幂等兜底：init() 已无条件武装，正常路径此处直接 return；
                    // 仅防「断线重连先于 reconnect 任务的 init() 完成回调」的极窄窗口
                    armDownlinkWatch()
                    // 连接建立后若已连 WiFi，立即上行眼镜 IP（首次/重连后让手机端尽快拿到）
                    ipReporter.sendGlassesIp()
                    // 断线重连成功：cxr-service 分发路由可能在重连后 stale（订阅返回 0 但实际不投递，
                    // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
                    // 启动观察窗口：期间收到任何下行（ping/config/tts）即健康，否则判定路由失效自愈。
                    if (reconnectArmed) {
                        reconnectArmed = false
                        reconnectAtMs = System.currentTimeMillis()
                        scheduleSelfHealCheck()
                    }
                }
                override fun onDisconnected() {
                    core.bridgeConnected = false
                    // IMU 数据流随链路一起停：手机不在时上行必失败，继续采样纯属耗电
                    runCatching { service.headImu.stop() }
                    // 🔴 清掉所有 overlay UI —— 手机端强制停止时不会发 process_done / close 指令，
                    // 过程卡片、确认面板、图片层、图表全屏查看器会永远挂在眼镜上（用户反馈 bug）
                    // ⚠️ 必须切主线程（2026-10-17 审计修）：onDisconnected 跑在 **binder/回调线程**，
                    //   而 release/clear 的实质是 WindowManager.removeView / View 树操作 ——
                    //   非主线程触碰 View 树随时抛 CalledFromWrongThreadException；抛在断线路径上
                    //   会让后面 4 个 release 一个都不执行，残影全挂住（本修的就是这个 bug 的因）。
                    //   与本文件 :222（toolConfirm.clear）/:345（hideImage）的既有约定对齐。
                    core.mainHandler.post {
                        runCatching { processTimeline.release() }
                        runCatching { toolConfirm.clear() }
                        runCatching { overlays.release() }
                        runCatching { chartViewer.release() }
                        Log.i(TAG, "CXR disconnected — all overlays released")
                    }
                    // 断线期不做路由判定：取消观察并武装，待重连成功后重新启动观察
                    reconnectArmed = true
                    cancelSelfHealCheck()
                    // 常驻观测同期停：断线了就没有"下行"可言，继续跑只会白记；重连时 onConnected 会重新 arm
                    // （若本次断线只因回调链已死而不回调，watch 仍在跑 —— 那正是我们要它兜的场景）
                    cancelDownlinkWatch()
                    Log.i(TAG, "CXR disconnected, will re-init in 3s")
                    // 断线期间清空下行过滤窗口与累积 ASR，避免重连后误吞用户提问/误拦文本
                    core.downlinkUntilMs = 0L
                    aiTakeover.cancelPendingContinueDialog()
                    // 断线自愈：cxr-service 重启/蓝牙闪断后重建桥接并重订阅，避免永久失联
                    // B1 修复：先取消旧任务去重；若 3s 内已自动重连成功则跳过，
                    //          避免丢弃刚连好的 bridge 重建、造成同一条 config/tts/ai 被重复下发
                    pendingReconnect?.let { core.mainHandler.removeCallbacks(it) }
                    val reconnectTask = Runnable {
                        pendingReconnect = null
                        if (core.bridgeConnected) {
                            Log.i(TAG, "CXR already reconnected before 3s, skip re-init")
                            return@Runnable
                        }
                        runCatching { core.bridge?.disconnectCXRDevice() }
                        runCatching {
                            core.bridge = null
                            init()
                        }.onFailure { Log.e(TAG, "re-init CXR bridge failed", it) }
                    }
                    pendingReconnect = reconnectTask
                    core.mainHandler.postDelayed(reconnectTask, 3000)
                }
                override fun onConnecting(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connecting: name=$name, address=$address, type=$type")
                }
                override fun onARTCStatus(health: Float, reset: Boolean) {}
                override fun onRokidAccountChanged(account: String) {
                    Log.i("CXRServiceBridge", "Rokid account changed: $account")
                }
            })

            val result = core.bridge?.subscribe(KeyButtonService.TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleKeyConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TOPIC}) -> $result")

            // 历史兜底订阅（wifi_config 已废弃，仅保留兼容旧版手机端；WiFi 配置主通道为官方 Wifi 频道）
            @Suppress("DEPRECATION")
            run {
                val wifiResult = core.bridge?.subscribe(KeyButtonService.WIFI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                    configHandlers.handleWifiConfig(args)
                })
                Log.i(TAG, "subscribe(${KeyButtonService.WIFI_TOPIC}) -> $wifiResult")
            }

            val ttsResult = core.bridge?.subscribe(KeyButtonService.TTS_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                // 语音助手活动证据：助手正在播报 ⇒ 刷新「自定义图」显示窗口
                markAssistantActive()
                aiTakeover.handleTtsPlay(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TTS_TOPIC}) -> $ttsResult")

            val ttsStopResult = core.bridge?.subscribe(KeyButtonService.TTS_STOP_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                Log.i(TAG, "Received tts_stop, stopping local TTS")
                TtsPlaybackHelper.stop()
                // 播报被打断：环境音 TTS 硬闸同步解除（真实播完回调不会再触发）
                AmbientListenController.ttsPlaying = false
            })
            Log.i(TAG, "subscribe(${KeyButtonService.TTS_STOP_TOPIC}) -> $ttsStopResult")

            // 工具确认请求（手机端 → 眼镜端）：call_phone 等副作用工具执行前的用户确认
            val toolConfirmResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_TOOL_CONFIRM,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    toolConfirm.handleRequest(args)
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TOOL_CONFIRM}) -> $toolConfirmResult")

            // 确认窗口关闭（手机端 → 眼镜端）：点头/摇头手势在手机端应答，眼镜端按键路径收不到
            // respond()，悬浮窗需由手机端通知关闭（旧版手机端不发此消息 → 悬浮窗仍由 30s 超时兜底）。
            val toolConfirmCloseResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_TOOL_CONFIRM_CLOSE,
                CXRServiceBridge.MsgCallback { _, _, _ ->
                    core.markDownlink()
                    core.mainHandler.post { toolConfirm.clear() }
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TOOL_CONFIRM_CLOSE}) -> $toolConfirmCloseResult")

            val quizResult = core.bridge?.subscribe(KeyButtonService.QUIZ_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleQuizConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.QUIZ_TOPIC}) -> $quizResult")

            // 连续对话（多轮免唤醒）开关：手机端设置页切换时下发，落 prefs 后立即改变
            // isContinueDialogEnabled() 的返回值（无需重连/重启服务）。
            val continueResult = core.bridge?.subscribe(KeyButtonService.CONTINUE_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                configHandlers.handleContinueDialogConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.CONTINUE_TOPIC}) -> $continueResult")

            // 推送通道远程踢活：手机端检测到 RFCOMM 推送监听连续秒断（监听假死）时经 CXR
            // 频道下发（CXR 由系统 cxr-service 托管，推送死了它仍可达），收到后重建 AsrPushServer。
            val pushRestartResult = core.bridge?.subscribe(AiChannel.TOPIC_PUSH_RESTART, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                Log.w(TAG, "push restart requested by phone, recreating AsrPushServer")
                AsrPushServer.restart()
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PUSH_RESTART}) -> $pushRestartResult")

            // 接收 Lab 下发的 AI 配置（baseUrl/apiKey/model），供眼镜端直接调用模型
            val aiCfgResult = core.bridge?.subscribe(KeyButtonService.AI_CONFIG_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                configHandlers.handleAiConfig(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AI_CONFIG_TOPIC}) -> $aiCfgResult")

            // 订阅 "Ai" 频道：拦截官方 AI 链路的 ASR 文字，改用 Lab 模型回复。
            // 广播式路由，与 AssistServer 的订阅不冲突（已真机验证）。
            val aiResult = core.bridge?.subscribe(KeyButtonService.AI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                // 语音助手活动证据：用户开口（ASR）才刷新「自定义图」显示窗口；
                // 手机端远程指令帧不算，否则任意界面遥控眼镜都会把图呼出来
                markAssistantActiveIfSpeaking(args)
                aiTakeover.handleAiChannel(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AI_TOPIC}) -> $aiResult")
            // 订阅显示状态页指令：手机端「打开 RokidLink」时带 EXTRA_SHOW_UI 显示 IP 状态页
            val showMainResult = core.bridge?.subscribe(KeyButtonService.SHOW_MAIN_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                configHandlers.showMainActivity()
            })
            Log.i(TAG, "subscribe(${KeyButtonService.SHOW_MAIN_TOPIC}) -> $showMainResult")

            // AIUI 自托管宿主控制：open / close / msg（onMessage 伪交互通道）
            val aiuiHostResult = core.bridge?.subscribe(KeyButtonService.AIUI_HOST_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                aiuiHost.handleAiuiHost(args)
            })
            Log.i(TAG, "subscribe(${KeyButtonService.AIUI_HOST_TOPIC}) -> $aiuiHostResult")

            // 下行存活探测订阅：手机端连接期间每 60s 下发一条空消息。
            // 断线重连后 cxr-service 分发路由可能 stale（订阅返回 0 但实际不投递，
            // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
            // ping 一旦到达即证明路由健康；观察窗口到期仍收不到则 Alarm 重启进程自愈。
            val pingResult = core.bridge?.subscribe(AiChannel.TOPIC_PING, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                // 空闲常开保活：聆听中时刷新 15min 失联看门狗（手机崩溃/失联后 ping 停止，自然到期关麦）
                ambient.onPhoneAlive()
                // 录像中时刷新 3min 失联看门狗（摄像头隐私兜底比麦克风更严）
                recorder.onPhoneAlive()
                Log.i(TAG, "Received ping — downlink route healthy")
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PING}) -> $pingResult")

            // 协议握手（插播 B · LinkProtocol v2）：手机端主动询问时回报版本与能力，
            // 覆盖「眼镜端后启动 / 手机端重连」场景。
            val helloResult = core.bridge?.subscribe(LinkProtocol.TOPIC_HELLO_REQ, CXRServiceBridge.MsgCallback { _, _, _ ->
                core.markDownlink()
                announceHello()
            })
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_HELLO_REQ}) -> $helloResult")

            // IMU 头动采集控制（v1 查询层）：手机端 CUSTOMAPP 会话就绪后下发 imu_start 开流，
            // 断连/销毁时停。默认关 —— 眼镜端不连接就不采样，零常驻开销。
            val imuCtrlResult = core.bridge?.subscribe(AiChannel.TOPIC_IMU_CTRL, CXRServiceBridge.MsgCallback { _, args, _ ->
                core.markDownlink()
                service.headImu.handleControl(args)
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_IMU_CTRL}) -> $imuCtrlResult")

            // 停止手机投屏指令：手机端按「停止投屏」时下发。
            // PhoneMirrorActivity 设计为 socket 断开后保持前台等重连（避免重连后画面更新在
            // 后台不可见），因此必须显式关闭，否则最后一帧画面会残留在眼镜上。
            // 复用页面既有的 ACTION_FINISH_MIRROR 广播（与 ScreenMirrorIntentActivity 同一通道）。
            val stopMirrorResult = core.bridge?.subscribe(
                AiChannel.TOPIC_STOP_PHONE_MIRROR,
                CXRServiceBridge.MsgCallback { _, _, _ ->
                    core.markDownlink()
                    Log.i(TAG, "Received stop_phone_mirror — closing PhoneMirrorActivity")
                    core.mainHandler.post {
                        runCatching {
                            service.sendBroadcast(
                                Intent(PhoneMirrorActivity.ACTION_FINISH_MIRROR)
                                    .setPackage(service.packageName)
                            )
                        }
                    }
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_STOP_PHONE_MIRROR}) -> $stopMirrorResult")

            // 图片下发：把手机端对话气泡里的图片显示到眼镜端（悬浮图片层，persist 跟随助手 / 否则 12s 自动隐藏）。
            // 与歌词/工具确认复用同一套悬浮层授权（SYSTEM_ALERT_WINDOW）。
            val showImageResult = core.bridge?.subscribe(
                AiChannel.TOPIC_SHOW_IMAGE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    overlays.handleShowImage(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_SHOW_IMAGE}) -> $showImageResult")

            // 自定义图开关被关掉：手机端主动下发收起指令。
            // ⚠️ 常驻图没有超时兜底（用户明确不要 60s 闪没），所以这条指令是「关开关立刻生效」
            // 的唯一路径；缺了它用户会以为没生效。
            val imageHideResult = core.bridge?.subscribe(
                AiChannel.TOPIC_IMAGE_HIDE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    if (AiChannel.decodeImageHide(capsToStrings(args))) {
                        core.mainHandler.post { overlays.hideImage() }
                    } else {
                        Log.w(TAG, "image_hide: rejected invalid payload (size=${args?.size()})")
                    }
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_IMAGE_HIDE}) -> $imageHideResult")

            // 生成式 HUD 控制（Cap.HUD）：手机端 glass_hud 工具下发场景 DSL，
            // try=60s 试戴 / apply=常驻 / hide=收起，解释渲染见 HudOverlayController。
            val hudCtrlResult = core.bridge?.subscribe(
                AiChannel.TOPIC_HUD_CTRL,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    hud.handleCtrl(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_HUD_CTRL}) -> $hudCtrlResult")

            // 生成式 HUD 数据下行（Cap.HUD）：bind 元素的实时数据源（docs/hud-dsl.md「数据绑定」），
            // 手机端 HudDataHub 事件驱动小包，更新即重绘。
            val hudDataResult = core.bridge?.subscribe(
                AiChannel.TOPIC_HUD_DATA,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    hud.handleData(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_HUD_DATA}) -> $hudDataResult")

            // 生成式 HUD 图片资源（Cap.HUD）：hud_image 工具登记的主视觉图，
            // 先推资源、后推引用它的场景 DSL（资源缓存见 HudOverlayController）。
            val hudImageResult = core.bridge?.subscribe(
                AiChannel.TOPIC_HUD_IMAGE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    hud.handleImage(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_HUD_IMAGE}) -> $hudImageResult")

            // 图表全屏查看器：手机端把 ECharts 图表渲染成图片下发（Cap.CHART_VIEW），
            // 查看器全屏常驻显示，滑动切换/单击重发/双击退出，见 ChartViewerController。
            val chartPushResult = core.bridge?.subscribe(
                AiChannel.TOPIC_CHART_PUSH,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    chartViewer.handleChartPush(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_CHART_PUSH}) -> $chartPushResult")

            // 过程时间线（v4.7，Cap.PROCESS_TIMELINE）：手机侧把 AgentStep 步骤流转成
            // 紧凑帧下行，眼镜端渲染成底部时间线 overlay（步骤流式流出 / 同 key 覆盖 /
            // 确认态高亮）。与 Ai 频道单行进度帧互斥由手机侧门禁保证（带本 Cap 位的
            // 眼镜端不再发单行帧），眼镜端只渲染本通道 ⇒ 不会出现两套过程显示。
            val processStepResult = core.bridge?.subscribe(
                AiChannel.TOPIC_PROCESS_STEP,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    processTimeline.handleStep(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PROCESS_STEP}) -> $processStepResult")

            // 轮次结束：时间线标终态并延迟收起（无失败/失败两种终态）。
            val processDoneResult = core.bridge?.subscribe(
                AiChannel.TOPIC_PROCESS_DONE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    processTimeline.handleDone(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PROCESS_DONE}) -> $processDoneResult")

            // 打开页面：手机端要求把某个眼镜端 Activity 拉到前台（如说「显示歌词」→ 系统音乐页）。
            val openAppResult = core.bridge?.subscribe(
                AiChannel.TOPIC_OPEN_APP,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    configHandlers.handleOpenApp(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_OPEN_APP}) -> $openAppResult")

            // 环境音监听控制（手机端 → 眼镜端）：start/stop 官方字幕链路（远场麦），见 AmbientListenController
            val ambientCtrlResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_AMBIENT_CTRL,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    ambient.handleControl(args)
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_AMBIENT_CTRL}) -> $ambientCtrlResult")

            // 眼镜摄像头录像控制（Cap.RECORD）：start/stop/status 下行，结果经
            // TOPIC_RECORD_RESULT 上行，文件拉回与产物登记在手机端 GlassesRecordToolProvider。
            val recordCtrlResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_RECORD_CTRL,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    recorder.handleCtrl(args)
                },
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_RECORD_CTRL}) -> $recordCtrlResult")

            // 离线 TTS PCM 流（B 方案）：手机端合成 → CXR 下发 → 本地 AudioTrack 播放。
            // ctrl 通道管生命周期，PCM 通道喂流，见 PcmStreamPlayer。
            val pcmCtrlResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_TTS_PCM_CTRL,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    core.markDownlink()
                    val action = runCatching { args.at(0).getString() }.getOrNull()
                    when (action) {
                        "start" -> {
                            val sr = runCatching { args.at(1).getInt() }.getOrDefault(0)
                            Log.i(TAG, "tts_pcm start (sr=$sr)")
                            PcmStreamPlayer.start(sr)
                        }
                        "end" -> PcmStreamPlayer.end()
                        "stop" -> PcmStreamPlayer.stop()
                    }
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TTS_PCM_CTRL}) -> $pcmCtrlResult")

            val pcmResult = core.bridge?.subscribe(
                LinkProtocol.TOPIC_TTS_PCM,
                CXRServiceBridge.MsgCallback { _, args, value ->
                    core.markDownlink()
                    // PCM 在 caps[0]（TYPE_BINARY）；若分发把原始 payload 放 value，兜底取之
                    val pcm = runCatching { args.at(0).getBinary() }.getOrNull()?.let { b ->
                        if (b.offset == 0 && b.length == b.data.size) b.data
                        else b.data.copyOfRange(b.offset, b.offset + b.length)
                    } ?: value?.takeIf { it.isNotEmpty() }
                    pcm?.let { PcmStreamPlayer.feed(it) }
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TTS_PCM}) -> $pcmResult")

            // 官方字幕频道广播：活跃会话期间把字幕 ASR JSON 经推送通道上行手机端
            val ambientAsrResult = core.bridge?.subscribe(
                LinkProtocol.CXR_CHANNEL_ACCESSIBILITY,
                CXRServiceBridge.MsgCallback { _, args, value ->
                    ambient.handleAccessibility(args, value)
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.CXR_CHANNEL_ACCESSIBILITY}) -> $ambientAsrResult")

            // 服务就绪：主动握手通告一次（手机端据此免探测获知眼镜端能力）
            announceHello()

            // 常驻观测在 init() 无条件武装（幂等）：不能只在 onConnected 里 arm ——
            // 2026-09-26 真机取证：进程启动时 cxr-service flora 侧已 stale，握手永远完不成、
            // onConnected 永不回调 ⇒ watch 永不武装，三道自愈防线（reconnectArmed 观察窗 /
            // downlink watch / watchdog 心跳）同时失效，故障静默存活到进程被外力重启。
            // init 期武装后，「手机在场（AsrPushServer.hasClient）+ 桥未连成 + 零下行」
            // 180s 即自愈重启；onConnected 里的 arm 因幂等守卫无副作用。
            armDownlinkWatch()

            // 助手页在场探测：常驻自持（不随桥连接状态起停）——
            // 眼镜可能在手机不在场时被戴上，此时没桥可发请求，但状态仍要跟踪，
            // 等手机回来后再由下一次状态变化/对话活动补要图。
            if (!assistantPageStarted) {
                assistantPageStarted = true
                assistantPage.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "initCxrBridge failed", e)
        }
    }

    // ──────────────────────────────────────────────
    //  协议握手（插播 B · LinkProtocol v2）
    // ──────────────────────────────────────────────

    /**
     * 上报本端协议版本与能力位（caps = [version, capsBitmask, linkVersion, processStartAt]）。
     *
     * 手机端据此免探测获知眼镜端能力；旧版手机端会忽略未知 topic/字段，无副作用。
     * 服务就绪时主动调用一次，并对 [LinkProtocol.TOPIC_HELLO_REQ] 应答。
     *
     * 第 4 字段 [PROCESS_START_AT_MS]：进程启动标记，手机端据此在眼镜端重启/重装后
     * 重推行为类开关（连续对话/按键答题的真值在手机端，眼镜端 prefs 可能已丢）。
     */
    internal fun announceHello() {
        val b = core.bridge ?: run {
            Log.w(TAG, "announceHello skipped: no bridge")
            return
        }
        runCatching {
            val linkVersion = runCatching {
                service.packageManager.getPackageInfo(service.packageName, 0).versionName
            }.getOrNull() ?: "-"
            val caps = Caps()
            caps.write(LinkProtocol.PROTOCOL_VERSION.toString())
            caps.write(LinkProtocol.Cap.ALL.toString())
            caps.write(linkVersion)
            caps.write(PROCESS_START_AT_MS.toString())
            val r = b.sendMessage(LinkProtocol.TOPIC_HELLO, caps)
            Log.i(TAG, "hello sent: version=${LinkProtocol.PROTOCOL_VERSION} caps=0x${LinkProtocol.Cap.ALL.toString(16)} linkVersion=$linkVersion procStart=$PROCESS_START_AT_MS -> $r")
        }.onFailure { Log.e(TAG, "announceHello failed", it) }
    }

    // ──────────────────────────────────────────────
    //  常驻下行观测（覆盖「回调链一起死、断线武装等不到」的场景）
    // ──────────────────────────────────────────────

    /**
     * 常驻观测：手机在场（本地 accept 到 RFCOMM/TCP 客户端）却长时间收不到任何下行
     * ⇒ 判定 cxr-service 分发路由 stale 并自愈。
     *
     * 为什么必须**常驻**、而不是只挂在「断线重连」上：
     * 2026-09-24 真机取证证明，路由 stale 时 **CXR 侧回调链会一起废掉** ——
     * `onDisconnected` 不回调 ⇒ `reconnectArmed` 永不置位 ⇒ 观察窗永不武装 ⇒
     * 故障静默存活到下一次进程重启（实测 27 分钟零恢复，用户表现为「眼镜语音有几率
     * 不进 Lab、也有几率不走自定义模型」，其实是「某次闪断后就一直坏」）。
     * ⚠️ 该状态下 `core.bridgeConnected` 会永远停在 true，**不可信**；
     * 只能用 [AsrPushServer.hasClient]（不经过 cxr-service）作为「手机真的在场」的证据。
     */
    private fun armDownlinkWatch() {
        // onConnected（binder 回调线程）与 init()（主线程）都可能进入：
        // 「判空 + 赋值」必须原子，否则双双通过 → 两个自续 Runnable 叠跑（双份健康检查/自愈判定）
        synchronized(this) {
            if (downlinkWatch != null) return
            downlinkBaseMs = System.currentTimeMillis()
            val task = object : Runnable {
                override fun run() {
                    runCatching { checkDownlinkHealth() }
                    core.mainHandler.postDelayed(this, DOWNLINK_WATCH_INTERVAL_MS)
                }
            }
            downlinkWatch = task
            core.mainHandler.postDelayed(task, DOWNLINK_WATCH_INTERVAL_MS)
        }
        Log.i(TAG, "downlink watch armed (interval=${DOWNLINK_WATCH_INTERVAL_MS}ms, " +
            "limit=${DOWNLINK_SILENT_LIMIT_MS}ms)")
    }

    private fun cancelDownlinkWatch() {
        synchronized(this) {
            downlinkWatch?.let { core.mainHandler.removeCallbacks(it) }
            downlinkWatch = null
        }
    }

    private fun checkDownlinkHealth() {
        val now = System.currentTimeMillis()
        val since = maxOf(core.lastDownlinkMs, downlinkBaseMs)
        val silentMs = now - since
        // 手机不在场（本地没有客户端连着）：不动手，否则手机关机/在远处时会反复自杀重启
        if (!AsrPushServer.hasClient) {
            downlinkBaseMs = now
            Log.i(TAG, "downlink watch: no RFCOMM client (phone away), reset " +
                "(last downlink ${silentMs / 1000}s ago)")
            return
        }
        if (silentMs < DOWNLINK_SILENT_LIMIT_MS) {
            Log.d(TAG, "downlink watch: healthy, last downlink ${silentMs / 1000}s ago")
            return
        }
        if (now - lastSelfHealMs < SELF_HEAL_MIN_GAP_MS) {
            downlinkBaseMs = now
            Log.w(TAG, "downlink watch: stale ${silentMs / 1000}s but self-heal throttled " +
                "(last ${(now - lastSelfHealMs) / 1000}s ago)")
            return
        }
        Log.w(TAG, "downlink watch: phone present but NO downlink for ${silentMs / 1000}s " +
            "— cxr-service route stale, restarting process")
        lastSelfHealMs = now
        downlinkBaseMs = now
        selfHealRestart()
    }

    // ──────────────────────────────────────────────
    //  断线自愈（cxr-service 分发路由 stale 探测）
    // ──────────────────────────────────────────────

    /** 重连成功（此前断线过）后启动观察：SELF_HEAL_CHECK_DELAY_MS 后检查
     *  期间是否收到过下行。路由 stale 时 cxr-service 不再投递任何订阅消息，
     *  进程内重建 bridge 无法恢复（实测仅进程重启有效），故经 Alarm 自杀重启。 */
    private fun scheduleSelfHealCheck() {
        cancelSelfHealCheck()
        val healCheck = Runnable {
            selfHealCheck = null
            runSelfHealCheck()
        }
        selfHealCheck = healCheck
        core.mainHandler.postDelayed(healCheck, SELF_HEAL_CHECK_DELAY_MS)
        Log.i(TAG, "Self-heal check armed in ${SELF_HEAL_CHECK_DELAY_MS}ms (reconnectAt=$reconnectAtMs)")
    }

    private fun cancelSelfHealCheck() {
        selfHealCheck?.let { core.mainHandler.removeCallbacks(it) }
        selfHealCheck = null
    }

    /** 观察窗口到期判定：bridge 仍连接 且 窗口内无任何下行 → 路由 stale，重启进程 */
    private fun runSelfHealCheck() {
        selfHealCheck = null
        if (!core.bridgeConnected) {
            Log.i(TAG, "Self-heal check: bridge disconnected, skip (will re-arm on reconnect)")
            return
        }
        if (core.lastDownlinkMs >= reconnectAtMs) {
            Log.i(TAG, "Self-heal check: downlink healthy (last=${core.lastDownlinkMs} reconnectAt=$reconnectAtMs)")
            return
        }
        Log.w(TAG, "Self-heal check: NO downlink since reconnect — cxr-service route stale, restarting process")
        selfHealRestart()
    }

    /** AlarmManager 延迟 SELF_HEAL_RESTART_DELAY_MS 后触发 SelfRestartReceiver：
     *  Handler.postDelayed 在进程自杀后不存活，必须用系统级 Alarm（RTC_WAKEUP）确保广播可投递。
     *  双闹钟设计：① 自杀闹钟（3s）→ 接收器杀进程，由 START_STICKY/显式启动重建订阅；
     *  ② 兜底拉起闹钟（+20s）→ 若粘性重启失败，Alarm 唤醒新进程显式启动两个常驻服务。 */
    private fun selfHealRestart() {
        try {
            val am = service.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val killPi = PendingIntent.getBroadcast(service, 0,
                Intent(service, SelfRestartReceiver::class.java)
                    .setAction(KeyButtonService.ACTION_SELF_HEAL_RESTART), flags)
            am.set(AlarmManager.RTC_WAKEUP, now + SELF_HEAL_RESTART_DELAY_MS, killPi)
            val bootPi = PendingIntent.getBroadcast(service, 1,
                Intent(service, SelfRestartReceiver::class.java)
                    .setAction(KeyButtonService.ACTION_SELF_HEAL_BOOTSTRAP), flags)
            am.set(AlarmManager.RTC_WAKEUP,
                now + SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS, bootPi)
            Log.w(TAG, "Self-heal: process restart scheduled (kill+${SELF_HEAL_RESTART_DELAY_MS}ms, " +
                "bootstrap+${SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "selfHealRestart failed", e)
        }
    }

    /**
     * 向手机端要一次「自定义图」。
     *
     * 手机端是开关与图的唯一真相源：开着且已存图才回 `show_image(persist=true)`，
     * 否则静默不回 —— 图存不存在、要不要显示，手机端判；眼镜端不猜、不缓存旧图。
     */
    private fun requestCustomImage() {
        val b = core.bridge ?: run {
            Log.w(TAG, "custom_img_req skipped: no CXR bridge")
            return
        }
        Thread {
            val r = runCatching {
                val caps = Caps()
                for (f in AiChannel.encodeCustomImgReq()) caps.write(f)
                core.sendCxrWithTimeout(b, AiChannel.TOPIC_CUSTOM_IMG_REQ, caps)
            }.getOrDefault(-1)
            Log.i(TAG, "custom_img_req -> $r")
        }.apply { name = "custom-img-req"; isDaemon = true }.start()
    }

    /**
     * 「自定义图」显示决策唯一入口（主线程）。
     *
     * 判定（并集，见 [AssistantPageWatcher.shouldShowCustomImage]）：
     * - 主界面在前台（watcher）→ 显示；常驻不自动收（原语义）；
     * - 语音助手活动窗口热（ASR/TTS 帧刷新）→ 显示；
     * - 都否 → 收起。
     *
     * @param debounceHideMs > 0 时，收起动作延后这么久再确认一次（watcher 判 hidden
     *   的调用点用）：期间 ASR 窗口接上就撤销收图，防助手打开瞬间图闪。
     */
    private fun syncCustomImageVisibility(debounceHideMs: Long = 0L) {
        core.mainHandler.removeCallbacks(hideImageDebounced)
        if (AssistantPageWatcher.shouldShowCustomImage(
                assistantPageVisible,
                System.currentTimeMillis() < assistantActiveUntilMs,
            )
        ) {
            // 图已挂着就不重复要：每次要图 = 手机端重发几十 KB Base64。
            // 但图**要不到**时（开关关着/没存图，isImageVisible 恒 false）每帧都会走到
            // 这里 —— 所以节流必须保留，否则一轮对话几十帧 ASR 会刷爆链路。
            if (!overlays.isImageVisible()) requestCustomImageThrottled()
        } else if (debounceHideMs > 0L) {
            core.mainHandler.postDelayed(hideImageDebounced, debounceHideMs)
        } else {
            overlays.hideImage()
            // 收起即清节流：否则「助手→主界面」来回切时，回主界面那次要图会被
            // 30s 节流挡住，用户看到图半天回不来。节流真正要防的是"要不到图"
            // （开关关着/没存图）时的每帧重发，那种场景不会走到这个分支。
            lastCustomImgReqAt = 0L
        }
    }

    /** 要图节流（仅主线程读写）：30s 内多次 sync 只发一次 custom_img_req */
    private fun requestCustomImageThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastCustomImgReqAt < CUSTOM_IMG_REQ_THROTTLE_MS) return
        lastCustomImgReqAt = now
        requestCustomImage()
    }

    /**
     * 标记「语音助手正在交互」：刷新活动窗口并重排过期检查。
     *
     * 调用源 = 官方 AI/TTS 频道的**用户侧**帧（ASR 文字 / 助手播报）——
     * 手机端远程下发的指令帧（KeyDown_Client / TTS_Result 重放等）不算，
     * 否则用户在任意界面用手机遥控眼镜也会把图呼出来。
     *
     * 线程：由 CXR 回调线程直调，全部读写经 mainHandler.post 落到主线程。
     */
    private fun markAssistantActive() {
        core.mainHandler.post {
            assistantActiveUntilMs = System.currentTimeMillis() + ASSISTANT_WINDOW_MS
            core.mainHandler.removeCallbacks(assistantWindowExpire)
            // 窗口到期后再确认一次：届时若 watcher 也不可见 ⇒ 收图
            core.mainHandler.postDelayed(assistantWindowExpire, ASSISTANT_WINDOW_MS + 500L)
            syncCustomImageVisibility()
        }
    }

    /**
     * AI 频道帧分类：只有**用户开口**（ASR_Result / ASR_End，且非 Lab 下行重放）
     * 才算「语音助手在场」证据。其他帧（KeyDown_Client / TTS_Result / 心跳）
     * 可能是手机端远程指令，不刷新窗口。
     */
    private fun markAssistantActiveIfSpeaking(args: Caps?) {
        val cmd = runCatching { args?.at(0) }
            .getOrNull()
            ?.takeIf { it.type() == Caps.Value.TYPE_STRING }
            ?.getString()
            ?: return
        if (cmd != "ASR_Result" && cmd != "ASR_End") return
        // Lab 下行序列里的重放帧不是用户开口（downlinkUntilMs 与 AiTakeoverCoordinator 同源）
        if (System.currentTimeMillis() < core.downlinkUntilMs) return
        markAssistantActive()
    }

    /** 服务销毁：断开 CXR 连接并清空桥接 */
    fun onDestroy() {
        // 录像强制停录释放摄像头：半装配/录到一半的服务销毁若不清，camera 句柄泄漏
        // 会让后续所有录像/拍照（含 assistserver 拍照）一直 camera_busy
        recorder.shutdown()
        assistantPage.stop()
        core.mainHandler.removeCallbacks(assistantWindowExpire)
        core.mainHandler.removeCallbacks(hideImageDebounced)
        runCatching { core.bridge?.disconnectCXRDevice() }
        core.bridge = null
    }
}
