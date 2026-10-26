package com.rokidlab.phone.glasses

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

/**
 * HUD 场景离屏渲染器（手机端）：把 [HudDsl.Scene] 画成 480x640。
 *
 * ⚠️ 绘制逻辑与 RokidLink `HudOverlayController.HudView` **同源双副本**（预览=眼镜所见，
 * 改绘制必须两端同步）。
 *
 * 两个入口共用同一套 [drawEl]：
 *  - [render]：静态帧（t=0），用于主题库缩略图 / 留档；
 *  - [renderFrame]：任意时刻帧，供聊天预览卡（Compose nativeCanvas）逐帧驱动 ——
 *    2026-10-20 起聊天里的 HUD 预览是真动画（用户确认方案 A），不再落 PNG。
 */
internal object HudRenderer {

    private val INK = intArrayOf(
        0xFF1E5C36.toInt(), 0xFF2E7A4B.toInt(), 0xFF9FE8B8.toInt(), 0xFF46FF86.toInt(),
    )

    private const val BG = 0xFF050805.toInt()

    /** 渲染成 480x640 ARGB 位图（深底，不透明；静态帧 t=0） */
    fun render(scene: HudDsl.Scene, images: Map<String, Bitmap> = emptyMap()): Bitmap {
        val bmp = Bitmap.createBitmap(HudDsl.SCREEN_W, HudDsl.SCREEN_H, Bitmap.Config.ARGB_8888)
        renderFrame(scene, 0f, Canvas(bmp), images = images)
        return bmp
    }

    /**
     * 把场景画到外部 [Canvas]（任意 t 时刻）。聊天预览卡每帧调用：
     * Canvas 来自 Compose 的 nativeCanvas，绘制代码与静态帧完全同源。
     *
     * [data] 为 bind 元素的数据源（key→原始值字符串，与 TOPIC_HUD_DATA 载荷同格式；
     * 预览侧传 HudDataHub 实时值保证预览=眼镜所见）。空 map = 全部空态（"--"/0/置暗）。
     * [anchors] 为各 key 数据到达锚点（elapsedRealtime），timer 键据此本地走秒。
     * [images] 为 hud_image 已登记的图片表（id→Bitmap），与眼镜端缓存同语义：miss 画占位虚线框。
     */
    fun renderFrame(
        scene: HudDsl.Scene,
        tMs: Float,
        canvas: Canvas,
        data: Map<String, String> = emptyMap(),
        anchors: Map<String, Long> = emptyMap(),
        images: Map<String, Bitmap> = emptyMap(),
    ) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        canvas.drawColor(BG)
        drawBackground(canvas, scene)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((idx, el) in scene.elements.withIndex()) {
            // v2 expr：每帧求值覆盖静态几何（无表达式的元素零开销直接画；与眼镜端同步）
            val e = if (el.exprs.isEmpty()) el else applyExpr(el, tMs, idx)
            drawEl(canvas, paint, e, tMs, data, anchors, nowMs, images)
        }
    }

    private fun drawBackground(canvas: Canvas, scene: HudDsl.Scene) {
        // 网格：中央最亮向外径向渐隐（每条线按到屏幕中心距离衰减）
        if (scene.grid >= 8f) {
            val cx = HudDsl.SCREEN_W / 2f
            val cy = HudDsl.SCREEN_H / 2f
            val maxDist = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
            val p = Paint().apply { strokeWidth = 1f }
            var x = scene.grid
            while (x < HudDsl.SCREEN_W) {
                val f = 1f - Math.hypot((x - cx).toDouble(), 0.0).toFloat() / maxDist
                p.color = INK[3]
                p.alpha = (255 * scene.gridOpacity * f).toInt().coerceIn(0, 255)
                canvas.drawLine(x, 0f, x, HudDsl.SCREEN_H.toFloat(), p)
                x += scene.grid
            }
            var y = scene.grid
            while (y < HudDsl.SCREEN_H) {
                val f = 1f - Math.hypot(0.0, (y - cy).toDouble()).toFloat() / maxDist
                p.color = INK[3]
                p.alpha = (255 * scene.gridOpacity * f).toInt().coerceIn(0, 255)
                canvas.drawLine(0f, y, HudDsl.SCREEN_W.toFloat(), y, p)
                y += scene.grid
            }
        }
        // 扫描线：每 3px 一条 1px 黑线（CRT 质感）
        if (scene.scanline > 0f) {
            val p = Paint().apply { color = 0xFF000000.toInt() }
            p.alpha = (255 * scene.scanline).toInt().coerceIn(0, 60)
            var y = 0
            while (y < HudDsl.SCREEN_H) {
                canvas.drawRect(0f, y.toFloat(), HudDsl.SCREEN_W.toFloat(), (y + 1).toFloat(), p)
                y += 3
            }
        }
    }

    private fun drawEl(
        canvas: Canvas,
        paint: Paint,
        el: HudDsl.El,
        tMs: Float,
        data: Map<String, String>,
        anchors: Map<String, Long>,
        nowMs: Long,
        images: Map<String, Bitmap>,
    ) {
        val bindKey = el.bindKey
        // bindOn 条件显隐：条件不满足（含无数据）→ 整元素不画
        // （enum 相等 or 数值阈值比较，与眼镜端 HudOverlayController.drawEl 同步）
        el.bindOnKey?.let { k ->
            if (!HudDsl.HudBind.matchOn(k, el.bindOnOp, el.bindOnCmp, el.bindOnVal, data[k])) return
        }
        // bind as=enum：off → 整体置暗 + 关呼吸（docs/hud-dsl.md「数据绑定」）
        val enumOff = el.bindAs == "enum" && bindKey != null &&
            (HudDsl.HudBind.isOn(bindKey, data[bindKey]) ?: false).not()
        val dim = if (enumOff) 0.3f else 1f
        // 效果预扫：glow/scale/dashFlow 不依赖 canvas 变换，先算好参数（text/ping 分支也用 glowMul）
        var glowMul = 1f
        var geoScale = 1f
        var flowOff = -1f // <0 = 无蚂蚁线
        for (a in el.anims) {
            when (a.type) {
                "glow" -> if (!enumOff) glowMul = 1f - a.amp + 2f * a.amp * wave(tMs, a.period, a.phase)
                "scale" -> geoScale = 1f + a.amp * (2f * wave(tMs, a.period, a.phase) - 1f)
                "dashFlow" -> {
                    val dir = if (a.reverse) -1f else 1f
                    val cyc = el.dashOn + el.dashOff
                    flowOff = ((tMs / 1000f * a.speed * dir) % cyc + cyc) % cyc
                }
            }
        }
        // fall 位移动画：y 向匀速循环位移（(t*speed)%zoneH，出底回顶）——text/ping/常规三分支各自应用
        val fallDy = el.anims.firstOrNull { it.type == "fall" }?.let { (tMs / 1000f * it.speed) % el.zoneH } ?: 0f
        // bind as=text：整条走文本分支（bind 优先于 content；无数据 "--"）
        if (el.type == "text") {
            val display = if (bindKey != null && el.bindAs == "text") {
                HudDsl.HudBind.displayText(bindKey, data[bindKey], anchors[bindKey] ?: nowMs, nowMs)
            } else {
                el.text ?: "--"
            }
            paint.style = Paint.Style.FILL
            paint.color = INK[el.stroke]
            paint.pathEffect = null
            paint.textSize = el.size
            paint.alpha = (255 * el.opacity * dim * glowMul).toInt().coerceIn(0, 255)
            if (fallDy != 0f) {
                canvas.save()
                canvas.translate(0f, fallDy)
            }
            if (el.dirDown) {
                // v2 纵排：逐字符下排（步进 size*1.2），x,y 为首字符锚点，align 控横向对齐
                var cy = el.y
                for (ch in display) {
                    val s = ch.toString()
                    val cx = if (el.align == "center") el.x - paint.measureText(s) / 2f else el.x
                    canvas.drawText(s, cx, cy, paint)
                    cy += el.size * 1.2f
                }
            } else {
                // P1-1 多行：display 含 \n 时逐行绘制（行距 size*1.2，与纵排步进一致；align 逐行生效）
                val lines = display.split('\n')
                if (lines.size <= 1) {
                    val tx = if (el.align == "center") el.x - paint.measureText(display) / 2f else el.x
                    canvas.drawText(display, tx, el.y, paint)
                } else {
                    lines.forEachIndexed { li, ln ->
                        val ty = el.y + li * el.size * 1.2f
                        val tx = if (el.align == "center") el.x - paint.measureText(ln) / 2f else el.x
                        canvas.drawText(ln, tx, ty, paint)
                    }
                }
            }
            if (fallDy != 0f) canvas.restore()
            return
        }
        // ping：独立的时间驱动扩散环
        if (el.type == "ping") {
            if (fallDy != 0f) {
                canvas.save()
                canvas.translate(0f, fallDy)
            }
            val period = el.interval * 1000f
            val phase = (tMs % period) / period
            val alpha = ((1f - phase) * el.opacity * 255f).toInt().coerceIn(0, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = INK[el.stroke]
            paint.alpha = alpha
            paint.pathEffect = null
            canvas.drawCircle(el.x, el.y, 2f + (el.r - 2f) * phase, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = (el.opacity * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(el.x, el.y, 2f, paint)
            if (fallDy != 0f) canvas.restore()
            return
        }
        var alphaMul = 1f
        canvas.save()
        // use 整组变换（rot/mirror 绕组定位点）：先组后部件，部件自身动画在组空间内叠加
        if (el.useRot != 0f || el.useMirror) {
            if (el.useRot != 0f) canvas.rotate(el.useRot, el.pivX, el.pivY)
            if (el.useMirror) canvas.scale(-1f, 1f, el.pivX, el.pivY)
        }
        // fall 位移在组变换后的元素空间内叠加（与 spin/pulse 等组合；与眼镜端同步）
        if (fallDy != 0f) canvas.translate(0f, fallDy)
        for (a in el.anims) {
            when (a.type) {
                "spin" -> {
                    val dir = if (a.reverse) -1f else 1f
                    canvas.rotate(tMs / 1000f / a.period * 360f * dir + a.phase, el.x, el.y)
                }
                "orbit" -> {
                    val dir = if (a.reverse) -1f else 1f
                    val theta = (tMs / 1000f / a.period + a.phase / 360f) * 2.0 * Math.PI * dir
                    val ox = el.x + a.orbitR * Math.cos(theta).toFloat()
                    val oy = el.y + a.orbitR * Math.sin(theta).toFloat()
                    paint.color = INK[el.stroke]
                    paint.style = Paint.Style.FILL
                    paint.alpha = (255 * el.opacity * glowMul).toInt().coerceIn(0, 255)
                    paint.pathEffect = null
                    canvas.drawCircle(ox, oy, if (el.r > 0f) el.r else 2f, paint)
                    paint.alpha = (120 * el.opacity * glowMul).toInt().coerceIn(0, 255)
                    // ⚠️ 2026-10-20 修：轨迹环必须 STROKE——此前 style 仍是上面画点用的 FILL，
                    //   会画出一个半径=orbitR 的巨大实心圆盘盖住整个构图（手机/眼镜同源同病）。
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.6f
                    canvas.drawCircle(el.x, el.y, a.orbitR, paint)
                    canvas.restore()
                    return
                }
                "pulse" -> if (!enumOff) alphaMul = 0.35f + 0.65f * wave(tMs, a.period, a.phase)
                "fade" -> if (!enumOff) alphaMul = 0.2f + 0.8f * wave(tMs, a.period, a.phase)
            }
        }
        // image：hud_image 登记的主视觉图（contain 适配 w×h 框居中；spin/scale/glow/
        // 组变换经 canvas/geoScale/glowMul 天然生效；缓存 miss 画占位虚线框。与眼镜端同步）
        if (el.type == "image") {
            val bmp = images[el.imageId]
            paint.color = INK[2]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.pathEffect = null
            if (bmp == null) {
                paint.pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
                paint.alpha = (255 * el.opacity * dim).toInt().coerceIn(0, 255)
                // x,y = 框中心（2026-10-21 中心原点；占位框同语义）
                canvas.drawRect(
                    el.x - el.w / 2f, el.y - el.h / 2f, el.x + el.w / 2f, el.y + el.h / 2f, paint,
                )
                canvas.restore()
                return
            }
            val fit = minOf(el.w / bmp.width, el.h / bmp.height)
            val bw = bmp.width * fit * geoScale
            val bh = bmp.height * fit * geoScale
            val cx = el.x
            val cy = el.y
            paint.isFilterBitmap = true
            paint.alpha = (255 * el.opacity * alphaMul * glowMul * dim)
                .toInt().coerceIn(0, 255)
            // P1-3 mask=circle：圆形蒙版（直径=min(w,h)，含 geoScale ⇒ 随呼吸缩放；
            // 与眼镜端同步）。占位框（bmp==null）仍是矩形虚线框，不做圆形裁剪。
            val clipCircle = el.mask == "circle"
            if (clipCircle) {
                canvas.save()
                canvas.clipPath(
                    android.graphics.Path().apply {
                        addCircle(cx, cy, minOf(bw, bh) / 2f, android.graphics.Path.Direction.CW)
                    },
                )
            }
            // P1-3 blend=screen：位图与已绘内容 Screen 叠印（黑区透出下层墨线、亮区加亮；
            // xfermode 画完必须复位——paint 跨元素复用，泄漏会污染后续所有元素）
            if (el.blend == "screen") {
                paint.xfermode = android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SCREEN,
                )
            }
            canvas.drawBitmap(
                bmp, null,
                android.graphics.RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f),
                paint,
            )
            paint.xfermode = null
            if (clipCircle) canvas.restore()
            canvas.restore()
            return
        }
        alphaMul *= glowMul * dim
        paint.color = INK[el.stroke]
        paint.alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
        paint.strokeWidth = el.width
        paint.pathEffect = if (flowOff >= 0f) {
            // dashFlow：蚂蚁线沿路径流动（DashPathEffect phase 负值 = 图案沿路径前进）
            DashPathEffect(floatArrayOf(el.dashOn, el.dashOff), -flowOff)
        } else if (el.dashOn > 0f && el.dashOff > 0f) {
            DashPathEffect(floatArrayOf(el.dashOn, el.dashOff), 0f)
        } else {
            null
        }
        paint.style = Paint.Style.STROKE
        // bind as=count：dot → 光点行（值/bindMax×容量；暗点画轨道，无数据全空）
        if (el.type == "dot" && el.bindAs == "count" && bindKey != null) {
            val v = HudDsl.HudBind.numeric(bindKey, data[bindKey])
            val count = if (v == null) 0 else ((v / el.bindMax.toDouble()) * el.maxDots).toInt().coerceIn(0, el.maxDots)
            val pr = if (el.r > 0f) el.r else 2f
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            paint.alpha = (255 * el.opacity * alphaMul * 0.18f).toInt().coerceIn(0, 255)
            for (i in 0 until el.maxDots) canvas.drawCircle(el.x + i * el.step, el.y, pr, paint)
            paint.alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
            for (i in 0 until count) canvas.drawCircle(el.x + i * el.step, el.y, pr, paint)
            canvas.restore()
            return
        }
        // bind as=progress：line 按比例描绘（值/bindMax；从 (x,y) 画到值的比例处，无数据 0）
        // 轨道用一条静态 line 自己画（同 count 点阵模式）；与眼镜端同步
        if (el.type == "line" && el.bindAs == "progress" && bindKey != null) {
            val v = HudDsl.HudBind.numeric(bindKey, data[bindKey])
            val pct = ((v ?: 0.0) / el.bindMax.toDouble()).toFloat().coerceIn(0f, 1f)
            canvas.drawLine(el.x, el.y, el.x + (el.x2 - el.x) * pct, el.y + (el.y2 - el.y) * pct, paint)
            canvas.restore()
            return
        }
        // bind as=sweep：arc 扫角由数据驱动（值/bindMax×360，无数据 0）
        val sweepOverride = if (el.bindAs == "sweep" && bindKey != null) {
            (HudDsl.HudBind.numeric(bindKey, data[bindKey]) ?: 0.0).toFloat() / el.bindMax * 360f
        } else {
            null
        }
        if (el.fillInk >= 0) {
            val fillPaint = Paint(paint).apply {
                style = Paint.Style.FILL
                color = INK[el.fillInk]
                alpha = (255 * el.opacity * alphaMul * 0.55f).toInt().coerceIn(0, 255)
                pathEffect = null
            }
            drawShape(canvas, fillPaint, el, sweepOverride, geoScale)
        }
        drawShape(canvas, paint, el, sweepOverride, geoScale)
        // bind as=bar：rect 比例填充条（值/bindMax；clip 到值比例，barFill 墨色；与眼镜端同步）
        if (el.type == "rect" && el.bindAs == "bar" && bindKey != null) {
            val v = HudDsl.HudBind.numeric(bindKey, data[bindKey])
            val pct = ((v ?: 0.0) / el.bindMax.toDouble()).toFloat().coerceIn(0f, 1f)
            val bw = el.w * geoScale
            val bh = el.h * geoScale
            val fillW = bw * pct
            if (fillW > 0.5f) {
                val left = el.x - bw / 2f
                val top = el.y - bh / 2f
                canvas.save()
                canvas.clipRect(left, top, left + fillW, top + bh)
                val barPaint = Paint(paint).apply {
                    style = Paint.Style.FILL
                    color = INK[el.barFill]
                    alpha = (255 * el.opacity * alphaMul).toInt().coerceIn(0, 255)
                    pathEffect = null
                }
                if (el.radius > 0f) {
                    canvas.drawRoundRect(left, top, left + bw, top + bh, el.radius, el.radius, barPaint)
                } else {
                    canvas.drawRect(left, top, left + bw, top + bh, barPaint)
                }
                canvas.restore()
            }
        }
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, paint: Paint, el: HudDsl.El, sweepOverride: Float? = null, scale: Float = 1f) {
        when (el.type) {
            "circle", "dot" -> {
                if (el.type == "dot") paint.style = Paint.Style.FILL
                val sr = if (el.r > 0f) el.r else 2f
                if (el.type == "circle" && (el.rx > 0f || el.ry > 0f)) {
                    // 椭圆：rx/ry 缺一侧时回退 r
                    val rx = (if (el.rx > 0f) el.rx else sr) * scale
                    val ry = (if (el.ry > 0f) el.ry else sr) * scale
                    canvas.drawOval(el.x - rx, el.y - ry, el.x + rx, el.y + ry, paint)
                } else {
                    canvas.drawCircle(el.x, el.y, sr * scale, paint)
                }
            }
            "arc" -> canvas.drawArc(
                el.x - el.r, el.y - el.r, el.x + el.r, el.y + el.r,
                el.start - 90f, sweepOverride ?: el.sweep, false, paint,
            )
            "line" -> canvas.drawLine(el.x, el.y, el.x2, el.y2, paint)
            "rect" -> {
                // x,y = 框中心；尺寸乘 scale 绕矩形自身中心呼吸（2026-10-21 中心原点）
                val w = el.w * scale
                val h = el.h * scale
                val left = el.x - w / 2f
                val top = el.y - h / 2f
                if (el.radius > 0f) {
                    canvas.drawRoundRect(left, top, left + w, top + h, el.radius, el.radius, paint)
                } else {
                    canvas.drawRect(left, top, left + w, top + h, paint)
                }
            }
            "polygon" -> {
                val path = Path()
                val seg = 2.0 * Math.PI / el.sides
                val pr = el.r * scale
                for (i in 0 until el.sides) {
                    val a = -Math.PI / 2 + seg * i
                    val px = el.x + pr * Math.cos(a).toFloat()
                    val py = el.y + pr * Math.sin(a).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
            "path" -> canvas.drawPath(buildPath(el.pathData ?: "", el.pathDx, el.pathDy), paint)
            "brackets" -> {
                // 四角角标框：x,y = 框中心，w/h = 框大小，r = 臂长（2026-10-21 重写：
                // 旧实现 inset=el.x、固定在屏幕四角且忽略 w/h/y，模型写的框尺寸全被丢弃）
                val len = el.r
                val left = el.x - el.w / 2f
                val top = el.y - el.h / 2f
                val right = el.x + el.w / 2f
                val bottom = el.y + el.h / 2f
                val path = Path()
                path.moveTo(left, top + len); path.lineTo(left, top); path.lineTo(left + len, top)
                path.moveTo(right - len, top); path.lineTo(right, top); path.lineTo(right, top + len)
                path.moveTo(left, bottom - len); path.lineTo(left, bottom); path.lineTo(left + len, bottom)
                path.moveTo(right - len, bottom); path.lineTo(right, bottom); path.lineTo(right, bottom - len)
                canvas.drawPath(path, paint)
            }
            "ruler" -> {
                val path = Path()
                var i = 0
                while (i * el.step <= el.w) {
                    val tall = i % el.major == 0
                    val th = if (tall) 7f else 4f
                    // x,y = 刻度行中心：从 x-w/2 起向右排（2026-10-21 中心原点）
                    val tx = el.x - el.w / 2f + i * el.step
                    path.moveTo(tx, el.y)
                    path.lineTo(tx, el.y + th)
                    i++
                }
                canvas.drawPath(path, paint)
            }
        }
    }

    /** 极简 SVG path → android Path（v1 仅绝对坐标 M/L/H/V/C/Q/Z，非法指令已在解析期拒绝） */
    private fun buildPath(d: String, dx: Float, dy: Float): Path {
        val path = Path()
        val tokens = TOKEN_REGEX.findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var lastX = 0f
        var lastY = 0f
        fun nextF(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0].uppercaseChar()
                i++
                if (cmd == 'Z') {
                    path.close()
                    continue
                }
            }
            when (cmd) {
                'M' -> { lastX = nextF() + dx; lastY = nextF() + dy; path.moveTo(lastX, lastY) }
                'L' -> { lastX = nextF() + dx; lastY = nextF() + dy; path.lineTo(lastX, lastY) }
                'H' -> { lastX = nextF() + dx; path.lineTo(lastX, lastY) }
                'V' -> { lastY = nextF() + dy; path.lineTo(lastX, lastY) }
                'C' -> {
                    val x1 = nextF() + dx; val y1 = nextF() + dy
                    val x2 = nextF() + dx; val y2 = nextF() + dy
                    lastX = nextF() + dx; lastY = nextF() + dy
                    path.cubicTo(x1, y1, x2, y2, lastX, lastY)
                }
                'Q' -> {
                    val x1 = nextF() + dx; val y1 = nextF() + dy
                    lastX = nextF() + dx; lastY = nextF() + dy
                    path.quadTo(x1, y1, lastX, lastY)
                }
            }
        }
        return path
    }

    private val TOKEN_REGEX = Regex("[MLHVQCZmlhvcz]|-?\\d+(?:\\.\\d+)?")

    /** 正弦波 0..1；phaseDeg = 周期内相位偏移（0–360，错峰呼吸/旋转） */
    private fun wave(tMs: Float, periodSec: Float, phaseDeg: Float = 0f): Float {
        val phase = (tMs / 1000f / periodSec + phaseDeg / 360f) * 2.0 * Math.PI
        return (0.5 + 0.5 * Math.sin(phase)).toFloat()
    }

    // ── v2 expr 求值挂钩（⚠️ 与 RokidLink HudOverlayController 同步）──

    /**
     * expr 属性逐帧求值，产出元素副本（不改解析结果本体：静态缩略图/逐帧预览共用同一 Scene）。
     * env：t 播放秒 / i 元素序号 / w,h 元素 zone 宽高 / sw,sh 屏宽高。
     */
    private fun applyExpr(el: HudDsl.El, tMs: Float, idx: Int): HudDsl.El {
        val c = HudDsl.El()
        c.type = el.type
        c.x = el.x; c.y = el.y; c.x2 = el.x2; c.y2 = el.y2
        c.r = el.r; c.rx = el.rx; c.ry = el.ry; c.w = el.w; c.h = el.h
        c.radius = el.radius; c.start = el.start; c.sweep = el.sweep
        c.sides = el.sides; c.pathData = el.pathData; c.pathDx = el.pathDx; c.pathDy = el.pathDy
        c.stroke = el.stroke; c.fillInk = el.fillInk; c.width = el.width
        c.opacity = el.opacity; c.dashOn = el.dashOn; c.dashOff = el.dashOff
        c.step = el.step; c.major = el.major; c.interval = el.interval
        c.anims = el.anims; c.text = el.text; c.size = el.size; c.align = el.align
        c.bindKey = el.bindKey; c.bindAs = el.bindAs; c.maxDots = el.maxDots; c.bindOn = el.bindOn
        c.bindOnKey = el.bindOnKey; c.bindOnOp = el.bindOnOp; c.bindOnVal = el.bindOnVal; c.bindOnCmp = el.bindOnCmp
        c.barFill = el.barFill; c.bindMax = el.bindMax
        c.imageId = el.imageId; c.useRot = el.useRot; c.useMirror = el.useMirror
        c.mask = el.mask; c.blend = el.blend
        c.pivX = el.pivX; c.pivY = el.pivY; c.zoneW = el.zoneW; c.zoneH = el.zoneH; c.dirDown = el.dirDown
        c.zoneCx = el.zoneCx; c.zoneCy = el.zoneCy
        val env = floatArrayOf(
            tMs / 1000f, idx.toFloat(), el.zoneW, el.zoneH,
            HudDsl.SCREEN_W.toFloat(), HudDsl.SCREEN_H.toFloat(),
        )
        for ((k, node) in el.exprs) {
            val v = clampExprAttr(k, HudExpr.eval(node, env))
            when (k) {
                // x/y/x2/y2 的 expr 结果 = 相对区域中心的偏移（与静态 x/y 同语义），
                // 加回 base 得到画布绝对坐标（2026-10-21 中心原点翻转，双端同步）
                "x" -> c.x = el.zoneCx + v
                "y" -> c.y = el.zoneCy + v
                "x2" -> c.x2 = el.zoneCx + v
                "y2" -> c.y2 = el.zoneCy + v
                "r" -> c.r = v
                "rx" -> c.rx = v
                "ry" -> c.ry = v
                "w" -> c.w = v
                "h" -> c.h = v
                "opacity" -> c.opacity = v
                "size" -> c.size = v
                "start" -> c.start = v
                "sweep" -> c.sweep = v
            }
        }
        return c
    }

    /** 求值结果钳制：x/y 轴为「相对区域中心的偏移」[-320,320]/[-400,400]（加回 base 后即画布坐标）、
     * 尺寸 [0,690]、opacity [0,1]、size [8,48]（docs §2；2026-10-21 随中心原点翻转调整 x/y 量纲） */
    private fun clampExprAttr(k: String, v: Float): Float = when (k) {
        "x", "x2" -> v.coerceIn(-320f, 320f)
        "y", "y2" -> v.coerceIn(-400f, 400f)
        "r", "rx", "ry", "w", "h" -> v.coerceIn(0f, 690f)
        "opacity" -> v.coerceIn(0f, 1f)
        "size" -> v.coerceIn(8f, 48f)
        "start" -> v.coerceIn(-360f, 720f)
        else -> v.coerceIn(0f, 360f) // sweep
    }
}
