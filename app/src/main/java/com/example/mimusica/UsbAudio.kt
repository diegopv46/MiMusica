package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

object UsbAudio {
    var enabled by mutableStateOf(false)
    var status by mutableStateOf("")
    private var sp: SharedPreferences? = null
    private var lastRate = 0
    private var lastCh = 0
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("usb", Context.MODE_PRIVATE)
        sp = s
        enabled = s.getBoolean("on", false)
    }

    fun setEnabled(ctx: Context, v: Boolean) {
        enabled = v
        sp?.edit()?.putBoolean("on", v)?.apply()
        if (v) {
            if (lastRate > 0) onStream(ctx, lastRate, lastCh)
            else status = "Activado: se aplicará al reproducir la próxima canción."
        } else {
            clear(ctx); status = ""
        }
    }

    private fun device(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

    private fun clear(ctx: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            device(am)?.let { am.clearPreferredMixerAttributes(attrs, it) }
        }
    }

    /** Se llama al abrir cada pista: si el DAC ofrece bit-perfect con el mismo formato, lo pide. */
    fun onStream(ctx: Context, rate: Int, channels: Int) {
        lastRate = rate; lastCh = channels
        if (!enabled) return
        if (Build.VERSION.SDK_INT < 34) { status = "Requiere Android 14 o superior."; return }
        runCatching {
            val am = ctx.getSystemService(AudioManager::class.java)
            val dev = device(am)
            if (dev == null) { status = "No hay DAC USB conectado."; return }
            val pick = am.getSupportedMixerAttributes(dev).firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.sampleRate == rate &&
                    Integer.bitCount(it.format.channelMask) == channels &&
                    it.format.encoding == AudioFormat.ENCODING_PCM_FLOAT
            }
            if (pick == null) {
                am.clearPreferredMixerAttributes(attrs, dev)
                status = "Tu DAC no ofrece modo directo en Float a $rate Hz. Suena por el mezclador normal."
                return
            }
            status = if (am.setPreferredMixerAttributes(attrs, dev, pick)) "Modo USB directo activo a $rate Hz (Float)."
            else "Android rechazó el modo directo."
        }.onFailure { status = "Error: ${it.message}" }
    }

    fun offersBitPerfect(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 34) return null
        val am = ctx.getSystemService(AudioManager::class.java)
        val dev = device(am) ?: return null
        return runCatching {
            am.getSupportedMixerAttributes(dev).any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        }.getOrDefault(false)
    }

    fun summary(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val dev = device(am) ?: return "No hay DAC USB conectado."
        if (Build.VERSION.SDK_INT < 34) return "${dev.productName}: el modo directo requiere Android 14 o superior."
        return if (offersBitPerfect(ctx) == true) "${dev.productName} ofrece modo directo (bit-perfect)."
        else "${dev.productName} no ofrece modo directo (bit-perfect). Puede depender del dispositivo o del teléfono."
    }

    private fun encName(e: Int) = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32"
        AudioFormat.ENCODING_PCM_FLOAT -> "Float"
        else -> "enc $e"
    }

    fun report(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val am = ctx.getSystemService(AudioManager::class.java)
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        sb.appendLine("Salidas detectadas: ${outs.size}")
        outs.forEach { sb.appendLine("• ${it.productName} (tipo ${it.type})") }
        val usb = device(am)
        if (usb == null) { sb.appendLine("\nNo hay DAC USB conectado."); return sb.toString() }
        sb.appendLine("\nDAC USB: ${usb.productName}")
        sb.appendLine("Frecuencias: ${usb.sampleRates.joinToString().ifEmpty { "no informadas" }}")
        sb.appendLine("Formatos: ${usb.encodings.joinToString { encName(it) }.ifEmpty { "no informados" }}")
        if (Build.VERSION.SDK_INT < 34) {
            sb.appendLine("\nLos atributos de mezcla USB requieren Android 14 o superior.")
            return sb.toString()
        }
        runCatching {
            val sup = am.getSupportedMixerAttributes(usb)
            sb.appendLine("\nAtributos de mezcla ofrecidos: ${sup.size}")
            if (sup.isEmpty()) sb.appendLine("El fabricante no los ofrece para este dispositivo.")
            sup.forEach { a ->
                val f = a.format
                val kind = if (a.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BIT_PERFECT" else "DEFAULT"
                sb.appendLine("$kind · ${f.sampleRate} Hz · ${encName(f.encoding)} · ${Integer.bitCount(f.channelMask)} canales")
            }
            am.getPreferredMixerAttributes(attrs, usb)?.let {
                sb.appendLine("\nActual: ${it.format.sampleRate} Hz · ${encName(it.format.encoding)}")
            }
        }.onFailure { sb.appendLine("Error: ${it.message}") }
        return sb.toString()
    }
}

@Composable
fun AudioInfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var key by remember { mutableIntStateOf(0) }
    val canBP = remember(key) { runCatching { UsbAudio.offersBitPerfect(ctx) }.getOrNull() }
    val summary = remember(key) { runCatching { UsbAudio.summary(ctx) }.getOrDefault("") }
    val report = remember(key) { runCatching { UsbAudio.report(ctx) }.getOrElse { "Error: ${it.message}" } }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
            Text("Audio y USB", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Motor de audio: coma flotante de 32 bits. Todo se convierte a float antes del ecualizador.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Modo USB directo (bit-perfect)", Modifier.weight(1f))
            Switch(checked = UsbAudio.enabled, enabled = canBP == true || UsbAudio.enabled, onCheckedChange = { UsbAudio.setEnabled(ctx, it); key++ })
        }
        Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        if (UsbAudio.status.isNotEmpty()) Text(UsbAudio.status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { key++ }) { Text("Actualizar") }
            OutlinedButton(onClick = {
                clip.setText(AnnotatedString("REGISTRO\n" + SessionLog.lines.joinToString("\n") + "\n\nUSB\n" + report))
            }) { Text("Copiar") }
            OutlinedButton(onClick = { SessionLog.clear() }) { Text("Borrar") }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "REGISTRO DE LA SESIÓN\n" + SessionLog.lines.joinToString("\n").ifEmpty { "(vacío)" } + "\n\nDIAGNÓSTICO USB\n" + report,
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
