package com.rokidlab.phone.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/**
 * 「查找手机」本机响铃控制器（2026-10-15 新增）。
 *
 * 用户（通常隔着屋子喊眼镜）说「查找手机」→ 模型调 `find_phone` 工具 → 本类在
 * **手机本机**循环播放来电铃声，直到用户走到手机旁按聊天页发送键（ChatScreen 把
 * 发送键临时变「停止响铃」），或超时自动停。
 *
 * ## 「保证声音是手机的」
 *  - 播放端就是手机进程里的 [MediaPlayer]，物理扬声器即手机扬声器；
 *  - 与眼镜的链路是蓝牙 RFCOMM（SPP 数据通道，无 A2DP），音频不存在被路由到眼镜的可能；
 *  - 不申请音频焦点（查找铃声就该盖过正在放的音乐），但走 [AudioAttributes.USAGE_ALARM]
 *    闹钟流：独立于媒体/铃声音量，**静音模式下也会响** —— 找手机场景必须如此。
 *
 * ## 音量
 * 启动时把 `STREAM_ALARM` 拉到最大（记下原值），停止时还原 —— 找手机时音量小等于没找。
 *
 * ## 兜底
 * 最长响 [TIMEOUT_MS] 自动停（用户找不到发送键/发送键被占用时不能无限响下去）；
 * MediaPlayer 出错（onError）也走同一条停止路径，不留半死播放器。
 */
internal object PhoneFinder {
    private const val TAG = "PhoneFinder"

    /** 最长响铃时长：2 分钟足够走遍一套房，也防「忘了停」无限耗电 */
    private const val TIMEOUT_MS = 120_000L

    /**
     * 是否正在响铃：Compose 快照状态，ChatScreen 直接读它驱动发送键换图标/换行为。
     * （跨层读 UI 状态看着越界，但消费方只有 ChatScreen 一处，比另起事件总线简单）
     */
    val ringing = mutableStateOf(false)

    /** 启动结果（execute 分支据此给模型回报） */
    internal enum class Result { STARTED, ALREADY_RINGING, FAILED }

    private var player: MediaPlayer? = null
    private var prevAlarmVolume = -1
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 开始响铃。已在响铃时返回 [Result.ALREADY_RINGING]（不叠加、不重置计时）。 */
    @Synchronized
    fun start(context: Context): Result {
        if (ringing.value) return Result.ALREADY_RINGING
        val ctx = context.applicationContext
        heldContext = ctx
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // 记住闹钟音量原值 → 拉满（找手机时音量小等于没找）；停止时还原
        prevAlarmVolume = runCatching { am.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
        runCatching {
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w(TAG, "setStreamVolume(ALARM, max) failed: ${it.message}") }

        // 铃声源：用户自己设置的来电铃声最直觉；个别机型没有默认铃声时逐级降级
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) {
            Log.w(TAG, "no default ringtone/alarm/notification uri on this device")
            restoreVolume(am)
            return Result.FAILED
        }

        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            p.setDataSource(ctx, uri)
            p.isLooping = true
            p.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer onError what=$what extra=$extra -> stop")
                stop()
                true
            }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            Log.e(TAG, "start ringing failed", e)
            runCatching { p.release() }
            restoreVolume(am)
            return Result.FAILED
        }
        player = p
        ringing.value = true
        mainHandler.postDelayed({ stop() }, TIMEOUT_MS)
        Log.i(TAG, "ringing started (alarm stream, uri=$uri, timeout=${TIMEOUT_MS / 1000}s)")
        return Result.STARTED
    }

    /** 停止响铃并还原音量。没在响时返回 false（调用方可据此回报「本来就没响」）。 */
    @Synchronized
    fun stop(): Boolean {
        if (!ringing.value) return false
        ringing.value = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            player?.stop()
            player?.release()
        }.onFailure { Log.w(TAG, "release player failed: ${it.message}") }
        player = null
        val ctx = appContext()
        heldContext = null
        if (ctx != null) {
            restoreVolume(ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        }
        Log.i(TAG, "ringing stopped")
        return true
    }

    private var heldContext: Context? = null

    private fun appContext(): Context? = heldContext

    private fun restoreVolume(am: AudioManager) {
        if (prevAlarmVolume < 0) return
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, prevAlarmVolume, 0) }
            .onFailure { Log.w(TAG, "restore alarm volume failed: ${it.message}") }
        prevAlarmVolume = -1
    }
}
