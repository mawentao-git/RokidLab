package com.rokidlab.phone.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 共享盘路径白名单护栏。
 *
 * `show_image` 现在接受容器内路径（/mnt/lab、/mnt/download），这是本功能**唯一**的安全边界：
 * 解析放宽成"任意绝对路径"就等于把整个手机存储暴露给模型。这个测试钉住三件事：
 *  1. 只认两个挂载点，且必须是子路径（挂载点本身、同前缀的 /mnt/labx 都不算）；
 *  2. `/mnt/download` 未挂载（无「所有文件访问」）时必须拒绝；
 *  3. 其它绝对路径、URL 一律拒绝。
 */
class GuestMountPathTest {

    private fun lab(rel: String) = ProotShell.GUEST_SHARE + rel

    @Test
    fun `lab 下的文件路径解析成相对路径`() {
        val (root, rel) = ProotShell.guestMountOf(lab("/a.jpg"), downloadMounted = false)!!
        assertEquals(ProotShell.ShareRoot.LAB, root)
        assertEquals("/a.jpg", rel)
    }

    @Test
    fun `lab 支持多级子目录`() {
        val (root, rel) = ProotShell.guestMountOf(lab("/out/2026/x.png"), downloadMounted = false)!!
        assertEquals(ProotShell.ShareRoot.LAB, root)
        assertEquals("/out/2026/x.png", rel)
    }

    @Test
    fun `首尾空白被忽略`() {
        val (_, rel) = ProotShell.guestMountOf("  ${lab("/a.jpg")}  ", downloadMounted = false)!!
        assertEquals("/a.jpg", rel)
    }

    @Test
    fun `挂载点本身不是文件路径`() {
        assertNull(ProotShell.guestMountOf(ProotShell.GUEST_SHARE, downloadMounted = false))
    }

    @Test
    fun `同前缀但不同目录必须拒绝`() {
        assertNull(ProotShell.guestMountOf("${ProotShell.GUEST_SHARE}x/a.jpg", downloadMounted = false))
        assertNull(ProotShell.guestMountOf("${ProotShell.GUEST_SHARE}_old/a.jpg", downloadMounted = false))
    }

    @Test
    fun `download 已挂载时可解析`() {
        val (root, rel) = ProotShell.guestMountOf(
            "${ProotShell.GUEST_DOWNLOAD}/pic.png",
            downloadMounted = true,
        )!!
        assertEquals(ProotShell.ShareRoot.DOWNLOAD, root)
        assertEquals("/pic.png", rel)
    }

    @Test
    fun `download 未挂载时一律拒绝`() {
        assertNull(ProotShell.guestMountOf("${ProotShell.GUEST_DOWNLOAD}/pic.png", downloadMounted = false))
    }

    @Test
    fun `其它绝对路径一律拒绝`() {
        listOf(
            "/etc/passwd",
            "/sdcard/DCIM/a.jpg",
            "/data/data/com.rokidlab.phone/files/a.jpg",
            "/",
            "",
        ).forEach { assertNull("应拒绝：$it", ProotShell.guestMountOf(it, downloadMounted = true)) }
    }

    @Test
    fun `URL 与 file scheme 一律拒绝`() {
        listOf(
            "https://example.com/a.jpg",
            "http://example.com/a.jpg",
            "file:///sdcard/a.jpg",
            "content://media/external/images/1",
            "data:image/png;base64,AAAA",
        ).forEach { assertNull("应拒绝：$it", ProotShell.guestMountOf(it, downloadMounted = true)) }
    }
}
