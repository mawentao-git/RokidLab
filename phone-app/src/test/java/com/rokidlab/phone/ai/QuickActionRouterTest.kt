package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuickActionRouter] 直办判据回归（纯函数，无 Android 依赖）。
 *
 * 纪律同 MusicDirectActionTest：**每条断言都要能红**——每组钉住一类「误放」或
 * 「漏放」边界。误放（用户说 A 却执行 B）比漏放（落回完整循环多花 token）严重得多，
 * 所以否定性断言的比重刻意更大。
 */
class QuickActionRouterTest {

    private fun plan(text: String) = QuickActionRouter.plan(text)

    // ── 音乐（委托 MusicDirectAction，回归锚点） ──

    @Test fun `music play delegated`() {
        val p = plan("放一首晴天")!!
        assertEquals("control_music", p.tool)
        assertEquals("play", p.args.optString("action"))
        assertEquals("晴天", p.args.optJSONArray("songNames")?.opt(0))
    }

    @Test fun `music stop delegated`() {
        assertEquals("control_music", plan("别放歌了")!!.tool)
    }

    // ── 手电筒 ──

    @Test fun `flashlight on`() {
        val p = plan("打开手电筒")!!
        assertEquals("set_flashlight", p.tool)
        assertTrue(p.args.optBoolean("enabled"))
        assertTrue(plan("把手电筒关掉")!!.args.optBoolean("enabled") == false)
        assertTrue(plan("帮我开一下手电筒吧")!!.args.optBoolean("enabled"))
    }

    @Test fun `flashlight compound falls through`() {
        assertNull(plan("打开手电筒然后查天气"))
        assertNull(plan("打开手机的手电筒设置"))
    }

    // ── 找手机 ──

    @Test fun `find phone start and stop`() {
        val p = plan("帮我找手机")!!
        assertEquals("find_phone", p.tool)
        assertEquals("start", p.args.optString("action"))
        assertEquals("stop", plan("别响了")!!.args.optString("action"))
        assertEquals("stop", plan("停止响铃")!!.args.optString("action"))
    }

    /** 「手机在哪」歧义（响铃 vs 定位）→ 交给模型，绝不硬办 */
    @Test fun `ambiguous where is phone falls through`() {
        assertNull(plan("手机在哪"))
        assertNull(plan("我手机去哪了"))
    }

    // ── 音量 ──

    @Test fun `volume mute max percent`() {
        assertEquals(0, plan("静音")!!.args.optInt("volume"))
        assertEquals(100, plan("把音量调到最大")!!.args.optInt("volume"))
        assertEquals(50, plan("音量50")!!.args.optInt("volume"))
        assertEquals(30, plan("媒体音量调到30%")!!.args.optInt("volume"))
    }

    @Test fun `volume out of range and relative fall through`() {
        assertNull(plan("音量200"))
        assertNull(plan("音量-5"))
        assertNull(plan("把音量调大一点"))
        assertNull(plan("音量小声点"))
    }

    // ── 计时器 ──

    @Test fun `timer remind with content`() {
        val p = plan("5分钟后提醒我喝水")!!
        assertEquals("manage_timer", p.tool)
        assertEquals(300L, p.args.optLong("seconds"))
        assertEquals("喝水", p.args.optString("content"))
    }

    @Test fun `timer half hour and bare remind`() {
        val p = plan("半小时后提醒我")!!
        assertEquals(1800L, p.args.optLong("seconds"))
        assertEquals("定时提醒", p.args.optString("content"))
    }

    @Test fun `timer countdown set`() {
        val p = plan("设个10分钟倒计时")!!
        assertEquals(600L, p.args.optLong("seconds"))
        assertEquals("倒计时", p.args.optString("content"))
    }

    @Test fun `timer cancel all`() {
        val p = plan("取消所有提醒")!!
        assertEquals("cancel", p.args.optString("action"))
        assertTrue(p.args.optBoolean("all"))
    }

    @Test fun `timer compound duration falls through`() {
        // 1小时30分钟换算不自含（regex 会切成 content=30分钟后…）→ 交给模型
        assertNull(plan("1小时30分钟后提醒我喝水"))
        // 固定时刻（HH:mm）不是相对时长 → 交给模型
        assertNull(plan("帮我定个闹钟7点"))
        // 0 秒非法
        assertNull(plan("0分钟后提醒我喝水"))
    }

    // ── 只读查询 ──

    @Test fun `time query variants`() {
        assertEquals("get_current_time", plan("现在几点了")!!.tool)
        assertEquals("get_current_time", plan("几点")!!.tool)
        assertEquals("get_current_time", plan("今天星期几")!!.tool)
        assertEquals("get_current_time", plan("今天几号")!!.tool)
    }

    @Test fun `battery query variants`() {
        assertEquals("get_phone_status", plan("手机还有多少电")!!.tool)
        assertEquals("get_phone_status", plan("电量怎么样")!!.tool)
        assertEquals("get_phone_status", plan("电池的电量")!!.tool)
    }

    /** ⚠️ 裸「电」字误伤回归：来电了/没电了/放电 绝不能触发电量查询 */
    @Test fun `battery lookalike sentences fall through`() {
        assertNull(plan("来电了"))
        assertNull(plan("没电了"))
        assertNull(plan("充电中"))
    }

    @Test fun `clipboard query variants`() {
        assertEquals("read_clipboard", plan("看看剪贴板")!!.tool)
        assertEquals("read_clipboard", plan("剪贴板里有什么")!!.tool)
    }

    // ── 打开应用 ──

    @Test fun `open app by name`() {
        val p = plan("打开微信")!!
        assertEquals("open_phone_app", p.tool)
        assertEquals("微信", p.args.optString("appName"))
        assertEquals("支付宝", plan("启动支付宝")!!.args.optString("appName"))
    }

    @Test fun `open device settings word falls through`() {
        // 打开蓝牙/WiFi 不是开应用，是系统设置 → 交给模型走正确工具
        assertNull(plan("打开蓝牙"))
        assertNull(plan("打开WiFi"))
        // 对照组：打开手电筒必须命中专属动作（不能被 open_phone_app 吞掉）
        assertEquals("set_flashlight", plan("打开手电筒")!!.tool)
    }

    @Test fun `open glasses app falls through`() {
        assertNull(plan("打开眼镜上的相册"))
    }

    // ── 纯算式 ──

    @Test fun `math expression passthrough`() {
        val p = plan("378*56")!!
        assertEquals("calculate", p.tool)
        assertEquals("378*56", p.args.optString("expression"))
        assertEquals("(1+2)*3", plan("(1+2)*3=")!!.args.optString("expression"))
        assertEquals("378*56", plan("378×56")!!.args.optString("expression"))
    }

    @Test fun `math lookalike falls through`() {
        assertNull(plan("2016-08-15")) // 日期形参当减法会算出荒谬结果
        assertNull(plan("378 乘 56")) // 中文应用题交给模型
        assertNull(plan("3.14")) // 纯数字无运算符
        assertNull(plan("hello+world")) // 无数字
    }

    // ── 全局漏放边界 ──

    @Test fun `task shaped and long input fall through`() {
        assertNull(plan("写一个放歌的页面"))
        assertNull(plan("帮我查一下明天天气怎么样"))
        assertNull(plan("放一首" + "啊".repeat(70)))
        assertNull(plan("放一首晴天\n再查天气"))
        assertNull(plan(""))
    }
}
