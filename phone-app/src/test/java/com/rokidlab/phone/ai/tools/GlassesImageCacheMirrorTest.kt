package com.rokidlab.phone.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlassesImageCacheMirror] 语义钉子 —— 它是「主题刷新慢」修复的正确性根基：
 * 镜像说「眼镜端有」就必须真有，否则场景引用的图画占位框。全部用例按
 * RokidLink `HudOverlayController.cacheImage` 的真实语义（2 槽 LRU）推演。
 */
class GlassesImageCacheMirrorTest {

    @Test
    fun `record then has hit`() {
        val m = GlassesImageCacheMirror()
        m.record("img1", 100)
        assertTrue(m.has("img1", 100))
        assertFalse(m.has("img1", 101)) // 内容变了 = miss
        assertFalse(m.has("img2", 100))
    }

    @Test
    fun `two slot lru evicts oldest`() {
        val m = GlassesImageCacheMirror()
        m.record("img1", 1)
        m.record("img2", 2)
        m.record("img3", 3) // 超 2 槽：淘汰最旧 img1
        assertFalse(m.has("img1", 1))
        assertTrue(m.has("img2", 2))
        assertTrue(m.has("img3", 3))
    }

    /** 与眼镜端 cacheImage 同款：同 id 重推刷新到最新槽，不被当作「最旧」淘汰 */
    @Test
    fun `re-push refreshes slot recency`() {
        val m = GlassesImageCacheMirror()
        m.record("img1", 1)
        m.record("img2", 2)
        m.record("img1", 1) // 刷新 img1 到最新 → img2 成为最旧
        m.record("img3", 3) // 淘汰 img2
        assertTrue(m.has("img1", 1))
        assertFalse(m.has("img2", 2))
        assertTrue(m.has("img3", 3))
    }

    /** 主题来回切：A(图1,2) → B(图3,4) → 回 A ⇒ 图1、2 已被淘汰，必须重推 */
    @Test
    fun `theme ping-pong re-pushes evicted images`() {
        val m = GlassesImageCacheMirror()
        m.record("img1", 1); m.record("img2", 2)   // 主题 A
        m.record("img3", 3); m.record("img4", 4)   // 主题 B：挤掉 img1、img2
        assertFalse(m.has("img1", 1))
        assertFalse(m.has("img2", 2))
        // 回到主题 A：has 全 miss → 调用方重推（正确行为，不是浪费）
    }

    @Test
    fun `generation change resets mirror`() {
        val m = GlassesImageCacheMirror()
        m.onGeneration(1000L)
        m.record("img1", 1)
        assertFalse(m.onGeneration(1000L)) // 同世代：不重置
        assertTrue(m.has("img1", 1))
        assertTrue(m.onGeneration(2000L))  // 眼镜端重连（新 HELLO）：全量重推
        assertFalse(m.has("img1", 1))
    }

    @Test
    fun `evict on failed push forces retry next time`() {
        val m = GlassesImageCacheMirror()
        m.record("img1", 1)
        m.evict("img1")
        assertFalse(m.has("img1", 1))
    }

    /** 失败方向性：任何 miss 只会导致「多推」，绝不出现「镜里有、镜外无」的漏推 */
    @Test
    fun `miss never blocks re-push`() {
        val m = GlassesImageCacheMirror()
        assertFalse(m.has("unknown", 0)) // 从未记录 = miss = 允许重推
        m.record("img1", 1)
        assertTrue(m.has("img1", 1))
        assertEquals(2, GlassesImageCacheMirror.SLOTS) // 与 RokidLink IMAGE_CACHE_SIZE 对齐的哨兵
    }
}
