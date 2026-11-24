package com.example.mimusica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private class UnitInfo(val subtype: Int, val id: Int, val source: Int, val termType: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun dbText(b: ByteArray): String {
        val raw = ((b[1].toInt() shl 8) or (b[0].toInt() and 0xFF)).toShort().toInt()
        return if (raw == -32768) "−∞" else "%.1f dB".format(raw / 256.0)
    }

    /** Lee, sin cambiar nada, los controles de volumen del DAC por USB y dice si Android lo permite. */
    fun probeHwVolume(ctx: Context): String {
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return "Este teléfono no ofrece USB host."
        val dev = usbMgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        } ?: return "No hay ningún dispositivo USB de audio conectado."
        val sb = StringBuilder()
        sb.appendLine("Dispositivo: ${dev.productName ?: "sin nombre"} (${"%04x".format(dev.vendorId)}:${"%04x".format(dev.productId)})")
        if (!usbMgr.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbMgr.requestPermission(
                dev, PendingIntent.getBroadcast(ctx, 0, Intent("com.example.mimusica.USB_PERMISSION").setPackage(ctx.packageName), flags)
            )
            sb.appendLine("Falta el permiso USB. Acepta el aviso de Android y vuelve a tocar el botón.")
            return sb.toString()
        }
        val conn = usbMgr.openDevice(dev) ?: return sb.append("No pude abrir el dispositivo.").toString()
        try {
            val raw = conn.rawDescriptors ?: return sb.append("No pude leer los descriptores del dispositivo.").toString()
            var acIface = -1
            var uac = 0
            var inAc = false
            val units = HashMap<Int, UnitInfo>()
            val outputs = ArrayList<UnitInfo>()
            var i = 0
            while (i + 2 < raw.size) {
                val len = u8(raw, i)
                if (len < 2 || i + len > raw.size) break
                val type = u8(raw, i + 1)
                if (type == 0x04 && len >= 9) {
                    inAc = u8(raw, i + 5) == 1 && u8(raw, i + 6) == 1
                    if (inAc && acIface < 0) acIface = u8(raw, i + 2)
                } else if (type == 0x24 && inAc && len >= 4) {
                    val st = u8(raw, i + 2)
                    val id = u8(raw, i + 3)
                    val term = if (len > 5) u8(raw, i + 4) or (u8(raw, i + 5) shl 8) else 0
                    when (st) {
                        0x01 -> if (len >= 5) uac = u8(raw, i + 3) or (u8(raw, i + 4) shl 8)
                        0x02 -> units[id] = UnitInfo(st, id, -1, term)
                        0x03 -> { val u = UnitInfo(st, id, if (len > 7) u8(raw, i + 7) else -1, term); units[id] = u; outputs += u }
                        0x06 -> units[id] = UnitInfo(st, id, if (len > 4) u8(raw, i + 4) else -1, 0)
                        else -> units[id] = UnitInfo(st, id, -1, 0)
                    }
                }
                i += len
            }
            val uac2 = uac >= 0x0200
            sb.appendLine("Clase de audio: ${if (uac == 0) "no detectada" else if (uac2) "UAC2" else "UAC1"} (bcdADC 0x${"%04x".format(uac)})")
            sb.appendLine("Interfaz de control de audio: ${if (acIface >= 0) acIface else "no encontrada"}")
            val playback = ArrayList<Int>()
            for (o in outputs) {
                sb.appendLine("Terminal de salida ${o.id}: tipo 0x${"%04x".format(o.termType)}, fuente ${o.source}")
                if ((o.termType shr 8) == 0x01) continue
                var src = o.source
                var hops = 0
                while (src > 0 && hops < 6) {
                    val u = units[src] ?: break
                    if (u.subtype == 0x06) { playback += u.id; break }
                    src = u.source; hops++
                }
            }
            val targets = playback.ifEmpty { units.values.filter { it.subtype == 0x06 }.map { it.id } }
            if (targets.isEmpty()) {
                sb.appendLine("\nNo encontré ninguna unidad de control de volumen en este dispositivo.")
                return sb.toString()
            }
            sb.appendLine("Unidades de volumen a probar: ${targets.joinToString()}" + if (playback.isEmpty()) " (no pude saber cuál es la del reproductor)" else "")
            val iface = if (acIface >= 0) acIface else 0
            var answered = false
            for (fu in targets) {
                sb.appendLine("\nUnidad $fu:")
                for (ch in 0..2) {
                    val name = if (ch == 0) "maestro" else "canal $ch"
                    val wValue = (0x02 shl 8) or ch
                    val wIndex = (fu shl 8) or iface
                    if (!uac2) {
                        val parts = listOf("actual" to 0x81, "mínimo" to 0x82, "máximo" to 0x83).map { (label, req) ->
                            val b = ByteArray(2)
                            val n = conn.controlTransfer(0xA1, req, wValue, wIndex, b, 2, 500)
                            if (n == 2) { answered = true; "$label ${dbText(b)}" } else "$label falló($n)"
                        }
                        sb.appendLine("  $name: ${parts.joinToString(" · ")}")
                    } else {
                        val cur = ByteArray(2)
                        val nc = conn.controlTransfer(0xA1, 0x01, wValue, wIndex, cur, 2, 500)
                        val rng = ByteArray(8)
                        val nr = conn.controlTransfer(0xA1, 0x02, wValue, wIndex, rng, 8, 500)
                        if (nc == 2) answered = true
                        val curTxt = if (nc == 2) "actual ${dbText(cur)}" else "actual falló($nc)"
                        val rngTxt = if (nr >= 8) {
                            answered = true
                            "mínimo ${dbText(byteArrayOf(rng[2], rng[3]))} · máximo ${dbText(byteArrayOf(rng[4], rng[5]))}"
                        } else "rango falló($nr)"
                        sb.appendLine("  $name: $curTxt · $rngTxt")
                    }
                }
            }
            sb.appendLine()
            sb.appendLine(
                if (answered) "RESULTADO: el DAC respondió. Controlar su volumen por USB parece posible sin reclamar la interfaz."
                else "RESULTADO: ninguna lectura respondió. Es lo esperable si el sistema ya usa la interfaz de audio; el control directo exigiría un controlador USB propio."
            )
        } finally {
            conn.close()
        }
        return sb.toString()
    }

    /** Abre el DAC y le pasa su descriptor de archivo al módulo nativo para que lo lea (solo lectura). */
    fun probeNative(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Biblioteca nativa: ${NativeUsb.versionOrError()}")
        if (!NativeUsb.available) return sb.toString()
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return sb.append("Este teléfono no ofrece USB host.").toString()
        val dev = usbMgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        } ?: return sb.append("No hay ningún dispositivo USB de audio conectado.").toString()
        if (!usbMgr.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbMgr.requestPermission(
                dev, PendingIntent.getBroadcast(ctx, 0, Intent("com.example.mimusica.USB_PERMISSION").setPackage(ctx.packageName), flags)
            )
            return sb.append("Falta el permiso USB. Acepta el aviso de Android y vuelve a tocar el botón.").toString()
        }
        val conn = usbMgr.openDevice(dev) ?: return sb.append("No pude abrir el dispositivo.").toString()
        try {
            sb.appendLine("Dispositivo: ${dev.productName ?: "sin nombre"}")
            sb.append(NativeUsb.probe(conn.fileDescriptor, conn.rawDescriptors ?: ByteArray(0)))
        } finally {
            conn.close()
        }
        return sb.toString()
    }

    fun sampleRates(ctx: Context): IntArray {
        val am = ctx.getSystemService(AudioManager::class.java)
        return device(am)?.sampleRates ?: IntArray(0)
    }

    fun directActive(): Boolean = status.startsWith("Modo USB directo activo")

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
    val scope = rememberCoroutineScope()
    var hwText by remember { mutableStateOf("") }
    var nativeText by remember { mutableStateOf("") }
    val canBP = remember(key) { runCatching { UsbAudio.offersBitPerfect(ctx) }.getOrNull() }
    val summary = remember(key) { runCatching { UsbAudio.summary(ctx) }.getOrDefault("") }
    val system = remember(key) { runCatching { SessionLog.systemSnapshot(ctx) }.getOrElse { "error: ${it.message}" } }
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
                clip.setText(AnnotatedString("COMPILACION " + SessionLog.BUILD + "\n\nSISTEMA\n" + system + "\nREGISTRO\n" + SessionLog.lines.joinToString("\n") + "\n\nUSB\n" + report + "\n\nVOLUMEN DEL DAC\n" + hwText + "\n\nMÓDULO USB NATIVO\n" + nativeText))
            }) { Text("Copiar") }
            OutlinedButton(onClick = { SessionLog.clear() }) { Text("Borrar") }
        }
        OutlinedButton(onClick = { SessionLog.testSeek(10_000); key++ }) { Text("Probar salto +10 s como el sistema") }
        OutlinedButton(onClick = {
            scope.launch {
                hwText = "Leyendo el DAC…"
                hwText = withContext(Dispatchers.IO) { runCatching { UsbAudio.probeHwVolume(ctx) }.getOrElse { "Error: ${it.message}" } }
            }
        }) { Text("Probar lectura de volumen del DAC") }
        OutlinedButton(onClick = {
            scope.launch {
                nativeText = "Leyendo el DAC con el módulo nativo…"
                nativeText = withContext(Dispatchers.IO) { runCatching { UsbAudio.probeNative(ctx) }.getOrElse { "Error: ${it.message}" } }
            }
        }) { Text("Probar módulo USB nativo") }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "COMPILACIÓN: " + SessionLog.BUILD + "\n\nLO QUE VE EL SISTEMA AHORA\n" + system +
                    "\nREGISTRO DE LA SESIÓN\n" + SessionLog.lines.joinToString("\n").ifEmpty { "(vacío)" } +
                    "\n\nDIAGNÓSTICO USB\n" + report +
                    (if (hwText.isNotEmpty()) "\n\nVOLUMEN DEL DAC (PRUEBA)\n" + hwText else "") +
                    (if (nativeText.isNotEmpty()) "\n\nMÓDULO USB NATIVO\n" + nativeText else ""),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
