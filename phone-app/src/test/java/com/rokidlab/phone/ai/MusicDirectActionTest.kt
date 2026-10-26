package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MusicDirectAction] 直办判据回归（纯函数，无 Android 依赖）。
 *
 * 纪律同 ToolIntentRouterTest：**每条断言都要能红**——下面每组都钉住一类
 * 「误放」或「漏放」边界，任一边界被改松/改紧，对应断言必须失败。
 */
class MusicDirectActionTest {

    // ── 点播（Play）──

    @Test fun `single song play`() {
        assertEquals(
            MusicDirectAction.Plan.Play(listOf("晴天")),
            MusicDirectAction.plan("放一首晴天"),
        )
    }

    @Test fun `bare play verb with song`() {
        assertEquals(
            MusicDirectAction.Plan.Play(listOf("稻香")),
            MusicDirectAction.plan("播放稻香"),
        )
    }

    @Test fun `multi songs split on enumeration comma`() {
        assertEquals(
            MusicDirectAction.Plan.Play(listOf("晴天", "稻香", "七里香")),
            MusicDirectAction.plan("播放晴天、稻香、七里香"),
        )
    }

    @Test fun `polite prefix and trailing particle stripped`() {
        assertEquals(
            MusicDirectAction.Plan.Play(listOf("青花瓷")),
            MusicDirectAction.plan("帮我放一首青花瓷吧"),
        )
    }

    @Test fun `want to hear phrasing`() {
        assertEquals(
            MusicDirectAction.Plan.Play(listOf("波西米亚狂想曲")),
            MusicDirectAction.plan("我想听波西米亚狂想曲"),
        )
    }

    // ── 停止（Stop）──

    @Test fun `negated stop`() {
        assertEquals(MusicDirectAction.Plan.Stop, MusicDirectAction.plan("别放歌了"))
    }

    @Test fun `imperative stop with object`() {
        assertEquals(MusicDirectAction.Plan.Stop, MusicDirectAction.plan("停止播放"))
        assertEquals(MusicDirectAction.Plan.Stop, MusicDirectAction.plan("关掉音乐"))
    }

    @Test fun `explicit stop`() {
        assertEquals(MusicDirectAction.Plan.Stop, MusicDirectAction.plan("不听了"))
    }

    // ── 漏放边界（必须落回完整循环）──

    @Test fun `play without song name falls through`() {
        assertNull(MusicDirectAction.plan("放首歌"))
        assertNull(MusicDirectAction.plan("来点音乐"))
        assertNull(MusicDirectAction.plan("播放音乐"))
        assertNull(MusicDirectAction.plan("放一首"))
    }

    @Test fun `pause and skip are not direct actions`() {
        // control_music 没有 pause/next 动作；办了只会拿到 schema 拒绝文案
        assertNull(MusicDirectAction.plan("暂停一下"))
        assertNull(MusicDirectAction.plan("下一首"))
        assertNull(MusicDirectAction.plan("上一首"))
    }

    @Test fun `bare play verb is not a stop`() {
        // 裸「播放」是点播语义（交给模型补全），绝不能判成停止
        assertNull(MusicDirectAction.plan("播放"))
        assertNull(MusicDirectAction.plan("放"))
    }

    @Test fun `negated play falls through to llm`() {
        // 「别放周杰伦的歌」不是停止（停止模式要求对象只有歌/音乐/了），也不是点播（前缀不含否定词）
        assertNull(MusicDirectAction.plan("别放周杰伦的歌"))
    }

    @Test fun `compound sentences never routed`() {
        assertNull(MusicDirectAction.plan("放一首晴天然后查一下天气"))
        assertNull(MusicDirectAction.plan("播放稻香。顺便设个闹钟"))
        assertNull(MusicDirectAction.plan("放一首晴天，再查天气"))
        assertNull(MusicDirectAction.plan("播放晴天还有稻香"))
    }

    @Test fun `song comment is not a play request`() {
        // 这首歌真好听 / 别放歌了 的对照组：陈述句不直办
        assertNull(MusicDirectAction.plan("这首歌真好听"))
        assertNull(MusicDirectAction.plan("昨天放的那首是什么歌"))
    }

    @Test fun `oversized and multiline input rejected`() {
        // 超 MAX_LEN(64) 与超 MAX_SONG_LEN(24) 两条长度防线各自都要能红
        assertNull(MusicDirectAction.plan("放一首" + "啊".repeat(70)))
        assertNull(MusicDirectAction.plan("放一首" + "啊".repeat(30)))
        assertNull(MusicDirectAction.plan("放一首晴天\n再查天气"))
    }

    @Test fun `task shaped sentence with song word falls through`() {
        // 与 ToolIntentRouter 的全局任务形状对齐：造物句绝不直办
        assertNull(MusicDirectAction.plan("写一个放歌的页面"))
    }

    @Test fun `empty remainder after generic token filter`() {
        // 余项只有通用令牌 ⇒ 等于没给歌名
        assertNull(MusicDirectAction.plan("点歌啥"))
    }

    @Test fun `plan is pure - same input same output`() {
        val a = MusicDirectAction.plan("放一首晴天")
        val b = MusicDirectAction.plan("  放一首晴天  ")
        assertEquals(a, b)
        assertTrue(a is MusicDirectAction.Plan.Play)
    }
}
