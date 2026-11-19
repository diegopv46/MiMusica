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
    private var lastFade = 1f

    // Limitador de picos: anticipación de ~2 ms, enlazado entre canales, liberación de ~80 ms.
    private val limThr = 0.989f
    private var limCh = 0
    private var limL = 0
    private var dl = FloatArray(0)
    private var gh = FloatArray(0)
    private var bh = FloatArray(0)
    private var bSum = 0.0
    private var gIdx = 0
    private var bIdx = 0
    private var dlIdx = 0
    private var gCur = 1f
    private var relCoef = 0.001f
    private var minGainBuf = 1f

    private fun ensureLimiter() {
        val l = max(16, (0.002 * rate).roundToInt())
        if (limCh == channels && limL == l && dl.isNotEmpty()) return
        limCh = channels; limL = l
        dl = FloatArray(l * channels)
        gh = FloatArray(l) { 1f }
        bh = FloatArray(l) { 1f }
        bSum = l.toDouble(); gIdx = 0; bIdx = 0; dlIdx = 0; gCur = 1f
        relCoef = (1.0 - exp(-1.0 / (0.08 * rate))).toFloat()
    }

    private fun resetLimiter() {
        if (dl.isEmpty()) return
        dl.fill(0f); gh.fill(1f); bh.fill(1f)
        bSum = limL.toDouble(); gIdx = 0; bIdx = 0; dlIdx = 0; gCur = 1f
    }

    /** Pasa un cuadro por el limitador y lo escribe con un retardo de (L-1) cuadros. */
    private fun emitFrame(o: ByteBuffer, fr: FloatArray, off: Int, limOn: Boolean) {
        val ch = channels
        var pk = 0f
        for (c in 0 until ch) { val a = abs(fr[off + c]); if (a > pk) pk = a }
        val req = if (limOn && pk > limThr) limThr / pk else 1f
        gh[gIdx] = req
        gIdx = (gIdx + 1) % limL
        var mn = 1f
        for (i in 0 until limL) if (gh[i] < mn) mn = gh[i]
        bSum += (mn - bh[bIdx]).toDouble()
        bh[bIdx] = mn
        bIdx = (bIdx + 1) % limL
        val gs = (bSum / limL).toFloat()
        gCur = if (gs < gCur) gs else gCur + (gs - gCur) * relCoef
        val p = dlIdx
        for (c in 0 until ch) dl[p * ch + c] = fr[off + c]
        val q = (p + 1) % limL
        for (c in 0 until ch) {
            val y = dl[q * ch + c] * gCur
            o.putFloat(if (y > 1f) 1f else if (y < -1f) -1f else y)
        }
        dlIdx = q
        if (gCur < minGainBuf) minGainBuf = gCur
    }

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
        Dsp.srcRate = inputFormat.sampleRate
        Dsp.srcDesc = when (e) {
            C.ENCODING_PCM_16BIT -> "PCM 16 bits"
            C.ENCODING_PCM_24BIT -> "PCM 24 bits"
            C.ENCODING_PCM_32BIT -> "PCM 32 bits"
            else -> "PCM float"
        } + " · ${inputFormat.sampleRate} Hz · ${inputFormat.channelCount} canales"
        st = DoubleArray(channels * Dsp.MAX_FILTERS * 4)
        tmp = FloatArray(channels)
        ensureLimiter()
        ver = -1
        lastFade = Dsp.fadeGain()
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
        lastFade = Dsp.fadeGain()
        resetLimiter()
        super.flush()
    }

    override fun reset() {
        pending = null; ringCount = 0; ringHead = 0
        resetLimiter()
        super.reset()
    }

    private fun refresh() {
        ver = Dsp.version
        on = Dsp.enabled
        lim = Dsp.limiter
        val eqPart = if (on) Dsp.preamp - (if (Dsp.autoPre) Dsp.peakDb() else 0f) else 0f
        val useAlbum = Dsp.rgMode == 2 && !Dsp.rgAlbum.isNaN()
        var rg = if (Dsp.rgMode == 0) 0f else (if (useAlbum) Dsp.rgAlbum else Dsp.rgTrack)
        val pk = if (useAlbum) Dsp.peakAlbum else Dsp.peakTrack
        if (rg.isNaN()) rg = 0f
        else if (rg > 0f && !pk.isNaN() && pk > 0f) rg = min(rg, -20f * log10(pk))
        rg = rg.coerceIn(-24f, 12f)
        gain = 10.0.pow((eqPart + Dsp.outGain + rg) / 20.0)
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
        val nf = coefs.size
        val doDsp = on && nf > 0
        val modify = doDsp || abs(gain - 1.0) > 1e-6
        val fg0 = lastFade
        val fg1 = Dsp.fadeGain()
        lastFade = fg1
        val fgStep = if (frames > 0) (fg1 - fg0) / frames else 0f
        val fading = fg0 < 0.999f || fg1 < 0.999f
        val limOn = lim && modify
        minGainBuf = 1f
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
                if (modify) {
                    x *= gain
                    val base = c * Dsp.MAX_FILTERS * 4
                    for (k in 0 until (if (doDsp) nf else 0)) {
                        val q = coefs[k]
                        val i = base + k * 4
                        val y = q.b0 * x + q.b1 * st[i] + q.b2 * st[i + 1] - q.a1 * st[i + 2] - q.a2 * st[i + 3]
                        st[i + 1] = st[i]; st[i] = x; st[i + 3] = st[i + 2]; st[i + 2] = y
                        x = y
                    }
                }
                if (fading) x *= (fg0 + fgStep * f)
                sumSq += x * x
                tmp[c] = x.toFloat()
            }
            Spectrum.push(if (ch > 1) (tmp[0] + tmp[1]) * 0.5f else tmp[0])
            if (skip > 0) { skip--; continue }
            if (padFrames > 0) {
                if (ringCount == padFrames) {
                    val b = ringHead * ch
                    emitFrame(o, ring, b, limOn)
                    for (c in 0 until ch) ring[b + c] = tmp[c]
                    ringHead = (ringHead + 1) % padFrames
                } else {
                    val b = ((ringHead + ringCount) % padFrames) * ch
                    for (c in 0 until ch) ring[b + c] = tmp[c]
                    ringCount++
                }
            } else {
                emitFrame(o, tmp, 0, limOn)
            }
        }
        Spectrum.rate = rate.toInt()
        Spectrum.analyze()
        if (minGainBuf < 0.995f) Dsp.noteLimit()
        if (frames > 0) Dsp.level = min(1.0, sqrt(sumSq / (frames * ch)) * 3.0).toFloat()
        inp.position(idx)
        o.flip()
        return o
    }
}
