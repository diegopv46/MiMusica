package com.example.mimusica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Pruebas del controlador USB propio. Cada paso es un botón aparte y solo se habilita si el anterior salió bien. */
object UsbLab {
    var connected by mutableStateOf(false)
    var claimed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var hasVol by mutableStateOf(false)
    var volMinDb by mutableFloatStateOf(0f)
    var volMaxDb by mutableFloatStateOf(0f)
    var volStartDb by mutableFloatStateOf(0f)
    var volDb by mutableFloatStateOf(0f)
    var allowMax by mutableStateOf(false)
    var log by mutableStateOf("")
    private var conn: UsbDeviceConnection? = null

    private fun add(msg: String) { log += msg + "\n" }
    private fun ok(r: String) = r.startsWith("OK")
    private fun text(r: String) = r.substringAfter(';', r)

    fun clearLog() { log = "" }

    private fun doConnect(ctx: Context): Pair<Boolean, String> {
        if (!NativeUsb.available) return false to "La biblioteca nativa no está disponible."
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return false to "Este teléfono no ofrece USB host."
        val dev = usbMgr.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        } ?: return false to "No hay ningún dispositivo USB de audio conectado."
        if (!usbMgr.hasPermission(dev)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usbMgr.requestPermission(
                dev, PendingIntent.getBroadcast(ctx, 0, Intent("com.example.mimusica.USB_PERMISSION").setPackage(ctx.packageName), flags)
            )
            return false to "Falta el permiso USB. Acéptalo y vuelve a tocar «Conectar»."
        }
        conn?.close()
        val c = usbMgr.openDevice(dev) ?: return false to "No pude abrir el dispositivo."
        val r = NativeUsb.labOpen(c.fileDescriptor, c.rawDescriptors ?: ByteArray(0))
        if (!ok(r)) { c.close(); conn = null; return false to "${dev.productName ?: "DAC"}: " + text(r) }
        conn = c
        return true to "${dev.productName ?: "DAC"} listo. " + text(r)
    }

    suspend fun connect(ctx: Context) {
        busy = true
        add("— 1. Conectar —")
        val (good, msg) = withContext(Dispatchers.IO) { doConnect(ctx) }
        connected = good
        add(msg)
        busy = false
    }

    suspend fun claim() {
        busy = true
        add("— 2. Reclamar el DAC —")
        WidgetUi.live?.pause()
        val r = withContext(Dispatchers.IO) { NativeUsb.labClaim() }
        claimed = ok(r)
        add(text(r))
        busy = false
    }

    suspend fun readVolume(ctx: Context) {
        busy = true
        add("— 3. Leer volumen —")
        (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
            add("Volumen del teléfono ahora: ${it.getStreamVolume(AudioManager.STREAM_MUSIC)} de ${it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
        }
        val r = withContext(Dispatchers.IO) { NativeUsb.labVolume() }
        if (ok(r)) {
            val p = r.split(';')
            val cur = p[1].toInt()
            val mn = p[2].toInt()
            val mx = p[3].toInt()
            volMinDb = mn / 256f
            volMaxDb = mx / 256f
            volDb = cur / 256f
            if (!hasVol) volStartDb = volDb
            hasVol = true
            add("Volumen actual %.1f dB. Rango del DAC: %.1f a %.1f dB (canal %s).".format(volDb, volMinDb, volMaxDb, p.getOrNull(5) ?: "?"))
        } else {
            add("No pude leer el volumen: " + text(r))
        }
        busy = false
    }

    suspend fun applyVolume() {
        val cap = if (allowMax) volMaxDb else volStartDb
        val target = volDb.coerceIn(volMinDb, maxOf(cap, volMinDb))
        volDb = target
        val r = withContext(Dispatchers.IO) { NativeUsb.labSetVolume((target * 256f).roundToInt()) }
        add(if (ok(r)) "Volumen fijado a %.1f dB (%s).".format(target, text(r)) else "No pude fijar el volumen: " + text(r))
    }

    suspend fun tone(urbMs: Int, nUrb: Int) {
        busy = true
        add("— 4. Tono de prueba (3 s, 48 kHz, suave; URB %d ms x %d) —".format(urbMs, nUrb))
        val r = withContext(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try { NativeUsb.labTone(48000, 3000, -36, urbMs, nUrb) }
            finally { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
        }
        add((if (ok(r)) "Terminó: " else "Falló: ") + text(r))
        busy = false
    }

    private fun recoverNow(): String {
        val sb = StringBuilder()
        if (claimed) sb.append(text(NativeUsb.labRelease()))
        runCatching { conn?.close() }
        conn = null
        return sb.toString().ifEmpty { "Conexión cerrada." }
    }

    suspend fun recover() {
        busy = true
        add("— 5. Devolver el DAC al sistema —")
        val msg = withContext(Dispatchers.IO) { recoverNow() }
        claimed = false; connected = false; hasVol = false
        add(msg)
        add("Si el DAC no vuelve a sonar, desconéctalo y vuelve a conectarlo.")
        busy = false
    }

    suspend fun reset() {
        busy = true
        add("— Reiniciar el dispositivo USB —")
        val r = withContext(Dispatchers.IO) { NativeUsb.labReset() }
        add(text(r))
        busy = false
    }

    /** Al salir de la pantalla, nunca dejamos el DAC sin devolver. */
    fun recoverBlocking() {
        if (claimed || conn != null) {
            recoverNow()
            claimed = false; connected = false; hasVol = false
        }
    }
}

@Composable
fun UsbLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var confirm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { UsbLab.recoverBlocking() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
            Text("Laboratorio USB", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "EXPERIMENTAL. El paso 2 le quita el DAC al sistema: el audio por USB se corta hasta que lo devuelves. Pausa la música antes. Si al final el DAC no vuelve a sonar, desconéctalo y vuelve a conectarlo.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = { scope.launch { UsbLab.connect(ctx) } }, enabled = !UsbLab.busy, modifier = Modifier.fillMaxWidth()) {
            Text("1. Conectar y leer el DAC")
        }
        Button(onClick = { confirm = true }, enabled = UsbLab.connected && !UsbLab.claimed && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("2. Reclamar el DAC (corta su audio)")
        }
        Button(onClick = { scope.launch { UsbLab.readVolume(ctx) } }, enabled = UsbLab.claimed && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("3. Leer el volumen del DAC")
        }
        if (UsbLab.hasVol) {
            val cap = if (UsbLab.allowMax) UsbLab.volMaxDb else UsbLab.volStartDb
            Text("Volumen del DAC: %.1f dB".format(UsbLab.volDb), modifier = Modifier.padding(top = 8.dp))
            if (cap - UsbLab.volMinDb > 0.5f) {
                Slider(
                    value = UsbLab.volDb.coerceIn(UsbLab.volMinDb, cap), valueRange = UsbLab.volMinDb..cap,
                    onValueChange = { UsbLab.volDb = it },
                    onValueChangeFinished = { scope.launch { UsbLab.applyVolume() } },
                    enabled = !UsbLab.busy
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = UsbLab.allowMax, onCheckedChange = { UsbLab.allowMax = it })
                Text("  Permitir subir hasta el máximo del DAC (cuidado con el oído)", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Por seguridad solo puedes bajar el volumen, o volver al nivel con que lo encontramos (%.1f dB).".format(UsbLab.volStartDb),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(onClick = { scope.launch { UsbLab.tone(8, 8) } }, enabled = UsbLab.claimed && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text("4a. Tono: URB de 8 ms x 8")
        }
        Button(onClick = { scope.launch { UsbLab.tone(2, 16) } }, enabled = UsbLab.claimed && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("4b. Tono: URB de 2 ms x 16")
        }
        Button(onClick = { scope.launch { UsbLab.tone(16, 8) } }, enabled = UsbLab.claimed && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("4c. Tono: URB de 16 ms x 8")
        }
        Button(onClick = { scope.launch { UsbLab.recover() } }, enabled = (UsbLab.claimed || UsbLab.connected) && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("5. Devolver el DAC al sistema")
        }
        OutlinedButton(onClick = { scope.launch { UsbLab.reset() } }, enabled = UsbLab.connected && !UsbLab.busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("Reiniciar el dispositivo USB (último recurso)")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = { clip.setText(AnnotatedString(UsbLab.log)) }) { Text("Copiar") }
            OutlinedButton(onClick = { UsbLab.clearLog() }) { Text("Borrar") }
        }
        Text(
            UsbLab.log.ifEmpty { "(sin mensajes)" },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("¿Reclamar el DAC?") },
            text = { Text("El audio de este DAC se cortará hasta que lo devuelvas con el paso 5. Se pausará la música. ¿Continuar?") },
            confirmButton = { TextButton(onClick = { confirm = false; scope.launch { UsbLab.claim() } }) { Text("Continuar") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } }
        )
    }
}
