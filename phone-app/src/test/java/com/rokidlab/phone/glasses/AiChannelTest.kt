package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiChannel 双端配置通道协议的单测。
 *
 * 锁定契约：
 *  1. 编码 = [cmd, schemaVersion, ...字段]，schemaVersion 恒为十进制字符串；
 *  2. v1 载荷 roundtrip 无损（mode 空串时回退 custom）；
 *  3. 未知版本（>当前）必须整体拒绝返回 null，防止旧端按错位偏移解析写入；
 *  4. v0 历史载荷（无版本字段）按旧偏移兼容解析；
 *  5. cmd 不符 / 字段缺失 → null。
 */
class AiChannelTest {

    // ── ai_config ──

    @Test
    fun `encodeAiConfig 格式为 cmd 版本 baseUrl apiKey model mode`() {
        val list = AiChannel.encodeAiConfig("https://api.deepseek.com", "sk-123", "deepseek-chat", "custom")
        assertEquals(listOf(
            AiChannel.CMD_AI_CONFIG,
            AiChannel.SCHEMA_VERSION.toString(),
            "https://api.deepseek.com",
            "sk-123",
            "deepseek-chat",
            "custom",
        ), list)
    }

    @Test
    fun `v1 ai_config roundtrip 无损`() {
        val encoded = AiChannel.encodeAiConfig("https://a.b", "key", "m1", AiChannel.AI_MODE_OFFICIAL)
        val cfg = AiChannel.decodeAiConfig(encoded)
        assertEquals(AiChannel.AiConfigFields("https://a.b", "key", "m1", AiChannel.AI_MODE_OFFICIAL), cfg)
    }

    @Test
    fun `v1 ai_config mode 为空时回退 custom`() {
        val cfg = AiChannel.decodeAiConfig(
            listOf(AiChannel.CMD_AI_CONFIG, "1", "https://a.b", "key", "m1", ""),
        )
        assertEquals(AiChannel.AiConfigFields("https://a.b", "key", "m1", AiChannel.AI_MODE_CUSTOM), cfg)
    }

    @Test
    fun `ai_config 未知 schema 版本返回 null 拒绝解析`() {
        val future = listOf(AiChannel.CMD_AI_CONFIG, "2", "https://a.b", "key", "m1", "official")
        assertNull("未来版本载荷必须整体丢弃，禁止按错位偏移解析", AiChannel.decodeAiConfig(future))
    }

    @Test
    fun `v0 ai_config 历史载荷按旧偏移兼容解析`() {
        // 历史版本手机端下发：[cmd, baseUrl, apiKey, model]，无版本字段
        val legacy = listOf(AiChannel.CMD_AI_CONFIG, "https://old.b", "old-key", "old-model")
        assertEquals(
            AiChannel.AiConfigFields("https://old.b", "old-key", "old-model", AiChannel.AI_MODE_CUSTOM),
            AiChannel.decodeAiConfig(legacy),
        )
    }

    @Test
    fun `v0 ai_config 携带 mode 时解析 mode`() {
        val legacy = listOf(AiChannel.CMD_AI_CONFIG, "https://old.b", "old-key", "old-model", "official")
        val cfg = AiChannel.decodeAiConfig(legacy)
        assertEquals(AiChannel.AI_MODE_OFFICIAL, cfg?.mode)
    }

    @Test
    fun `ai_config cmd 不符或字段缺失返回 null`() {
        assertNull(AiChannel.decodeAiConfig(listOf("other_cmd", "1", "url", "k", "m")))
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "1", "url", "k"))) // 长度不足
        assertNull(AiChannel.decodeAiConfig(emptyList()))
        assertNull(AiChannel.decodeAiConfig(listOf(null, "1")))
        // v0 载荷长度不足 4
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "url", "key")))
    }

    // ── key_config ──

    @Test
    fun `key_config v1 roundtrip 与 v0 兼容`() {
        val encoded = AiChannel.encodeKeyConfig("p1", "a1", "p2", "a2")
        assertEquals(listOf(AiChannel.CMD_KEY_CONFIG, "1", "p1", "a1", "p2", "a2"), encoded)
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(encoded),
        )
        // v0：[cmd, shortPkg, shortAct, longPkg, longAct]
        val legacy = listOf(AiChannel.CMD_KEY_CONFIG, "p1", "a1", "p2", "a2")
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(legacy),
        )
    }

    @Test
    fun `key_config 未知版本与非法载荷返回 null`() {
        assertNull(AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "2", "a", "b", "c", "d")))
        assertNull(AiChannel.decodeKeyConfig(listOf("wrong", "1", "a", "b", "c", "d")))
        assertNull(AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "1", "a", "b", "c"))) // 长度不足
    }

    // ── quiz_enabled ──

    @Test
    fun `quiz v1 与 v0 编码解析`() {
        assertEquals(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "true"), AiChannel.encodeQuizConfig(true))
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "true")))
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "false")))
        // v0：[cmd, "true"/"false"]
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "true")))
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "false")))
    }

    @Test
    fun `quiz 未知版本与非法载荷返回 null`() {
        assertNull(AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "2", "true")))
        assertNull(AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1")))
        assertNull(AiChannel.decodeQuizConfig(listOf("wrong", "1", "true")))
        // v1 中 "TRUE" 等非标准写法不视为开启
        assertEquals(false, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "1", "TRUE")))
    }

    // ── tts_play ──

    @Test
    fun `tts_play v1 编码为 cmd 版本 text`() {
        val encoded = AiChannel.encodeTtsPlay("你好，世界")
        assertEquals(listOf(AiChannel.CMD_TTS_PLAY, "1", "你好，世界"), encoded)
    }

    @Test
    fun `tts_play v1 roundtrip 无损`() {
        assertEquals("你好，世界", AiChannel.decodeTtsPlay(AiChannel.encodeTtsPlay("你好，世界")))
        // v1 明文载荷
        assertEquals("播报内容", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", "播报内容")))
    }

    @Test
    fun `tts_play v0 历史载荷按旧偏移兼容解析`() {
        // v0：[cmd, text]，无版本字段
        assertEquals("历史文本", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "历史文本")))
    }

    @Test
    fun `tts_play 纯数字文本歧义防护`() {
        // v0 载荷文本恰为 "1"（纯数字）：size==2 必须按 v0 文本解析，不得当作缺文本的 v1 丢弃
        assertEquals("1", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1")))
        // v1 载荷：[cmd, version="1", text]
        assertEquals("2", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", "2")))
        // v0 文本为其他纯数字同样按 v0 解析
        assertEquals("42", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "42")))
    }

    @Test
    fun `tts_play 非法载荷与未知版本返回 null`() {
        // 未知未来版本
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "2", "text")))
        // v1 文本字段缺失（非字符串）
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", null)))
        // cmd 不符 / 长度不足
        assertNull(AiChannel.decodeTtsPlay(listOf("other", "1", "text")))
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY)))
        assertNull(AiChannel.decodeTtsPlay(emptyList()))
    }

    // ── glasses_ip / show_image / open_app（v1 新增通道）──

    @Test
    fun `glasses_ip v1 roundtrip 与非法载荷拒绝`() {
        val encoded = AiChannel.encodeGlassesIp("192.168.1.23")
        assertEquals(listOf(AiChannel.CMD_GLASSES_IP, "1", "192.168.1.23"), encoded)
        assertEquals("192.168.1.23", AiChannel.decodeGlassesIp(encoded))

        // 未知未来版本：整体丢弃（旧端不得按错位偏移解析出 IP）
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "2", "10.0.0.1")))
        // 缺字段 / 空串 / 空白 IP 一律丢弃（空 IP 会让上层把 ADB 指向 0.0.0.0）
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "1")))
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "1", "")))
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "1", "   ")))
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "1", null)))
        // v0（无版本字段）从未存在过该通道，不得被误解析
        assertNull(AiChannel.decodeGlassesIp(listOf(AiChannel.CMD_GLASSES_IP, "192.168.1.23")))
        assertNull(AiChannel.decodeGlassesIp(listOf("wrong", "1", "192.168.1.23")))
    }

    @Test
    fun `show_image v1 roundtrip 且图片缺失时整体丢弃`() {
        val encoded = AiChannel.encodeShowImage("BASE64JPEG", "一只猫")
        assertEquals(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "BASE64JPEG", "一只猫", "0", "0"), encoded)
        assertEquals(
            AiChannel.ShowImagePayload("BASE64JPEG", "一只猫", persist = false, animated = false),
            AiChannel.decodeShowImage(encoded),
        )

        // caption 允许缺省（悬浮层只显示图片）
        assertEquals(
            AiChannel.ShowImagePayload("BASE64JPEG", "", persist = false, animated = false),
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "BASE64JPEG")),
        )

        // persist=true（设置页手动传图）：第 5 字段 "1"
        assertEquals(
            AiChannel.ShowImagePayload("BASE64JPEG", "一只猫", persist = true, animated = false),
            AiChannel.decodeShowImage(AiChannel.encodeShowImage("BASE64JPEG", "一只猫", persist = true)),
        )
        // animated=true（动画 WebP 透传）：第 6 字段 "1"
        assertEquals(
            AiChannel.ShowImagePayload("B64WEBP", "", persist = true, animated = true),
            AiChannel.decodeShowImage(
                AiChannel.encodeShowImage("B64WEBP", "", persist = true, animated = true),
            ),
        )
        // 旧端 4 字段兼容：缺省 persist = false（12s 老行为）
        assertEquals(
            AiChannel.ShowImagePayload("BASE64JPEG", "一只猫", persist = false, animated = false),
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "BASE64JPEG", "一只猫")),
        )

        assertNull("版本不符必须整体丢弃", AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "2", "x", "c")))
        assertNull("图片数据缺失不得下发空图片", AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "", "c")))
        assertNull(AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", null, "c")))
        assertNull("长度不足（无图片字段）", AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1")))
        assertNull(AiChannel.decodeShowImage(emptyList()))
    }

    @Test
    fun `custom_img_req 与 image_hide roundtrip 且非法载荷丢弃`() {
        assertTrue(AiChannel.decodeCustomImgReq(AiChannel.encodeCustomImgReq()))
        assertTrue(AiChannel.decodeImageHide(AiChannel.encodeImageHide()))
        // 未知未来版本 / cmd 不符 / 空载荷：静默丢弃（不猜字段、不按错位解析）
        assertFalse(AiChannel.decodeCustomImgReq(listOf(AiChannel.CMD_CUSTOM_IMG_REQ, "9")))
        assertFalse(AiChannel.decodeCustomImgReq(listOf("wrong", "1")))
        assertFalse(AiChannel.decodeCustomImgReq(emptyList()))
        assertFalse(AiChannel.decodeImageHide(listOf(AiChannel.CMD_IMAGE_HIDE, "9")))
        assertFalse(AiChannel.decodeImageHide(listOf("wrong", "1")))
        assertFalse(AiChannel.decodeImageHide(emptyList()))
    }

    @Test
    fun `show_image persist 缺省 false 兼容旧端 4 字段`() {
        // 旧版手机端只写 4 字段 ⇒ 新端必须解出 persist=false（12s 老行为），不能崩也不能当 true
        assertEquals(
            AiChannel.ShowImagePayload("B64", "cap", persist = false, animated = false),
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "B64", "cap")),
        )
        // 只有显式 "1" 才是常驻；"0"/"true"/乱值都按 false（宁可不常驻也不误常驻）
        assertEquals(false, AiChannel.decodeShowImage(AiChannel.encodeShowImage("B64", "", persist = false))?.persist)
        assertEquals(true, AiChannel.decodeShowImage(AiChannel.encodeShowImage("B64", "", persist = true))?.persist)
        assertEquals(
            false,
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "B64", "", "true"))?.persist,
        )
    }

    @Test
    fun `show_image animated 缺省 false 兼容旧端 5 字段`() {
        // 旧版手机端只写 5 字段（无 animated）⇒ 新端必须按静态 JPEG 处理，不能崩
        assertEquals(
            AiChannel.ShowImagePayload("B64", "cap", persist = true, animated = false),
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "B64", "cap", "1")),
        )
        // 只有显式 "1" 才是动画；"0"/"true"/乱值都按静态（宁可不动也不误解码）
        assertEquals(
            false,
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "B64", "", "1", "true"))?.animated,
        )
        assertEquals(
            true,
            AiChannel.decodeShowImage(AiChannel.encodeShowImage("B64", "", persist = true, animated = true))?.animated,
        )
    }

    @Test
    fun `open_app v1 roundtrip 且包名或 Activity 缺失时整体丢弃`() {
        val encoded = AiChannel.encodeOpenApp("com.rokid.os.sprite.launcher", ".page.music.MusicPageActivity")
        assertEquals(
            listOf(AiChannel.CMD_OPEN_APP, "1", "com.rokid.os.sprite.launcher", ".page.music.MusicPageActivity"),
            encoded,
        )
        assertEquals(
            "com.rokid.os.sprite.launcher" to ".page.music.MusicPageActivity",
            AiChannel.decodeOpenApp(encoded),
        )

        assertNull("版本不符必须整体丢弃", AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "2", "p", "a")))
        assertNull("包名缺失", AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "1", "", "a")))
        assertNull("Activity 缺失", AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "1", "p", "")))
        assertNull(AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "1", "p", null)))
        assertNull("长度不足", AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "1", "p")))
        assertNull(AiChannel.decodeOpenApp(listOf("wrong", "1", "p", "a")))
    }

    @Test
    fun `跨端 topic 与 cmd 常量稳定（改名即断双端）`() {
        assertEquals("rokidlab_glasses_ip", AiChannel.TOPIC_GLASSES_IP)
        assertEquals("rokidlab_show_image", AiChannel.TOPIC_SHOW_IMAGE)
        assertEquals("rokidlab_open_app", AiChannel.TOPIC_OPEN_APP)
        assertEquals("rokidlab_stop_phone_mirror", AiChannel.TOPIC_STOP_PHONE_MIRROR)
        assertEquals("show_image", AiChannel.CMD_SHOW_IMAGE)
        assertEquals("open_app", AiChannel.CMD_OPEN_APP)
        assertEquals("glasses_ip", AiChannel.CMD_GLASSES_IP)
        assertEquals("stop_phone_mirror", AiChannel.CMD_STOP_PHONE_MIRROR)
        assertEquals("tts_stop", AiChannel.CMD_TTS_STOP)
        assertEquals("rokidlab_chart_push", AiChannel.TOPIC_CHART_PUSH)
        assertEquals("chart_push", AiChannel.CMD_CHART_PUSH)
        assertEquals("rokidlab_chart_resend", AiChannel.TOPIC_CHART_RESEND)
        assertEquals("chart_resend", AiChannel.CMD_CHART_RESEND)
        assertEquals("rokidlab_hud_image", AiChannel.TOPIC_HUD_IMAGE)
        assertEquals("hud_image", AiChannel.CMD_HUD_IMAGE)
    }

    // ── hud_image（HUD 主视觉图片资源）──

    @Test
    fun `hud_image v1 roundtrip 且 id 或图片缺失时整体丢弃`() {
        val encoded = AiChannel.encodeHudImage("img1", "BASE64JPEG")
        assertEquals(
            listOf(AiChannel.CMD_HUD_IMAGE, "1", "img1", "BASE64JPEG"),
            encoded,
        )
        assertEquals(
            AiChannel.HudImagePayload("img1", "BASE64JPEG"),
            AiChannel.decodeHudImage(encoded),
        )
        assertNull("版本不符必须整体丢弃", AiChannel.decodeHudImage(listOf(AiChannel.CMD_HUD_IMAGE, "2", "id", "b")))
        assertNull("id 缺失（引用关联键，不得为空）", AiChannel.decodeHudImage(listOf(AiChannel.CMD_HUD_IMAGE, "1", "", "b")))
        assertNull("图片数据缺失不得下发空图", AiChannel.decodeHudImage(listOf(AiChannel.CMD_HUD_IMAGE, "1", "id", "")))
        assertNull(AiChannel.decodeHudImage(listOf(AiChannel.CMD_HUD_IMAGE, "1", "id", null)))
        assertNull("长度不足（无图片字段）", AiChannel.decodeHudImage(listOf(AiChannel.CMD_HUD_IMAGE, "1", "id")))
        assertNull(AiChannel.decodeHudImage(listOf("wrong", "1", "id", "b")))
    }

    // ── chart_push / chart_resend（图表全屏查看器）──

    @Test
    fun `chart_push v1 roundtrip 且关键字段缺失时整体丢弃`() {
        val encoded = AiChannel.encodeChartPush("1728200000000", "BASE64JPEG", "华为K线")
        assertEquals(
            listOf(AiChannel.CMD_CHART_PUSH, "1", "1728200000000", "BASE64JPEG", "华为K线"),
            encoded,
        )
        assertEquals(
            Triple("1728200000000", "BASE64JPEG", "华为K线"),
            AiChannel.decodeChartPush(encoded),
        )

        // caption 允许缺省（查看器只显示图片）
        assertEquals(
            Triple("id1", "B64", ""),
            AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "1", "id1", "B64")),
        )
        assertNull("版本不符必须整体丢弃", AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "2", "id", "b", "c")))
        assertNull("chartId 缺失（重发关联键，不得为空）", AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "1", "", "b", "c")))
        assertNull("图片数据缺失不得下发空图", AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "1", "id", "", "c")))
        assertNull(AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "1", "id", null, "c")))
        assertNull("长度不足（无图片字段）", AiChannel.decodeChartPush(listOf(AiChannel.CMD_CHART_PUSH, "1", "id")))
        assertNull(AiChannel.decodeChartPush(listOf("wrong", "1", "id", "b", "c")))
    }

    @Test
    fun `chart_resend v1 roundtrip 且 chartId 缺失时拒绝`() {
        val encoded = AiChannel.encodeChartResend("1728200000000")
        assertEquals(listOf(AiChannel.CMD_CHART_RESEND, "1", "1728200000000"), encoded)
        assertEquals("1728200000000", AiChannel.decodeChartResend(encoded))

        assertNull("版本不符必须整体丢弃", AiChannel.decodeChartResend(listOf(AiChannel.CMD_CHART_RESEND, "2", "id")))
        assertNull("chartId 缺失", AiChannel.decodeChartResend(listOf(AiChannel.CMD_CHART_RESEND, "1", "")))
        assertNull(AiChannel.decodeChartResend(listOf(AiChannel.CMD_CHART_RESEND, "1", null)))
        assertNull("长度不足", AiChannel.decodeChartResend(listOf(AiChannel.CMD_CHART_RESEND, "1")))
        assertNull(AiChannel.decodeChartResend(listOf("wrong", "1", "id")))
    }

    // ── 过程时间线：kind 字段（尾部可选，2026-10-15 新增）──

    @Test
    fun `process_step kind 往返且位置 0-5 未变`() {
        val encoded = AiChannel.encodeProcessStep("k1", "正在思考", "", "RUNNING", "THINKING")
        // 位置 0–5 与旧版逐字一致（尾部追加，不动既有偏移）⇒ 旧眼镜按原偏移解析照常工作
        assertEquals(AiChannel.CMD_PROCESS_STEP, encoded[0])
        assertEquals("1", encoded[1])
        assertEquals("k1", encoded[2])
        assertEquals("正在思考", encoded[3])
        assertEquals("", encoded[4])
        assertEquals("RUNNING", encoded[5])
        assertEquals("THINKING", encoded[6])
        val f = AiChannel.decodeProcessStep(encoded)!!
        assertEquals("THINKING", f.kind)
    }

    @Test
    fun `process_step 不传 kind 时编码为尾部空串且解码兜底空串`() {
        val f = AiChannel.decodeProcessStep(AiChannel.encodeProcessStep("k1", "执行中", "d", "RUNNING"))!!
        assertEquals("", f.kind)
        // 旧眼镜（6 字段）送来的帧同样兜底空串：kind 缺失是正常情况，不是协议错误
        assertEquals("", AiChannel.decodeProcessStep(listOf(AiChannel.CMD_PROCESS_STEP, "1", "k1", "l", "d", "OK"))!!.kind)
    }

    // ── 版本契约总检 ──

    @Test
    fun `当前 SCHEMA_VERSION 为 1 且双端命令值稳定`() {
        assertEquals(1, AiChannel.SCHEMA_VERSION)
        assertEquals("ai_config", AiChannel.CMD_AI_CONFIG)
        assertEquals("key_config", AiChannel.CMD_KEY_CONFIG)
        assertEquals("quiz_enabled", AiChannel.CMD_QUIZ_ENABLED)
        assertTrue(AiChannel.decodeAiConfig(AiChannel.encodeAiConfig("u", "k", "m", "custom")) != null)
    }
}
