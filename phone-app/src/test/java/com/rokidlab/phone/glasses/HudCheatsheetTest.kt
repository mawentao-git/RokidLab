package com.rokidlab.phone.glasses

import com.rokidlab.phone.ai.SkillMarkdown
import com.rokidlab.phone.ai.tools.GlassHudToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HUD 创作规范的防腐烂测试（2026-10-24 反同构改版 → 2026-10-25 skill 化拆分）。
 *
 * 背景：用户实测「不管什么风格，出来的 HUD 都差不多」。根因在提示词层——cheatsheet
 * 注意力 95% 在语法、零视觉锚点、bg 默认值被照抄、无反同构机制。解法是
 * 「创作契约前置 + 正交构图示例 + 默认套路黑名单」。
 *
 * 2026-10-25 skill 化（用户定调：DSL 做框架，规范做成可升级 skill，各改各的）后
 * 规范分居两处，本测试按新划界各守一边：
 *  - **hud-design SKILL.md**（assets/skills/hud-design/，经 load_skill 按需加载）：
 *    创作契约 / 黑名单 / 构图范式 / 多样性铁律 / 动画编排 / 参考工作流 / 正交示例；
 *  - **GlassHudToolProvider.DSL_CHEATSHEET**（工具描述，与解析器同版本）：
 *    只留最小语法集（版本强耦合，不能进独立仓库——上游 skill 与 App 内解析器
 *    版本错配会写出被拒的 DSL）。
 *
 * 锁定的硬契约：
 *  A. SKILL.md 契约要素在位（将来改技能误删即红）；
 *  B. 三个正交示例 JSON 必须能被 HudDsl.parse 原样接受（示例语法腐烂即红——
 *     模型会照抄示例，示例错了比没有示例更糟）；
 *  C. 技能名与 glass_hud 描述的 load_skill 纪律互相咬合（改名/删纪律即红——
 *     模型跳过 load 就直接凭语法骨架产出，反同构修复全部作废）；
 *  D. 技能正文 ≤ 全文加载阈值（防膨胀退化成目录模式，每次设计多 1-2 轮 load）。
 */
class HudCheatsheetTest {

    private val cheatsheet: String get() = GlassHudToolProvider.DSL_CHEATSHEET

    /**
     * hud-design SKILL.md 全文。
     *
     * 路径双候选：Gradle 单元测试的 cwd 可能是 phone-app/（AGP 默认）或仓库根，
     * 两种都试；找不到直接 fail 并打出 cwd（静默跳过等于没有断言）。
     */
    private val skillMd: String
        get() {
            val f = listOf(
                File("src/main/assets/skills/hud-design/SKILL.md"),
                File("phone-app/src/main/assets/skills/hud-design/SKILL.md"),
            ).firstOrNull { it.isFile }
                ?: error("找不到 hud-design SKILL.md（cwd=${File("").absolutePath}）")
            return f.readText(Charsets.UTF_8)
        }

    private val skillDoc: SkillMarkdown.ParsedSkill
        get() = SkillMarkdown.parse(skillMd)
            ?: error("hud-design SKILL.md 解析失败（frontmatter 不合法）")

    @Test
    fun `创作契约四要素在位`() {
        val cs = skillMd
        assertTrue("创作契约区块缺失", cs.contains("创作契约"))
        assertTrue("默认套路黑名单缺失", cs.contains("默认套路黑名单"))
        assertTrue("构图范式清单缺失", cs.contains("构图范式"))
        assertTrue("多样性铁律缺失", cs.contains("多样性铁律"))
    }

    @Test
    fun `动画编排与参考工作流在位`() {
        val cs = skillMd
        assertTrue("动画编排段缺失", cs.contains("动画编排"))
        assertTrue("动画预算明示缺失（模型会自发保守）", cs.contains("48"))
        assertTrue("参考图工作流缺失", cs.contains("参考图工作流"))
        assertTrue("检索链缺失（search_web→fetch_webpage→hud_image）", cs.contains("search_web"))
    }

    @Test
    fun `正交构图示例三个且可解析`() {
        // 示例行形如 "A 阵列重复·数字雨：{json}"，JSON 到行尾为止
        val examples = skillMd.lines()
            .filter { it.startsWith("A ") || it.startsWith("B ") || it.startsWith("C ") }
            .map { line -> line.substringAfter("：").trim() }
        assertEquals("正交示例应为 3 个（A/B/C）", 3, examples.size)
        examples.forEachIndexed { idx, json ->
            assertTrue("示例 ${'A' + idx} 不是 JSON 对象开头", json.startsWith("{\"v\":1"))
            // 解析失败会抛 IllegalArgumentException —— 示例语法腐烂即红
            val scene = HudDsl.parse(json)
            assertTrue("示例 ${'A' + idx} 解析后场景为空", scene.elements.isNotEmpty())
        }
    }

    @Test
    fun `示例覆盖不同构图范式`() {
        // 三个示例必须来自不同范式且动用不同元素族——同质化的示例矩阵没有存在意义
        val a = skillMd.lines().first { it.startsWith("A ") }
        val b = skillMd.lines().first { it.startsWith("B ") }
        val c = skillMd.lines().first { it.startsWith("C ") }
        assertTrue("A 应用宏实例（阵列重复范式）", a.contains("macros") && a.contains("\"use\""))
        assertTrue("B 应用表达式波形（极简单线范式）", b.contains("expr:") && b.contains("\"line\""))
        assertTrue("C 应用数据绑定（角落栖息范式）", c.contains("\"bind\"") && c.contains("zone\":\"B\""))
    }

    @Test
    fun `scene 一致性纪律在位`() {
        // 2026-10-25 实测失守：用户「在眼镜预览」（动作词）被模型当新创作重新生成 scene
        val cs = skillMd
        assertTrue("scene 一致性纪律缺失", cs.contains("禁止重新生成 scene"))
        assertTrue("动作词清单缺失", cs.contains("预览/试戴/上屏/保存"))
        assertTrue("多样性铁律作用边界缺失（帮凶未堵）", cs.contains("作用边界"))
    }

    @Test
    fun `技能正文在全文加载阈值内`() {        // >12000 字符会退化成「章节目录 + load_skill_section 分章取」，
        // 每次设计主题多 1-2 轮 LLM 往返 —— 预算纪律钉死在这里
        val body = skillDoc.body
        assertTrue(
            "SKILL.md 正文 ${body.length} 字符超过全文加载阈值 ${SkillMarkdown.MAX_INLINE_CHARS}，" +
                "会退化成目录模式（请精简或拆分）",
            body.length <= SkillMarkdown.MAX_INLINE_CHARS,
        )
    }

    @Test
    fun `技能名与工具描述纪律互相咬合`() {
        // 工具描述里的 load_skill(name="hud-design") 与 SKILL.md frontmatter 名必须一致：
        // 改名任何一侧而不同步改另一侧，模型 load 不到技能 ⇒ 静默退化
        assertEquals("hud-design", skillDoc.name)
        assertTrue("description 缺少 HUD 命中词", skillDoc.description.contains("HUD"))
        val entry = GlassHudToolProvider.tools().first { it.name == "glass_hud" }
        val desc = entry.schema.getJSONObject("function").getString("description")
        assertTrue("glass_hud 描述缺少 load_skill 纪律", desc.contains("load_skill"))
        assertTrue("glass_hud 描述的 load_skill 未点名 hud-design", desc.contains("hud-design"))
        // 语法骨架必须仍在工具速查里（与解析器同版本的那一半不能误删）
        assertTrue("语法段被误删", cheatsheet.contains("【语法"))
        assertTrue("语法段缺少 anim 行", cheatsheet.contains("anim"))
    }
}
