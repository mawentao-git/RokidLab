package com.rokidlab.rokidlink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ScreenStateHub 单测：初始态回放 / 变更分发 / 重复抑制 / 异常隔离 / 反注册。
 *
 * 这四条语义是四个灭屏降载消费端（UsageStats 轮询 / HUD ticker / IMU 流）不出错的前提：
 *  - 「回放」最关键 —— 灭屏中被创建的组件若拿不到当前态，会以「屏幕开着」的默认态启动，
 *    灭屏降载直接静默失效（且这种失效没有任何报错，只能靠耗电复测发现）；
 *  - 「重复抑制」防灭屏中又来一条 SCREEN_OFF 时监听器反复重启/停轮询；
 *  - 「异常隔离」防单个组件回调抛异常带走整条分发链（那比单个组件失效更糟）。
 */
class ScreenStateHubTest {

    private val received = mutableListOf<Boolean>()
    private val listener: (Boolean) -> Unit = { received.add(it) }

    @Before
    fun setUp() {
        ScreenStateHub.initState(true)
        received.clear()
        ScreenStateHub.removeListener(listener)
    }

    @After
    fun tearDown() {
        ScreenStateHub.removeListener(listener)
        ScreenStateHub.initState(true)
    }

    @Test
    fun addListener_replaysCurrentState() {
        ScreenStateHub.initState(false) // 模拟灭屏中被创建
        ScreenStateHub.addListener(listener)
        assertEquals(listOf(false), received)
    }

    @Test
    fun dispatch_notifiesOnlyOnChange() {
        ScreenStateHub.addListener(listener)
        received.clear() // 去掉注册时的那次回放
        ScreenStateHub.dispatch(false)
        ScreenStateHub.dispatch(false) // 重复状态：不应再通知
        ScreenStateHub.dispatch(true)
        assertEquals(listOf(false, true), received)
    }

    @Test
    fun isScreenOn_tracksState() {
        assertTrue(ScreenStateHub.isScreenOn)
        ScreenStateHub.dispatch(false)
        assertFalse(ScreenStateHub.isScreenOn)
        ScreenStateHub.dispatch(true)
        assertTrue(ScreenStateHub.isScreenOn)
    }

    @Test
    fun failingListener_doesNotBlockOthers() {
        val other = mutableListOf<Boolean>()
        val otherListener: (Boolean) -> Unit = { other.add(it) }
        val boom: (Boolean) -> Unit = { throw RuntimeException("boom") }
        ScreenStateHub.addListener(boom)
        ScreenStateHub.addListener(otherListener)
        other.clear()
        ScreenStateHub.dispatch(false)
        assertEquals(listOf(false), other)
        ScreenStateHub.removeListener(boom)
        ScreenStateHub.removeListener(otherListener)
    }

    @Test
    fun removeListener_stopsNotifications() {
        ScreenStateHub.addListener(listener)
        ScreenStateHub.removeListener(listener)
        received.clear()
        ScreenStateHub.dispatch(false)
        assertTrue(received.isEmpty())
    }
}
