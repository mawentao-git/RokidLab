package com.rokidlab.rokidlink

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.MediaRecorder
import android.util.Log
import com.rokid.cxr.Caps
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 眼镜摄像头录像控制器 —— 真正的 Camera1 + MediaRecorder 视频录制（mp4 落眼镜本地）。
 *
 * ## 为什么自己开摄像头，而不是走 CXR / Rokid 自带服务（2026-10-22 探查定论）
 *
 *  - **CXR SDK 1.1.2 没有录像接口**：反编译 `client-l-1.1.2.aar` 的 `IMediaStreamService`
 *    全部方法里摄像头只有 `takePhoto`（单帧）+ `registerImageCallback`（每次拍照回一帧），
 *    唯一成对的"流"接口是音频（startAudioStream/stopAudioStream）。没有视频录制。
 *  - **Rokid 自带 RecordingService 是 system 私有**：`com.rokid.os.sprite.record` 只有一个
 *    未导出的 `.service.RecordingService`（uid 1000，signature 权限保护，正被
 *    `assistserver` 绑定）—— shell 与第三方 APK 都调不动，这就是 CXR 不给录像接口的原因。
 *  - **但 Camera HAL 是标准 Android 12**：`dumpsys media.camera` 显示 1 颗后置（API1+API2
 *    双支持、带视频防抖、AE 15/24/30/60fps）—— 普通 APK 申请 CAMERA 权限即可用。
 *    运行时授权走标准弹窗：MainActivity 启动时链式申请（蓝牙框之后）CAMERA+RECORD_AUDIO，
 *    用户在眼镜上点允许；`adb pm grant` 只是开发机快捷方式，分发场景不依赖。
 *
 * ## 协议（LinkProtocol.TOPIC_RECORD_CTRL / TOPIC_RECORD_RESULT）
 *
 *  - 下行 caps[0] = "start" / "stop" / "status"；
 *  - 上行 caps = [事件, JSON 载荷]，事件 "started"/"stopped"/"error"/"status"，
 *    载荷含 path（眼镜端 mp4 绝对路径）/ durationSec / bytes / error。
 *    stop 成功后手机端凭 path 走 ADB 把文件拉回「下载/Lab」（ffmpeg 工具直接可读）。
 *
 * ## 职责
 *
 *  1. 响应手机端控制（start / stop / status）
 *  2. 录像落眼镜本地 `getExternalFilesDir(null)/LabRecord/`（app 外部私有目录：
 *     免存储权限、卸载才清；拉回手机后由手机端负责删眼镜端文件）
 *  3. 看门狗：手机失联 [WATCHDOG_MS] 自动停 —— 摄像头是最高级别隐私敏感面，
 *     比远场麦（15min）更严，3min 收不到任何下行即强制停录（同 AmbientListenController 判据）
 *  4. KeyButtonService.onDestroy 时 [shutdown] 强制停录释放摄像头
 *
 * ⚠️ 已知待真机验证项：**画面方向**。HAL 上报 orientation=270，本实现暂不调
 * `setOrientationHint`（保留传感器原始方向）；真机录出的画面若横竖颠倒，在这里补 hint。
 */
@Suppress("DEPRECATION") // Camera1：MediaRecorder 组合代码量最小、Android 12 仍完全支持
internal class CameraRecordingController(
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG

        /**
         * 失联自动停看门狗：手机端每 60s 下行 ping（rokidlab_ping）续期一次；
         * 连续 [WATCHDOG_MS] 收不到任何下行（手机崩溃/失联）即强制停录 —— 隐私兜底。
         */
        private const val WATCHDOG_MS = 3 * 60_000L

        /** 单段录像硬上限（MediaRecorder setMaxDuration）：忘 stop 也不至于无限录。 */
        private const val MAX_DURATION_MS = 15 * 60_000

        /** 采集帧率：眼镜 HAL 的 AE 支持 15/24/30/60，取 30（功耗与流畅度折中） */
        private const val VIDEO_FPS = 30

        /** 摄像头编号（眼镜只有一颗后置；探查 dumpsys 确认 Device 0 = Facing Back） */
        private const val CAMERA_ID = 0

        /** 分辨率挑选上限：≤1080p（4K 对眼镜这颗 sensor 意义不大，还拖慢编码/传输） */
        private const val MAX_PIXELS = 1920 * 1080

        private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /**
         * 从 HAL 支持的尺寸里挑录制尺寸：横向量里选「≤1080p 的最大那档」。
         *
         * 只在横向尺寸里挑（width ≥ height）：眼镜是第一视角横向视野，
         * 挑竖向尺寸（若 HAL 列出）会让画面上下裁掉大半。
         */
        private fun pickVideoSize(sizes: List<Camera.Size>?): Camera.Size? {
            if (sizes.isNullOrEmpty()) return null
            val landscape = sizes.filter { it.width >= it.height }.ifEmpty { sizes }
            val capped = landscape.filter { it.width * it.height <= MAX_PIXELS }
            return (capped.ifEmpty { landscape }).maxByOrNull { it.width * it.height }
        }

        /** 码率跟随像素量：1080p≈6Mbps、720p≈2.8Mbps（经验值 bpp≈3），下限 2Mbps 保清晰度 */
        private fun bitrateFor(w: Int, h: Int): Int = maxOf(2_000_000, w * h * 3)

        /**
         * 顺时针旋转角度（写进 mp4 的 tkhd 矩阵）：让播放器按「设备自然方向正立」显示。
         *
         * 后置：`(sensorOrientation - deviceRotation + 360) % 360`
         * 前置：`(sensorOrientation + deviceRotation) % 360`（前置是镜像语义，标准差异）
         *
         * 眼镜（RG-glasses）实测 `Orientation: 270`、HWC 固定 landscape（transform ROT_270），
         * 故 deviceRotation 恒为 0 ⇒ hint = 270。真机若仍看到躺倒，把这里改成固定 90 试
         * （两者差的就是"设备自然方向算横还是算竖"这一判断）。
         */
        @Suppress("DEPRECATION")
        private fun orientationHint(ctx: android.content.Context): Int = runCatching {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(CAMERA_ID, info)
            val deviceDeg = runCatching {
                val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE)
                    as android.view.WindowManager
                when (wm.defaultDisplay.rotation) {
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            }.getOrDefault(0)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                (info.orientation + deviceDeg) % 360
            } else {
                (info.orientation - deviceDeg + 360) % 360
            }
        }.getOrDefault(0)
    }

    /** 是否在录像中（控制 start 幂等 / stop no-op / 看门狗） */
    @Volatile
    private var recording = false

    private var camera: Camera? = null
    private var recorder: MediaRecorder? = null
    private var previewTexture: SurfaceTexture? = null
    private var outputFile: File? = null
    private var startedAtMs = 0L

    private var watchdog: Runnable? = null

    /**
     * 控制序列专用单线程执行器（同 AmbientListenController 判据）：handleCtrl 来自 CXR
     * 订阅回调线程，而 Camera.open/prepare/mr.stop 在蓝牙半开或 HAL 忙时可能耗时数百 ms，
     * 绝不能在回调线程同步执行。单线程保证 start→stop 天然序列化（stop 排队时 start 必已完成）。
     */
    private val ctlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "record-ctl").apply { isDaemon = true }
    }

    /** 手机端控制指令（[LinkProtocol.TOPIC_RECORD_CTRL]）：caps[0] = "start"/"stop"/"status"。 */
    fun handleCtrl(args: Caps?) {
        when (val cmd = firstString(args)) {
            "start" -> ctlExecutor.execute { start() }
            "stop" -> ctlExecutor.execute { stop("phone requested") }
            "status" -> ctlExecutor.execute { sendStatus() }
            else -> Log.w(TAG, "record ctrl unknown cmd: $cmd")
        }
    }

    /** 会话是否活跃（诊断用） */
    val active: Boolean get() = recording

    /** 手机端存活保活：收到任意下行（60s 周期 ping）时重置看门狗（CxrBridgeCoordinator 调）。 */
    fun onPhoneAlive() {
        if (recording) armWatchdog()
    }

    /** 服务销毁兜底：强制停录并释放摄像头（KeyButtonService.onDestroy 调）。 */
    fun shutdown() {
        ctlExecutor.execute { stop("service destroyed") }
    }

    // ── start / stop ──

    private fun start() {
        if (recording) {
            Log.i(TAG, "record start: already recording, ignore")
            sendResult("error", JSONObject().put("error", "already_recording"))
            return
        }
        val ctx = core.service
        // 运行时权限检查：CAMERA 缺失直接如实上报（正常运行时由 MainActivity 弹窗授予；
        // `adb pm grant` 只是开发机快捷方式）。
        // 不做"缺麦克风就录无声视频"的静默降级 —— 用户要的是完整录像，缺权限就该说清楚。
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "record start: CAMERA permission not granted")
            sendResult("error", JSONObject().put("error", "no_camera_permission"))
            return
        }
        val hasAudio = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

        // Android 11+ AppOps 对 CAMERA 是 MODE_FOREGROUND：必须有 camera|microphone
        // 类型的前台服务才允许开相机（否则 "Camera 0 disabled by policy"，真机定论
        // 见 CameraRecordingService 文档）。阻塞等前台态就位再 open，避免竞态。
        if (!CameraRecordingService.ensureForeground(ctx)) {
            Log.w(TAG, "record start: camera FGS did not come up")
            sendResult(
                "error",
                JSONObject().put("error", "start_failed").put("message", "camera fgs start failed"),
            )
            return
        }

        val dir = File(ctx.getExternalFilesDir(null), "LabRecord").apply { mkdirs() }
        val out = File(dir, "rec_${TS.format(Date())}.mp4")

        val cam = try {
            Camera.open(0)
        } catch (e: Exception) {
            // 两种截然不同的原因都会走到这里，不能一律报 "busy"（2026-10-22 教训：
            // 实际原因是 AppOps 策略拒绝，却报成"被占用"，排查多绕一圈）：
            //  ① 其他客户端真在出流（assistserver / 相机 App）⇒ camera_busy
            //  ② 策略拒绝（CAMERA AppOps=foreground 但当前不算前台使用）⇒ 异常 message
            //     含 "disabled by policy"，此时直接如实上报原文，别让模型误判成"稍等再试"
            // 一并带上异常原文，手机端原样呈现给用户，避免再猜。
            val msg = e.message ?: "unknown"
            Log.w(TAG, "record start: Camera.open(0) failed: $msg (fgs=${CameraRecordingService.isForegroundNow})")
            CameraRecordingService.stop(ctx)
            val busy = msg.contains("disabled by policy").not()
            sendResult(
                "error",
                JSONObject()
                    .put("error", if (busy) "camera_busy" else "camera_policy_denied")
                    .put("message", msg),
            )
            return
        }
        val tex = SurfaceTexture(0) // 无 UI dummy preview：部分 HAL 要求 preview 在跑才出正常帧
        val mr = MediaRecorder()

        // ── 采集参数：从 HAL 真实支持列表里挑，**不信任 CamcorderProfile** ──
        // 2026-10-22 真机教训：Rokid ROM 的 `CamcorderProfile.get(QUALITY_HIGH)` 返回
        // **176x144（QCIF）** —— 录出来的片子糊到不可用（真机录出文件实测 176x144）。
        // 而 dumpsys 显示 HAL 支持到 4032x3024 ⇒ profile 表与 HAL 能力脱节。
        // 改为直接读 `supportedVideoSizes` 自己挑（≤1080p 里最大的，退化到最大可用）。
        var pickedW = 1280
        var pickedH = 720
        var orientHint = 0
        var sizeCandidates = ""
        try {
            val p = cam.parameters
            val sizes = p.supportedVideoSizes?.takeIf { it.isNotEmpty() }
                ?: p.supportedPreviewSizes.orEmpty()
            sizeCandidates = sizes.joinToString(",") { "${it.width}x${it.height}" }.take(300)
            pickVideoSize(sizes)?.let {
                pickedW = it.width
                pickedH = it.height
                // preview 与 video 同尺寸：两者不一致时部分 HAL 会拒绝或内部缩放（糊）
                runCatching {
                    p.setPreviewSize(pickedW, pickedH)
                    p.setPreviewFormat(ImageFormat.NV21)
                    cam.parameters = p
                }
            }
            orientHint = orientationHint(ctx)
            Log.i(TAG, "record: videoSize=${pickedW}x$pickedH orientHint=$orientHint sizes=[$sizeCandidates]")
        } catch (e: Exception) {
            Log.w(TAG, "record: param probe failed: ${e.message} (fallback ${pickedW}x$pickedH)")
        }

        try {
            cam.setPreviewTexture(tex)
            cam.startPreview()
            cam.unlock()
            mr.setCamera(cam)
            if (hasAudio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.CAMERA)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (hasAudio) mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setVideoSize(pickedW, pickedH)
            mr.setVideoFrameRate(VIDEO_FPS)
            mr.setVideoEncodingBitRate(bitrateFor(pickedW, pickedH))
            // 旋转元数据（必须在 prepare 前设）：HAL 上报的 orientation 是「画面需顺时针转
            // 多少度才在设备自然方向下正立」。不设的话 mp4 的 tkhd 矩阵是单位阵，
            // 播放器只能按 sensor 原始方向显示 ⇒ 看到的画面是躺倒的（用户报的就是这个）。
            if (orientHint != 0) runCatching { mr.setOrientationHint(orientHint) }
            mr.setOutputFile(out.absolutePath)
            mr.setMaxDuration(MAX_DURATION_MS)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.i(TAG, "record: max duration reached")
                    ctlExecutor.execute { stop("max duration") }
                }
            }
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            // 半装配状态必须清干净：已 open 的 camera 不释放会让后续所有录像/拍照都 busy
            Log.w(TAG, "record start: prepare/start failed: ${e.message}")
            runCatching { mr.release() }
            runCatching { cam.stopPreview() }
            runCatching { cam.release() }
            runCatching { tex.release() }
            CameraRecordingService.stop(ctx)
            out.delete()
            sendResult(
                "error",
                JSONObject().put("error", "start_failed").put("message", e.message ?: "unknown"),
            )
            return
        }
        camera = cam
        recorder = mr
        previewTexture = tex
        outputFile = out
        startedAtMs = System.currentTimeMillis()
        recording = true
        armWatchdog()
        Log.i(TAG, "record started: ${out.absolutePath} (audio=$hasAudio)")
        sendResult("started", JSONObject().put("path", out.absolutePath))
    }

    private fun stop(reason: String) {
        val mr = recorder
        if (mr == null) {
            Log.i(TAG, "record stop($reason): not recording, no-op")
            return
        }
        val cam = camera
        val out = outputFile
        val tex = previewTexture
        val durationSec = if (startedAtMs > 0) (System.currentTimeMillis() - startedAtMs) / 1000.0 else 0.0
        // 先清状态再做事：stop 链路任何一步抛异常都不能让状态卡在"录像中"
        recording = false
        cancelWatchdog()
        recorder = null
        camera = null
        previewTexture = null
        outputFile = null
        startedAtMs = 0

        // MediaRecorder.stop() 若抛（无有效帧等）⇒ 文件缺 moov 是坏的，必须删掉别留垃圾
        var finalized = false
        try {
            mr.stop()
            finalized = true
        } catch (e: Exception) {
            Log.w(TAG, "record stop($reason): mr.stop failed: ${e.message}")
        }
        runCatching { mr.release() }
        runCatching { cam?.stopPreview() }
        runCatching { cam?.release() }
        runCatching { tex?.release() }
        CameraRecordingService.stop(core.service)

        if (finalized && out != null && out.exists() && out.length() > 0) {
            Log.i(TAG, "record stopped($reason): ${out.absolutePath} (${out.length()}B, ${durationSec}s)")
            sendResult(
                "stopped",
                JSONObject()
                    .put("path", out.absolutePath)
                    .put("durationSec", durationSec)
                    .put("bytes", out.length()),
            )
        } else {
            out?.delete()
            sendResult("error", JSONObject().put("error", "record_failed").put("reason", reason))
        }
    }

    private fun sendStatus() {
        val payload = JSONObject().put("recording", recording)
        if (recording) {
            payload.put("path", outputFile?.absolutePath ?: "")
            if (startedAtMs > 0) {
                payload.put("durationSec", (System.currentTimeMillis() - startedAtMs) / 1000.0)
            }
        }
        sendResult("status", payload)
    }

    // ── 上行 / 看门狗 ──

    /**
     * 结果上行（[LinkProtocol.TOPIC_RECORD_RESULT]）：caps = [事件, JSON 载荷]。
     * 走 [KeyServiceCore.sendCxrWithTimeout]（sendMessage 蓝牙半开可能无限阻塞，必须超时保护）。
     * bridge 已断时只丢弃上行 —— 文件在眼镜本地，手机重连后用 status 查询即可补救。
     */
    private fun sendResult(event: String, payload: JSONObject) {
        val b = core.bridge
        if (b == null) {
            Log.w(TAG, "record result($event): bridge gone, drop ($payload)")
            return
        }
        val caps = Caps()
        caps.write(event)
        caps.write(payload.toString())
        val r = core.sendCxrWithTimeout(b, LinkProtocol.TOPIC_RECORD_RESULT, caps)
        Log.i(TAG, "record result($event) -> $r: $payload")
    }

    private fun armWatchdog() {
        cancelWatchdog()
        val task = Runnable {
            watchdog = null
            if (recording) ctlExecutor.execute { stop("watchdog timeout") }
        }
        watchdog = task
        core.mainHandler.postDelayed(task, WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { core.mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    /** 取 caps 里第一个非空字符串（控制指令只有 1 个元素，容忍脏序；同 AmbientListenController） */
    private fun firstString(args: Caps?): String? {
        if (args == null) return null
        for (i in 0 until args.size()) {
            val s = runCatching { args.at(i).getString() }.getOrNull() ?: continue
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }
}
