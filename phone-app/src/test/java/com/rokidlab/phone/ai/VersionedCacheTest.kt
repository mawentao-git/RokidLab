package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionedCache] 行为锁定（2026-10-23）。
 *
 * 锁的是四条不变式（缺任何一条，缓存要么白建、要么静默陈旧）：
 *  ① 同版本同键只算一次 —— 这是优化存在的理由；
 *  ② 版本号变化必须重算 —— 防"内容变了还给旧值"（比慢更糟的静默错误）；
 *  ③ 键变化必须重算 —— 防两个切片（分组）交替请求时串味；
 *  ④ null 是有效缓存值 —— 零技能场景不能每轮重扫目录。
 */
class VersionedCacheTest {

    @Test
    fun sameVersionAndKeyComputesOnce() {
        val cache = VersionedCache<String>()
        var calls = 0
        repeat(3) {
            val v = cache.getOrCompute(version = 7, key = "g1") { calls++; "清单A" }
            assertEquals("清单A", v)
        }
        assertEquals("同版本同键三次取用只应渲染一次", 1, calls)
    }

    @Test
    fun versionBumpRecomputes() {
        val cache = VersionedCache<String>()
        var calls = 0
        cache.getOrCompute(version = 1, key = "g1") { calls++; "旧" }
        val v = cache.getOrCompute(version = 2, key = "g1") { calls++; "新" }
        assertEquals("版本号 +1 后必须拿到新内容（写入方已 bump）", "新", v)
        assertEquals(2, calls)
    }

    @Test
    fun keyChangeRecomputes() {
        val cache = VersionedCache<String>()
        var calls = 0
        cache.getOrCompute(version = 1, key = setOf("skillA")) { calls++; "A组清单" }
        val v = cache.getOrCompute(version = 1, key = setOf("skillB")) { calls++; "B组清单" }
        assertEquals("键不同必须重算，否则两个分组串味", "B组清单", v)
        assertEquals(2, calls)
    }

    @Test
    fun nullValueIsCached() {
        val cache = VersionedCache<String?>()
        var calls = 0
        val first = cache.getOrCompute(version = 1, key = null) { calls++; null }
        assertNull("零技能 = null 是有效语义", first)
        val second = cache.getOrCompute(version = 1, key = null) { calls++; "不该被算出来" }
        assertNull(second)
        assertEquals("null 也必须落缓存：否则零技能场景每轮重扫目录", 1, calls)
        assertTrue(cache.hasForTest())
    }

    @Test
    fun freshCacheHasNothing() {
        val cache = VersionedCache<String>()
        assertFalse("新缓存必须处于未缓存态", cache.hasForTest())
        var calls = 0
        cache.getOrCompute(version = 1, key = null) { calls++; "x" }
        assertTrue(calls == 1)
    }

    @Test
    fun cachedValueIsSameInstance() {
        val cache = VersionedCache<List<String>>()
        val v1 = cache.getOrCompute(version = 1, key = null) { listOf("a") }
        val v2 = cache.getOrCompute(version = 1, key = null) { listOf("b") }
        assertSame("命中缓存时零拷贝复用", v1, v2)
    }
}
