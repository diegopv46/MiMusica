package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Perfil de ecualizador por salida de audio: al conectar unos audífonos se carga su ajuste guardado. */
object DeviceProfiles {
    var key by mutableStateOf("")
    var name by mutableStateOf("")
    var profiles by mutableStateOf<Map<String, Dsp.Custom>>(emptyMap())
    private var sp: SharedPreferences? = null
    private var started = false

    private fun rank(t: Int): Int = when (t) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 5
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> 4
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val s = app.getSharedPreferences("devices", Context.MODE_PRIVATE)
        sp = s
        runCatching {
            val o = org.json.JSONObject(s.getString("profiles", "{}") ?: "{}")
            val m = LinkedHashMap<String, Dsp.Custom>()
            for (k in o.keys()) m[k] = Dsp.fromJson(o.getJSONObject(k))
            profiles = m
        }
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
        val newKey = if (r >= 3) "$label:$product" else "Altavoz"
        val newName = if (r >= 3) "$label · $product" else "Altavoz del teléfono"
        val changed = newKey != key
        key = newKey
        name = newName
        if (changed) profiles[newKey]?.let { Dsp.applySnapshot(it) }
    }

    private fun save() {
        val o = org.json.JSONObject()
        profiles.forEach { (k, c) -> o.put(k, Dsp.toJson(c)) }
        sp?.edit()?.putString("profiles", o.toString())?.apply()
    }

    fun bindCurrent() {
        if (key.isEmpty()) return
        profiles = profiles + (key to Dsp.snapshot())
        save()
    }

    fun unbindCurrent() {
        profiles = profiles - key
        save()
    }
}
