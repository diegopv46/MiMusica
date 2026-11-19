package com.example.mimusica

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Analizador de espectro: 32 bandas entre 40 Hz y 16 kHz, calculadas con una FFT de 1024 puntos. */
object Spectrum {
    private const val N = 1024
    const val BANDS = 32
    private val ring = FloatArray(N)
    private var wp = 0
    private var filled = 0
    private var lastNs = 0L
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    @Volatile var bands = FloatArray(BANDS)
    @Volatile var rate = 44100

    fun push(x: Float) {
        ring[wp] = x
        wp = (wp + 1) and (N - 1)
        if (filled < N) filled++
    }

    fun analyze() {
        val now = System.nanoTime()
        if (now - lastNs < 33_000_000L || filled < N) return
        lastNs = now
        for (i in 0 until N) { re[i] = ring[(wp + i) and (N - 1)] * win[i]; im[i] = 0f }
        fft()
        val out = FloatArray(BANDS)
        val nyq = rate / 2.0
        for (b in 0 until BANDS) {
            val f0 = 40.0 * (16000.0 / 40.0).pow(b / BANDS.toDouble())
            val f1 = 40.0 * (16000.0 / 40.0).pow((b + 1) / BANDS.toDouble())
            val k0 = (f0 / nyq * (N / 2)).toInt().coerceIn(1, N / 2 - 1)
            val k1 = (f1 / nyq * (N / 2)).toInt().coerceIn(k0, N / 2 - 1)
            var m = 0f
            for (k in k0..k1) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > m) m = mag
            }
            val db = 20f * log10(max(m / (N / 4f), 1e-6f))
            out[b] = ((db + 70f) / 60f).coerceIn(0f, 1f)
        }
        bands = out
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = i + k + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

@Composable
fun SpectrumPanel(playing: Boolean, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    var frame by remember { mutableLongStateOf(0L) }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    val peaks = remember { FloatArray(Spectrum.BANDS) }
    LaunchedEffect(playing) {
        while (true) {
            withFrameNanos { n ->
                val src = Spectrum.bands
                for (i in shown.indices) {
                    val t = if (playing) src[i] else 0f
                    shown[i] = if (t > shown[i]) t else shown[i] * 0.88f + t * 0.12f
                    peaks[i] = if (shown[i] > peaks[i]) shown[i] else max(0f, peaks[i] - 0.006f)
                }
                frame = n
            }
        }
    }
    Canvas(modifier.clip(RoundedCornerShape(28.dp)).background(bg)) {
        val tick = frame
        if (tick < 0L) return@Canvas
        val n = Spectrum.BANDS
        val pad = 20.dp.toPx()
        val w = (size.width - 2 * pad) / n
        val maxH = size.height - 2 * pad
        for (i in 0 until n) {
            val h = shown[i] * maxH
            val x = pad + i * w + w * 0.15f
            drawRoundRect(ink, Offset(x, pad + maxH - h), Size(w * 0.7f, h.coerceAtLeast(2f)), CornerRadius(w * 0.2f))
            val py = pad + maxH - peaks[i] * maxH
            drawRoundRect(ink.copy(alpha = 0.55f), Offset(x, py - 6f), Size(w * 0.7f, 3f), CornerRadius(1.5f))
        }
    }
}
