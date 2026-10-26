package com.rokidlab.phone.glasses

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.ai.tools.GlassHudToolProvider
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewTextBright

/**
 * HUD 实时渲染预览卡（2026-10-20 方案 A，用户确认）：聊天气泡里的 HUD 预览不是图片，
 * 而是 Compose Canvas 按 DSL **逐帧真渲染** —— 与眼镜端 HudOverlayController /
 * HudRenderer 同源绘制，预览就是活的（spin/orbit/pulse/ping 全部真实运动）。
 *
 * 消息侧承载：ChatMsg.imageUrl = `hud://<URL编码的 DSL JSON>`（见
 * [GlassHudToolProvider]），复用图片消息的持久化/挂气泡逻辑，模型零改动。
 *
 * 标题栏两个图标按钮（2026-10-25 用户要求补「关闭」）：放大 = 全屏实时渲染；
 * **关闭 = 摘掉手机端预览卡 + 同时收起眼镜端 HUD**（[onClose] 由 ChatBubble 传入，
 * 内部 dismissHudPreview + hideHud 两件事都做）。全屏 Dialog 里的关闭只退放大，
 * 不动预览卡本身。
 *
 * 时钟说明：`while + withFrameNanos` 连续时钟（不取模），任意 period 的动画都无循环
 * 接缝跳变 —— 与眼镜端连续时钟语义一致。组件滚出屏幕后循环仍在跑，但每帧只是
 * 更新一个 float + 一次被跳过的 draw，开销 O(1)；预览卡同屏最多一两条，可接受。
 */
@Composable
internal fun HudPreviewRoute(url: String, onClose: (() -> Unit)? = null) {
    val dsl = remember(url) {
        runCatching {
            java.net.URLDecoder.decode(url.removePrefix("hud://"), "UTF-8")
        }.getOrNull()
    }
    if (dsl == null) {
        HudPreviewShell(title = "HUD 预览", onClose = onClose) {
            Text("场景数据损坏", color = BrewMuted, fontSize = 12.sp)
        }
        return
    }
    val scene = remember(dsl) {
        runCatching { HudDsl.parse(dsl) }.getOrNull()
    }
    if (scene == null) {
        HudPreviewShell(title = "HUD 预览", onClose = onClose) {
            Text("场景 DSL 不合法", color = BrewMuted, fontSize = 12.sp)
        }
        return
    }
    var fullscreen by remember { mutableStateOf(false) }
    HudPreviewShell(title = "HUD 预览 · 实时", onExpand = { fullscreen = true }, onClose = onClose) {
        HudSceneCanvas(scene, Modifier.fillMaxWidth())
    }
    if (fullscreen) {
        HudFullscreenDialog(scene) { fullscreen = false }
    }
}

/** 卡片外壳：与 ChatImageCard 同一套卡片语言（标题栏 + 1dp 边框 + 圆角 10dp）。 */
@Composable
private fun HudPreviewShell(
    title: String,
    onExpand: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            if (onExpand != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onExpand),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInFull,
                        contentDescription = "放大预览",
                        tint = BrewMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 关闭预览卡（同时收起眼镜端 HUD，见 [HudPreviewRoute] 的 onClose）
            if (onClose != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "关闭预览",
                        tint = BrewMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF050805))
                .padding(6.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/**
 * 场景实时画布：每帧把 [HudRenderer.renderFrame] 画到 Compose 的 nativeCanvas。
 * 与眼镜端共享同一份绘制代码，所见即眼镜所得。
 *
 * P1-4 时间轴（2026-10-25）：画布下方一行「播放/暂停 + 0–30s 滑块 + 时刻读数」——
 * 拖动滑块即暂停并把 t 钉到该时刻（核对任意动画帧），点播放从当前 t 继续。
 * 播放超过 30s 后滑块钉在末端（动画继续播，拖动即定位回可调范围）。
 * 暂停时不请求帧 ⇒ 零开销；三个使用面（对话预览卡/全屏/主题管理预览）自动获得。
 */
@Composable
internal fun HudSceneCanvas(scene: HudDsl.Scene, modifier: Modifier = Modifier) {
    // playing 时连续时钟推进（跨暂停不跳变：增量累加，effect 重启不清 t）；
    // 暂停时 tMs 只由滑块把持。remember(scene) 换场景归零（与旧行为一致）。
    var tMs by remember(scene) { mutableFloatStateOf(0f) }
    var playing by remember(scene) { mutableStateOf(true) }
    LaunchedEffect(scene, playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                tMs += (now - last) / 1_000_000f
                last = now
            }
        }
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(HudDsl.SCREEN_W.toFloat() / HudDsl.SCREEN_H.toFloat())) {
            // ⚠️ 2026-10-20 修两处（用户实测：预览构图偏左上、标题栏/放大键被盖掉）：
            //  ① 场景是 480x640 坐标系，必须缩放到 Compose 画布实际尺寸——此前 1:1 直画，
            //     场景被锚在卡片左上角一小块里（眼镜端是 480x640 物理屏才恰好 1:1）；
            //  ② drawColor 会无视组合件边界填满整个绘制表面，把标题栏/放大键整个盖黑——
            //     必须先 clipRect 把绘制约束在本画布内。
            // bind 数据：预览直接取 HudDataHub 实时值（预览=眼镜所见）；timer 按锚点本地走秒。
            val nc = drawContext.canvas.nativeCanvas
            nc.save()
            nc.scale(size.width / HudDsl.SCREEN_W, size.height / HudDsl.SCREEN_H)
            nc.clipRect(0f, 0f, HudDsl.SCREEN_W.toFloat(), HudDsl.SCREEN_H.toFloat())
            // hud_image 登记图：预览=眼镜所见（登记表 miss 时 renderFrame 画占位虚线框，与眼镜端同语义）
            val images = scene.elements.mapNotNull { el ->
                el.imageId?.let { id -> GlassHudToolProvider.imageFor(id)?.let { id to it } }
            }.toMap()
            HudRenderer.renderFrame(scene, tMs, nc, HudDataHub.current(), HudDataHub.currentAnchors(), images)
            nc.restore()
        }
        HudTimelineRow(
            tSec = hudTimelineSec(tMs),
            playing = playing,
            onScrub = { sec ->
                playing = false
                tMs = sec * 1000f
            },
            onTogglePlay = { playing = !playing },
        )
    }
}

/** P1-4 时间轴滑块量程（秒）：覆盖默认 period 10s 的三轮循环，足够核对中间帧 */
private const val TIMELINE_MAX_SEC = 30f

/**
 * P1-4 时间轴读数：播放时刻 tMs → 滑块秒值（钳 0..[TIMELINE_MAX_SEC]）。
 *
 * 播放超过量程后钉在末端（动画继续播、拖动即定位回可调范围）——钳域与滑块值域必须
 * 同源：读数/滑块/单测三方共用这一个函数，杜绝「标签显示 45s 而滑块钉在 30s」的分叉。
 */
internal fun hudTimelineSec(tMs: Float): Float = (tMs / 1000f).coerceIn(0f, TIMELINE_MAX_SEC)

/** P1-4 时间轴行：播放/暂停 + 滑块 + 时刻读数（墨线体系配色，与预览卡同一套 Brew 语言）。 */
@Composable
private fun HudTimelineRow(
    tSec: Float,
    playing: Boolean,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 全屏/主题预览 Dialog 是「点任意处退出」：本行的空白处吞掉点击，避免瞄滑块
            // 误关 Dialog（滑块/播放键是子节点，主手势通路优先，不受影响）
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 2.dp, top = 5.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (playing) "暂停预览" else "播放预览",
                tint = BrewMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Slider(
            value = tSec,
            onValueChange = onScrub,
            valueRange = 0f..TIMELINE_MAX_SEC,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = BrewChat,
                activeTrackColor = BrewChat,
                inactiveTrackColor = BrewBorder,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.1fs", tSec),
            color = BrewMuted,
            fontSize = 10.sp,
        )
    }
}

/** 全屏查看：真比例 480x640 实时渲染，点任意处/关闭按钮退出。 */
@Composable
private fun HudFullscreenDialog(scene: HudDsl.Scene, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg.copy(alpha = 0.97f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            HudSceneCanvas(
                scene,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 44.dp),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = BrewTextBright,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 14.dp)
                    .size(22.dp)
                    .clickable(onClick = onDismiss),
            )
        }
    }
}

/** 主题库缩略图用：静态首帧位图（非 Compose 场合复用）。 */
internal fun HudDsl.Scene.renderThumbnail(): Bitmap = HudRenderer.render(this)
