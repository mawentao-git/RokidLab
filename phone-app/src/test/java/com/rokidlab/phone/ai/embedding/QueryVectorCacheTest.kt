package com.rokidlab.phone.ai.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [QueryVectorCache] 行为锁定（2026-10-23）。
 *
 * 锁的是三条不变式：
 *  ① 同键只调一次嵌入（这是整个优化存在的理由 —— 少了它缓存就白建）；
 *  ② 失败不落缓存（降级路必须每次都有机会重试，不能被一次网络抖动钉死）；
 *  ③ 线程隔离（生成线程与工具线程互不串味 —— ThreadLocal 是前提假设）。
 */
class QueryVectorCacheTest {

    private fun vec(vararg xs: Float) = xs

    @Test
    fun sameKeyEmbedsOnce() {
        QueryVectorCache.clearForTest()
        var calls = 0
        repeat(3) {
            val v = QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "今天天气") {
                calls++
                vec(1f, 0f)
            }
            assertNotNull(v)
        }
        assertEquals("同 (endpoint, model, query) 三次取用只应触发一次嵌入", 1, calls)
    }

    @Test
    fun differentQueryOrModelReEmbeds() {
        QueryVectorCache.clearForTest()
        var calls = 0
        val embed = {
            calls++
            vec(1f, 0f)
        }
        QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "问题A", embed)
        QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "问题B", embed)
        QueryVectorCache.getOrEmbed("https://api.example.com", "other-model", "问题A", embed)
        QueryVectorCache.getOrEmbed("https://other.example.com", "bge-m3", "问题A", embed)
        assertEquals("query/model/baseUrl 任一不同都必须重新嵌入", 4, calls)
    }

    @Test
    fun failureIsNotCached() {
        QueryVectorCache.clearForTest()
        var attempts = 0
        // 第一次失败（模拟网络抖动）：返回 null，不得落缓存
        val first = QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q") {
            attempts++
            null
        }
        assertNull(first)
        // 第二次成功：必须真的再打一次（而不是复用"失败的 null"）
        val second = QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q") {
            attempts++
            vec(0.5f, 0.5f)
        }
        assertNotNull(second)
        assertEquals("失败不落缓存：两次取用应触发两次嵌入", 2, attempts)
    }

    @Test
    fun exceptionPropagatesAndIsNotCached() {
        QueryVectorCache.clearForTest()
        var attempts = 0
        val boom = {
            attempts++
            throw IllegalStateException("embeddings HTTP 502")
        }
        runCatching {
            QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q", boom)
        }
        assertEquals("异常必须原样抛给调用方（各自的降级路在调用侧）", 1, attempts)
        // 异常后同键再次取用：照旧重新尝试
        runCatching {
            QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q", boom)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun threadsAreIsolated() {
        QueryVectorCache.clearForTest()
        val mainCalls = java.util.concurrent.atomic.AtomicInteger()
        val otherCalls = java.util.concurrent.atomic.AtomicInteger()
        QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "共享问题") {
            mainCalls.incrementAndGet(); vec(1f)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        Thread {
            // 另一条线程首次取同一键：必须自己嵌一次（ThreadLocal 隔离）
            QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "共享问题") {
                otherCalls.incrementAndGet(); vec(1f)
            }
            latch.countDown()
        }.start()
        latch.await()
        assertEquals(1, mainCalls.get())
        assertEquals("另一条线程的缓存互不可见", 1, otherCalls.get())
    }

    @Test
    fun cachedVectorIsSameInstance() {
        QueryVectorCache.clearForTest()
        val v1 = QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q") { vec(1f, 2f) }
        val v2 = QueryVectorCache.getOrEmbed("https://api.example.com", "bge-m3", "q") { vec(9f, 9f) }
        assertSame("命中缓存时应返回同一份向量（零拷贝复用）", v1, v2)
    }
}
