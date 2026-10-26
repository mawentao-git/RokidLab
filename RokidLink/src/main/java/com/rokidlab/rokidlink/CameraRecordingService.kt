package com.rokidlab.rokidlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 录像专用前台服务（foregroundServiceType="camera|microphone"）。
 *
 * ## 为什么必须有它（2026-10-22 真机定论，不是可选项）
 *
 * Android 11+ 对 CAMERA / RECORD_AUDIO 的 AppOps 默认模式是 **MODE_FOREGROUND**：
 * 只有「处于前台状态」的进程才能使用，前台状态判定 = 可见 Activity **或**
 * 持有声明了 camera/microphone 类型的前台服务。RokidLink 常驻的 KeyButtonService
 * 类型是 connectedDevice ⇒ 从它里面开 Camera 被判为后台使用，
 * CameraService 直接拒绝并报 **"Camera 0 disabled by policy"**
 * （`dumpsys media.camera` 实锤 REJECT 记录，AppOps `CAMERA: foreground` 同证）。
 * 解法 = 录像期间挂起本服务，AppOps 看到 camera 类型 FGS ⇒ 放行。
 *
 * ## 生命周期与 CameraRecordingController 严格配对
 *
 *  - start 前 [ensureForeground]（阻塞等 startForeground 完成，避免 AppOps 检查跑在服务就位前）；
 *  - stop / 所有失败路径必须 [stop] 撤服务（挂着 camera 类型的空 FGS 既浪费又吓人）。
 *
 * ⚠️ 已知理论边界：Android 12+ 的 "while-in-use" 限制（后台启动的 camera 类型 FGS
 * 能否立即用摄像头）在 stock ROM 上可能拦截；本机为 Rokid 定制 ROM，实测定论。
 * 若真机仍拒，降级方案 = 先隐形拉起 MainActivity 再开录（Activity 前台无歧义）。
 */
class CameraRecordingService : Service() {

    companion object {
        private const val TAG = KeyButtonService.TAG
        private const val CHANNEL_ID = "camera_recording"
        private const val NOTIFICATION_ID = 0x4C42

        /** startForeground 成功标志与就绪闩（ensureForeground 阻塞等待用） */
        @Volatile
        private var lastStartOk = false

        @Volatile
        private var readyLatch: CountDownLatch? = null

        /**
         * 启动并**阻塞等待**前台态就位。AppOps 在 Camera.open 时检查 FGS 类型，
         * 若 startForeground 还没跑完就 open，检查仍按后台判 ⇒ 必须等就位再开相机。
         *
         * @return true = 服务已持 camera|microphone 类型前台态，可以开摄像头
         */
        fun ensureForeground(context: Context, timeoutMs: Long = 3_000L): Boolean {
            val latch = CountDownLatch(1)
            lastStartOk = false
            readyLatch = latch
            val intent = Intent(context, CameraRecordingService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS) && lastStartOk
            } catch (e: Exception) {
                Log.w(TAG, "CameraRecordingService.ensureForeground failed: ${e.message}")
                false
            }
        }

        /** 前台态是否已就位（诊断用：Camera.open 失败时一并打日志，区分"服务没起来"与"策略拒绝"） */
        val isForegroundNow: Boolean get() = lastStartOk

        /** 录像结束/失败路径撤销前台服务（幂等） */
        fun stop(context: Context) {
            lastStartOk = false
            runCatching { context.stopService(Intent(context, CameraRecordingService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 硬约束：5 秒内必须前台化（同 BtTunnelService 判据）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            lastStartOk = true
            Log.i(TAG, "CameraRecordingService foreground ok (camera|microphone)")
        } catch (e: Exception) {
            // 前台化失败（部分 ROM 对后台启动 camera 类型 FGS 有限制）：
            // 不硬撑 —— 如实置败，控制器收到 ensureForeground=false 后上报 start_failed
            Log.e(TAG, "CameraRecordingService startForeground failed: ${e.message}")
            lastStartOk = false
            stopSelf()
        } finally {
            readyLatch?.countDown()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Glasses camera recording" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("RokidLink")
            .setContentText("Camera recording")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
