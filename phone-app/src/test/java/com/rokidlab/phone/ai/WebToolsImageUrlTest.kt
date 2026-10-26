package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * extractImageUrls（2026-10-25）的防腐烂测试。
 *
 * 背景：fetch_webpage 原先剥掉所有 HTML 标签，模型永远拿不到页面里的图 ——
 * 「search_web 找参考 → fetch_webpage 读页面 → 拿图」这条链在最后一环断开
 * （用户实测「需要查看图片设计时不主动」的能力根因之一）。
 *
 * 本测试钉住提取规则：直链/懒加载属性/srcset、相对地址解析、data: 与跟踪像素
 * 跳过、去重、limit。规则漂移会让模型拿到坏地址（hud_image 下载失败）或
 * 拿到一串 1px 像素（白占 8 个名额）。
 */
class WebToolsImageUrlTest {

    @Test
    fun `提取 http 直链并保序`() {
        val html = """
            <div><img src="https://cdn.example.com/a.jpg"><img src="http://example.com/b.png"></div>
        """.trimIndent()
        assertEquals(
            listOf("https://cdn.example.com/a.jpg", "http://example.com/b.png"),
            WebTools.extractImageUrls(html),
        )
    }

    @Test
    fun `data 内联图跳过`() {
        val html = """<img src="data:image/png;base64,iVBORw0KGgo="><img src="https://x.com/a.jpg">"""
        assertEquals(listOf("https://x.com/a.jpg"), WebTools.extractImageUrls(html))
    }

    @Test
    fun `跟踪像素与 spacer 跳过`() {
        val html = """
            <img src="https://x.com/px.gif" width="1" height="1">
            <img src="https://x.com/spacer.gif" width="2">
            <img src="https://x.com/real.jpg" width="800">
        """.trimIndent()
        assertEquals(listOf("https://x.com/real.jpg"), WebTools.extractImageUrls(html))
    }

    @Test
    fun `去重且遵守 limit`() {
        val html = """
            <img src="https://x.com/a.jpg"><img src="https://x.com/a.jpg">
            <img src="https://x.com/b.jpg"><img src="https://x.com/c.jpg">
        """.trimIndent()
        assertEquals(listOf("https://x.com/a.jpg", "https://x.com/b.jpg"), WebTools.extractImageUrls(html, limit = 2))
    }

    @Test
    fun `懒加载属性与 srcset 回退`() {
        val html = """
            <img data-src="https://x.com/lazy.jpg">
            <img srcset="https://x.com/s1.jpg 1x, https://x.com/s2.jpg 2x">
            <img data-original="https://x.com/orig.jpg">
        """.trimIndent()
        assertEquals(
            listOf("https://x.com/lazy.jpg", "https://x.com/s1.jpg", "https://x.com/orig.jpg"),
            WebTools.extractImageUrls(html),
        )
    }

    @Test
    fun `懒加载图取真图不取占位 src`() {
        // 负向回顾钉住：src 正则不能命中 data-src= 的尾巴，否则拿的是占位灰条
        val html = """<img src="https://x.com/placeholder.gif" data-src="https://x.com/real.jpg">"""
        assertEquals(listOf("https://x.com/real.jpg"), WebTools.extractImageUrls(html))
    }

    @Test
    fun `相对地址基于页面 URL 解析`() {
        val html = """
            <img src="/static/a.jpg">
            <img src="//cdn.example.com/b.jpg">
            <img src="../c.jpg">
        """.trimIndent()
        assertEquals(
            listOf("https://example.com/static/a.jpg", "https://cdn.example.com/b.jpg"),
            WebTools.extractImageUrls(html, baseUrl = "https://example.com/page/1"),
        )
        // 没有 baseUrl 时不猜相对地址
        assertEquals(emptyList<String>(), WebTools.extractImageUrls("""<img src="/static/a.jpg">"""))
    }

    @Test
    fun `无 img 或属性缺失返回空`() {
        assertEquals(emptyList<String>(), WebTools.extractImageUrls("<p>没有图</p>"))
        assertEquals(emptyList<String>(), WebTools.extractImageUrls("""<img alt="没 src">"""))
    }
}
