package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成式 HUD：text 原子 + bind 数据绑定护栏（docs/hud-dsl.md「数据绑定」）。
 *
 * 锁定契约：
 *  1. text：content/bind 必有其一；内容 ≤20 字符；content/size/align 仅 text 支持；
 *  2. bind.key 只认白名单；as 按元素类型推断（arc→sweep / dot→count / text→text / 其他→enum）；
 *  3. as ↔ 元素类型 ↔ key 类型三方校验（sweep/count←percent、enum←enum、text←任意）；
 *  4. 单场景 bind 元素 ≤ 8；
 *  5. HudBind 解释纯函数：percent→"NN%"、timer 本地走秒、enum→ON/OFF、IDLE/BUSY，无数据"--"；
 *  5b. bindOn v2：两段 enum 相等 + 三段 "数值键:op:值" 阈值比较（matchOn 纯函数；无数据/非数值→隐藏）；
 *  6. AiChannel hud_data 编解码 roundtrip + 坏载荷整体拒绝。
 */
class HudBindDslTest {

    private fun parse(el: String): HudDsl.Scene = HudDsl.parse("""{"v":1,"scene":[$el]}""")

    // ── text 原子 ──

    @Test
    fun `text 带 content 可解析`() {
        val scene = parse("""{"type":"text","zone":"C","x":34,"y":600,"content":"ONLINE"}""")
        assertEquals("text", scene.elements.single().type)
    }

    @Test
    fun `text 缺 content 与 bind 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10}""")
        }
    }

    @Test
    fun `text 内容超 20 字符拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"123456789012345678901"}""")
        }
        parse("""{"type":"text","x":10,"y":10,"content":"12345678901234567890"}""")
    }

    @Test
    fun `content 字段仅 text 支持`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":5,"content":"x"}""")
        }
    }

    // ── bind 解析护栏 ──

    @Test
    fun `bind key 不在白名单拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"battery_car"}}""")
        }
    }

    @Test
    fun `bind as 按元素类型推断`() {
        val arc = parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"battery_phone"}}""").elements.single()
        assertEquals("battery_phone", arc.bindKey)
        assertEquals("sweep", arc.bindAs)
        val dot = parse("""{"type":"dot","x":10,"y":10,"bind":{"key":"battery_phone"}}""").elements.single()
        assertEquals("count", dot.bindAs)
        val txt = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"timer"}}""").elements.single()
        assertEquals("text", txt.bindAs)
        // 未知类型 → enum
        val ring = parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"link"}}""").elements.single()
        assertEquals("enum", ring.bindAs)
    }

    @Test
    fun `bind as sweep 仅 arc`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"battery_phone","as":"sweep"}}""")
        }
    }

    @Test
    fun `bind as count 仅 dot`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"bind":{"key":"battery_phone","as":"count"}}""")
        }
    }

    @Test
    fun `bind as sweep 拒绝 enum 键`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"link","as":"sweep"}}""")
        }
    }

    @Test
    fun `bind as enum 拒绝 percent 键`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"battery_phone","as":"enum"}}""")
        }
    }

    @Test
    fun `bind as 未知值拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"battery_phone","as":"color"}}""")
        }
    }

    @Test
    fun `bind 弧可省略静态 sweep`() {
        // sweep=0 的 bind 弧合法（静态值不再必需）
        parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"battery_phone"}}""")
    }

    @Test
    fun `bind 元素单场景上限 8`() {
        fun scene(n: Int): String = (1..n).joinToString(",") {
            """{"type":"dot","x":10,"y":$it,"bind":{"key":"battery_phone","as":"count"}}"""
        }.let { """{"v":1,"scene":[$it]}""" }
        assertThrows(IllegalArgumentException::class.java) { HudDsl.parse(scene(9)) }
        HudDsl.parse(scene(8))
    }

    @Test
    fun `bind count 点阵容量按区域右缘算`() {
        val el = parse("""{"type":"dot","x":10,"y":10,"bind":{"key":"battery_phone"}}""").elements.single()
        assertEquals("count", el.bindAs)
        // full 区右缘 480；x=10 是中心原点偏移 ⇒ 画布 250；step 默认 48 → 容量 (480-250)/48 = 4
        assertEquals(4, el.maxDots)
    }

    // ── HudBind 取值解释 ──

    @Test
    fun `displayText percent`() {
        assertEquals("87%", HudDsl.HudBind.displayText("battery_phone", "87.0", 0, 0))
        assertEquals("0%", HudDsl.HudBind.displayText("battery_phone", "-5", 0, 0))
        assertEquals("100%", HudDsl.HudBind.displayText("battery_phone", "120", 0, 0))
    }

    @Test
    fun `displayText timer 本地走秒且停在 0`() {
        // 剩余 65s，锚点后 5s → 01:00
        assertEquals("01:00", HudDsl.HudBind.displayText("timer", "65", 10_000, 15_000))
        assertEquals("00:00", HudDsl.HudBind.displayText("timer", "65", 10_000, 100_000))
    }

    @Test
    fun `displayText enum`() {
        assertEquals("ON", HudDsl.HudBind.displayText("link", "connected", 0, 0))
        assertEquals("OFF", HudDsl.HudBind.displayText("link", "disconnected", 0, 0))
        assertEquals("BUSY", HudDsl.HudBind.displayText("ai_state", "busy", 0, 0))
        assertEquals("IDLE", HudDsl.HudBind.displayText("ai_state", "idle", 0, 0))
    }

    @Test
    fun `displayText 无数据一律 --`() {
        assertEquals("--", HudDsl.HudBind.displayText("battery_phone", null, 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("battery_phone", "", 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("battery_phone", "abc", 0, 0))
    }

    @Test
    fun `numeric 仅数值键可解析`() {
        assertEquals(87.0, HudDsl.HudBind.numeric("battery_phone", "87")!!, 1e-9)
        assertEquals(65.0, HudDsl.HudBind.numeric("timer", "65")!!, 1e-9)
        assertNull(HudDsl.HudBind.numeric("link", "connected"))
        assertNull(HudDsl.HudBind.numeric("battery_phone", null))
    }

    @Test
    fun `isOn 枚举映射`() {
        assertEquals(true, HudDsl.HudBind.isOn("link", "connected"))
        assertEquals(false, HudDsl.HudBind.isOn("link", "disconnected"))
        assertEquals(false, HudDsl.HudBind.isOn("ai_state", "idle"))
        assertNull(HudDsl.HudBind.isOn("battery_phone", "80"))
        assertNull(HudDsl.HudBind.isOn("link", null))
    }

    // ── AiChannel hud_data 编解码 ──

    @Test
    fun `hud_data roundtrip 无损`() {
        val entries = listOf("battery_phone" to "87", "link" to "connected")
        val fields = AiChannel.encodeHudData(entries)
        assertEquals(
            listOf(AiChannel.CMD_HUD_DATA, AiChannel.SCHEMA_VERSION.toString(), "2", "battery_phone", "87", "link", "connected"),
            fields,
        )
        assertEquals(entries.toMap(), AiChannel.decodeHudData(fields))
    }

    @Test
    fun `hud_data 坏载荷整体拒绝`() {
        val fields = AiChannel.encodeHudData(listOf("a" to "1"))
        assertNull(AiChannel.decodeHudData(emptyList()))
        assertNull(AiChannel.decodeHudData(listOf("wrong", "1", "1", "a", "1")))
        assertNull(AiChannel.decodeHudData(fields.dropLast(1))) // 字段数不足
        // 空 key 跳过、value 允许空串（清数据语义）
        assertEquals(mapOf("a" to ""), AiChannel.decodeHudData(listOf(AiChannel.CMD_HUD_DATA, "1", "2", "", "x", "a", "")))
    }

    // ── Scene.hasTickingBind ──

    @Test
    fun `hasTickingBind 仅走秒键置真`() {
        val timer = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"timer"}}""")
        org.junit.Assert.assertTrue(timer.hasTickingBind)
        org.junit.Assert.assertFalse(timer.hasAnim)
        val calendar = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"calendar_next"}}""")
        org.junit.Assert.assertTrue(calendar.hasTickingBind)
        val no = parse("""{"type":"text","x":10,"y":10,"content":"X"}""")
        org.junit.Assert.assertFalse(no.hasTickingBind)
        // unread 不走秒
        val unread = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"unread"}}""")
        org.junit.Assert.assertFalse(unread.hasTickingBind)
    }

    // ── 第二批数据键：calendar_next / unread / tool_result ──

    @Test
    fun `新 number 键 as=text 可绑定`() {
        val cal = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"calendar_next"}}""").elements.single()
        assertEquals("calendar_next", cal.bindKey)
        assertEquals("text", cal.bindAs)
        val unread = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"unread"}}""").elements.single()
        assertEquals("unread", unread.bindKey)
    }

    @Test
    fun `tool_result text 键仅 as=text`() {
        val ok = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"tool_result"}}""").elements.single()
        assertEquals("tool_result", ok.bindKey)
        assertEquals("text", ok.bindAs)
        // sweep/count ← percent、enum ← enum：text 类键一律拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"tool_result","as":"sweep"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"tool_result","as":"enum"}}""")
        }
    }

    @Test
    fun `displayText calendar_next 本地走秒`() {
        // 剩余 2700s（45min），锚点后 0s → 45m
        assertEquals("45m", HudDsl.HudBind.displayText("calendar_next", "2700", 0, 0))
        // 锚点后 60s → 44m
        assertEquals("44m", HudDsl.HudBind.displayText("calendar_next", "2700", 0, 60_000))
        // ≥1h → 1h05（3900s）
        assertEquals("1h05", HudDsl.HudBind.displayText("calendar_next", "3900", 0, 0))
        // 走到 0 停住
        assertEquals("0m", HudDsl.HudBind.displayText("calendar_next", "30", 0, 60_000))
    }

    @Test
    fun `displayText unread 与 tool_result`() {
        assertEquals("3", HudDsl.HudBind.displayText("unread", "3", 0, 0))
        assertEquals("0", HudDsl.HudBind.displayText("unread", "0", 0, 0))
        assertEquals("READY", HudDsl.HudBind.displayText("tool_result", "READY", 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("tool_result", "", 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("unread", "abc", 0, 0))
    }

    @Test
    fun `numeric 覆盖新 number 键`() {
        assertEquals(2700.0, HudDsl.HudBind.numeric("calendar_next", "2700")!!, 1e-9)
        assertEquals(3.0, HudDsl.HudBind.numeric("unread", "3")!!, 1e-9)
        assertNull(HudDsl.HudBind.numeric("tool_result", "READY"))
        assertNull(HudDsl.HudBind.isOn("tool_result", "READY"))
    }

    // ── 动画 v1.1：组合动画 / scale / glow / dashFlow / phase ──

    @Test
    fun `anim 单对象兼容解析`() {
        val el = parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"spin","period":8}}""").elements.single()
        assertEquals(1, el.anims.size)
        assertEquals("spin", el.anims[0].type)
        assertEquals(8f, el.anims[0].period, 1e-6f)
    }

    @Test
    fun `anim 数组组合动画可解析且 hasAnim 置真`() {
        val scene = parse(
            """{"type":"circle","x":10,"y":10,"r":20,"anim":[{"type":"spin","period":8},{"type":"glow","amp":0.6}]}""",
        )
        val el = scene.elements.single()
        assertEquals(2, el.anims.size)
        assertEquals("spin", el.anims[0].type)
        assertEquals("glow", el.anims[1].type)
        org.junit.Assert.assertTrue(scene.hasAnim)
    }

    @Test
    fun `anim 数组超 2 个拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":[{"type":"spin"},{"type":"glow"},{"type":"pulse"}]}""")
        }
    }

    @Test
    fun `anim 同元素重复类型拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":[{"type":"spin"},{"type":"spin"}]}""")
        }
    }

    @Test
    fun `未知动画拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"wiggle"}}""")
        }
    }

    @Test
    fun `scale 仅限 circle rect dot polygon`() {
        parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"scale"}}""")
        parse("""{"type":"rect","x":10,"y":10,"w":40,"h":30,"anim":{"type":"scale"}}""")
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"line","x":10,"y":10,"x2":50,"y2":50,"anim":{"type":"scale"}}""")
        }
    }

    @Test
    fun `dashFlow 需要 dash 且仅描边图形`() {
        // 无 dash 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"dashFlow"}}""")
        }
        // 有 dash 的描边图形合法
        parse("""{"type":"circle","x":10,"y":10,"r":20,"dash":[6,4],"anim":{"type":"dashFlow","speed":40}}""")
        // dot 恒填充、text/ping 独立分支 → 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"dot","x":10,"y":10,"r":4,"dash":[6,4],"anim":{"type":"dashFlow"}}""")
        }
    }

    @Test
    fun `amp 与 phase 越界钳制`() {
        val hi = parse(
            """{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"scale","amp":5,"phase":500}}""",
        ).elements.single()
        assertEquals(0.9f, hi.anims[0].amp, 1e-6f)
        assertEquals(360f, hi.anims[0].phase, 1e-6f)
        val lo = parse(
            """{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"glow","amp":-1,"phase":-5}}""",
        ).elements.single()
        assertEquals(0f, lo.anims[0].amp, 1e-6f)
        assertEquals(0f, lo.anims[0].phase, 1e-6f)
    }

    @Test
    fun `scale amp 默认 0_3 glow 默认 0_5`() {
        val s = parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"scale"}}""").elements.single()
        assertEquals(0.3f, s.anims[0].amp, 1e-6f)
        val g = parse("""{"type":"circle","x":10,"y":10,"r":20,"anim":{"type":"glow"}}""").elements.single()
        assertEquals(0.5f, g.anims[0].amp, 1e-6f)
    }

    @Test
    fun `glow 不适用于 ping`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"ping","x":10,"y":10,"r":60,"anim":{"type":"glow"}}""")
        }
    }

    // ── 几何 v1.1：椭圆 / 圆角 / use 组变换 / bindOn ──

    @Test
    fun `circle rx ry 椭圆字段`() {
        val el = parse("""{"type":"circle","x":10,"y":10,"r":20,"rx":30,"ry":18}""").elements.single()
        assertEquals(30f, el.rx, 1e-6f)
        assertEquals(18f, el.ry, 1e-6f)
        // 缺省 rx/ry = 0（绘制端回退 r）
        val fallback = parse("""{"type":"circle","x":10,"y":10,"r":20}""").elements.single()
        assertEquals(0f, fallback.rx, 1e-6f)
        // rx/ry 仅 circle
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"dot","x":10,"y":10,"r":5,"rx":10}""")
        }
    }

    @Test
    fun `rect radius 圆角钳到短边一半`() {
        val el = parse("""{"type":"rect","x":10,"y":10,"w":40,"h":30,"radius":100}""").elements.single()
        assertEquals(15f, el.radius, 1e-6f)
        // radius 仅 rect
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":5,"radius":3}""")
        }
    }

    @Test
    fun `use rot mirror 传播到宏内部件`() {
        val scene = HudDsl.parse(
            """{"v":1,"macros":{"m":[{"type":"dot","x":5,"y":5,"r":2}]},""" +
                """"scene":[{"type":"use","use":"m","zone":"full","x":100,"y":50,"rot":45,"mirror":true}]}""",
        )
        val el = scene.elements.single()
        assertEquals(45f, el.useRot, 1e-6f)
        org.junit.Assert.assertTrue(el.useMirror)
        // 锚点 = use 所在 zone 中心(full=240,320) + use x/y（2026-10-21 中心原点）
        assertEquals(340f, el.pivX, 1e-6f)
        assertEquals(370f, el.pivY, 1e-6f)
    }

    @Test
    fun `use rot 嵌套拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(
                """{"v":1,"macros":{""" +
                    """"inner":[{"type":"use","use":"leaf","rot":10}],""" +
                    """"leaf":[{"type":"dot","x":1,"y":1,"r":2}]},""" +
                    """"scene":[{"type":"use","use":"inner","rot":20}]}""",
            )
        }
    }

    @Test
    fun `bindOn 解析与白名单`() {
        val on = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"link:connected"}""").elements.single()
        assertEquals("link:connected", on.bindOn)
        // 反状态合法
        val off = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"ai_state:idle"}""").elements.single()
        assertEquals("ai_state:idle", off.bindOn)
        // 非 enum 键拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"battery_phone:80"}""")
        }
        // 缺值/坏格式拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"link"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":":connected"}""")
        }
    }

    // ── image 元素（hud_image 两步式，docs/hud-dsl.md「图片主视觉」）──

    @Test
    fun `image 合法解析与 w h 钳制`() {
        val el = parse("""{"type":"image","id":"img1","x":100,"y":200,"w":120,"h":90}""").elements.single()
        assertEquals("image", el.type)
        assertEquals("img1", el.imageId)
        assertEquals(120f, el.w, 1e-6f)
        assertEquals(90f, el.h, 1e-6f)
        // 过小钳到 8，过大钳到屏宽/高
        val clamped = parse("""{"type":"image","id":"img1","x":0,"y":0,"w":2,"h":9999}""").elements.single()
        assertEquals(8f, clamped.w, 1e-6f)
        assertEquals(HudDsl.SCREEN_H.toFloat(), clamped.h, 1e-6f)
    }

    @Test
    fun `image 缺 id 或 w h 拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","x":0,"y":0,"w":10,"h":10}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":0,"y":0,"h":10}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":0,"y":0,"w":10}""")
        }
    }

    @Test
    fun `image 未登记 id 校验且空表跳过`() {
        val json = """{"v":1,"scene":[{"type":"image","id":"img9","x":0,"y":0,"w":10,"h":10}]}"""
        // 空登记表（主题库等离线场景）跳过校验
        HudDsl.parse(json)
        // 非空登记表：未登记 id 拒绝，已登记放行
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(json, registeredImages = setOf("img1"))
        }
        HudDsl.parse(json, registeredImages = setOf("img1", "img9"))
    }

    @Test
    fun `image 超上限 2 拒绝`() {
        val json = """{"v":1,"scene":[""" +
            (1..3).joinToString(",") {
                """{"type":"image","id":"img$it","x":0,"y":0,"w":10,"h":10}"""
            } + """]}"""
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(json, registeredImages = setOf("img1", "img2", "img3"))
        }
    }

    @Test
    fun `image 拒绝墨线字段与 orbit dashFlow 且 id 仅 image`() {
        fun image(extra: String) = """{"type":"image","id":"img1","x":0,"y":0,"w":10,"h":10,$extra}"""
        // 墨线体系仅矢量元素
        listOf(""""stroke":1""", """"fillInk":1""", """"width":2""", """"dash":[6,4]""").forEach { f ->
            assertThrows(IllegalArgumentException::class.java) { parse(image(f)) }
        }
        // orbit 会以圆点替代元素本体、dashFlow 是描边流动 —— 对位图均无意义
        assertThrows(IllegalArgumentException::class.java) { parse(image(""""anim":{"type":"orbit"}""")) }
        assertThrows(IllegalArgumentException::class.java) { parse(image(""""anim":{"type":"dashFlow"}""")) }
        // id 字段仅 image 支持（其他元素引用登记 id 无意义）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"id":"img1"}""")
        }
    }

    @Test
    fun `image 兼容组合动画与 bindOn`() {
        val el = parse(
            """{"type":"image","id":"img1","x":0,"y":0,"w":60,"h":60,""" +
                """"anim":[{"type":"spin","period":8},{"type":"pulse","period":3}],"bindOn":"link:connected"}""",
        ).elements.single()
        assertEquals(2, el.anims.size)
        assertEquals("link:connected", el.bindOn)
    }

    // ── image 蒙版与混合（2026-10-25 P1-3：mask=circle / blend=screen，仅 image）──

    @Test
    fun `image mask blend 白名单解析`() {
        val el = parse(
            """{"type":"image","id":"img1","x":0,"y":0,"w":60,"h":60,"mask":"circle","blend":"screen"}""",
        ).elements.single()
        assertEquals("circle", el.mask)
        assertEquals("screen", el.blend)
        // 缺省 = 不裁不混（null）
        val plain = parse("""{"type":"image","id":"img1","x":0,"y":0,"w":60,"h":60}""").elements.single()
        assertNull(plain.mask)
        assertNull(plain.blend)
    }

    @Test
    fun `image mask blend 白名单外值与非 image 拒绝`() {
        // 值白名单（写错解析期拒绝，错误消息回给模型自修正）
        val badMask = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":0,"y":0,"w":60,"h":60,"mask":"square"}""")
        }
        assertTrue(badMask.message!!.contains("circle"))
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":0,"y":0,"w":60,"h":60,"blend":"multiply"}""")
        }
        // 仅 image 支持：矢量元素写即拒
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"mask":"circle"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":40,"h":30,"blend":"screen"}""")
        }
    }

    // ── bindOn v2：数值阈值比较（2026-10-25 P0-1；三段 "数值键:op:值"）──

    @Test
    fun `bindOn 三段阈值解析出 key op 值`() {
        val t = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"timer:<300"}""").elements.single()
        assertEquals("timer:<300", t.bindOn)
        assertEquals("timer", t.bindOnKey)
        assertEquals("<", t.bindOnOp)
        assertEquals(300f, t.bindOnCmp, 1e-6f)
        assertNull(t.bindOnVal)
        // 比较符 <= 与阈值 20（battery_phone 是 percent 键）
        val neg = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"battery_phone:<=20"}""").elements.single()
        assertEquals("<=", neg.bindOnOp)
        assertEquals(20f, neg.bindOnCmp, 1e-6f)
    }

    @Test
    fun `bindOn 两段 enum 相等保持旧语义`() {
        val on = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"link:connected"}""").elements.single()
        assertEquals("link", on.bindOnKey)
        assertNull(on.bindOnOp)
        assertEquals("connected", on.bindOnVal)
    }

    @Test
    fun `matchOn 六种比较符语义`() {
        // op=null（enum 相等）
        assertEquals(true, HudDsl.HudBind.matchOn("link", null, 0f, "connected", "connected"))
        assertEquals(false, HudDsl.HudBind.matchOn("link", null, 0f, "connected", "disconnected"))
        // 数值阈值：timer=100 vs 阈值 300
        assertEquals(true, HudDsl.HudBind.matchOn("timer", "<", 300f, null, "100"))
        assertEquals(false, HudDsl.HudBind.matchOn("timer", "<", 300f, null, "300"))
        assertEquals(true, HudDsl.HudBind.matchOn("timer", "<=", 300f, null, "300"))
        assertEquals(true, HudDsl.HudBind.matchOn("battery_phone", ">", 20f, null, "21"))
        assertEquals(false, HudDsl.HudBind.matchOn("battery_phone", ">", 20f, null, "20"))
        assertEquals(true, HudDsl.HudBind.matchOn("battery_phone", ">=", 20f, null, "20"))
        assertEquals(true, HudDsl.HudBind.matchOn("unread", "==", 3f, null, "3"))
        assertEquals(true, HudDsl.HudBind.matchOn("unread", "!=", 3f, null, "4"))
    }

    @Test
    fun `matchOn 无数据或非数值一律隐藏`() {
        // 阈值比较：无数据/空串/非数值 → false（隐藏；与"数据未到达也隐藏"的旧 enum 语义一致）
        assertEquals(false, HudDsl.HudBind.matchOn("timer", "<", 300f, null, null))
        assertEquals(false, HudDsl.HudBind.matchOn("timer", "<", 300f, null, ""))
        assertEquals(false, HudDsl.HudBind.matchOn("timer", "<", 300f, null, "abc"))
        // enum 相等：无数据 ≠ 指定值
        assertEquals(false, HudDsl.HudBind.matchOn("link", null, 0f, "connected", null))
    }

    @Test
    fun `bindOn 数值键写相等拒绝且提示阈值写法`() {
        // timer:300（相等比较）→ 定向提示 "key:比较符值" 写法
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"timer:300"}""")
        }
        assertTrue(ex.message!!.contains("比较符值"))
        assertTrue(ex.message!!.contains("timer:<300"))
        // 比较符后缺数字
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"timer:<"}""")
        }
    }

    @Test
    fun `bindOn enum 键写阈值与坏阈值拒绝`() {
        // enum 键不接受阈值比较
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"link:<300"}""")
        }
        // 阈值非数字（含误写两个冒号 timer:<:300）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"timer:<:300"}""")
        }
        // 四段及以上格式错误
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"timer:<300:300"}""")
        }
    }

    // ── as=bar / as=progress 比例映射（2026-10-25 P0-2）──

    @Test
    fun `as=bar 仅 rect 且不接受 enum text 键`() {
        val bar = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"battery_phone","as":"bar"}}""",
        ).elements.single()
        assertEquals("bar", bar.bindAs)
        // percent 键比例映射满量程恒 100（P1-2：渲染端除数是 bindMax 不是硬编码 100）
        assertEquals(100f, bar.bindMax, 1e-6f)
        // rect 缺省 as 仍是 enum（bar 必须显式写）
        val rect = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"link"}}""",
        ).elements.single()
        assertEquals("enum", rect.bindAs)
        // bar 不接受其他元素类型
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"battery_phone","as":"bar"}}""")
        }
        // bar 不接受 enum 键（number 键配 max 合法，见 P1-2 专测）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"link","as":"bar"}}""")
        }
    }

    @Test
    fun `as=progress 仅 line 且不接受 enum text 键`() {
        val p = parse(
            """{"type":"line","x":0,"y":0,"x2":100,"y2":0,"bind":{"key":"battery_phone","as":"progress"}}""",
        ).elements.single()
        assertEquals("progress", p.bindAs)
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"battery_phone","as":"progress"}}""")
        }
        // enum / text 键不做比例
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"line","x":0,"y":0,"x2":100,"y2":0,"bind":{"key":"link","as":"progress"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"line","x":0,"y":0,"x2":100,"y2":0,"bind":{"key":"tool_result","as":"progress"}}""")
        }
    }

    @Test
    fun `barFill 解析钳制且仅 as=bar rect`() {
        val el = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"barFill":1,"bind":{"key":"battery_phone","as":"bar"}}""",
        ).elements.single()
        assertEquals(1, el.barFill)
        // 缺省 3（亮绿）
        val d = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"battery_phone","as":"bar"}}""",
        ).elements.single()
        assertEquals(3, d.barFill)
        // 越界钳到 0..3
        val hi = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"barFill":9,"bind":{"key":"battery_phone","as":"bar"}}""",
        ).elements.single()
        assertEquals(3, hi.barFill)
        // 非 as=bar 的 rect / 其他类型 → 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"barFill":1,"bind":{"key":"link"}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":10,"y":10,"r":20,"barFill":1,"bind":{"key":"battery_phone","as":"bar"}}""")
        }
    }

    // ── text 多行（2026-10-25 P1-1：\n 显式换行，≤4 行，每行 ≤20 字符，中文可用）──

    @Test
    fun `text content 支持换行与中文`() {
        val el = parse("""{"type":"text","x":10,"y":10,"content":"心率 142\n配速 5:30"}""").elements.single()
        assertEquals("心率 142\n配速 5:30", el.text)
        // 中文单行短句（绿屏美学从 ASCII 短词扩为短句；字形以眼镜端系统字体为准）
        val zh = parse("""{"type":"text","x":10,"y":10,"content":"夜间模式"}""").elements.single()
        assertEquals("夜间模式", zh.text)
        // 4 行合法
        parse("""{"type":"text","x":10,"y":10,"content":"a\nb\nc\nd"}""")
        // bind 文本仍走 displayText（单行值，不受多行影响）
        val bound = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"timer"}}""").elements.single()
        assertNull(bound.text)
    }

    @Test
    fun `text 多行护栏`() {
        // 超 4 行拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"a\nb\nc\nd\ne"}""")
        }
        // 单行超 20 字符拒绝（旧契约保持）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"123456789012345678901"}""")
        }
        // 多行中某行超 20 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"a\n123456789012345678901"}""")
        }
        // dir:down 纵排 + \n 拒绝（纵排本身逐字符下排，语义冲突）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"a\nb","dir":"down"}""")
        }
    }

    // ── 采集键 gps_speed/gps_bearing/steps/music（2026-10-25 P1-2）──

    @Test
    fun `number 键比例映射必须显式 max`() {
        // 缺 max 拒绝（量程各异，猜不得）
        val ex = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"gps_speed","as":"bar"}}""")
        }
        assertTrue(ex.message!!.contains("bind.max"))
        assertTrue(ex.message!!.contains("as=text"))
        // 显式 max 放行并落字段（数字与数字串两种写法都吃）
        val bar = parse(
            """{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"gps_speed","as":"bar","max":120}}""",
        ).elements.single()
        assertEquals(120f, bar.bindMax, 1e-6f)
        val arc = parse(
            """{"type":"arc","x":10,"y":10,"r":20,"bind":{"key":"steps","as":"sweep","max":"10000"}}""",
        ).elements.single()
        assertEquals(10000f, arc.bindMax, 1e-6f)
    }

    @Test
    fun `bind max 护栏 越界与非比例映射拒绝`() {
        // percent 键恒 100：显式 max 拒绝（冗余且误导）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"battery_phone","as":"bar","max":100}}""")
        }
        // 量程 2..999999
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"steps","as":"bar","max":1}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"steps","as":"bar","max":1000000}}""")
        }
        // 非数字（含把 max 写成对象）
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"rect","x":10,"y":10,"w":100,"h":8,"bind":{"key":"steps","as":"bar","max":"abc"}}""")
        }
        // as=text 不按比例：给 max 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"bind":{"key":"gps_speed","max":120}}""")
        }
    }

    @Test
    fun `采集键 as=text 原值显示且不带 max`() {
        val speed = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"gps_speed"}}""").elements.single()
        assertEquals("text", speed.bindAs)
        assertEquals(0f, speed.bindMax, 1e-6f) // 非比例映射 = 0
        val music = parse("""{"type":"text","x":10,"y":10,"bind":{"key":"music"}}""").elements.single()
        assertEquals("music", music.bindKey)
        val ring = parse("""{"type":"circle","x":10,"y":10,"r":20,"bind":{"key":"music"}}""").elements.single()
        assertEquals("enum", ring.bindAs)
    }

    @Test
    fun `displayText 采集键`() {
        // gps_speed：km/h 取整、负值钳 0
        assertEquals("12", HudDsl.HudBind.displayText("gps_speed", "12.7", 0, 0))
        assertEquals("0", HudDsl.HudBind.displayText("gps_speed", "-3", 0, 0))
        // gps_bearing：八方位缩写（22.5° 一档）
        assertEquals("N", HudDsl.HudBind.displayText("gps_bearing", "0", 0, 0))
        assertEquals("N", HudDsl.HudBind.displayText("gps_bearing", "22", 0, 0))
        assertEquals("NE", HudDsl.HudBind.displayText("gps_bearing", "23", 0, 0))
        assertEquals("NE", HudDsl.HudBind.displayText("gps_bearing", "45", 0, 0))
        assertEquals("E", HudDsl.HudBind.displayText("gps_bearing", "90", 0, 0))
        assertEquals("S", HudDsl.HudBind.displayText("gps_bearing", "180", 0, 0))
        assertEquals("W", HudDsl.HudBind.displayText("gps_bearing", "270", 0, 0))
        assertEquals("NW", HudDsl.HudBind.displayText("gps_bearing", "337", 0, 0))
        assertEquals("N", HudDsl.HudBind.displayText("gps_bearing", "338", 0, 0))
        assertEquals("N", HudDsl.HudBind.displayText("gps_bearing", "359", 0, 0))
        // steps：整数
        assertEquals("5432", HudDsl.HudBind.displayText("steps", "5432", 0, 0))
        // music：PLAY/PAUSE
        assertEquals("PLAY", HudDsl.HudBind.displayText("music", "playing", 0, 0))
        assertEquals("PAUSE", HudDsl.HudBind.displayText("music", "paused", 0, 0))
        // 无数据/非数值 → "--"
        assertEquals("--", HudDsl.HudBind.displayText("gps_speed", null, 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("gps_bearing", "abc", 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("steps", "", 0, 0))
        assertEquals("--", HudDsl.HudBind.displayText("music", null, 0, 0))
    }

    @Test
    fun `isOn 与 numeric 覆盖 music 与采集键`() {
        assertEquals(true, HudDsl.HudBind.isOn("music", "playing"))
        assertEquals(false, HudDsl.HudBind.isOn("music", "paused"))
        assertNull(HudDsl.HudBind.isOn("gps_speed", "12"))
        assertEquals(12.5, HudDsl.HudBind.numeric("gps_speed", "12.5")!!, 1e-9)
        assertEquals(270.0, HudDsl.HudBind.numeric("gps_bearing", "270")!!, 1e-9)
        assertEquals(5432.0, HudDsl.HudBind.numeric("steps", "5432")!!, 1e-9)
        assertNull(HudDsl.HudBind.numeric("music", "playing"))
    }

    @Test
    fun `bindOn 新数值键阈值与 music 枚举相等`() {
        // gps_speed 三段阈值（超速才显示）
        val t = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"gps_speed:>60"}""").elements.single()
        assertEquals("gps_speed", t.bindOnKey)
        assertEquals(">", t.bindOnOp)
        assertEquals(60f, t.bindOnCmp, 1e-6f)
        // steps 阈值
        val s = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"steps:>=8000"}""").elements.single()
        assertEquals(">=", s.bindOnOp)
        assertEquals(8000f, s.bindOnCmp, 1e-6f)
        // music 两段枚举相等
        val m = parse("""{"type":"circle","x":10,"y":10,"r":20,"bindOn":"music:playing"}""").elements.single()
        assertEquals("music", m.bindOnKey)
        assertNull(m.bindOnOp)
        assertEquals("playing", m.bindOnVal)
        // matchOn：阈值 + enum 两条路径都通
        assertEquals(true, HudDsl.HudBind.matchOn("gps_speed", ">", 60f, null, "72"))
        assertEquals(false, HudDsl.HudBind.matchOn("gps_speed", ">", 60f, null, null))
        assertEquals(true, HudDsl.HudBind.matchOn("music", null, 0f, "playing", "playing"))
    }
}
