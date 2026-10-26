package com.rokidlab.rokidlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 眼镜端 AiChannel 协议冒烟测试。
 *
 * 说明：AiChannel.kt 在 phone-app 与 RokidLink 各持一份同源副本
 * （phone-app 的 preBuild 有 checkAiChannelSynced 守护，双端逐字一致），
 * 完整的协议契约单测在 phone-app 的 AiChannelTest（17 用例）。
 * 本文件只做眼镜端核心解析路径的冒烟锁定，避免测试重复维护；
 * 眼镜端自身纯逻辑（非同源代码）请在此追加测试。
 */
class AiChannelProtocolTest {

    @Test
    fun `v1 tts_play roundtrip`() {
        val encoded = AiChannel.encodeTtsPlay("眼镜本地播报")
        assertEquals(listOf(AiChannel.CMD_TTS_PLAY, "1", "眼镜本地播报"), encoded)
        assertEquals("眼镜本地播报", AiChannel.decodeTtsPlay(encoded))
    }

    @Test
    fun `v0 tts_play 历史文本与纯数字歧义兼容`() {
        // v0：[cmd, text]，无版本字段
        assertEquals("历史文本", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "历史文本")))
        // 文本恰为 "1"（size==2 恒按 v0 文本解析，不当作缺文本的 v1）
        assertEquals("1", AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1")))
    }

    @Test
    fun `非法或未知版本 tts_play 整体丢弃`() {
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "2", "text"))) // 未来版本
        assertNull(AiChannel.decodeTtsPlay(listOf(AiChannel.CMD_TTS_PLAY, "1", null))) // 文本缺失
        assertNull(AiChannel.decodeTtsPlay(listOf("other", "1", "text")))
        assertNull(AiChannel.decodeTtsPlay(emptyList()))
    }

    @Test
    fun `config 类通道 v1 与 v0 解析契约`() {
        assertEquals(
            AiChannel.AiConfigFields("https://a.b", "k", "m", AiChannel.AI_MODE_CUSTOM),
            AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "1", "https://a.b", "k", "m", "")),
        )
        // v0 key_config：[cmd, pkg...] 无版本字段
        assertEquals(
            AiChannel.KeyConfigFields("p1", "a1", "p2", "a2"),
            AiChannel.decodeKeyConfig(listOf(AiChannel.CMD_KEY_CONFIG, "p1", "a1", "p2", "a2")),
        )
        // v0 quiz：[cmd, "true"]
        assertEquals(true, AiChannel.decodeQuizConfig(listOf(AiChannel.CMD_QUIZ_ENABLED, "true")))
        // 未知版本全部拒绝
        assertNull(AiChannel.decodeAiConfig(listOf(AiChannel.CMD_AI_CONFIG, "9", "u", "k", "m", "c")))
    }

    @Test
    fun `v1 新增下行通道 roundtrip`() {
        // 眼镜端只需能解析出手机端下发的图片/跳转指令；边界矩阵在 phone-app 的 AiChannelTest
        assertEquals("192.168.1.23", AiChannel.decodeGlassesIp(AiChannel.encodeGlassesIp("192.168.1.23")))
        assertEquals(
            AiChannel.ShowImagePayload("BASE64JPEG", "一只猫", persist = false, animated = false),
            AiChannel.decodeShowImage(AiChannel.encodeShowImage("BASE64JPEG", "一只猫")),
        )
        // 动画 WebP：第 6 字段，眼镜端据此走 AnimatedImageDrawable 播放路径
        assertEquals(
            AiChannel.ShowImagePayload("B64WEBP", "", persist = true, animated = true),
            AiChannel.decodeShowImage(AiChannel.encodeShowImage("B64WEBP", "", persist = true, animated = true)),
        )
        // 旧端 5 字段（无 animated）按静态处理
        assertEquals(
            AiChannel.ShowImagePayload("B64", "cap", persist = true, animated = false),
            AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "1", "B64", "cap", "1")),
        )
        assertEquals(
            "com.rokid.os.sprite.launcher" to ".page.music.MusicPageActivity",
            AiChannel.decodeOpenApp(AiChannel.encodeOpenApp("com.rokid.os.sprite.launcher", ".page.music.MusicPageActivity")),
        )
        // 未知版本一律整体丢弃
        assertNull(AiChannel.decodeOpenApp(listOf(AiChannel.CMD_OPEN_APP, "9", "p", "a")))
        assertNull(AiChannel.decodeShowImage(listOf(AiChannel.CMD_SHOW_IMAGE, "9", "x", "c")))
        // 自定义图控制指令（助手页要图 / 收起）：眼镜端两个方向都要认
        assertTrue(AiChannel.decodeCustomImgReq(AiChannel.encodeCustomImgReq()))
        assertTrue(AiChannel.decodeImageHide(AiChannel.encodeImageHide()))
        assertFalse(AiChannel.decodeCustomImgReq(listOf(AiChannel.CMD_CUSTOM_IMG_REQ, "9")))
        assertFalse(AiChannel.decodeImageHide(listOf(AiChannel.CMD_IMAGE_HIDE, "9")))
    }

    // ── 过程时间线：kind 字段（尾部可选，v4.9 步骤类型图标）──

    @Test
    fun `process_step 携带 kind 往返`() {
        val encoded = AiChannel.encodeProcessStep("k1", "正在思考", "", "RUNNING", "THINKING")
        assertEquals(
            listOf(AiChannel.CMD_PROCESS_STEP, "1", "k1", "正在思考", "", "RUNNING", "THINKING"),
            encoded,
        )
        val f = AiChannel.decodeProcessStep(encoded)!!
        assertEquals("k1", f.key)
        assertEquals("RUNNING", f.state)
        assertEquals("THINKING", f.kind)
    }

    @Test
    fun `process_step 旧端 6 字段帧仍可解析且 kind 兜底空串`() {
        // 反向兼容锁：旧手机端（未升级）只发 6 字段，眼镜端必须照常渲染（只是没图标）
        val f = AiChannel.decodeProcessStep(
            listOf(AiChannel.CMD_PROCESS_STEP, "1", "k1", "正在思考", "d", "OK"),
        )!!
        assertEquals("正在思考", f.label)
        assertEquals("OK", f.state)
        assertEquals("", f.kind)
        // 显式空串/空白串同样归为空（不画图标，不猜兜底）
        assertEquals("", AiChannel.decodeProcessStep(AiChannel.encodeProcessStep("k", "l", "", "OK", ""))!!.kind)
        assertEquals("", AiChannel.decodeProcessStep(AiChannel.encodeProcessStep("k", "l", "", "OK", "  "))!!.kind)
    }

    @Test
    fun `process_step 关键字段缺失与未知版本仍整体丢弃`() {
        assertNull(AiChannel.decodeProcessStep(listOf(AiChannel.CMD_PROCESS_STEP, "9", "k", "l", "d", "OK", "TOOL")))
        assertNull(AiChannel.decodeProcessStep(listOf(AiChannel.CMD_PROCESS_STEP, "1", "", "l", "d", "OK", "TOOL")))
        assertNull(AiChannel.decodeProcessStep(listOf(AiChannel.CMD_PROCESS_STEP, "1", "k")))
        assertNull(AiChannel.decodeProcessStep(emptyList()))
    }

    @Test
    fun `步骤类型图标映射 覆盖全部 kind 且未知返回 null`() {
        // 必须覆盖手机端 AgentStep.Kind 的每个枚举名（PLAN 不进帧但仍保留防御项）
        val expected = setOf(
            "THINKING", "KNOWLEDGE", "TOOL", "COMPRESS",
            "PLAN", "SUMMARY", "FRAMEWORK", "MEMORY", "REFLECT",
        )
        val mapped = expected.mapNotNull { ProcessTimelineController.stepIconCodepoint(it) }
        // 每个已知 kind 都必须有码位（少一个 = 某类步骤静默丢图标）
        assertEquals(expected.size, mapped.size)
        // 码位互不相同（否则两类步骤画同一个图标，等于没区分）
        assertEquals(mapped.size, mapped.toSet().size)
        // 未知/空类型不画图标，且**不猜兜底码位**
        assertNull(ProcessTimelineController.stepIconCodepoint(null))
        assertNull(ProcessTimelineController.stepIconCodepoint(""))
        assertNull(ProcessTimelineController.stepIconCodepoint("   "))
        assertNull(ProcessTimelineController.stepIconCodepoint("NOT_A_KIND"))
        // 锚定具体码位：改映射表必须同步改这里（否则换图标无声漂移）
        assertEquals(0xEA4A, ProcessTimelineController.stepIconCodepoint("THINKING")) // psychology
        assertEquals(0xE869, ProcessTimelineController.stepIconCodepoint("TOOL"))      // build
        assertEquals(0xEA19, ProcessTimelineController.stepIconCodepoint("KNOWLEDGE")) // menu_book
    }
}
