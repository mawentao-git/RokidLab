package com.rokidlab.phone.store

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewPink
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 音视频扩展名（文件卡据此分流到播放卡） */
private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "3gp")
private val AUDIO_EXTS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus")

/** 这个文件是不是媒体（决定「文件卡」还是「播放卡」） */
internal fun isMediaFile(name: String): Boolean {
    val ext = ChatAttachments.extOf(name)
    return VIDEO_EXTS.contains(ext) || AUDIO_EXTS.contains(ext)
}

/**
 * 音视频卡：**内置播放器**（`VideoView`，系统自带解码器，不引 ExoPlayer 这类依赖）。
 *
 * 出现的场合：AI 在容器里用 ffmpeg 之类生成音视频、眼镜录像回传、或用户把音视频放进共享目录后由 diff 识别。
 *
 * 取舍：
 *  - 用 `VideoView` + 系统 `MediaController`：零依赖、自带进度条/暂停，代价是样式朴素；
 *  - **点一下才开始播**（不自动播放）：对话列表里自动出声是最招人烦的交互之一；
 *  - 音频没有画面，用一个大图标占位 + 同一个播放器（VideoView 播音频时画面是黑的，
 *    所以给音频单开一条"图标 + 时长"的形态，避免出现一块莫名其妙的黑矩形）。
 *
 * ## 封面帧（2026-10-22 修：卡片第一帧是黑的）
 *
 * `VideoView` 在 `start()` 之前不绘制任何内容 —— 卡片上就是一块黑矩形，用户以为片子是坏的。
 * 修法 = 用 [MediaMetadataRetriever] 取一帧当封面盖在播放器上，点播放时撤掉：
 *  - **刻意取 1s 处而不是第 0 帧**：H.264 首帧常是编码器预热帧（纯黑/纯灰），
 *    取它等于白干；不足 1s 的短片由 `getFrameAtTime` 返回最接近的帧，不会失败；
 *  - 取帧在 IO 线程、`use` 保证 release（Retriever 持有 native 句柄，泄漏会拖死解码器）；
 *  - 取不到封面不算错误：退回原来的黑底 + 播放按钮，功能不受影响。
 *
 * ## 全屏（与图片卡 `HeaderGlyph` + `OpenInFull` 同款式）
 *
 * 标题栏右上角放大按钮 → 近黑遮罩 Dialog 里**另一个** `VideoView` 全屏播（不缩原卡片）。
 * 两个播放器不会同时出声：进全屏前先 `stopPlayback()` 原播放器。
 */
@Composable
internal fun ChatMediaCard(path: String, name: String) {
    val ctx = LocalContext.current
    val isVideo = VIDEO_EXTS.contains(ChatAttachments.extOf(name))
    val uri = remember(path) { Uri.fromFile(java.io.File(path)) }
    var videoView by remember(path) { mutableStateOf<VideoView?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var prepared by remember(path) { mutableStateOf(false) }
    var fullscreen by remember(path) { mutableStateOf(false) }
    /** 封面帧：null = 还没取好或取不到（此时按旧行为显示黑底+播放键） */
    var cover by remember(path) { mutableStateOf<Bitmap?>(null) }
    /** 用户点过播放（或全屏）后不再显示封面，避免盖住正在播的画面 */
    var started by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (!isVideo) return@LaunchedEffect
        cover = withContext(Dispatchers.IO) { extractCoverFrame(path) }
    }

    // 离开屏幕就释放播放器（不释放会一直占着解码器，列表里滑几下就一堆实例）
    DisposableEffect(path) {
        onDispose {
            runCatching {
                videoView?.stopPlayback()
                videoView?.setMediaController(null as MediaController?)
            }
            videoView = null
            // Bitmap 是 native 内存，列表滑走要显式回收，否则长列表会 OOM
            runCatching { cover?.recycle() }
        }
    }

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
                .background(BrewPink.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Outlined.Movie else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = BrewPink,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    if (isVideo) R.string.chat_media_video else R.string.chat_media_audio,
                ),
                color = BrewPink,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                color = BrewMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 全屏入口：只有视频有（音频全屏没意义），与图片/图表卡的 HeaderGlyph 同款
            if (isVideo) {
                HeaderGlyph(
                    enabled = prepared,
                    onClick = {
                        // 不让两个播放器同时出声：进全屏先停卡片内播放
                        runCatching { videoView?.pause() }
                        playing = false
                        started = true
                        fullscreen = true
                    },
                    icon = Icons.Outlined.OpenInFull,
                    description = stringResource(R.string.chat_media_fullscreen),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(BrewPanel),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            prepared = true
                            // 不自动播：等用户点
                            mp.isLooping = false
                        }
                        setOnCompletionListener {
                            playing = false
                            // 播完回到封面态（否则停在最后一帧，用户看不出"已经放完了"）
                            started = false
                        }
                        // 系统控制器：进度条 + 暂停都自带（音频时它也有用）
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        videoView = this
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isVideo) 200.dp else 1.dp),
            )
            if (isVideo) {
                val bmp = cover
                if (bmp != null && !started) {
                    // 封面盖在播放器上：点封面 = 播放（与点播放键同一效果）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(BrewPanelAlt)
                            .clickable {
                                started = true
                                startPlayback(videoView) { playing = it }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // 中心播放键（半透明黑底圆）：明确的"可以点"的视觉提示
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.chat_media_play),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                } else if (!started && !prepared) {
                    // 封面还没取好：给一句话而不是干等黑屏
                    Text(
                        text = stringResource(R.string.chat_media_loading),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                } else if (!started) {
                    // 取不到封面的降级态：黑底 + 播放键（旧行为，但至少能点）
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clickable {
                                started = true
                                startPlayback(videoView) { playing = it }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.chat_media_play),
                                tint = Color.White,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            } else {
                // 音频：播放/暂停 + 状态文案（VideoView 的画面区在音频时是黑的，这里盖掉）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val v = videoView ?: return@clickable
                            if (playing) {
                                v.pause()
                                playing = false
                            } else {
                                v.start()
                                playing = true
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.chat_media_pause else R.string.chat_media_play,
                        ),
                        tint = BrewChat,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            when {
                                playing -> R.string.chat_media_playing
                                prepared -> R.string.chat_media_play
                                else -> R.string.chat_media_loading
                            },
                        ),
                        color = BrewTextBright,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    if (fullscreen && isVideo) {
        MediaFullscreenDialog(
            uri = uri,
            name = name,
            onDismiss = { fullscreen = false },
        )
    }
}

/** 卡片内播放：`start()` 后由 [onPlaying] 回写状态（失败不抛，最多停在原位） */
private fun startPlayback(v: VideoView?, onPlaying: (Boolean) -> Unit) {
    val vv = v ?: return
    runCatching {
        vv.start()
        onPlaying(true)
    }
}

/**
 * 取封面帧（IO 线程调用）。
 *
 * 为什么不取第 0 帧：H.264 首帧常见是编码器的预热黑帧（尤其 `MediaRecorder` 直出的
 * mp4），取它等于还是黑屏。1s 处稳定得多；短片由 `getFrameAtTime` 就近返回，不失败。
 * 两次尝试都失败返回 null（调用方降级为黑底 + 播放键）。
 */
private fun extractCoverFrame(path: String): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(path)
        r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/** 全屏播放：近黑遮罩 + 撑满的 VideoView，点右上角关闭（与图片全屏同一形态）。 */
@Composable
private fun MediaFullscreenDialog(uri: Uri, name: String, onDismiss: () -> Unit) {
    var fullView by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                fullView?.stopPlayback()
                fullView?.setMediaController(null as MediaController?)
            }
            fullView = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg.copy(alpha = 0.97f)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(uri)
                        setOnPreparedListener { mp ->
                            mp.isLooping = false
                            // 全屏是用户明确点进来的：直接播，不必再点一次
                            runCatching { start() }
                        }
                        setMediaController(MediaController(c).also { it.setAnchorView(this) })
                        fullView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 44.dp),
            )
            Text(
                text = name,
                color = BrewMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 14.dp, start = 14.dp, end = 44.dp),
            )
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.chat_media_fullscreen),
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
