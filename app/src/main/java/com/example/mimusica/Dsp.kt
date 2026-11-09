package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.*

object Dsp {
    val freqs = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val presets: Map<String, FloatArray> = linkedMapOf(
        "Plano" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Rock" to floatArrayOf(5.8f, 3.2f, 1.3f, -3f, -2.3f, 2.2f, 3.6f, 4.5f, 4.8f, 4.8f),
        "Pop" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1.5f, -1.5f, -1.5f),
        "Jazz" to floatArrayOf(3f, 2f, 1f, 2f, -1.5f, -1.5f, 0f, 1f, 2f, 3f),
        "Clásica" to floatArrayOf(4f, 3f, 2f, 1.5f, -1f, -1f, 0f, 2f, 3f, 3.5f),
        "Graves+" to floatArrayOf(6f, 5f, 4f, 2.5f, 1f, 0f, 0f, 0f, 0f, 0f),
        "Agudos+" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2.5f, 4f, 5f, 6f),
        "Vocal" to floatArrayOf(-2f, -2f, -1f, 1f, 3.5f, 3.5f, 3f, 1f, 0f, -1f),
        "Electrónica" to floatArrayOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 3f, 4.5f, 5f),
        "Hip-Hop" to floatArrayOf(5f, 4.5f, 3f, 1.5f, -0.5f, -0.5f, 1.5f, -0.5f, 2f, 3f)
    )

    var enabled by mutableStateOf(false)
    var preamp by mutableFloatStateOf(0f)
    val gains = mutableStateListOf<Float>().apply { repeat(10) { add(0f) } }
    var toneOn by mutableStateOf(false)
    var bass by mutableFloatStateOf(0f)
    var treble by mutableFloatStateOf(0f)
    var limiter by mutableStateOf(true)
    var preset by mutableStateOf("Plano")
    @Volatile var version = 0
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("dsp", Context.MODE_PRIVATE)
        sp = s
        enabled = s.getBoolean("on", false); preamp = s.getFloat("pre", 0f)
        toneOn = s.getBoolean("tone", false); bass = s.getFloat("bass", 0f); treble = s.getFloat("treb", 0f)
        limiter = s.getBoolean("lim", true); preset = s.getString("preset", "Plano") ?: "Plano"
        (s.getString("g", "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }
            .takeIf { it.size == 10 }?.forEachIndexed { i, v -> gains[i] = v }
        version++
    }

    fun persist() {
        sp?.edit()?.putBoolean("on", enabled)?.putFloat("pre", preamp)?.putBoolean("tone", toneOn)
            ?.putFloat("bass", bass)?.putFloat("treb", treble)?.putBoolean("lim", limiter)
            ?.putString("preset", preset)?.putString("g", gains.joinToString(","))?.apply()
    }

    fun commit() { version++; persist() }
    fun bump() { enabled = true; version++ }

    fun applyPreset(name: String) {
        presets[name]?.forEachIndexed { i, v -> gains[i] = v }
        preset = name; enabled = true; commit()
    }

    fun reset() {
        applyPreset("Plano"); preamp = 0f; bass = 0f; treble = 0f; commit()
    }

    class Coef(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    private val identity = Coef(1.0, 0.0, 0.0, 0.0, 0.0)

    private fun peaking(f: Double, db: Double, q: Double, fs: Double): Coef {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w)
        val a0 = 1 + al / a
        return Coef((1 + al * a) / a0, -2 * c / a0, (1 - al * a) / a0, -2 * c / a0, (1 - al / a) / a0)
    }

    private fun lowShelf(f: Double, db: Double, fs: Double): Coef {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w)
        val sq = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        val a0 = (a + 1) + (a - 1) * c + sq
        return Coef(
            a * ((a + 1) - (a - 1) * c + sq) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0,
            a * ((a + 1) - (a - 1) * c - sq) / a0, -2 * ((a - 1) + (a + 1) * c) / a0,
            ((a + 1) + (a - 1) * c - sq) / a0
        )
    }

    private fun highShelf(f: Double, db: Double, fs: Double): Coef {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w)
        val sq = 2 * sqrt(a) * (sin(w) / 2 * sqrt(2.0))
        val a0 = (a + 1) - (a - 1) * c + sq
        return Coef(
            a * ((a + 1) + (a - 1) * c + sq) / a0, -2 * a * ((a - 1) + (a + 1) * c) / a0,
            a * ((a + 1) + (a - 1) * c - sq) / a0, 2 * ((a - 1) - (a + 1) * c) / a0,
            ((a + 1) - (a - 1) * c - sq) / a0
        )
    }

    /** Siempre 12 filtros (10 bandas + graves + agudos) para que el estado no se desordene al cambiar valores. */
    fun chain(fs: Double): List<Coef> {
        val list = ArrayList<Coef>(12)
        for (i in 0 until 10) {
            val f = freqs[i].toDouble(); val g = gains[i].toDouble()
            list += if (f < fs * 0.45 && abs(g) > 0.01) peaking(f, g, 1.4, fs) else identity
        }
        list += if (toneOn && bass > 0.5f) lowShelf(120.0, bass / 10.0, fs) else identity
        list += if (toneOn && treble > 0.5f) highShelf(8000.0, treble / 10.0, fs) else identity
        return list
    }

    private fun magDb(c: Coef, w: Double): Double {
        val cw = cos(w); val c2 = cos(2 * w)
        val num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 + 2 * (c.b0 * c.b1 + c.b1 * c.b2) * cw + 2 * c.b0 * c.b2 * c2
        val den = 1 + c.a1 * c.a1 + c.a2 * c.a2 + 2 * (c.a1 + c.a1 * c.a2) * cw + 2 * c.a2 * c2
        return 10 * log10(max(num / den, 1e-12))
    }

    fun curve(points: Int = 96): List<Float> {
        val ch = chain(44100.0)
        return List(points) { i ->
            val f = 20.0 * 1000.0.pow(i / (points - 1.0))
            val w = 2 * PI * f / 44100.0
            ch.sumOf { magDb(it, w) }.toFloat()
        }
    }
}

class DspProcessor : BaseAudioProcessor() {
    private var coefs: List<Dsp.Coef> = emptyList()
    private var st = DoubleArray(0)
    private var ver = -1
    private var gain = 1.0
    private var on = false
    private var lim = true
    private var channels = 0
    private var rate = 44100.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate.toDouble()
        st = DoubleArray(channels * 12 * 4)
        ver = -1
        return inputAudioFormat
    }

    override fun onFlush() {
        st = DoubleArray(channels * 12 * 4)
        ver = -1
    }

    private fun refresh() {
        ver = Dsp.version
        on = Dsp.enabled
        lim = Dsp.limiter
        gain = 10.0.pow(Dsp.preamp / 20.0)
        coefs = Dsp.chain(rate)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (ver != Dsp.version) refresh()
        if (!on || coefs.size != 12) {
            out.put(inputBuffer); out.flip(); return
        }
        val nf = 12
        val frameBytes = 2 * channels
        while (inputBuffer.remaining() >= frameBytes) {
            for (c in 0 until channels) {
                var x = inputBuffer.getShort() / 32768.0 * gain
                val base = c * nf * 4
                for (f in 0 until nf) {
                    val k = coefs[f]; val i = base + f * 4
                    val y = k.b0 * x + k.b1 * st[i] + k.b2 * st[i + 1] - k.a1 * st[i + 2] - k.a2 * st[i + 3]
                    st[i + 1] = st[i]; st[i] = x; st[i + 3] = st[i + 2]; st[i + 2] = y
                    x = y
                }
                if (lim) {
                    val a = abs(x)
                    if (a > 0.85) x = sign(x) * (0.85 + 0.15 * tanh((a - 0.85) / 0.15))
                }
                out.putShort((x.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            }
        }
        out.flip()
    }
}
