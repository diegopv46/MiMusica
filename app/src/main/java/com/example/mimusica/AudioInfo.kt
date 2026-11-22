package com.example.mimusica

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun Head(t: String) {
    Text(t, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

/** Resume la cadena completa de la canción actual y da una estimación orientativa de fidelidad. */
@Composable
fun AudioInfoDialog(info: String, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val rates = remember { runCatching { UsbAudio.sampleRates(ctx) }.getOrDefault(IntArray(0)) }
    val rgValue = if (Dsp.rgMode == 2 && !Dsp.rgAlbum.isNaN()) Dsp.rgAlbum else Dsp.rgTrack
    val rgText = when {
        Dsp.rgMode == 0 -> "desactivada"
        rgValue.isNaN() -> "sin etiqueta en esta pista"
        else -> "%.1f dB".format(rgValue)
    }
    val auto = if (Dsp.autoPre) Dsp.peakDb() else 0f
    val eq = when {
        !Dsp.enabled -> "apagado"
        Dsp.paramMode -> "paramétrico, ${Dsp.pbands.count { it.on }} bandas"
        else -> "gráfico de 10 bandas"
    }
    val tone = if (Dsp.toneOn) "graves ${Dsp.bass.roundToInt()} % a ${Dsp.bassFreq.roundToInt()} Hz · agudos ${Dsp.treble.roundToInt()} % a ${Dsp.trebleFreq.roundToInt()} Hz" else "apagado"
    val active = listOf(Dsp.enabled, Dsp.toneOn, Dsp.outGain > 0.05f, Dsp.rgMode != 0 && !rgValue.isNaN(), Dsp.fadeMs > 0, Dsp.limiter && Dsp.enabled).count { it }

    var score = 100
    val notes = ArrayList<String>()
    val lossy = listOf("MP3", "AAC", "OGG", "OPUS").any { info.uppercase().contains(it) }
    if (lossy) { score -= 25; notes += "El archivo es con pérdida (−25)." }
    val src = Dsp.srcRate
    if (rates.isNotEmpty() && src > 0 && src !in rates) {
        val sameFamily = (src % 44100 == 0) == rates.any { it % 44100 == 0 }
        if (sameFamily) { score -= 10; notes += "El DAC no acepta $src Hz y Android remuestrea (−10)." }
        else { score -= 15; notes += "El DAC no acepta $src Hz, de otra familia (44,1 / 48 kHz); Android remuestrea (−15)." }
    }
    if (rates.isNotEmpty() && !UsbAudio.directActive()) { score -= 5; notes += "La salida USB no está en modo directo bit-perfect (−5)." }
    if (notes.isEmpty()) notes += "Sin penalizaciones detectadas."

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Cerrar") } },
        title = { Text("Información de audio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Head("Fuente")
                Text("Archivo: ${info.ifEmpty { "sin datos" }}")
                Text("Entrada al motor: ${Dsp.srcDesc.ifEmpty { "sin datos" }}")
                Head("Procesamiento ($active activos)")
                Text("Motor: float de 32 bits, proceso interno de 64 bits")
                Text("Ecualizador: $eq")
                Text("Tono: $tone")
                Text("Preamp: %.1f dB (automático %.1f dB)".format(Dsp.preamp, -auto))
                Text("ReplayGain: $rgText")
                Text("Ganancia de salida: +%.1f dB".format(Dsp.outGain))
                Text("Fundido: " + if (Dsp.fadeMs > 0) "%.1f s".format(Dsp.fadeMs / 1000f) else "apagado")
                Text("Limitador: " + if (Dsp.limiter) "encendido" else "apagado")
                Head("Salida")
                Text("Dispositivo: ${DeviceProfiles.name.ifEmpty { "sin datos" }}")
                if (rates.isNotEmpty()) Text("Frecuencias del DAC: ${rates.joinToString(", ")} Hz")
                if (UsbAudio.status.isNotEmpty()) Text(UsbAudio.status)
                if (UsbAudio.directActive() && active > 0) {
                    Text(
                        "Nota: con el procesamiento activo la señal ya no es idéntica al archivo, aunque sigue evitando el remuestreo del sistema.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Head("Fidelidad (estimación): $score / 100")
                notes.forEach { Text("• $it") }
                Text("Es una estimación orientativa, no una medición.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    )
}
