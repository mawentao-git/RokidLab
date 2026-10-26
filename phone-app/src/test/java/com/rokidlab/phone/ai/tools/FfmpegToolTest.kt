package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolRiskMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFmpeg 工具（[FfmpegToolProvider]）的**参数校验与登记不变式**回归测试。
 *
 * 这一组的风险不在"ffmpeg 会不会跑"，而在**参数校验被悄悄放宽**：
 * 路径放行相对路径/`..` 穿越、编码器名放进选项注入、时间格式放过脏值 ——
 * 每一种都让"结构化工具"退化成"任意 shell 拼接"，且**不报错**。
 * 校验函数全是纯函数（[FfmpegToolProvider] 的 internal 方法），这里逐条钉死。
 */
class FfmpegToolTest {

    // ── 路径：只认两个挂载点前缀的绝对路径 ──

    @Test
    fun `媒体路径只接受两个挂载点下的绝对路径`() {
        assertEquals("/mnt/lab/a.mp4", FfmpegToolProvider.normalizeMediaPath("  /mnt/lab/a.mp4  "))
        assertEquals("/mnt/lab/子目录/b.mkv", FfmpegToolProvider.normalizeMediaPath("/mnt/lab/子目录/b.mkv"))
        assertEquals("/mnt/download/x.mp3", FfmpegToolProvider.normalizeMediaPath("/mnt/download/x.mp3"))
    }

    @Test
    fun `媒体路径拒绝相对路径_穿越_同前缀诈欺与控制字符`() {
        // 相对路径：cwd 语义会漂
        assertNull(FfmpegToolProvider.normalizeMediaPath("a.mp4"))
        assertNull(FfmpegToolProvider.normalizeMediaPath("./a.mp4"))
        // .. 穿越：/mnt/lab/../../etc/passwd
        assertNull(FfmpegToolProvider.normalizeMediaPath("/mnt/lab/../../etc/passwd"))
        assertNull(FfmpegToolProvider.normalizeMediaPath("/mnt/download/../lab/x.mp4"))
        // 同前缀诈欺：/mnt/labX/ 不是 /mnt/lab/
        assertNull(FfmpegToolProvider.normalizeMediaPath("/mnt/labbackup/x.mp4"))
        // 容器里不存在的手机路径
        assertNull(FfmpegToolProvider.normalizeMediaPath("/sdcard/x.mp4"))
        assertNull(FfmpegToolProvider.normalizeMediaPath("/data/local/tmp/x.mp4"))
        // 空与控制字符（换行能让脚本多出一行）
        assertNull(FfmpegToolProvider.normalizeMediaPath(""))
        assertNull(FfmpegToolProvider.normalizeMediaPath("   "))
        assertNull(FfmpegToolProvider.normalizeMediaPath("/mnt/lab/a\nrm -rf /"))
        assertNull(FfmpegToolProvider.normalizeMediaPath("/mnt/lab/a\u0000.mp4"))
    }

    // ── 编码器名：白名单字符 + 禁选项注入 ──

    @Test
    fun `编码器名接受常见值与保留语义`() {
        listOf("libx264", "libx265", "aac", "libmp3lame", "libopus", "vp9", "av1", "copy", "none", "hevc")
            .forEach { c ->
                assertEquals(c, FfmpegToolProvider.normalizeCodec(c))
            }
    }

    @Test
    fun `编码器名拒绝选项注入与脏字符`() {
        assertNull(FfmpegToolProvider.normalizeCodec("-f"))            // 选项注入
        assertNull(FfmpegToolProvider.normalizeCodec("x; rm -rf /"))  // shell 片段
        assertNull(FfmpegToolProvider.normalizeCodec("lib x264"))     // 空白
        assertNull(FfmpegToolProvider.normalizeCodec(""))
        assertNull(FfmpegToolProvider.normalizeCodec("x".repeat(41))) // 超长
    }

    // ── 分辨率：短边 / 精确宽高 → scale 值 ──

    @Test
    fun `分辨率归一化为 scale 滤镜值`() {
        assertEquals("1280:720", FfmpegToolProvider.normalizeResolution("1280x720"))
        assertEquals("-2:720", FfmpegToolProvider.normalizeResolution("720"))
        assertEquals("-2:1080", FfmpegToolProvider.normalizeResolution(" 1080 "))
    }

    @Test
    fun `分辨率拒绝无法转成 scale 的写法`() {
        assertNull(FfmpegToolProvider.normalizeResolution("720p"))
        assertNull(FfmpegToolProvider.normalizeResolution("1280*720"))
        assertNull(FfmpegToolProvider.normalizeResolution("abc"))
        assertNull(FfmpegToolProvider.normalizeResolution(""))
        assertNull(FfmpegToolProvider.normalizeResolution("0"))
        assertNull(FfmpegToolProvider.normalizeResolution("1920x"))   // 半边
    }

    // ── 时间（start/duration） ──

    @Test
    fun `时间参数接受秒_分秒_时分秒与小数`() {
        listOf("10", "1:30", "00:01:30", "1:30.5", "0.25").forEach {
            assertEquals(it, FfmpegToolProvider.normalizeTime(it))
        }
    }

    @Test
    fun `时间参数拒绝脏值`() {
        listOf("abc", "1:2:3:4", "-5", "", "10 s", "1.", ":", "1:2:3.4.5").forEach {
            assertNull("应拒绝：$it", FfmpegToolProvider.normalizeTime(it))
        }
    }

    // ── 码率 ──

    @Test
    fun `码率参数接受 k_M_G 与纯数字`() {
        listOf("1M", "128k", "2500k", "500", "2.5M").forEach {
            assertEquals(it, FfmpegToolProvider.normalizeBitrate(it))
        }
    }

    @Test
    fun `码率参数拒绝带单位后缀与脏值`() {
        listOf("1MB", "128 kb", "abc", "", "-1", "1kbps").forEach {
            assertNull("应拒绝：$it", FfmpegToolProvider.normalizeBitrate(it))
        }
    }

    // ── 展示格式（回填文本的稳定性） ──

    @Test
    fun `时长格式化为时分秒点厘秒`() {
        assertEquals("00:00:10.00", FfmpegToolProvider.formatDuration(10.0))
        assertEquals("00:01:30.50", FfmpegToolProvider.formatDuration(90.5))
        assertEquals("01:02:05.25", FfmpegToolProvider.formatDuration(3725.25))
        assertEquals("未知", FfmpegToolProvider.formatDuration(-1.0))
    }

    @Test
    fun `帧率解析分数形式_未知返回 null`() {
        assertEquals("30", FfmpegToolProvider.formatFrameRate("30/1"))
        assertEquals("29.97", FfmpegToolProvider.formatFrameRate("2997/100"))
        assertEquals("60", FfmpegToolProvider.formatFrameRate("60/1"))
        assertNull(FfmpegToolProvider.formatFrameRate("0/0"))
        assertNull(FfmpegToolProvider.formatFrameRate("N/A"))
    }

    @Test
    fun `码率按 Mbps_kbps 展示`() {
        assertEquals("3.5 Mbps", FfmpegToolProvider.formatBitrate(3_527_000))
        assertEquals("128 kbps", FfmpegToolProvider.formatBitrate(128_000))
    }

    // ── 登记不变式（与 ScreenOpToolTest 同款断言形状） ──

    @Test
    fun `两个 FFmpeg 工具都登记在 shell 域且风险档正确`() {
        val registered = ToolRegistry.toolList.map { it.name }.toSet()
        listOf(FfmpegToolProvider.TOOL_PROBE, FfmpegToolProvider.TOOL_CONVERT).forEach { name ->
            assertTrue("$name 没有登记进工具表（模型永远调不到，且不报错）", name in registered)
            val meta = ToolRegistry.toolList.first { it.name == name }
            assertEquals("$name 必须在 shell 域", ToolRegistry.DOMAIN_SHELL, meta.group)
        }
        // probe 只读；convert 本机副作用
        assertEquals(ToolRisk.READ_ONLY, ToolRiskMap.riskOf(FfmpegToolProvider.TOOL_PROBE))
        assertEquals(ToolRisk.LOCAL_SIDE_EFFECT, ToolRiskMap.riskOf(FfmpegToolProvider.TOOL_CONVERT))
    }

    @Test
    fun `convert 是副作用工具_probe 不是`() {
        // convert 重放不幂等（重跑=完整编码+覆盖输出）⇒ 必须 sideEffect=true（串行泳道、失败不重试）
        assertTrue(FfmpegToolProvider.TOOL_CONVERT in ToolRegistry.SIDE_EFFECT_TOOLS)
        assertFalse(FfmpegToolProvider.TOOL_PROBE in ToolRegistry.SIDE_EFFECT_TOOLS)
        // probe 只读 ⇒ 进只读名单（无人值守可探测）；convert 不在
        assertTrue(FfmpegToolProvider.TOOL_PROBE in ToolRegistry.readOnlyToolNames())
        assertFalse(FfmpegToolProvider.TOOL_CONVERT in ToolRegistry.readOnlyToolNames())
    }

    @Test
    fun `schema 名与声明名一致且带齐必要参数`() {
        ToolRegistry.toolList
            .filter { it.name == FfmpegToolProvider.TOOL_PROBE || it.name == FfmpegToolProvider.TOOL_CONVERT }
            .forEach { meta ->
                val schema = ToolRegistry.buildSchema(meta)
                val fn = schema.getJSONObject("function")
                assertEquals("schema 内 name 必须与声明一致", meta.name, fn.getString("name"))
                val params = fn.getJSONObject("parameters")
                assertEquals("object", params.getString("type"))
                val required = params.getJSONArray("required")
                val requiredNames = (0 until required.length()).map { required.getString(it) }
                if (meta.name == FfmpegToolProvider.TOOL_PROBE) {
                    assertEquals(listOf("path"), requiredNames)
                } else {
                    assertEquals(listOf("input", "output"), requiredNames)
                }
            }
    }
}
