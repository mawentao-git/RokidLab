package com.rokidlab.phone.adb

import com.rokidlab.phone.util.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * `AdbFileManagerClient` 的 sync 子流协议 + `drainStalePackets` 回归测试。
 *
 * 覆盖三个历史上只能真机复现的坑：
 *  1. **陈旧 CLSE 被误读成本次响应**：上一条命令残留的 CLSE 会撞进新命令的读取序列，
 *     表现为「刚下载就报失败」/「随机命令失败」。修复是每条命令前先 `drainStalePackets`
 *     把陈旧包排空（收到 CLSE 要回一个交换过 arg0/arg1 的 CLSE 才算干净关闭）。
 *  2. **FAIL 被当成成功**：远端回 FAIL（文件不存在 / 无权限）时必须失败，而不是留个空文件。
 *  3. **截断被当成成功**（2026-10-18 补测）：字节数与远端不符仍报 true，
 *     事故形态是「8.7MB 的 APK 只落 64KB，却告诉用户已保存」。修复是落盘后对账、
 *     不符即删半成品。⚠️ 该修复早已在 HEAD，但此前**零测试覆盖** ——
 *     测了「成功」与「FAIL」两条路，独独没测事故本体那条（见类内注释）。
 *
 * 脚本分段语义见 [ScriptedPeer]：段间读超时 —— drainStalePackets 正是靠读超时退出的，
 * 所以第一段用来喂「陈旧包」（空段即「一读就超时」）。
 */
class AdbFileManagerSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var written: ByteArrayOutputStream

    @Before
    fun setUp() {
        LogCollector.clear()
        written = ByteArrayOutputStream()
    }

    private fun clientFor(vararg segments: ByteArray): AdbFileManagerClient =
        AdbFileManagerClient(null, "127.0.0.1").also {
            it.attachStreamsForTest(ScriptedPeer(segments.toList()), written)
        }

    private fun newTarget(): String = File(tmp.newFolder(), "out.bin").absolutePath

    private fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) =
        AdbTestProto.packet(command, arg0, arg1, payload)

    private fun deviceFrame(id: String, data: ByteArray = ByteArray(0)) =
        packet(AdbTestProto.CMD_WRTE, REMOTE_ID, SID, AdbTestProto.syncFrame(id, data))

    /** sync 建流应答 + RECV 应答，再拼后续帧 */
    private fun syncOpen(vararg frames: ByteArray): ByteArray {
        val okays = packet(AdbTestProto.CMD_OKAY, REMOTE_ID, SID) +
            packet(AdbTestProto.CMD_OKAY, REMOTE_ID, SID)
        return frames.fold(okays) { acc, f -> acc + f }
    }

    private fun sent(): List<AdbTestProto.Written> = AdbTestProto.parseWritten(written.toByteArray())

    // ── downloadFile ──

    @Test
    fun `downloadFile 成功：DATA 累加写入，DONE 收尾返回 true`() {
        val client = clientFor(
            ByteArray(0), // drainStalePackets：无陈旧包，一次读超时即退出
            syncOpen(
                deviceFrame("DATA", "abc".toByteArray()),
                deviceFrame("DATA", "def".toByteArray()),
                deviceFrame("DONE"),
            ),
        )
        val target = File(newTarget())

        val ok = client.downloadFile("/sdcard/a.bin", target.absolutePath)

        assertTrue(ok)
        assertEquals("abcdef", target.readText())
        assertEquals("OPEN", "sync:\u0000", String(sent()[0].payload, Charsets.UTF_8))
        assertEquals(
            "DONE 之后必须关闭 sync 流",
            AdbTestProto.CMD_CLSE,
            sent().last().command,
        )
        assertEquals(SID, sent().last().arg0)
        assertEquals(REMOTE_ID, sent().last().arg1)
    }

    @Test
    fun `downloadFile 收到 FAIL：返回 false 且落 LogCollector`() {
        val client = clientFor(
            ByteArray(0),
            syncOpen(deviceFrame("FAIL", "no such file".toByteArray())),
        )
        val target = File(newTarget())

        val ok = client.downloadFile("/sdcard/missing.bin", target.absolutePath)

        assertFalse("远端 FAIL 必须如实返回失败", ok)
        assertTrue(
            "关键链路下载失败必须落 LogCollector",
            LogCollector.getErrorLogText().contains("AdbFileManager"),
        )
    }

    @Test
    fun `downloadFile 前的 drainStalePackets 会关闭陈旧 CLSE 且不影响本次下载`() {
        // 陈旧流：对端分配过的 remoteId=99 / localId=77，与本次无关
        val staleClse = packet(AdbTestProto.CMD_CLSE, 99, 77)
        val client = clientFor(
            staleClse,
            syncOpen(deviceFrame("DATA", "ok".toByteArray()), deviceFrame("DONE")),
        )
        val target = File(newTarget())

        assertTrue("陈旧包必须被排空，不能污染本次命令", client.downloadFile("/sdcard/a.bin", target.absolutePath))
        assertEquals("ok", target.readText())

        val first = sent().first()
        assertEquals("排空陈旧 CLSE 时必须以 CLSE 应答", AdbTestProto.CMD_CLSE, first.command)
        assertEquals("关闭陈旧流要交换 arg0/arg1（arg0=对方的 localId）", 77, first.arg0)
        assertEquals(99, first.arg1)
    }

    // ── 落盘大小对账（事故：「8.7MB APK 只落 64KB 却报已保存」）──
    //
    // 📌 这组测的是**已落地但零覆盖**的 `reconcileDownloadedSize`：
    // 该修复在 HEAD 里，但 AdbFileManagerSyncTest 只测了「正常下载成功」与「远端 FAIL」，
    // 从没测过"下载成功但字节数不符"这条分支 —— 而它恰恰是事故本体。
    // 契约（与 AdbShellClient.pullFile 同源）：
    //   expectedSize >= 0 且实际 != 期望 ⇒ 判失败 + **删掉半成品** + 落 LogCollector。
    // 半成品必须删：留在 cache 里会被后续预览/分享当完整文件用（这比报错更糟）。

    @Test
    fun `downloadFile 字节数不符：判失败并删除半成品`() {
        val client = clientFor(
            ByteArray(0),
            syncOpen(
                deviceFrame("DATA", "abc".toByteArray()), // 实际只落 3 字节
                deviceFrame("DONE"),
            ),
        )
        val target = File(newTarget())

        val ok = client.downloadFile("/sdcard/a.bin", target.absolutePath, expectedSize = 8_700_000L)

        assertFalse("字节数与远端不符必须判失败，不能报已保存", ok)
        assertFalse("截断的半成品必须删除，否则会被当完整文件用", target.exists())
        assertTrue(
            "截断必须落 LogCollector（此前完全不可见）",
            LogCollector.getErrorLogText().contains("截断"),
        )
    }

    @Test
    fun `downloadFile 字节数相符：正常成功且保留文件`() {
        val client = clientFor(
            ByteArray(0),
            syncOpen(deviceFrame("DATA", "abcdef".toByteArray()), deviceFrame("DONE")),
        )
        val target = File(newTarget())

        assertTrue(client.downloadFile("/sdcard/a.bin", target.absolutePath, expectedSize = 6L))
        assertTrue("字节数相符时文件必须留下", target.exists())
        assertEquals("abcdef", target.readText())
    }

    @Test
    fun `downloadFile 未提供远端大小：跳过对账保持旧行为`() {
        // expectedSize = -1 是「拿不到远端大小」的合法情形（如从别的入口调用），
        // 此时跳过对账而不是误判失败 —— 否则会把正常下载全部打掉。
        val client = clientFor(
            ByteArray(0),
            syncOpen(deviceFrame("DATA", "abc".toByteArray()), deviceFrame("DONE")),
        )
        val target = File(newTarget())

        assertTrue(client.downloadFile("/sdcard/a.bin", target.absolutePath))
        assertEquals("未提供大小时不做对账", "abc", target.readText())
    }

    // ── parseDateTime（ls -la 时间列解析）──

    @Test
    fun `parseDateTime 解析 yyyy-MM-dd 格式`() {
        val millis = AdbFileManagerClient.parseDateTime("2024-01-15", "10:30")
        assertTrue("合法日期必须解析成功", millis > 0)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        assertEquals("2024-01-15 10:30", fmt.format(java.util.Date(millis)))
    }

    @Test
    fun `parseDateTime 解析月份英文格式按当年计算`() {
        val millis = AdbFileManagerClient.parseDateTime("Jun", "7")
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        assertEquals("年份取当前年", Calendar.getInstance().get(Calendar.YEAR), cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(7, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `parseDateTime 非法输入返回 0 而不是抛异常`() {
        assertEquals(0L, AdbFileManagerClient.parseDateTime("not-a-month", "7"))
        assertEquals(0L, AdbFileManagerClient.parseDateTime("Jun", "not-a-day"))
        assertEquals(0L, AdbFileManagerClient.parseDateTime("2024-01-15", "bad-time"))
    }

    private companion object {
        const val SID = 1
        const val REMOTE_ID = 7
    }
}
