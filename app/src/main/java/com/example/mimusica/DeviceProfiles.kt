package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ajustes de ecualizador por canción, por dispositivo y por tipo de salida (cable, USB, Bluetooth, altavoz).
 * Prioridad: canción > dispositivo > tipo de salida > ajuste manual.
 */
object DeviceProfiles {
    var key by mutableStateOf("")
    var name by mutableStateOf("")
    var typeKey by mutableStateOf("")
    var active by mutableStateOf("")
    var currentSong by mutableLongStateOf(0L)
    var profiles by mutableStateOf<Map<String, Dsp.Custom>>(emptyMap())
    var typeProfiles by mutableStateOf<Map<String, Dsp.Custom>>(emptyMap())
    var songProfiles by mutableStateOf<Map<Long, Dsp.Custom>>(emptyMap())
    private var sp: SharedPreferences? = null
    private var started = false
    private var baseline: Dsp.Custom? = null
    private var applied: Dsp.Custom? = null

    private fun rank(t: Int): Int = when (t) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> 4
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun loadMap(s: SharedPreferences, k: String): Map<String, Dsp.Custom> = runCatching {
        val o = org.json.JSONObject(s.getString(k, "{}") ?: "{}")
        val m = LinkedHashMap<String, Dsp.Custom>()
        for (n in o.keys()) m[n] = Dsp.fromJson(o.getJSONObject(n))
        m as Map<String, Dsp.Custom>
    }.getOrDefault(emptyMap())

    private fun mapToJson(m: Map<String, Dsp.Custom>): String {
        val o = org.json.JSONObject()
        m.forEach { (k, c) -> o.put(k, Dsp.toJson(c)) }
        return o.toString()
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val s = app.getSharedPreferences("devices", Context.MODE_PRIVATE)
        sp = s
        profiles = loadMap(s, "profiles")
        typeProfiles = loadMap(s, "types")
        songProfiles = loadMap(s, "songs").mapKeys { it.key.toLong() }
        val am = app.getSystemService(AudioManager::class.java)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { refresh(app) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { refresh(app) }
        }, Handler(Looper.getMainLooper()))
        refresh(app)
    }

    private fun refresh(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val best = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { rank(it.type) } ?: return
        val r = rank(best.type)
        val label = when (r) { 5 -> "USB"; 4 -> "Cable"; 3 -> "Bluetooth"; else -> "" }
        val product = best.productName?.toString().orEmpty()
        key = if (r >= 3) "$label:$product" else "Altavoz"
        name = if (r >= 3) "$label · $product" else "Altavoz del teléfono"
        typeKey = if (r >= 3) label else "Altavoz"
        reapply()
    }

    fun onTrack(mediaId: String?) {
        currentSong = mediaId?.toLongOrNull() ?: 0L
        reapply()
    }

    private fun reapply() {
        val (src, c) = when {
            songProfiles[currentSong] != null -> "canción" to songProfiles[currentSong]
            profiles[key] != null -> "dispositivo" to profiles[key]
            typeProfiles[typeKey] != null -> "salida" to typeProfiles[typeKey]
            else -> "" to null
        }
        if (c != null) {
            if (c === applied && src == active) return
            if (active.isEmpty()) baseline = Dsp.snapshot()
            Dsp.applySnapshot(c)
            applied = c
            active = src
        } else if (active.isNotEmpty()) {
            baseline?.let { Dsp.applySnapshot(it) }
            baseline = null
            applied = null
            active = ""
        }
    }

    private fun mark(c: Dsp.Custom, src: String) {
        if (active.isEmpty()) baseline = c
        applied = c
        active = src
    }

    private fun save() {
        sp?.edit()
            ?.putString("profiles", mapToJson(profiles))
            ?.putString("types", mapToJson(typeProfiles))
            ?.putString("songs", mapToJson(songProfiles.mapKeys { it.key.toString() }))
            ?.apply()
    }

    fun bindSong() {
        if (currentSong == 0L) return
        val c = Dsp.snapshot()
        songProfiles = songProfiles + (currentSong to c)
        mark(c, "canción"); save()
    }

    fun bindDevice() {
        if (key.isEmpty()) return
        val c = Dsp.snapshot()
        profiles = profiles + (key to c)
        if (songProfiles[currentSong] == null) mark(c, "dispositivo")
        save()
    }

    fun bindType() {
        if (typeKey.isEmpty()) return
        val c = Dsp.snapshot()
        typeProfiles = typeProfiles + (typeKey to c)
        if (songProfiles[currentSong] == null && profiles[key] == null) mark(c, "salida")
        save()
    }

    fun unbindSong() { songProfiles = songProfiles - currentSong; applied = null; reapply(); save() }
    fun unbindDevice() { profiles = profiles - key; applied = null; reapply(); save() }
    fun unbindType() { typeProfiles = typeProfiles - typeKey; applied = null; reapply(); save() }
}
