package com.example.mimusica

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun Glass(
    bg: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    fg: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    var m = Modifier.clip(RoundedCornerShape(50)).background(bg)
    if (onClick != null) m = m.clickable(onClick = onClick)
    CompositionLocalProvider(LocalContentColor provides fg) {
        Row(m.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun RoundBtn(size: Dp, icon: Dp, bg: Color, tint: Color, vector: ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(vector, desc, Modifier.size(icon), tint = tint)
    }
}

@Composable
fun Waveform(seed: Long, progress: Float, playing: Boolean, modifier: Modifier, onPreview: (Float) -> Unit, onCommit: (Float) -> Unit) {
    val bars = 56
    val heights = remember(seed) { val r = java.util.Random(seed); FloatArray(bars) { 0.2f + r.nextFloat() * 0.8f } }
    val ink = MaterialTheme.colorScheme.onSurface
    val pv by rememberUpdatedState(onPreview)
    val cm by rememberUpdatedState(onCommit)
    var last by remember { mutableFloatStateOf(0f) }
    var phase by remember { mutableFloatStateOf(0f) }
    var lvl by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            while (true) {
                withFrameNanos { n ->
                    phase = (n / 1_000_000_000.0).toFloat() * 7f
                    lvl += (Dsp.level - lvl) * 0.25f
                }
            }
        } else {
            while (lvl > 0.01f) withFrameNanos { lvl *= 0.9f }
            lvl = 0f
        }
    }
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { cm((it.x / size.width).coerceIn(0f, 1f)) }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { last = (it.x / size.width).coerceIn(0f, 1f); pv(last) },
                    onDragEnd = { cm(last) },
                    onHorizontalDrag = { ch, _ -> last = (ch.position.x / size.width).coerceIn(0f, 1f); pv(last) }
                )
            }
    ) {
        val k = lvl
        val ph = phase
        val bw = size.width / bars
        for (i in 0 until bars) {
            val wob = 0.5f + 0.5f * sin(ph + i * 0.6f)
            val h = (heights[i] * (0.7f + 0.6f * k * wob)).coerceAtMost(1f) * size.height
            drawRoundRect(
                if ((i + 0.5f) / bars <= progress) ink.copy(alpha = 0.9f) else ink.copy(alpha = 0.25f),
                Offset(i * bw + bw * 0.2f, (size.height - h) / 2), Size(bw * 0.6f, h), CornerRadius(bw * 0.3f)
            )
        }
    }
}

@Composable
fun NowPlaying(
    c: MediaController?, item: MediaItem?, isPlaying: Boolean, shuffle: Boolean, repeat: Int,
    position: Long, duration: Long, remainingMs: Long,
    onBack: () -> Unit, onEq: () -> Unit, onTimer: () -> Unit
) {
    val ctx = LocalContext.current
    val id = item?.mediaId?.toLongOrNull()
    val uri = id?.let { uriOf(it) }
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        tint = uri?.let { u -> loadArt(ctx, u)?.let { b -> Color(Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)) } }
    }
    var info by remember(id) { mutableStateOf("") }
    LaunchedEffect(id) {
        info = if (uri == null) "" else withContext(Dispatchers.IO) { runCatching { techInfo(ctx, uri) }.getOrDefault("") }
    }
    val surface = MaterialTheme.colorScheme.surface
    val onSurf = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val top by animateColorAsState(tint ?: surface, label = "bg")
    var drag by remember { mutableStateOf<Float?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    val prog = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownProg = drag ?: prog
    val artist = item?.mediaMetadata?.artist?.toString() ?: ""
    val album = item?.mediaMetadata?.albumTitle?.toString() ?: ""
    val title = item?.mediaMetadata?.title?.toString() ?: ""
    val isFav = id != null && id in Store.favorites

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(surface, top, 0.7f), surface)))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp)) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val s = minOf(maxWidth, maxHeight)
            Box(
                Modifier.size(s)
                    .offset { IntOffset(dx.roundToInt(), 0) }
                    .pointerInput(c) {
                        val th = 90.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dx > th) c?.seekToPreviousMediaItem() else if (dx < -th) c?.seekToNextMediaItem()
                                dx = 0f
                            },
                            onDragCancel = { dx = 0f },
                            onHorizontalDrag = { _, d -> dx += d }
                        )
                    }
            ) {
                Art(uri, Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)))
                Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Glass(bg = Color.Black.copy(alpha = 0.4f), fg = Color.White, onClick = { id?.let { Store.toggleFav(it) } }) {
                        Icon(if (isFav) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, "Me gusta")
                    }
                    Glass(bg = Color.Black.copy(alpha = 0.4f), fg = Color.White, onClick = { c?.seekToNext() }) {
                        Icon(Icons.Rounded.ThumbDownOffAlt, "No me gusta")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Glass { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(6.dp))
        Glass { Text(if (album.isNotEmpty()) "$artist - $album" else artist, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glass(onClick = onEq) { Icon(Icons.Rounded.GraphicEq, "Ecualizador") }
            Spacer(Modifier.width(8.dp))
            Glass(onClick = onTimer, fg = if (remainingMs > 0) primary else onSurf) {
                Icon(Icons.Rounded.Schedule, "Temporizador")
                if (remainingMs > 0) Text("  ${remainingMs / 60000 + 1} min", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.weight(1f))
            Glass(fg = if (repeat == Player.REPEAT_MODE_OFF) onSurf else primary, onClick = {
                c?.repeatMode = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) { Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir") }
            Spacer(Modifier.width(8.dp))
            Glass(fg = if (shuffle) primary else onSurf, onClick = { c?.shuffleModeEnabled = !shuffle }) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RoundBtn(44.dp, 24.dp, onSurf.copy(alpha = 0.12f), onSurf.copy(alpha = 0.7f), Icons.Rounded.Replay10, "-10 s") { c?.seekTo((position - 10_000).coerceAtLeast(0)) }
            RoundBtn(60.dp, 32.dp, onSurf, surface, Icons.Rounded.SkipPrevious, "Anterior") { c?.seekToPrevious() }
            RoundBtn(84.dp, 48.dp, onSurf, surface, if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa") {
                c?.let { if (it.isPlaying) it.pause() else it.play() }
            }
            RoundBtn(60.dp, 32.dp, onSurf, surface, Icons.Rounded.SkipNext, "Siguiente") { c?.seekToNext() }
            RoundBtn(44.dp, 24.dp, onSurf.copy(alpha = 0.12f), onSurf.copy(alpha = 0.7f), Icons.Rounded.Forward10, "+10 s") { c?.seekTo(position + 10_000) }
        }
        Spacer(Modifier.height(10.dp))
        Waveform(
            seed = id ?: 0L, progress = shownProg, playing = isPlaying, modifier = Modifier.fillMaxWidth().height(52.dp),
            onPreview = { drag = it },
            onCommit = { f -> drag = null; if (duration > 0) c?.seekTo((f * duration).toLong()) }
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(fmt(if (drag != null) (drag!! * duration).toLong() else position), fmt(duration)).forEach {
                Box(Modifier.clip(RoundedCornerShape(50)).background(onSurf).padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Text(it, color = surface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).height(36.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (info.isNotEmpty()) Glass { Text(info.uppercase(), style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun BottomDock(
    current: MediaItem?, isPlaying: Boolean, position: Long, duration: Long,
    nav: Int, onNav: (Int) -> Unit, c: MediaController?, onOpen: () -> Unit
) {
    val dock = Color(0xFF1C1C1E)
    val grey = Color(0xFF8E8E93)
    Surface(
        shape = RoundedCornerShape(32.dp), color = dock,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (current != null) {
                Row(Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(current.mediaId.toLongOrNull()?.let { uriOf(it) }, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(current.mediaMetadata.title?.toString() ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.mediaMetadata.artist?.toString() ?: "", color = grey, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { c?.seekToPrevious() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(28.dp), tint = Color.White)
                    }
                    IconButton(onClick = { c?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(44.dp)) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa", Modifier.size(34.dp), tint = Color.White)
                    }
                    IconButton(onClick = { c?.seekToNext() }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(28.dp), tint = Color.White)
                    }
                }
                MiniSeek(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f) { f ->
                    if (duration > 0) c?.seekTo((f * duration).toLong())
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                listOf(Icons.Rounded.GridView, Icons.Rounded.Search, Icons.Rounded.QueueMusic, Icons.Rounded.Equalizer)
                    .forEachIndexed { i, icon ->
                        IconButton(onClick = { onNav(i) }) {
                            Icon(icon, null, Modifier.size(if (nav == i) 32.dp else 28.dp), tint = if (nav == i) Color.White else grey)
                        }
                    }
            }
        }
    }
}

@Composable
fun MiniSeek(progress: Float, onSeek: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var hold by remember { mutableStateOf<Float?>(null) }
    var last by remember { mutableFloatStateOf(0f) }
    val cm by rememberUpdatedState(onSeek)
    LaunchedEffect(hold) { if (hold != null) { delay(700); hold = null } }
    val shown = drag ?: hold ?: progress
    Canvas(
        Modifier.fillMaxWidth().height(28.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { val f = (it.x / size.width).coerceIn(0f, 1f); cm(f); hold = f }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { last = (it.x / size.width).coerceIn(0f, 1f); drag = last },
                    onDragEnd = { cm(last); hold = last; drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { ch, _ -> last = (ch.position.x / size.width).coerceIn(0f, 1f); drag = last }
                )
            }
    ) {
        val y = size.height / 2
        val h = 4.dp.toPx()
        drawRoundRect(Color(0xFF3A3A3C), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(size.width * shown, h), CornerRadius(h / 2))
        drawCircle(Color.White, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(size.width * shown, y))
    }
}
