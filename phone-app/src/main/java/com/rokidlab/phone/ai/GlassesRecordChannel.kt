package com.rokidlab.phone.ai

import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 眼镜摄像头录像结果等待通道（Cap.RECORD，2026-10-22）。
 *
 * 手机端下发 [com.rokidlab.phone.glasses.LinkProtocol.TOPIC_RECORD_CTRL] 后，眼镜端
 * CameraRecordingController 经 [com.rokidlab.phone.glasses.LinkProtocol.TOPIC_RECORD_RESULT]
 * 上行结果（事件 + JSON 载荷）。链路两端职责：
 *  - **本类**：工具执行线程（[com.rokidlab.phone.ai.tools.GlassesRecordToolProvider]）
 *    以 [awaitResult] 挂起等待；CXR binder 回调线程把 (event, json) 喂进 [onResult]。
 *  - 分流点在 CxrLHiRokidSession.registerGlobalCmdListener（binder 线程，只解码+投递，无阻塞）。
 *
 * 并发语义：**同一时刻只有一个等待方**（工具在串行泳道执行，本通道不排队）——
 * 后到的 awaitResult 会顶掉先到的 pending（旧等待自然超时），与工具串行执行的前提一致。
 * CXR 链路重建时旧 session 的迟到回执喂进来没有 pending 接收，静默丢弃（无害）。
 */
object GlassesRecordChannel {

    private const val TAG = "GlassesRecordChannel"

    private class Pending(val want: Set<String>) {
        val latch = CountDownLatch(1)
        @Volatile
        var event: String? = null

        @Volatile
        var payload: String? = null
    }

    private val lock = Any()
    private var pending: Pending? = null

    /**
     * 等待眼镜端回执，直到收到 [want] 中的任一事件或超时。
     * @return (事件, JSON 载荷)；超时/被打断返回 null。
     */
    fun awaitResult(want: Set<String>, timeoutMs: Long): Pair<String, String>? {
        val p = Pending(want)
        synchronized(lock) { pending = p }
        try {
            val ok = p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                Log.w(TAG, "awaitResult($want) timeout after ${timeoutMs}ms")
                return null
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "awaitResult interrupted")
            return null
        } finally {
            synchronized(lock) { if (pending === p) pending = null }
        }
        val e = p.event ?: return null
        return e to (p.payload ?: "")
    }

    /** CXR binder 回调入口：喂事件并唤醒等待方（非阻塞）。 */
    fun onResult(event: String, payload: String) {
        val p = synchronized(lock) { pending } ?: run {
            Log.d(TAG, "onResult($event): no waiter, drop")
            return
        }
        if (event !in p.want) {
            Log.d(TAG, "onResult($event): waiter wants ${p.want}, ignore")
            return
        }
        p.event = event
        p.payload = payload
        p.latch.countDown()
        Log.i(TAG, "onResult($event) delivered: ${payload.take(120)}")
    }
}
