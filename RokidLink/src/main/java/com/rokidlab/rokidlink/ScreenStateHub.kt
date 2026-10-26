package com.rokidlab.rokidlink

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 屏幕状态中枢（2026-10-24 省电优化，真机实测驱动）。
 *
 * 背景：息屏待机实测（35s 干净采样）抓到三类「屏幕灭了还在干」的常驻开销：
 *  ① [AssistantPageWatcher] 每 2s 轮询 UsageStats —— main 线程 560ms/35s，占待机 CPU 65%；
 *  ② HUD ticker：动画场景 apply 后灭屏仍空转帧循环；
 *  ③ [HeadImuService] 20Hz 常采 —— batterystats 归因 sensors=5.55mAh，App 侧最大单项。
 * 三者共同缺口：**全项目此前只有 SCREEN_ON 广播、零 SCREEN_OFF 分支**。
 *
 * 设计约束：
 *  - 单一广播源：不自己注册 receiver，由 [KeepAliveManager] 现有的屏幕接收器
 *    （扩一个 action）dispatch 进来 —— 避免第二接收器与其生命周期管理；
 *  - 回调在主线程（动态广播接收器所在线程），回调体必须轻，重活自行 post；
 *  - [addListener] 立即以当前态回放一次：组件可能在灭屏期间才被创建
 *    （watchdog / 手机端 ensureGlassesLinkRunning 拉活），没有回放就会出现
 *    「灭屏中启动的组件以为屏幕开着」的初始态错误；
 *  - 单个监听器抛异常不能带走其他监听器 —— 分发链断了比单个组件失效更糟；
 *  - 重复状态（灭屏中又来一条 SCREEN_OFF）不分发，监听器不必自卫。
 */
internal object ScreenStateHub {

    private const val TAG = KeyButtonService.TAG

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * 当前屏幕是否点亮。
     * 默认 true = 未收到任何广播前的保守态：组件按亮屏工作，不会误停
     * （误停的代价是功能静默失效，误转的代价只是多耗电）。
     */
    @Volatile
    private var screenOn: Boolean = true

    val isScreenOn: Boolean get() = screenOn

    /** 注册监听；立即以当前状态回调一次（见类注释：初始态回放） */
    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        runCatching { listener(screenOn) }
            .onFailure { Log.e(TAG, "screen listener initial replay failed", it) }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 状态静默初始化（不触发回调）：由 [KeepAliveManager] 在注册接收器时用
     * PowerManager.isInteractive 代入 —— 服务可能在灭屏中被拉活，没有广播可依赖。
     */
    fun initState(on: Boolean) {
        screenOn = on
    }

    /** 广播入口：状态变化才分发 */
    fun dispatch(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        Log.i(TAG, "screen state -> ${if (on) "ON" else "OFF"}, notifying ${listeners.size} listeners")
        listeners.forEach { l ->
            runCatching { l(on) }.onFailure { Log.e(TAG, "screen listener failed", it) }
        }
    }
}
