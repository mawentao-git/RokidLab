package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HUD v2 框架化护栏（docs/hud-dsl-framework.md）。
 *
 * 锁定契约：
 *  1. 表达式求值器：优先级/括号/一元负号/函数；变量 env；除零模零 → 0；求值永不抛；
 *  2. r01 确定性：同输入恒同输出；10Hz 时间粒度；输出 [0,1)；
 *  3. fall vs expr 等价锚点：(t*speed)%h 帧采样一致（验证求值器正确性）；
 *  4. 解析护栏：长度/字面量位数/嵌套深度/每元素 ≤3/全场景 ≤48/不适用属性拒绝；
 *  5. 宏参数：替换、缺省 |、未提供拒绝、嵌套继承、p 非数字拒绝、引用处数上限；
 *  6. fall 动画解析（speed 钳 [10,300]）+ text dir:down 纵排字段；
 *  7. expr 元素计入 hasAnim（眼镜端需要跑 ticker）。
 */
class HudExprTest {

    /** env = [t, i, w, h, sw, sh]（与 HudExpr.eval 契约对齐；zone 默认 full 480x640） */
    private fun env(t: Float = 0f, i: Float = 0f, w: Float = 480f, h: Float = 640f): FloatArray =
        floatArrayOf(t, i, w, h, 480f, 640f)

    private fun compile(src: String): HudExpr.Node = HudExpr.compile(src)

    private fun ev(src: String, e: FloatArray = env()): Float = HudExpr.eval(compile(src), e)

    /** 单元素场景快捷解析 */
    private fun parse(el: String): HudDsl.Scene = HudDsl.parse("""{"v":1,"scene":[$el]}""")

    // ── 1. 求值器基础 ──

    @Test
    fun `常量与四则优先级`() {
        assertEquals(14f, ev("2+3*4"), 1e-4f) // 优先级：2+(3*4)
        assertEquals(20f, ev("(2+3)*4"), 1e-4f)
        assertEquals(2.5f, ev("10/4"), 1e-4f)
        assertEquals(1f, ev("10%3"), 1e-4f)
    }

    @Test
    fun `一元负号与浮点字面量`() {
        assertEquals(-5f, ev("-5"), 1e-4f)
        assertEquals(1f, ev("3-2*1.0"), 1e-4f)
        assertEquals(-1.5f, ev("-(1.5)"), 1e-4f)
        assertEquals(-3f, ev("1-2*2"), 1e-4f)
    }

    @Test
    fun `变量 env 取值`() {
        assertEquals(2.5f, ev("t", env(t = 2.5f)), 1e-4f)
        assertEquals(3f, ev("i", env(i = 3f)), 1e-4f)
        assertEquals(200f, ev("w/2", env(w = 400f)), 1e-4f)
        assertEquals(160f, ev("h*0.25", env(h = 640f)), 1e-4f)
        assertEquals(240f, ev("sw/2"), 1e-4f)
        assertEquals(320f, ev("sh/2"), 1e-4f)
    }

    @Test
    fun `函数与参数个数校验`() {
        assertEquals(3f, ev("min(3,7)"), 1e-4f)
        assertEquals(7f, ev("max(3,7)"), 1e-4f)
        assertEquals(3f, ev("floor(3.9)"), 1e-4f)
        assertEquals(5f, ev("abs(-5)"), 1e-4f)
        assertThrows(IllegalArgumentException::class.java) { compile("min(3)") }
        assertThrows(IllegalArgumentException::class.java) { compile("abs(1,2)") }
        assertThrows(IllegalArgumentException::class.java) { compile("tan(3)") }
    }

    @Test
    fun `sin 波形 StrictMath 跨设备一致`() {
        assertEquals(0f, ev("sin(0)"), 1e-6f)
        assertEquals(1f, ev("sin(1.5708)"), 1e-5f) // ≈π/2
        assertEquals(0f, ev("sin(3.14159)"), 1e-4f) // ≈π
        assertEquals(-1f, ev("sin(-1.5708)"), 1e-5f)
        // 波形组合：静止锚点 y = h/2 + sin(0)*40 = 320
        assertEquals(320f, ev("h/2+sin(0)*40", env(h = 640f)), 1e-4f)
        // 漂移：t=1.5708 时 sin≈1 → 中点下方 40px
        assertEquals(360f, ev("h/2+sin(t)*40", env(t = 1.5708f, h = 640f)), 1e-3f)
        assertThrows(IllegalArgumentException::class.java) { compile("sin(1,2)") }
    }

    @Test
    fun `cos 与 pow StrictMath 跨设备一致`() {
        // cos：StrictMath fdlibm 固定算法（与 sin 同铁律）
        assertEquals(1f, ev("cos(0)"), 1e-6f)
        assertEquals(-1f, ev("cos(3.14159)"), 1e-4f)
        // cos(x) ≡ sin(x+1.5708)（等价锚点，防两函数实现漂移）
        for (x in listOf(0f, 0.5f, 1f, 2f, 3f, 6.28f)) {
            assertEquals(ev("sin($x+1.5708)"), ev("cos($x)"), 1e-4f)
        }
        // pow：整数幂精确；分数/负幂可用（抛物线/缓动）
        assertEquals(1024f, ev("pow(2,10)"), 1e-3f)
        assertEquals(0.25f, ev("pow(2,-2)"), 1e-4f)
        assertEquals(9f, ev("pow(t%10,2)", env(t = 3f)), 1e-3f) // 加速落体 (t*s)%h 的近亲
        // pow(0,负) = Inf → 归 0（求值永不炸）
        assertEquals(0f, ev("pow(0,-1)"), 1e-4f)
        // 参数个数：pow 必须两个
        assertThrows(IllegalArgumentException::class.java) { compile("pow(2)") }
        assertThrows(IllegalArgumentException::class.java) { compile("cos(1,2)") }
    }

    @Test
    fun `除零模零取 0 且求值不抛`() {
        assertEquals(0f, ev("5/0"), 1e-4f)
        assertEquals(0f, ev("5%0"), 1e-4f)
        assertEquals(0f, ev("5/(3-3)"), 1e-4f)
    }

    @Test
    fun `语法错误拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { compile("2+") }
        assertThrows(IllegalArgumentException::class.java) { compile("(2+3") }
        assertThrows(IllegalArgumentException::class.java) { compile("2 3") }
        assertThrows(IllegalArgumentException::class.java) { compile("2@3") }
        assertThrows(IllegalArgumentException::class.java) { compile("foo") }
    }

    // ── 2. r01 确定性 ──

    @Test
    fun `r01 同输入恒同输出且落在 0 到 1`() {
        val e1 = env(t = 3.7f, i = 2f)
        val e2 = env(t = 3.7f, i = 2f)
        for (n in 0..20) {
            val a = ev("r01($n)", e1)
            val b = ev("r01($n)", e2)
            assertEquals(a, b, 0f)
            assertTrue("r01 应在 [0,1)：$a", a in 0f..1f)
        }
        // 同一时刻不同 n → 值不同（散列分布，不允许全同）
        assertTrue(ev("r01(1)", e1) != ev("r01(2)", e1) || ev("r01(1)", e1) != ev("r01(3)", e1))
    }

    @Test
    fun `r01 时间粒度 100ms`() {
        // 100ms 内不变，跨 100ms 边界变化（floor(t*10) 语义）
        val a1 = ev("r01(5)", env(t = 1.00f))
        val a2 = ev("r01(5)", env(t = 1.09f))
        val b = ev("r01(5)", env(t = 1.10f))
        assertEquals(a1, a2, 0f)
        assertTrue(a1 != b)
    }

    // ── 3. fall vs expr 等价锚点（docs §5.2：帧采样误差 ≤0.5px）──

    @Test
    fun `fall 语义与表达式 t*speed 取模 h 等价`() {
        val speed = 90f
        val h = 640f
        var t = 0f
        while (t <= 30f) {
            val viaExpr = ev("(t*90)%h", env(t = t, h = h))
            val viaFall = (t * speed) % h // 绘制端 fall 的直接公式
            assertTrue("t=$t 误差 ${Math.abs(viaExpr - viaFall)}", Math.abs(viaExpr - viaFall) <= 0.5f)
            t += 0.5f
        }
    }

    // ── 4. 解析护栏 ──

    @Test
    fun `表达式长度超限拒绝`() {
        val long = "1+".repeat(25) + "1" // 51 字符
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":100,"y":100,"r":5,"x2":"expr:$long"}]}""")
        }
    }

    @Test
    fun `数字字面量位数超限拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:1234567","y":10,"r":5}]}""")
        }
        HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:123456","y":10,"r":5}]}""")
    }

    @Test
    fun `嵌套深度超限拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:((((((1))))))","y":10,"r":5}]}""")
        }
        HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:((((1))))","y":10,"r":5}]}""")
    }

    @Test
    fun `单元素 expr 属性超 3 个拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse(
                """{"v":1,"scene":[{"type":"circle","x":"expr:t","y":"expr:t","r":"expr:t","opacity":"expr:t"}]}""",
            )
        }
    }

    @Test
    fun `全场景含 expr 元素超 48 拒绝`() {
        val els = (0 until 49).joinToString(",") {
            """{"type":"dot","x":"expr:t*$it","y":10}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[$els]}""")
        }
        val ok = (0 until 48).joinToString(",") {
            """{"type":"dot","x":"expr:t*$it","y":10}"""
        }
        HudDsl.parse("""{"v":1,"scene":[$ok]}""")
    }

    @Test
    fun `expr 不适用属性拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"text","x":10,"y":10,"content":"expr:1+1"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:t","y":10,"r":5,"zone":"expr:A"}]}""")
        }
        // 白名单属性可用
        HudDsl.parse("""{"v":1,"scene":[{"type":"circle","x":"expr:t","y":10,"r":5,"start":"expr:t*10","sweep":"expr:90"}]}""")
    }

    // ── 5. 宏参数 ──

    @Test
    fun `宏参数静态替换`() {
        val scene = HudDsl.parse(
            """{"v":1,
                "macros":{"col":[{"type":"dot","x":"p.x","y":"expr:(t*p.v0)%h"}]},
                "scene":[
                    {"type":"use","use":"col","p":{"x":32,"v0":60}},
                    {"type":"use","use":"col","p":{"x":64,"v0":110}}
                ]}""",
        )
        val els = scene.elements
        assertEquals(2, els.size)
        // use 无 x/y ⇒ base = full 中心 240；p.x 替换值 + base 即画布坐标（2026-10-21 中心原点）
        assertEquals(272f, els[0].x, 1e-4f)
        assertEquals(304f, els[1].x, 1e-4f)
        // expr 体内 p.v0 已替换成数字（求值 = (t*60)%h）
        assertEquals((0.5f * 60f) % 640f, HudExpr.eval(els[0].exprs.getValue("y"), env(t = 0.5f, h = 640f)), 1e-3f)
    }

    @Test
    fun `宏参数 expr 内缺省值`() {
        val s = HudDsl.parse(
            """{"v":1,
                "macros":{"col":[{"type":"dot","x":"p.x|20","y":"expr:(t*p.v0|90)%h"}]},
                "scene":[{"type":"use","use":"col","p":{"x":50}}]}""",
        )
        val el = s.elements.single()
        assertEquals(290f, el.x, 1e-4f) // 240(base) + 50(p.x)
        assertEquals((2f * 90f) % 640f, HudExpr.eval(el.exprs.getValue("y"), env(t = 2f, h = 640f)), 1e-3f)
    }

    @Test
    fun `宏参数缺省值与未提供拒绝`() {
        // 缺省：宏内 p.dim|0.8，use 未给 dim → 用 0.8
        val s = HudDsl.parse(
            """{"v":1,
                "macros":{"col":[{"type":"dot","x":"p.x|20","y":100,"opacity":"p.dim|0.8"}]},
                "scene":[{"type":"use","use":"col","p":{"x":50}}]}""",
        )
        assertEquals(290f, s.elements[0].x, 1e-4f) // 240(base) + 50(p.x)
        assertEquals(0.8f, s.elements[0].opacity, 1e-4f)
        // 未提供且无缺省 → 拒绝
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(
                """{"v":1,
                    "macros":{"col":[{"type":"dot","x":"p.x","y":100}]},
                    "scene":[{"type":"use","use":"col"}]}""",
            )
        }
    }

    @Test
    fun `宏参数嵌套继承与内层覆盖`() {
        val s = HudDsl.parse(
            """{"v":1,
                "macros":{
                    "inner":[{"type":"dot","x":"p.a","y":100}],
                    "outer":[
                        {"type":"use","use":"inner","p":{"a":7}},
                        {"type":"dot","x":"p.a","y":200}
                    ]
                },
                "scene":[{"type":"use","use":"outer","p":{"a":3}}]}""",
        )
        // inner 的 p.a=7 覆盖外层 3；outer 自身元素用外层 3
        // 嵌套 use 均无 x/y ⇒ base 一路是 full 中心 240（2026-10-21 中心原点）
        assertEquals(247f, s.elements[0].x, 1e-4f)
        assertEquals(243f, s.elements[1].x, 1e-4f)
    }

    @Test
    fun `宏参数非数字与非法名拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(
                """{"v":1,"macros":{"col":[{"type":"dot","x":"p.x","y":100}]},
                    "scene":[{"type":"use","use":"col","p":{"x":"abc"}}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(
                """{"v":1,"macros":{"col":[{"type":"dot","x":"p.Xx","y":100}]},
                    "scene":[{"type":"use","use":"col","p":{"Xx":1}}]}""",
            )
        }
    }

    @Test
    fun `宏参数不适用于结构字段`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse(
                """{"v":1,"macros":{"col":[{"type":"text","content":"p.txt","x":10,"y":100}]},
                    "scene":[{"type":"use","use":"col","p":{"txt":5}}]}""",
            )
        }
    }

    @Test
    fun `宏参数引用处数超限拒绝`() {
        // 13 个引用 > 12
        val els = (0 until 13).joinToString(",") { """{"type":"dot","x":"p.a$it","y":100}""" }
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"macros":{"col":[$els]},"scene":[{"type":"use","use":"col","p":{"a0":1}}]}""")
        }
    }

    @Test
    fun `顶层元素 p 引用拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            HudDsl.parse("""{"v":1,"scene":[{"type":"dot","x":"p.x","y":100}]}""")
        }
    }

    // ── 6. fall 动画 + dir 纵排 ──

    @Test
    fun `fall 解析与速度钳制`() {
        val s = parse("""{"type":"dot","x":10,"y":10,"anim":{"type":"fall","speed":90}}""")
        val anim = s.elements.single().anims.single()
        assertEquals("fall", anim.type)
        assertEquals(90f, anim.speed, 1e-4f)
        // 越界钳制：5 → 10；999 → 300
        assertEquals(10f, parse("""{"type":"dot","x":10,"y":10,"anim":{"type":"fall","speed":5}}""").elements.single().anims.single().speed, 1e-4f)
        assertEquals(300f, parse("""{"type":"dot","x":10,"y":10,"anim":{"type":"fall","speed":999}}""").elements.single().anims.single().speed, 1e-4f)
    }

    @Test
    fun `text dir down 解析与非法值拒绝`() {
        val s = parse("""{"type":"text","x":10,"y":10,"content":"RAIN","dir":"down"}""")
        assertTrue(s.elements.single().dirDown)
        val h = parse("""{"type":"text","x":10,"y":10,"content":"ROW"}""")
        assertTrue(!h.elements.single().dirDown)
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"X","dir":"up"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"dot","x":10,"y":10,"dir":"down"}""")
        }
    }

    // ── 7. hasAnim 联动 ──

    @Test
    fun `expr 元素计入 hasAnim`() {
        val s = parse("""{"type":"dot","x":"expr:t*50","y":10}""")
        assertTrue(s.hasAnim)
        val static = parse("""{"type":"dot","x":10,"y":10}""")
        assertTrue(!static.hasAnim)
    }
}
