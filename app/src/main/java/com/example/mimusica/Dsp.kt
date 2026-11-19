package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.*
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
    var bassFreq by mutableFloatStateOf(120f)
    var trebleFreq by mutableFloatStateOf(8000f)
    var toneQ by mutableFloatStateOf(0.71f)
    var limiter by mutableStateOf(true)
    var autoPre by mutableStateOf(true)
    var outGain by mutableFloatStateOf(0f)
    var rgMode by mutableIntStateOf(1) // 0 off, 1 pista, 2 álbum
    @Volatile var rgTrack = Float.NaN
    @Volatile var rgAlbum = Float.NaN
    @Volatile var peakTrack = Float.NaN
    @Volatile var peakAlbum = Float.NaN
    var preset by mutableStateOf("Plano")

    const val MAX_FILTERS = 32
    const val MAX_BANDS = 30
    const val T_PK = 0
    const val T_LS = 1
    const val T_HS = 2
    const val T_LP = 3
    const val T_HP = 4

    /** Banda libre: tipo (campana, repisa de graves/agudos, paso bajo/alto), frecuencia, ganancia y Q. */
    data class PBand(val type: Int, val freq: Float, val gain: Float, val q: Float, val on: Boolean = true)

    val pbands = mutableStateListOf<PBand>()
    var paramMode by mutableStateOf(false)

    class Custom(
        val gains: List<Float>, val preamp: Float, val bass: Float, val treble: Float, val tone: Boolean,
        val param: Boolean = false, val pbands: List<PBand> = emptyList()
    )
    var customs by mutableStateOf<Map<String, Custom>>(emptyMap())

    var fadeMs by mutableIntStateOf(0)
    @Volatile var posMs = 0L
    @Volatile var durMs = 0L
    @Volatile var hasNext = false

    /** Ganancia (0..1) del fundido: baja al final de cada pista (si hay otra después) y sube al inicio. */
    fun fadeGain(): Float {
        val f = fadeMs
        if (f <= 0 || durMs <= 0) return 1f
        val fin = (posMs.toFloat() / f).coerceIn(0f, 1f)
        val fout = if (hasNext) ((durMs - posMs).toFloat() / f).coerceIn(0f, 1f) else 1f
        return min(fin, fout)
    }
    @Volatile var version = 0
    @Volatile var level = 0f
    @Volatile var srcDesc = ""
    @Volatile var srcRate = 0
    @Volatile var latencyMs = 200L
    private val evt = LongArray(64)
    @Volatile private var evtIdx = 0

    fun noteLimit() {
        val now = SystemClock.elapsedRealtime()
        val i = evtIdx
        if (now - evt[(i + 63) % 64] >= 10) { evt[i % 64] = now; evtIdx = (i + 1) % 64 }
    }

    /** ¿Se está oyendo ahora una reducción del limitador? Se retrasa por la latencia de salida y dura al menos 150 ms. */
    fun limiterLit(): Boolean {
        val now = SystemClock.elapsedRealtime()
        for (t in evt) if (t > 0 && now - t in latencyMs..(latencyMs + 150)) return true
        return false
    }
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("dsp", Context.MODE_PRIVATE)
        sp = s
        enabled = s.getBoolean("on", false); preamp = s.getFloat("pre", 0f)
        toneOn = s.getBoolean("tone", false); bass = s.getFloat("bass", 0f); treble = s.getFloat("treb", 0f)
        limiter = s.getBoolean("lim", true); autoPre = s.getBoolean("auto", true); outGain = s.getFloat("out", 0f); rgMode = s.getInt("rgm", 1); fadeMs = s.getInt("fade", 0); bassFreq = s.getFloat("bf", 120f); trebleFreq = s.getFloat("tf", 8000f); toneQ = s.getFloat("tq", 0.71f); preset = s.getString("preset", "Plano") ?: "Plano"
        (s.getString("g", "") ?: "").split(",").mapNotNull { it.toFloatOrNull() }
            .takeIf { it.size == 10 }?.forEachIndexed { i, v -> gains[i] = v }
        paramMode = s.getBoolean("pmode", false)
        runCatching { s.getString("pb", null)?.let { pbands.clear(); pbands.addAll(bandsFromJson(org.json.JSONArray(it))) } }
        loadCustoms(s)
        version++
    }

    private fun loadCustoms(s: SharedPreferences) {
        runCatching {
            val o = org.json.JSONObject(s.getString("custom", "{}") ?: "{}")
            val m = LinkedHashMap<String, Custom>()
            for (k in o.keys()) m[k] = fromJson(o.getJSONObject(k))
            customs = m
        }
    }

    private fun persistCustoms() {
        val o = org.json.JSONObject()
        customs.forEach { (k, c) -> o.put(k, toJson(c)) }
        sp?.edit()?.putString("custom", o.toString())?.apply()
    }

    fun bandsToJson(l: List<PBand>): org.json.JSONArray {
        val a = org.json.JSONArray()
        l.forEach { b ->
            a.put(org.json.JSONObject().put("t", b.type).put("f", b.freq.toDouble()).put("g", b.gain.toDouble()).put("q", b.q.toDouble()).put("on", b.on))
        }
        return a
    }

    fun bandsFromJson(a: org.json.JSONArray): List<PBand> = List(a.length()) { i ->
        val e = a.getJSONObject(i)
        PBand(e.getInt("t"), e.getDouble("f").toFloat(), e.getDouble("g").toFloat(), e.getDouble("q").toFloat(), e.optBoolean("on", true))
    }

    fun toJson(c: Custom): org.json.JSONObject {
        val o = org.json.JSONObject()
            .put("g", org.json.JSONArray(c.gains.map { it.toDouble() }))
            .put("pre", c.preamp.toDouble()).put("bass", c.bass.toDouble())
            .put("treb", c.treble.toDouble()).put("tone", c.tone).put("param", c.param)
        if (c.param) o.put("pb", bandsToJson(c.pbands))
        return o
    }

    fun fromJson(e: org.json.JSONObject): Custom {
        val g = e.getJSONArray("g")
        val param = e.optBoolean("param", false)
        return Custom(
            List(10) { g.getDouble(it).toFloat() }, e.getDouble("pre").toFloat(),
            e.getDouble("bass").toFloat(), e.getDouble("treb").toFloat(), e.getBoolean("tone"),
            param, if (param && e.has("pb")) bandsFromJson(e.getJSONArray("pb")) else emptyList()
        )
    }

    fun snapshot(): Custom = Custom(gains.toList(), preamp, bass, treble, toneOn, paramMode, pbands.toList())

    private fun restore(c: Custom) {
        c.gains.forEachIndexed { i, v -> gains[i] = v }
        preamp = c.preamp; bass = c.bass; treble = c.treble; toneOn = c.tone
        paramMode = c.param
        if (c.param) { pbands.clear(); pbands.addAll(c.pbands) }
    }

    fun applySnapshot(c: Custom) {
        restore(c)
        preset = "Perfil del dispositivo"; enabled = true
        commit()
    }

    fun saveCustom(name: String) {
        customs = customs + (name to snapshot())
        preset = name
        persistCustoms(); persist()
    }

    fun applyCustom(name: String) {
        val c = customs[name] ?: return
        restore(c)
        preset = name; enabled = true
        commit()
    }

    fun deleteCustom(name: String) {
        customs = customs - name
        if (preset == name) preset = "Personalizado"
        persistCustoms(); persist()
    }

    // ---------- Bandas libres ----------
    fun typeName(t: Int) = when (t) { T_PK -> "PK"; T_LS -> "LS"; T_HS -> "HS"; T_LP -> "LP"; else -> "HP" }

    fun chooseParamMode(on: Boolean) {
        if (on && pbands.isEmpty()) freqs.forEachIndexed { i, f -> pbands.add(PBand(T_PK, f.toFloat(), gains[i], 1.4f)) }
        paramMode = on
        preset = "Personalizado"; enabled = true
        commit()
    }

    fun updateBand(i: Int, b: PBand) {
        if (i in pbands.indices) { pbands[i] = b; preset = "Personalizado"; bump() }
    }

    fun addBand() {
        if (pbands.size < MAX_BANDS) { pbands.add(PBand(T_PK, 1000f, 0f, 1.0f)); preset = "Personalizado"; commit() }
    }

    fun removeBand(i: Int) {
        if (i in pbands.indices) { pbands.removeAt(i); preset = "Personalizado"; commit() }
    }

    /** Importa líneas “Filter n: ON PK Fc 100 Hz Gain 3 dB Q 1” y “Preamp: -4 dB” (AutoEq / EqualizerAPO). */
    fun importApo(text: String): Int {
        val re = Regex("""(?i)Filter\s*\d*\s*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+([0-9.]+)\s*(?:Hz)?(?:\s+Gain\s+([-+]?[0-9.]+)\s*(?:dB)?)?(?:\s+Q\s+([0-9.]+))?""")
        val out = ArrayList<PBand>()
        for (line in text.lines()) {
            val m = re.find(line) ?: continue
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> T_PK
                "LSC", "LS", "LSQ" -> T_LS
                "HSC", "HS", "HSQ" -> T_HS
                "LP", "LPQ", "LPF" -> T_LP
                "HP", "HPQ", "HPF" -> T_HP
                else -> continue
            }
            val f = m.groupValues[3].toFloatOrNull() ?: continue
            val g = m.groupValues[4].toFloatOrNull() ?: 0f
            val q = m.groupValues[5].toFloatOrNull() ?: 0.707f
            out += PBand(type, f.coerceIn(10f, 24000f), g.coerceIn(-24f, 24f), q.coerceIn(0.1f, 12f), m.groupValues[1].uppercase() == "ON")
            if (out.size >= MAX_BANDS) break
        }
        if (out.isEmpty()) return 0
        val pre = Regex("""(?i)Preamp\s*:\s*([-+]?[0-9.]+)""").find(text)?.groupValues?.get(1)?.toFloatOrNull()
        pbands.clear(); pbands.addAll(out)
        paramMode = true
        if (pre != null) { preamp = pre.coerceIn(-12f, 12f); autoPre = false }
        preset = "Importado"; enabled = true
        commit()
        return out.size
    }

    fun exportApo(): String {
        val us = java.util.Locale.US
        val sb = StringBuilder()
        sb.appendLine("Preamp: ${"%.1f".format(us, preamp - (if (autoPre) peakDb() else 0f))} dB")
        if (paramMode) {
            pbands.forEachIndexed { i, b ->
                val tn = when (b.type) { T_PK -> "PK"; T_LS -> "LSC"; T_HS -> "HSC"; T_LP -> "LPQ"; else -> "HPQ" }
                val g = if (b.type <= T_HS) " Gain ${"%.1f".format(us, b.gain)} dB" else ""
                sb.appendLine("Filter ${i + 1}: ${if (b.on) "ON" else "OFF"} $tn Fc ${b.freq.roundToInt()} Hz$g Q ${"%.2f".format(us, b.q)}")
            }
        } else {
            freqs.forEachIndexed { i, f ->
                sb.appendLine("Filter ${i + 1}: ON PK Fc $f Hz Gain ${"%.1f".format(us, gains[i])} dB Q 1.40")
            }
        }
        return sb.toString()
    }

    fun persist() {
        sp?.edit()?.putBoolean("on", enabled)?.putFloat("pre", preamp)?.putBoolean("tone", toneOn)
            ?.putFloat("bass", bass)?.putFloat("treb", treble)?.putBoolean("lim", limiter)
            ?.putBoolean("auto", autoPre)?.putFloat("out", outGain)?.putInt("rgm", rgMode)?.putInt("fade", fadeMs)?.putFloat("bf", bassFreq)?.putFloat("tf", trebleFreq)?.putFloat("tq", toneQ)?.putBoolean("pmode", paramMode)?.putString("pb", bandsToJson(pbands.toList()).toString())?.putString("preset", preset)?.putString("g", gains.joinToString(","))?.apply()
    }

    fun commit() { version++; persist() }
    fun bump() { enabled = true; version++ }

    fun applyPreset(name: String) {
        presets[name]?.forEachIndexed { i, v -> gains[i] = v }
        paramMode = false
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
        val list = ArrayList<Coef>(MAX_FILTERS)
        if (!paramMode) {
            for (i in 0 until 10) {
                val f = freqs[i].toDouble(); val g = gains[i].toDouble()
                list += if (f < fs * 0.45 && abs(g) > 0.01) peaking(f, g, 1.4, fs) else identity
            }
        } else {
            for (b in pbands) list += if (b.on && b.freq < fs * 0.49) design(b, fs) else identity
        }
        list += if (toneOn && bass > 0.5f) shelfQ(bassFreq.toDouble(), bass / 10.0, toneQ.toDouble(), fs, true) else identity
        list += if (toneOn && treble > 0.5f) shelfQ(trebleFreq.toDouble(), treble / 10.0, toneQ.toDouble(), fs, false) else identity
        return list
    }

    private fun design(b: PBand, fs: Double): Coef {
        val f = b.freq.toDouble().coerceIn(10.0, fs * 0.49)
        val q = b.q.toDouble().coerceIn(0.1, 12.0)
        val g = b.gain.toDouble().coerceIn(-24.0, 24.0)
        return when (b.type) {
            T_PK -> if (abs(g) > 0.01) peaking(f, g, q, fs) else identity
            T_LS -> shelfQ(f, g, q, fs, true)
            T_HS -> shelfQ(f, g, q, fs, false)
            T_LP -> pass(f, q, fs, true)
            else -> pass(f, q, fs, false)
        }
    }

    private fun shelfQ(f: Double, db: Double, q: Double, fs: Double, low: Boolean): Coef {
        val a = 10.0.pow(db / 40); val w = 2 * PI * f / fs; val c = cos(w)
        val sq = 2 * sqrt(a) * (sin(w) / (2 * q))
        return if (low) {
            val a0 = (a + 1) + (a - 1) * c + sq
            Coef(
                a * ((a + 1) - (a - 1) * c + sq) / a0, 2 * a * ((a - 1) - (a + 1) * c) / a0,
                a * ((a + 1) - (a - 1) * c - sq) / a0, -2 * ((a - 1) + (a + 1) * c) / a0,
                ((a + 1) + (a - 1) * c - sq) / a0
            )
        } else {
            val a0 = (a + 1) - (a - 1) * c + sq
            Coef(
                a * ((a + 1) + (a - 1) * c + sq) / a0, -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - sq) / a0, 2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - sq) / a0
            )
        }
    }

    private fun pass(f: Double, q: Double, fs: Double, low: Boolean): Coef {
        val w = 2 * PI * f / fs; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        return if (low) Coef((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
        else Coef((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - al) / a0)
    }

    private fun magDb(c: Coef, w: Double): Double {
        val cw = cos(w); val c2 = cos(2 * w)
        val num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 + 2 * (c.b0 * c.b1 + c.b1 * c.b2) * cw + 2 * c.b0 * c.b2 * c2
        val den = 1 + c.a1 * c.a1 + c.a2 * c.a2 + 2 * (c.a1 + c.a1 * c.a2) * cw + 2 * c.a2 * c2
        return 10 * log10(max(num / den, 1e-12))
    }

    /** Mayor realce (dB) de la curva: el preamp automático lo resta para no saturar. */
    fun peakDb(): Float = max(0f, curve(64).maxOrNull() ?: 0f)

    fun curve(points: Int = 96): List<Float> {
        val ch = chain(44100.0)
        return List(points) { i ->
            val f = 20.0 * 1000.0.pow(i / (points - 1.0))
            val w = 2 * PI * f / 44100.0
            ch.sumOf { magDb(it, w) }.toFloat()
        }
    }
}

