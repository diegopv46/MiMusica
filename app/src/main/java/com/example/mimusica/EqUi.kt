package com.example.mimusica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Lime = Color(0xFFC6FF00)
private val Green = Color(0xFF00E676)
private val Grey = Color(0xFF4A4A4E)

private fun fmt1(v: Float) = "%.1f".format(v)

private fun yToValue(y: Float, h: Float, kh: Float, min: Float, max: Float): Float {
    val top = kh / 2
    val bot = h - kh / 2
    val t = ((y - top) / (bot - top)).coerceIn(0f, 1f)
    return ((max - t * (max - min)) * 10).roundToInt() / 10f
}

@Composable
fun VSlider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val cb by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Canvas(
        modifier
            .pointerInput(min, max) {
                val kh = 48.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { cb(yToValue(it.y, size.height.toFloat(), kh, min, max)) },
                    onDragEnd = { done() },
                    onVerticalDrag = { ch, _ -> cb(yToValue(ch.position.y, size.height.toFloat(), kh, min, max)) }
                )
            }
            .pointerInput(min, max) {
                val kh = 48.dp.toPx()
                detectTapGestures(onTap = { cb(yToValue(it.y, size.height.toFloat(), kh, min, max)); done() })
            }
    ) {
        val kh = 48.dp.toPx()
        val kw = minOf(40.dp.toPx(), size.width * 0.92f)
        val top = kh / 2
        val bot = size.height - kh / 2
        val cx = size.width / 2
        fun yOf(v: Float) = top + (max - v) / (max - min) * (bot - top)
        for (t in 0..4) {
            val y = top + (bot - top) * t / 4f
            drawLine(Grey, Offset(cx - size.width * 0.48f, y), Offset(cx - size.width * 0.34f, y), 2.dp.toPx())
            drawLine(Grey, Offset(cx + size.width * 0.34f, y), Offset(cx + size.width * 0.48f, y), 2.dp.toPx())
        }
        drawLine(Grey, Offset(cx, top), Offset(cx, bot), 3.dp.toPx(), StrokeCap.Round)
        val y0 = yOf(0f)
        val y1 = yOf(value.coerceIn(min, max))
        if (abs(y1 - y0) > 1f) {
            val brush = Brush.verticalGradient(listOf(Lime, Green), startY = minOf(y0, y1), endY = maxOf(y0, y1))
            drawLine(Lime.copy(alpha = 0.22f), Offset(cx, y0), Offset(cx, y1), 16.dp.toPx(), StrokeCap.Round)
            drawLine(brush, Offset(cx, y0), Offset(cx, y1), 4.dp.toPx(), StrokeCap.Round)
        }
        drawRoundRect(Color(0xFF26262A), Offset(cx - kw / 2, y1 - kh / 2), Size(kw, kh), CornerRadius(minOf(18.dp.toPx(), kw * 0.45f)))
        drawRoundRect(Color(0xFFBDBDBD), Offset(cx - kw * 0.22f, y1 - 1.5.dp.toPx()), Size(kw * 0.44f, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}

@Composable
fun Knob(label: String, value: Float, active: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val cb by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val cur by rememberUpdatedState(value)
    val ink = if (active) Color.White else Color(0xFF666669)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(54.dp).pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { done() },
                    onVerticalDrag = { _, dy -> cb((cur - dy * 0.3f).coerceIn(0f, 100f)) }
                )
            }
        ) {
            drawCircle(Color(0xFF2A2A2D))
            val a = Math.toRadians(-135.0 + cur / 100.0 * 270.0)
            val r = size.minDimension / 2
            drawLine(
                ink, center,
                Offset(center.x + (sin(a) * r * 0.8).toFloat(), center.y - (cos(a) * r * 0.8).toFloat()),
                4.dp.toPx(), StrokeCap.Round
            )
        }
        Text("$label ${value.toInt()}%", color = ink, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape)
            .background(if (selected) Color(0xFF2A2A2D) else Color.Transparent)
            .border(1.5.dp, if (selected) Color(0xFF2A2A2D) else Color(0xFFE0E0E0), shape)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
fun EqScreen() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            val curve = Dsp.curve()
            var presetMenu by remember { mutableStateOf(false) }
            var moreMenu by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Color(0xFF161618)).padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        VSlider(Dsp.preamp, -12f, 12f, { Dsp.preamp = it; Dsp.bump() }, { Dsp.persist() }, Modifier.weight(1f).fillMaxWidth())
                        Text("Preamp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        Text(fmt1(Dsp.preamp), color = Color(0xFFB0B0B0), fontSize = 10.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).fillMaxHeight()) {
                        Dsp.freqs.forEachIndexed { i, f ->
                            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                VSlider(
                                    Dsp.gains[i], -12f, 12f,
                                    { Dsp.gains[i] = it; Dsp.preset = "Personalizado"; Dsp.bump() },
                                    { Dsp.persist() }, Modifier.weight(1f).fillMaxWidth()
                                )
                                Text(if (f >= 1000) "${f / 1000}K" else "$f", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                                Text(fmt1(Dsp.gains[i]), color = Color(0xFFB0B0B0), fontSize = 9.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF1A1A1C))) {
                    val midY = size.height / 2
                    drawLine(Color(0xFF3A3A3E), Offset(0f, midY), Offset(size.width, midY), 1.dp.toPx())
                    val path = Path()
                    curve.forEachIndexed { i, db ->
                        val x = size.width * i / (curve.size - 1)
                        val y = midY - (db.coerceIn(-12f, 12f) / 12f) * (midY - 6.dp.toPx())
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path, Brush.horizontalGradient(listOf(Lime, Green)),
                        alpha = if (Dsp.enabled) 1f else 0.35f,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(
                        "EQ 10 BANDAS", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1C1C1E)).padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(104.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Ecu", Dsp.enabled) { Dsp.enabled = !Dsp.enabled; Dsp.commit() }
                        Pill("Tono", Dsp.toneOn) { Dsp.toneOn = !Dsp.toneOn; Dsp.commit() }
                        Pill("Límite", Dsp.limiter) { Dsp.limiter = !Dsp.limiter; Dsp.commit() }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                Pill(Dsp.preset, false, Modifier.fillMaxWidth()) { presetMenu = true }
                                DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                    Dsp.presets.keys.forEach { n ->
                                        DropdownMenuItem(text = { Text(n) }, onClick = { Dsp.applyPreset(n); presetMenu = false })
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Box {
                                IconButton(
                                    onClick = { moreMenu = true },
                                    modifier = Modifier.size(48.dp).border(1.5.dp, Color(0xFFE0E0E0), CircleShape)
                                ) { Icon(Icons.Rounded.MoreVert, "Más") }
                                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                    DropdownMenuItem(text = { Text("Restablecer todo") }, onClick = { Dsp.reset(); moreMenu = false })
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Knob("Graves", Dsp.bass, Dsp.toneOn, { Dsp.bass = it; Dsp.toneOn = true; Dsp.bump() }, { Dsp.persist() })
                            Knob("Agudos", Dsp.treble, Dsp.toneOn, { Dsp.treble = it; Dsp.toneOn = true; Dsp.bump() }, { Dsp.persist() })
                        }
                    }
                }
            }
        }
    }
}
