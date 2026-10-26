package com.rokidlab.phone.ai.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * glass_hud 的 scene 一致性与漂移检测（2026-10-25）。
 *
 * 背景：用户实测「第一次让生成，第二次说预览，AI 却重新设计了一版」。
 * 事件流证据：turn2 用户只说「在眼镜预览」（动作词），模型却当新创作请求
 * 重新生成了 scene，还被契约④多样性铁律推着换了构图（正好换进黑名单套路）。
 *
 * 双防线各自的钉测试：
 *  1. **沿用机制**（`pickSceneJson`）：参数优先、缺省沿用上一次、都没有返回 null；
 *  2. **漂移告警**（`sceneDriftWarn`）：try/apply/save 显式重传 scene 且与上一次
 *     设计结构差异大（Jaccard < 0.5）时产出提醒文案。判据是**结构指纹**（元素 type
 *     多重集合 + bg 启用特性），不信数值——坐标/透明度微调是正常迭代，不算漂移。
 *     解析失败一律 null（宽容：宁可漏报也不误报，误报会干扰真正的修改请求）。
 */
class HudSceneMemoryTest {

    private fun args(scene: String?): JSONObject = JSONObject().apply {
        put("action", "try")
        if (scene != null) put("scene", scene)
    }

    // ── pickSceneJson：沿用机制 ──────────────────────────────

    @Test
    fun `scene 参数优先`() {
        val given = """{"v":1,"scene":[{"type":"circle","x":0,"y":0,"r":10}]}"""
        assertEquals(given, GlassHudToolProvider.pickSceneJson(args(given), "上一次的旧scene"))
    }

    @Test
    fun `scene 缺省沿用上一次`() {
        val last = """{"v":1,"scene":[{"type":"circle","x":1,"y":2,"r":9}]}"""
        assertEquals(last, GlassHudToolProvider.pickSceneJson(args(null), last))
    }

    @Test
    fun `scene 空串视为缺省`() {
        val last = """{"v":1,"scene":[{"type":"dot","x":0,"y":0,"r":3}]}"""
        assertEquals(last, GlassHudToolProvider.pickSceneJson(args(""), last))
    }

    @Test
    fun `scene 与上一次都没有时返回 null`() {
        assertNull(GlassHudToolProvider.pickSceneJson(args(null), null))
        assertNull(GlassHudToolProvider.pickSceneJson(args("   "), "  "))
    }

    // ── sceneDriftWarn：漂移检测 ─────────────────────────────

    private val inkScene = """{"v":1,"bg":{"scanline":0.025},"scene":[
        {"type":"circle","x":112,"y":-158,"r":76,"opacity":0.07},
        {"type":"path","x":0,"y":286,"d":"M -240 24 Q -196 -8 -160 6"},
        {"type":"text","x":40,"y":200,"content":"山色空蒙","size":14},
        {"type":"dot","x":-60,"y":-100,"r":3}
    ]}"""

    /** 同结构微调：type 集合与数量不变，只动数值（正常迭代，不算漂移） */
    private val inkSceneTweaked = """{"v":1,"bg":{"scanline":0.05},"scene":[
        {"type":"circle","x":100,"y":-150,"r":70,"opacity":0.1},
        {"type":"path","x":0,"y":280,"d":"M -240 30 Q -196 0 -160 10"},
        {"type":"text","x":44,"y":204,"content":"山色空蒙","size":16},
        {"type":"dot","x":-58,"y":-96,"r":4}
    ]}"""

    /** 结构完全不同：黑名单套路（三同心圆 + dashFlow 虚线），无 path/text/dot */
    private val clicheScene = """{"v":1,"bg":{"scanline":0.08,"grid":48},"scene":[
        {"type":"circle","x":118,"y":-186,"r":82,"opacity":0.1},
        {"type":"circle","x":118,"y":-186,"r":56,"opacity":0.5},
        {"type":"circle","x":118,"y":-186,"r":38,"opacity":0.1},
        {"type":"line","x":-165,"y":-118,"x2":-45,"y2":-118,"dash":[8,8]},
        {"type":"line","x":-140,"y":-96,"x2":-30,"y2":-96,"dash":[5,9]},
        {"type":"line","x":-190,"y":-74,"x2":-70,"y2":-74,"dash":[12,10]}
    ]}"""

    @Test
    fun `任一侧为空不告警`() {
        assertNull(GlassHudToolProvider.sceneDriftWarn(null, inkScene))
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, null))
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, "   "))
    }

    @Test
    fun `相同 JSON 不告警`() {
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, inkScene))
    }

    @Test
    fun `同结构微调不告警`() {
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, inkSceneTweaked))
    }

    @Test
    fun `结构完全不同触发告警`() {
        val warn = GlassHudToolProvider.sceneDriftWarn(inkScene, clicheScene)
        assertNotNull("水墨→黑名单套路必须告警（真实事故的形态）", warn)
        assertTrue("告警须指导模型如何修正（不传 scene 重试）", warn!!.contains("不传 scene"))
        assertTrue("告警须允许「用户确实要改」的出口", warn.contains("忽略本提示"))
    }

    @Test
    fun `部分重叠按相似度判定`() {
        // 保留 inkScene 的 4 个元素 + 追加 2 个新 type ⇒ sim = 4/6 ≈ 0.67 ≥ 0.5 不告警
        val extended = inkScene.trimEnd().let { s ->
            s.dropLast(1) + ""","type":"rect","x":0,"y":0,"w":20,"h":10},
            {"type":"ping","x":30,"y":30,"r":5}]}"""
        }
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, extended))
    }

    @Test
    fun `非法 JSON 宽容不告警`() {
        assertNull(GlassHudToolProvider.sceneDriftWarn(inkScene, "{不是JSON"))
        assertNull(GlassHudToolProvider.sceneDriftWarn("{坏", clicheScene))
    }
}
