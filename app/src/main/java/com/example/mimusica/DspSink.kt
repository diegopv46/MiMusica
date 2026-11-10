package com.example.mimusica

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Envuelve el AudioSink de ExoPlayer: convierte cualquier PCM (16/24/32 bits o float) a float,
 * aplica preamp + 12 filtros + limitador en doble precisión y entrega float al sistema.
 * También recorta el retardo/relleno del codificador (gapless), que la ruta float de ExoPlayer no hace.
 */
class DspAudioSink(private val ctx: Context, inner: AudioSink) : ForwardingAudioSink(inner) {
    private var active = false
    private var enc = C.ENCODING_INVALID
    private var channels = 0
    private var rate = 44100.0
    private var coefs: List<Dsp.Coef> = emptyList()
    private var st = DoubleArray(0)
    private var ver = -1
    private var gain = 1.0
    private var on = false
    private var lim = true
    private var scratch: ByteBuffer? = null
    private var pending: ByteBuffer? = null
    private var tmp = FloatArray(2)
    private var skip = 0
    private var padFrames = 0
    private var ring = FloatArray(0)
    private var ringHead = 0
    private var ringCount = 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val e = inputFormat.pcmEncoding
        active = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType &&
            (e == C.ENCODING_PCM_16BIT || e == C.ENCODING_PCM_24BIT || e == C.ENCODING_PCM_32BIT || e == C.ENCODING_PCM_FLOAT)
        pending = null
        if (!active) {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            return
        }
        enc = e
        channels = inputFormat.channelCount
        rate = inputFormat.sampleRate.toDouble()
        st = DoubleArray(channels * 12 * 4)
        tmp = FloatArray(channels)
        ver = -1
        skip = max(0, inputFormat.encoderDelay)
        padFrames = min(20000, max(0, inputFormat.encoderPadding))
        ring = if (padFrames > 0) FloatArray(padFrames * channels) else FloatArray(0)
        ringHead = 0; ringCount = 0
        runCatching { UsbAudio.onStream(ctx, inputFormat.sampleRate, channels) }
        val floatFormat = inputFormat.buildUpon()
            .setPcmEncoding(C.ENCODING_PCM_FLOAT).setEncoderDelay(0).setEncoderPadding(0).build()
        super.configure(floatFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        var p = pending
        if (p == null) {
            if (!buffer.hasRemaining()) return true
            p = process(buffer)
            if (!p.hasRemaining()) return true
            pending = p
        }
        val done = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
        if (done) pending = null
        return done
    }

    override fun playToEndOfStream() {
        ringCount = 0 // lo retenido es el relleno del codificador: se descarta
        super.playToEndOfStream()
    }

    override fun flush() {
        pending = null; ringCount = 0; ringHead = 0
        st.fill(0.0)
        super.flush()
    }

    override fun reset() {
        pending = null; ringCount = 0; ringHead = 0
        super.reset()
    }

    private fun refresh() {
        ver = Dsp.version
        on = Dsp.enabled
        lim = Dsp.limiter
        val auto = if (Dsp.autoPre) Dsp.peakDb() else 0f
        gain = 10.0.pow((Dsp.preamp - auto) / 20.0)
        coefs = Dsp.chain(rate)
    }

    private fun process(inp: ByteBuffer): ByteBuffer {
        inp.order(ByteOrder.LITTLE_ENDIAN)
        val bytes = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val ch = channels
        val frames = inp.remaining() / (bytes * ch)
        val need = frames * ch * 4
        val cur = scratch
        val o: ByteBuffer = if (cur != null && cur.capacity() >= need) cur
        else ByteBuffer.allocateDirect(need + 8192).order(ByteOrder.nativeOrder()).also { scratch = it }
        o.clear()
        if (ver != Dsp.version) refresh()
        val nf = 12
        val doDsp = on && coefs.size == nf
        var idx = inp.position()
        var sumSq = 0.0
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var x: Double = when (enc) {
                    C.ENCODING_PCM_16BIT -> inp.getShort(idx) / 32768.0
                    C.ENCODING_PCM_24BIT ->
                        ((inp.get(idx + 2).toInt() shl 16) or ((inp.get(idx + 1).toInt() and 0xFF) shl 8) or (inp.get(idx).toInt() and 0xFF)) / 8388608.0
                    C.ENCODING_PCM_32BIT -> inp.getInt(idx) / 2147483648.0
                    else -> inp.getFloat(idx).toDouble()
                }
                idx += bytes
                if (doDsp) {
                    x *= gain
                    val base = c * nf * 4
                    for (k in 0 until nf) {
                        val q = coefs[k]
                        val i = base + k * 4
                        val y = q.b0 * x + q.b1 * st[i] + q.b2 * st[i + 1] - q.a1 * st[i + 2] - q.a2 * st[i + 3]
                        st[i + 1] = st[i]; st[i] = x; st[i + 3] = st[i + 2]; st[i + 2] = y
                        x = y
                    }
                    if (lim) {
                        val a = abs(x)
                        if (a > 0.85) x = sign(x) * (0.85 + 0.15 * tanh((a - 0.85) / 0.15))
                    }
                    x = x.coerceIn(-1.0, 1.0)
                }
                sumSq += x * x
                tmp[c] = x.toFloat()
            }
            if (skip > 0) { skip--; continue }
            if (padFrames > 0) {
                if (ringCount == padFrames) {
                    val b = ringHead * ch
                    for (c in 0 until ch) { o.putFloat(ring[b + c]); ring[b + c] = tmp[c] }
                    ringHead = (ringHead + 1) % padFrames
                } else {
                    val b = ((ringHead + ringCount) % padFrames) * ch
                    for (c in 0 until ch) ring[b + c] = tmp[c]
                    ringCount++
                }
            } else {
                for (c in 0 until ch) o.putFloat(tmp[c])
            }
        }
        if (frames > 0) Dsp.level = min(1.0, sqrt(sumSq / (frames * ch)) * 3.0).toFloat()
        inp.position(idx)
        o.flip()
        return o
    }
}
