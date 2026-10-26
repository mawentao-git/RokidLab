package com.rokidlab.phone.ai

import android.os.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载作用域的**目录解析**（[FileWorkspace.resolveDownloadPath]）回归测试。
 *
 * ## 锁的是哪个 bug
 * `read_text_file(scope="downloads")` 原先只取路径**第一段**当项目名（`Download/<首段>/`），
 * 而写侧（`WebTools.writeProjectFile`）与解压侧（[ArchiveTools.unzipFile]）用的都是
 * **完整路径**。于是 `unzip_file` 解出来的嵌套文件（`app/pages/index/index.ink`）
 * 永远读不到 —— 而两个工具的 schema 互相承诺了这个闭环，用户看到的是
 * "文件明明在、列得出来、就是读不出内容"。
 *
 * 这类 bug 的共性是**纯路径规则**，所以它值得一条不依赖 MediaStore / Context 的单测
 * （[FileWorkspace.resolveDownloadPath] 就是为它抽出来的纯函数）。
 */
class FileWorkspaceDownloadPathTest {

    /**
     * 剥掉「下载目录」前缀后剩下的相对目录。
     *
     * ⚠️ 不能把前缀写死成 `"Download"`：JVM 单测跑在 AGP 的 mockable android.jar 上，
     * `Environment.DIRECTORY_DOWNLOADS` 这个常量在那里被清成了 **null**
     * （首次写本测试时实测 actual = `"null/app/pages/index/"`）。
     * 好在本次回归要锁的是**目录层级**（"完整路径" 还是 "只取首段"），前缀由环境决定，
     * 所以统一剥掉再断言 —— 这样在真机与 mock 环境里都能跑。
     */
    private fun dirOf(r: Pair<String, String>): String =
        r.first.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/")

    @Test
    fun `嵌套路径按完整目录定位`() {
        val r = FileWorkspace.resolveDownloadPath("", "app/pages/index/index.ink")!!
        assertEquals("app/pages/index/", dirOf(r))
        assertEquals("app/pages/index/index.ink", r.second)
    }

    @Test
    fun `单层路径与根目录文件`() {
        val nested = FileWorkspace.resolveDownloadPath("", "app/app.json")!!
        assertEquals("app/", dirOf(nested))
        assertEquals("app/app.json", nested.second)

        val root = FileWorkspace.resolveDownloadPath("", "readme.txt")!!
        assertEquals("根目录文件：目录部分为空", "", dirOf(root))
        assertEquals("readme.txt", root.second)
    }

    @Test
    fun `另传 project 时补在路径前面`() {
        val r = FileWorkspace.resolveDownloadPath("app", "pages/index/index.ink")!!
        assertEquals("app/pages/index/", dirOf(r))
        assertEquals("app/pages/index/index.ink", r.second)
    }

    @Test
    fun `path 已自带项目名时不会被重复拼接`() {
        val r = FileWorkspace.resolveDownloadPath("app", "app/pages/index.ink")!!
        assertEquals("app/pages/", dirOf(r))
        assertEquals("app/pages/index.ink", r.second)
    }

    @Test
    fun `反斜杠与首尾斜杠被归一`() {
        assertEquals(
            FileWorkspace.resolveDownloadPath("", "app/index.ink"),
            FileWorkspace.resolveDownloadPath("", "\\app\\index.ink\\"),
        )
    }

    @Test
    fun `逃逸与非法规格一律返回 null`() {
        // 空路径（读文件必须有具体目标）
        assertNull(FileWorkspace.resolveDownloadPath("", ""))
        // 路径穿越
        assertNull(FileWorkspace.resolveDownloadPath("", "../secret.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("", "app/../../secret.txt"))
        // 段名不合规（段内空格起始、非法字符）
        // ⚠️ 注意：整串的首尾空白会先被 trim 掉，所以用例要打在**段中间**（"app/ x.txt"）
        assertNull(FileWorkspace.resolveDownloadPath("", "app/ x.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("", "app/x:y.txt"))
        // 项目名不合规（与 WebTools 的项目名规则同源）
        assertNull(FileWorkspace.resolveDownloadPath("bad/name", "x.txt"))
        assertNull(FileWorkspace.resolveDownloadPath("bad name", "x.txt"))
    }

    // ═══════════════════════════════════════════════════════
    // 删除用的路径归一（normalizeDownloadRel）
    //
    // ## 锁的是哪个 bug（2026-10-22 用户实测）
    // 用户："预览有，但我说删除又说没找到？"
    // 真因：`delete_file(scope="downloads")` 只认「相对 Download」的路径，而模型几乎总
    // 沿用**容器坐标系** `/mnt/lab/x.mp4`（ffmpeg / run_shell / 录像工具全程这么说话，
    // schema 里也是这个形态）⇒ 旧实现拼出前缀 `Download/mnt/lab/` 去匹配 MediaStore，
    // 永远落空，于是"文件明明在、卡片能预览、就是删不掉"。
    //
    // 这组用例把「同一个文件的几种说法必须归一到同一个结果」钉死：
    // 任何一条挂掉都意味着用户又会看到"找不到文件"。
    // ═══════════════════════════════════════════════════════

    @Test
    fun `容器路径与宿主路径归一为同一个相对路径`() {
        val expect = "Lab/glasses_rec_20261022_120000.mp4"
        // 容器共享盘（ffmpeg / run_shell / 录像工具都说这个）
        assertEquals(expect, FileWorkspace.normalizeDownloadRel("/mnt/lab/glasses_rec_20261022_120000.mp4"))
        // 宿主绝对路径（聊天卡片/文件管理器里看到的）
        assertEquals(expect, FileWorkspace.normalizeDownloadRel("/sdcard/Download/Lab/glasses_rec_20261022_120000.mp4"))
        assertEquals(
            expect,
            FileWorkspace.normalizeDownloadRel("/storage/emulated/0/Download/Lab/glasses_rec_20261022_120000.mp4"),
        )
        // 裸 Download 前缀 / 原生相对路径
        assertEquals(expect, FileWorkspace.normalizeDownloadRel("Download/Lab/glasses_rec_20261022_120000.mp4"))
        assertEquals(expect, FileWorkspace.normalizeDownloadRel("Lab/glasses_rec_20261022_120000.mp4"))
        // 首尾斜杠与反斜杠
        assertEquals(expect, FileWorkspace.normalizeDownloadRel("\\mnt\\lab\\glasses_rec_20261022_120000.mp4\\"))
    }

    @Test
    fun `嵌套目录同样归一`() {
        // 共享盘里的子目录
        assertEquals(
            "Lab/sub/x.mp4",
            FileWorkspace.normalizeDownloadRel("/mnt/lab/sub/x.mp4"),
        )
        // Download 下别的应用目录（同样该能用相对路径访问）
        assertEquals(
            "someapp/x.mp4",
            FileWorkspace.normalizeDownloadRel("Download/someapp/x.mp4"),
        )
        // ⚠️ 特意不测 `/mnt/download/...`：那不是本 App 的挂载点（共享盘只有 /mnt/lab），
        //   它归一后是个 Download 下不存在的相对路径 —— 查不到是**正确**行为，
        //   写成"应当归一成 a.mp4"反而会把错误语义固化下来。
    }

    @Test
    fun `归一后仍拒绝逃逸与非法输入`() {
        // 指向整个共享目录：删除请求必须拒绝（等价于"清空 Download/Lab"）
        assertNull(FileWorkspace.normalizeDownloadRel("/mnt/lab"))
        assertNull(FileWorkspace.normalizeDownloadRel("/mnt/lab/"))
        // 空与纯斜杠
        assertNull(FileWorkspace.normalizeDownloadRel(""))
        assertNull(FileWorkspace.normalizeDownloadRel("///"))
        assertNull(FileWorkspace.normalizeDownloadRel("Download/"))
        // 穿越（归一化不能变成绕过 splitRel 的后门）
        assertNull(FileWorkspace.normalizeDownloadRel("/mnt/lab/../../etc/passwd"))
        assertNull(FileWorkspace.normalizeDownloadRel("Lab/../secret.txt"))
        // 段名非法
        assertNull(FileWorkspace.normalizeDownloadRel("/mnt/lab/x:y.mp4"))
    }

    @Test
    fun `护栏判据在归一化之后仍落在同一个坐标系`() {
        // ⚠️ 护栏只认**下载根**那份（`RokidLab规则.md`，无斜杠）—— 子目录里的同名文件
        //   不参与提示词注入，拦它属于过度保护（见 PromptGuardrails 的判据注释）。
        //   这里锁的是：归一化把各类写法收敛到同一坐标系后，护栏判据仍能正确落到根上。
        assertEquals("RokidLab规则.md", FileWorkspace.normalizeDownloadRel("/sdcard/Download/RokidLab规则.md"))
        assertEquals("RokidLab规则.md", FileWorkspace.normalizeDownloadRel("Download/RokidLab规则.md"))
        assertTrue(PromptGuardrails.isGuardedDownloadPath("RokidLab规则.md"))

        // 共享盘（子目录）里的同名文件归一后带目录段 ⇒ 不参与护栏，但路径本身是合法的
        assertEquals("Lab/RokidLab规则.md", FileWorkspace.normalizeDownloadRel("/mnt/lab/RokidLab规则.md"))
        assertFalse(PromptGuardrails.isGuardedDownloadPath("Lab/RokidLab规则.md"))
    }
}
