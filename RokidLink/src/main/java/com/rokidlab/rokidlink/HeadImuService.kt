package com.rokidlab.rokidlink

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.rokid.cxr.Caps
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 眼镜端 IMU 头动采集服务（v1 查询层数据源）。
 *
 * 职责单一：**只采数、只上报** —— 按手机端指令（rokidlab_imu_ctrl）启停，
 * 采样加速度计 + 陀螺仪 + 游戏旋转向量，合并为 ~20Hz 样本流，
 * 500ms 批量打包经 CXR 通道（rokidlab_imu）上行；全部逻辑在手机端（MotionBuffer/规则引擎），
 * 改需求不动眼镜代码。
 *
 * 设计约束：
 *  - 默认关：连接建立不自动开（眼镜端零常驻开销），手机端 CUSTOMAPP 会话就绪后下发 imu_start；
 *  - 眼镜端回调线程禁止阻塞（历史教训）：传感器回调只做「降频 + 缓冲」，
 *    发送全部走独立 imu-send 执行器，且忙时**丢批**（遥测语义，保新鲜度不保全量）；
 *  - 20Hz 远低于原始采样能力（GAME ~50Hz），省电且蓝牙带宽 ~1KB/s 无压力；
 *  - 断连时手机端会调用 stop（CxrBridgeCoordinator.onDisconnected），采样停止省电；
 *  - 灭屏宽限自动停流（2026-10-24 省电实测：batterystats 归因 sensors=5.55mAh，App 侧
 *    最大单项）：息屏超过 [SCREEN_OFF_STOP_GRACE_MS] 自动停，亮屏且手机端开过流则恢复。
 *    手机端查询/审批路径有 stale 懒重启兜底，规则引擎 tick 遇数据陈旧空转不误触发。
 */
internal class HeadImuService(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 目标采样周期 50ms ≈ 20Hz */
        private const val SAMPLE_PERIOD_US = 50_000
        /** 批量上行间隔 */
        private const val BATCH_INTERVAL_MS = 500L
        /** 传感器事件降频下限：两次入样至少间隔 45ms（防 GAME 档实际频率高于预期） */
        private const val MIN_SAMPLE_INTERVAL_MS = 45L
        /**
         * 灭屏自动停流宽限（2026-10-24 省电实测驱动，见类注释）。
         * 息屏超过该时长且手机端没有新指令就停流让传感器休眠，亮屏恢复。
         * 取 2min：覆盖「亮屏→灭屏→又亮屏」的正常交互间隙，只砍长时待机。
         */
        private const val SCREEN_OFF_STOP_GRACE_MS = 120_000L
    }

    private var sensorManager: SensorManager? = null

    /**
     * 手机端指令态：true = 最近一条控制指令是 imu_start。
     * 灭屏自动停流**不清**此位 —— 亮屏时据此恢复「手机端本来开着」的流。
     */
    @Volatile
    private var userEnabled = false

    /** 灭屏自动停流的宽限任务（亮屏 / 手机端重新开流 / stop 时取消） */
    @Volatile
    private var autoStopTask: Runnable? = null

    init {
        // 灭屏宽限自动停流、亮屏恢复（见 [SCREEN_OFF_STOP_GRACE_MS] 与类注释）：
        //  - 灭屏：流在跑就摆宽限定时器，超时自动停（传感器休眠）；
        //  - 亮屏：手机端开过流（userEnabled）就自动恢复，用户无感；
        //  - 手机端 imu_start 会经 start()→refreshAutoStop 重摆宽限 —— 灭屏期间的按需
        //    查询/审批因此能拿到一段宽限数据，不会一查就被停。
        // addListener 立即回放当前屏幕态：灭屏中被创建时直接进入宽限逻辑，不会先漏采一轮。
        ScreenStateHub.addListener { on ->
            if (on) {
                cancelAutoStop()
                if (userEnabled && !running) {
                    Log.i(TAG, "screen on: resuming imu stream (phone had it enabled)")
                    start()
                }
            } else {
                refreshAutoStop()
            }
        }
    }

    /** 采集运行中（volatile：start/stop 来自 CXR 回调线程，传感器回调读） */
    @Volatile
    private var running = false

    /** 待上行批次（传感器回调线程写 / main handler 线程取走，锁保护） */
    private val batch = ArrayList<AiChannel.ImuSample>(32)

    /** 各传感器最近一次入样时刻（降频去重，按 sensor type 索引） */
    private val lastAcceptMs = mutableMapOf<Int, Long>()

    // 最近一次传感器值（回调线程写 / 批次组装读，单写单读 + 锁兜底）
    private val sampleLock = Any()
    private val lastAccel = FloatArray(3)
    private val lastGyro = FloatArray(3)
    private val lastQuat = FloatArray(4) // w,x,y,z
    private var hasQuat = false

    /**
     * 独立上行执行器：不复用 [KeyServiceCore.aiSendExecutor]（那是 AI 频道专用，
     * IMU 2 msg/s 的持续流量不应与其争用线程）。忙时丢批由 [sending] 标志实现。
     */
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "imu-send").apply { isDaemon = true }
    }

    /** 丢批标志：上一批还在发送（蓝牙拥塞/阻塞）时，新批次直接丢弃，不排队积压 */
    private val sending = AtomicBoolean(false)

    /** 批量定时任务引用（main handler，cancel 用） */
    private var batchTask: Runnable? = null

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running) return
            val now = System.currentTimeMillis()
            // 降频：同一传感器 45ms 内的重复事件丢弃（注册周期只是「期望值」，
            // 部分 ROM 会按硬件 FIFO 更快投递）
            val last = lastAcceptMs[event.sensor.type] ?: 0L
            if (now - last < MIN_SAMPLE_INTERVAL_MS) return
            lastAcceptMs[event.sensor.type] = now
            synchronized(sampleLock) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        lastAccel[0] = event.values[0]; lastAccel[1] = event.values[1]; lastAccel[2] = event.values[2]
                        // 加速度计作为「心跳」：它过频控时组装一个合并样本
                        //（陀螺仪/旋转向量只刷新最近值，避免同一样本被三条回调重复生成）
                        appendSample(now)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        lastGyro[0] = event.values[0]; lastGyro[1] = event.values[1]; lastGyro[2] = event.values[2]
                    }
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        // 游戏旋转向量：values[0..2] = x,y,z（sin θ/2 × 轴），w 用 sqrt(1-x²-y²-z²)
                        // 现场计算而非读 values[3] —— 部分驱动不填第 4 个分量，sqrt 推导恒定可用
                        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                        val w = Math.sqrt(Math.max(0.0, 1.0 - x * x - y * y - z * z)).toFloat()
                        lastQuat[0] = w; lastQuat[1] = x; lastQuat[2] = y; lastQuat[3] = z
                        hasQuat = true
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** 组装一个合并样本（必须在 sampleLock 内调用） */
    private fun appendSample(now: Long) {
        val s = AiChannel.ImuSample(
            t = now,
            ax = lastAccel[0], ay = lastAccel[1], az = lastAccel[2],
            gx = lastGyro[0], gy = lastGyro[1], gz = lastGyro[2],
            qw = lastQuat[0], qx = lastQuat[1], qy = lastQuat[2], qz = lastQuat[3],
        )
        synchronized(batch) { batch.add(s) }
    }

    /** 开始采集（幂等）：注册传感器 + 启动批量上行定时器 */
    fun start() {
        if (running) {
            Log.d(TAG, "imu already running, ignore start")
            return
        }
        val sm = service.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            Log.w(TAG, "imu start failed: no SensorManager")
            return
        }
        sensorManager = sm
        synchronized(batch) { batch.clear() }
        lastAcceptMs.clear()
        running = true
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (accel == null || gyro == null || rot == null) {
            Log.w(TAG, "imu start: missing sensors accel=${accel != null} gyro=${gyro != null} rot=${rot != null}")
        }
        // 旋转向量可能未就绪：四元数先置单位阵，首个旋转向量事件到位后自然覆盖
        synchronized(sampleLock) {
            if (!hasQuat) { lastQuat[0] = 1f }
        }
        try {
            accel?.let { sm.registerListener(sensorListener, it, SAMPLE_PERIOD_US) }
            gyro?.let { sm.registerListener(sensorListener, it, SAMPLE_PERIOD_US) }
            rot?.let { sm.registerListener(sensorListener, it, SAMPLE_PERIOD_US) }
        } catch (e: Exception) {
            Log.e(TAG, "imu registerListener failed", e)
            running = false
            return
        }
        scheduleBatch()
        userEnabled = true
        refreshAutoStop()
        Log.i(TAG, "imu stream started (20Hz, batch=${BATCH_INTERVAL_MS}ms)")
    }

    /** 停止采集（幂等）：反注册传感器 + 停定时器 + 清缓冲。userEnabled 不动（自动停流靠它恢复） */
    fun stop() {
        if (!running) return
        running = false
        batchTask?.let { core.mainHandler.removeCallbacks(it) }
        batchTask = null
        runCatching { sensorManager?.unregisterListener(sensorListener) }
        sensorManager = null
        synchronized(batch) { batch.clear() }
        cancelAutoStop()
        Log.i(TAG, "imu stream stopped")
    }

    /** 服务销毁：停止采集并释放执行器 */
    fun onDestroy() {
        stop()
        sendExecutor.shutdownNow()
    }

    /** 处理手机端控制指令（CxrBridgeCoordinator 订阅 rokidlab_imu_ctrl 后转发到这里） */
    fun handleControl(args: Caps?) {
        when (AiChannel.decodeImuControl(capsToStrings(args))) {
            true -> start()
            false -> {
                userEnabled = false // 手机端显式停流：亮屏后不自动恢复
                stop()
            }
            null -> Log.w(TAG, "imu ctrl: rejected invalid/unsupported payload")
        }
    }

    /**
     * 按当前屏幕状态重摆自动停定时：亮屏不摆；灭屏且流在跑 = 宽限 [SCREEN_OFF_STOP_GRACE_MS] 后自动停。
     * 手机端每次 imu_start 也会走到这里 —— 灭屏期间的按需查询因此能拿到一段宽限数据。
     * 任务体内二次校验 running/isScreenOn：过期任务（亮屏后、停流后）安全空转。
     */
    private fun refreshAutoStop() {
        cancelAutoStop()
        if (!running || ScreenStateHub.isScreenOn) return
        val task = Runnable {
            autoStopTask = null
            if (!running || ScreenStateHub.isScreenOn) return@Runnable
            Log.i(TAG, "imu auto-stop: screen off > ${SCREEN_OFF_STOP_GRACE_MS}ms, releasing sensors")
            stop()
        }
        autoStopTask = task
        core.mainHandler.postDelayed(task, SCREEN_OFF_STOP_GRACE_MS)
    }

    private fun cancelAutoStop() {
        autoStopTask?.let { core.mainHandler.removeCallbacks(it) }
        autoStopTask = null
    }

    /** 批量上行定时器：500ms 取走缓冲成一批发送，自续期到 running=false */
    private fun scheduleBatch() {
        batchTask?.let { core.mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (!running) return@Runnable
            flushBatch()
            scheduleBatch()
        }
        batchTask = task
        core.mainHandler.postDelayed(task, BATCH_INTERVAL_MS)
    }

    /** 取走缓冲批量并投递到发送执行器（main handler 线程，取走本身 O(n) 拷贝，无阻塞） */
    private fun flushBatch() {
        val out = synchronized(batch) {
            if (batch.isEmpty()) return
            val c = ArrayList(batch)
            batch.clear()
            c
        }
        // 忙时丢批：上一批还没发出去（蓝牙拥塞 / sendMessage 阻塞），本批直接放弃。
        // 遥测语义：保新鲜度、保发送线程不被积压任务拖垮；50Hz→20Hz 降频已把丢失影响压到最小。
        if (!sending.compareAndSet(false, true)) {
            Log.w(TAG, "imu batch dropped (previous send in flight), n=${out.size}")
            return
        }
        sendExecutor.execute {
            try {
                sendBatch(out)
            } finally {
                sending.set(false)
            }
        }
    }

    /** 实际发送：Caps 打包 + sendCxrWithTimeout 超时保护（2s，防蓝牙半开挂死执行器） */
    private fun sendBatch(samples: List<AiChannel.ImuSample>) {
        val b = core.bridge ?: return
        if (!core.bridgeConnected) return
        val caps = Caps()
        AiChannel.encodeImuData(samples).forEach { caps.write(it) }
        val r = core.sendCxrWithTimeout(b, AiChannel.TOPIC_IMU_DATA, caps)
        if (r != 0) {
            Log.w(TAG, "imu send -> $r (n=${samples.size})")
        }
    }
}
