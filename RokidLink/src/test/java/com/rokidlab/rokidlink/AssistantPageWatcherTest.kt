package com.rokidlab.rokidlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自定义图」显示判定纯函数单测（2026-10-24 用户需求：只在眼镜主界面与语音助手下显示）。
 *
 * 锁两条语义：
 * ① [AssistantPageWatcher.isAssistantPage] —— 主界面 = launcher 包的 SpriteMainActivity；
 *    同包其他页面（音乐页等）必须 false，这是"别在别的界面显示"的关键边界；
 * ② [AssistantPageWatcher.shouldShowCustomImage] —— 主界面 ∪ 语音助手窗口；
 *    其中 (null, false) = "watcher 不可用且助手窗口过期" 必须 false ——
 *    旧实现这里是"永显"（降级路径挂上图后没有任何收起路径），即用户报的
 *    「自定义图全部情况下都会显示」，这条断言就是那场事故的回归锁。
 */
class AssistantPageWatcherTest {

    // 与 AssistantPageWatcher companion 同源（private const，测试内镜像一份）
    private val launcherPkg = "com.rokid.os.sprite.launcher"

    // ── ① 主界面判定 ────────────────────────────────────────────

    @Test
    fun isAssistantPage_mainScreen_true() {
        assertTrue(
            AssistantPageWatcher.isAssistantPage(
                launcherPkg,
                "com.rokid.os.sprite.launcher.main.SpriteMainActivity",
            )
        )
    }

    @Test
    fun isAssistantPage_samePackageOtherPage_false() {
        // 同包其他页面（音乐页与主界面同包，类名不含 SpriteMainActivity）必须 false
        assertFalse(
            AssistantPageWatcher.isAssistantPage(
                launcherPkg,
                "com.rokid.os.sprite.launcher.page.music.MusicPageActivity",
            )
        )
    }

    @Test
    fun isAssistantPage_otherPackageWithSameActivityName_false() {
        // 包名必须精确相等：别的包里的同名类不算主界面
        assertFalse(AssistantPageWatcher.isAssistantPage("com.evil.app", "SpriteMainActivity"))
    }

    @Test
    fun isAssistantPage_nullInputs_false() {
        assertFalse(AssistantPageWatcher.isAssistantPage(null, "SpriteMainActivity"))
        assertFalse(AssistantPageWatcher.isAssistantPage(launcherPkg, null))
        assertFalse(AssistantPageWatcher.isAssistantPage(null, null))
    }

    // ── ② 显示决策（并集） ──────────────────────────────────────

    @Test
    fun shouldShow_homeScreenVisible_stays() {
        // 主界面在前台：常显，与助手窗口无关（2026-10-14 原语义）
        assertTrue(AssistantPageWatcher.shouldShowCustomImage(pageVisible = true, assistantActive = false))
    }

    @Test
    fun shouldShow_voiceAssistantWindowActive_shows() {
        // 语音助手活动窗口热：显示（2026-10-24 新需求），主界面此刻被助手盖住
        assertTrue(AssistantPageWatcher.shouldShowCustomImage(pageVisible = false, assistantActive = true))
    }

    @Test
    fun shouldShow_bothInactive_hides() {
        // 既不在主界面也不在助手交互 ⇒ 收起
        assertFalse(AssistantPageWatcher.shouldShowCustomImage(pageVisible = false, assistantActive = false))
    }

    @Test
    fun shouldShow_watcherUnknown_windowActive_shows() {
        // watcher 降级/未知（null）时只剩窗口一条路：对话期间仍显示
        assertTrue(AssistantPageWatcher.shouldShowCustomImage(pageVisible = null, assistantActive = true))
    }

    @Test
    fun shouldShow_watcherUnknown_windowExpired_hides() {
        // ★ 回归锁：watcher 不可用 + 窗口过期 ⇒ 必须收起。
        // 旧降级路径没有收起机制（图挂上后 hideImage 永不被调），用户视角即
        // 「自定义图全部情况下都会显示」。
        assertFalse(AssistantPageWatcher.shouldShowCustomImage(pageVisible = null, assistantActive = false))
    }
}
