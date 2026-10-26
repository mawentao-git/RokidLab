package com.rokidlab.phone.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 生成式 HUD：中心原点坐标系统 + box 元素 anchor（2026-10-21 翻转）。
 *
 * 背景（实测复现，两轮）：DSL 旧语义「x,y = 相对区域左上角的绝对值」，而模型两轮都按
 * 中心原点思维写坐标 —— 第一轮把 image 写在画布中心（full = 240,320，实为框左上角
 * ⇒ 图偏右下）；第二轮更直接：所有元素 x:0,y:0 当中心、四角爱心写 ±152/±236 负数偏移
 * ⇒ 照片被扔到左上角、负数偏移被 clampCoord 压糊在角上（设备截图 + 会话日志实锤）。
 * 这不是渲染 bug，是坐标空间与模型的自然写法相反。翻成中心原点后，
 * 模型的自然写法即正确写法。
 *
 * 锁定契约（x,y = 元素定位点相对区域中心的偏移；full 画布中心 = 0,0）：
 *  1. x:0,y:0 ⇒ 定位到区域中心（240,320）——第 1 条在旧代码上会红（0≠240），证明断言有效；
 *  2. 负数偏移合法（-152,-236 ⇒ 88,84），不再被压糊；
 *  3. 缺省 x/y = 区域中心（所有元素同规则；非 full zone 取该 zone 中心）；
 *  4. box 元素（image/rect/brackets/ruler）x,y = 框中心；anchor:"topleft" opt-out 回左上角；
 *  5. anchor:topleft 拒绝配 x/y/w/h 表达式；anchor 仅 box 元素支持；取值只认 topleft/center；
 *  6. 宏内部件：base = use 定位点（use 所在 zone 中心 + use x/y），缺省 x/y = 定位点；
 *  7. path 的 d = 以元素 x,y 为原点的局部坐标（pathDx/pathDy == el.x/el.y）；
 *  8. brackets：len 映射臂长、w/h 缺省 = zone 大小、inset 内缩（修 doc/code 不一致）。
 *
 * 两端同源：眼镜端 RokidLink HudOverlayController.parseEl 为逐字镜像（该模块无解析单测，
 * 靠本文件锁定契约 + 双端注释互指）。
 */
class HudAnchorDslTest {

    private fun parse(el: String): HudDsl.Scene = HudDsl.parse("""{"v":1,"scene":[$el]}""")

    // ── 1. 中心原点：0,0 = 区域中心 ──

    @Test
    fun `image x y 写 0 0 定位到画布中心`() {
        val el = parse("""{"type":"image","id":"img1","x":0,"y":0,"w":182,"h":240}""").elements.single()
        assertEquals(240f, el.x, 0.01f)
        assertEquals(320f, el.y, 0.01f)
    }

    @Test
    fun `circle x y 写 0 0 定位到画布中心`() {
        val el = parse("""{"type":"circle","x":0,"y":0,"r":150}""").elements.single()
        assertEquals(240f, el.x, 0.01f)
        assertEquals(320f, el.y, 0.01f)
    }

    // ── 2. 负数偏移合法（四角装饰的自然写法，旧语义下会被压糊到角上）──

    @Test
    fun `image 负数偏移不再被压糊`() {
        val el = parse("""{"type":"image","id":"img1","x":-152,"y":-236,"w":112,"h":148}""").elements.single()
        assertEquals(88f, el.x, 0.01f) // 240-152
        assertEquals(84f, el.y, 0.01f) // 320-236
    }

    // ── 3. 缺省 x/y = 区域中心（所有元素同规则）──

    @Test
    fun `image 缺省 x y 真正居中`() {
        val el = parse("""{"type":"image","id":"img1","w":182,"h":240}""").elements.single()
        assertEquals(240f, el.x, 0.01f)
        assertEquals(320f, el.y, 0.01f)
    }

    @Test
    fun `非 full zone 的 base 是该 zone 中心`() {
        // zone C = (6,552,468,80)，中心 = (6+234, 552+40) = (240, 592)
        val el = parse("""{"type":"circle","zone":"C","x":0,"y":0,"r":10}""").elements.single()
        assertEquals(240f, el.x, 0.01f)
        assertEquals(592f, el.y, 0.01f)
    }

    // ── 4. box 元素：x,y = 框中心；anchor:"topleft" opt-out ──

    @Test
    fun `image 缺省 anchor 时 x y 即框中心`() {
        val el = parse("""{"type":"image","id":"img1","x":0,"y":0,"w":182,"h":240}""").elements.single()
        // el.x/el.y 即框中心（画布中心）；渲染端据此画框，免手算 (480-w)/2
        assertEquals(240f, el.x, 0.01f)
        assertEquals(320f, el.y, 0.01f)
    }

    @Test
    fun `rect anchor topleft 时 x y 是框左上角`() {
        val el = parse("""{"type":"rect","x":0,"y":0,"w":40,"h":20,"anchor":"topleft"}""").elements.single()
        assertEquals(240f - 20f, el.x, 0.01f) // 框中心 240 减半宽
        assertEquals(320f - 10f, el.y, 0.01f)
    }

    @Test
    fun `brackets 与 ruler 也是 box 元素`() {
        val br = parse("""{"type":"brackets","x":0,"y":0,"w":126,"h":162,"len":16}""").elements.single()
        assertEquals(240f, br.x, 0.01f)
        assertEquals(320f, br.y, 0.01f)
        val ru = parse("""{"type":"ruler","x":0,"y":0,"w":120,"step":24,"major":3}""").elements.single()
        assertEquals(240f, ru.x, 0.01f)
        assertEquals(320f, ru.y, 0.01f)
    }

    // ── 5. 严格性 ──

    @Test
    fun `anchor topleft 配 x 表达式拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":"expr:t","w":182,"h":240,"anchor":"topleft"}""")
        }
    }

    @Test
    fun `anchor topleft 配 w 表达式拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":100,"y":100,"w":"expr:180","h":240,"anchor":"topleft"}""")
        }
    }

    @Test
    fun `anchor 非 box 元素拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"circle","x":100,"y":100,"r":10,"anchor":"center"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"text","x":10,"y":10,"content":"HI","anchor":"topleft"}""")
        }
    }

    @Test
    fun `anchor 非法值拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"type":"image","id":"img1","x":10,"y":10,"w":50,"h":50,"anchor":"middle"}""")
        }
    }

    // ── 6. 宏内部件 base = use 定位点 ──

    @Test
    fun `宏内部件 base 是 use 定位点`() {
        val s = HudDsl.parse(
            """{"v":1,"macros":{"m":[{"type":"dot","x":10,"y":-10,"r":2}]},""" +
                """"scene":[{"type":"use","use":"m","x":100,"y":50,"rot":45}]}""",
        )
        val el = s.elements.single()
        // use 定位点 = full 中心 + use x/y = (340, 370)；部件偏移 (10,-10) ⇒ (350, 360)
        assertEquals(350f, el.x, 1e-4f)
        assertEquals(360f, el.y, 1e-4f)
        // 组变换锚点 = use 定位点（仅 use 带 rot/mirror 时传播给部件）
        assertEquals(340f, el.pivX, 1e-4f)
        assertEquals(370f, el.pivY, 1e-4f)
    }

    @Test
    fun `宏内部件缺省 x y 落在 use 定位点`() {
        val s = HudDsl.parse(
            """{"v":1,"macros":{"m":[{"type":"dot","r":2}]},""" +
                """"scene":[{"type":"use","use":"m","x":-60,"y":40}]}""",
        )
        val el = s.elements.single()
        assertEquals(180f, el.x, 1e-4f) // 240-60
        assertEquals(360f, el.y, 1e-4f) // 320+40
    }

    // ── 7. path 的 d = 以元素 x,y 为原点的局部坐标 ──

    @Test
    fun `path 的 d 以元素 x y 为局部原点`() {
        val el = parse("""{"type":"path","x":0,"y":0,"d":"M -10 10 L 10 10"}""").elements.single()
        assertEquals(240f, el.pathDx, 0.01f)
        assertEquals(320f, el.pathDy, 0.01f)
        val corner = parse("""{"type":"path","x":-152,"y":-236,"d":"M 0 0 L 20 20"}""").elements.single()
        assertEquals(88f, corner.pathDx, 0.01f)
        assertEquals(84f, corner.pathDy, 0.01f)
    }

    // ── 8. brackets 字段映射（len/inset 修 doc/code 不一致）──

    @Test
    fun `brackets len 映射臂长且 w h 缺省为 zone 大小`() {
        val el = parse("""{"type":"brackets","len":16}""").elements.single()
        assertEquals(16f, el.r, 0.01f)
        assertEquals(480f, el.w, 0.01f) // 缺省 = full zone 宽
        assertEquals(640f, el.h, 0.01f)
        val shrunk = parse("""{"type":"brackets","w":126,"h":162,"len":16,"inset":6}""").elements.single()
        assertEquals(114f, shrunk.w, 0.01f) // 126-2*6
        assertEquals(150f, shrunk.h, 0.01f) // 162-2*6
    }

    // ── 回归：zoneCx/zoneCy 基准不变量（expr 运行时覆盖 x/y 时加回它）──

    @Test
    fun `zoneCx zoneCy 是定位基准`() {
        val el = parse("""{"type":"image","id":"img1","x":100,"y":-40,"w":10,"h":10}""").elements.single()
        assertEquals(240f, el.zoneCx, 0.01f)
        assertEquals(320f, el.zoneCy, 0.01f)
        assertEquals(340f, el.x, 0.01f) // 240+100
        assertEquals(280f, el.y, 0.01f) // 320-40
    }
}
