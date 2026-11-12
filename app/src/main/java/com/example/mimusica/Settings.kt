package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

object AppPrefs {
    var theme by mutableIntStateOf(0) // 0 sistema, 1 claro, 2 oscuro
    var stopOnClose = false
        private set
    var lyricsOnline by mutableStateOf(true)
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        sp = s
        theme = s.getInt("theme", 0)
        stopOnClose = s.getBoolean("stop", false)
        lyricsOnline = s.getBoolean("lyr", true)
    }

    fun chooseLyrics(v: Boolean) {
        lyricsOnline = v
        sp?.edit()?.putBoolean("lyr", v)?.apply()
    }

    fun chooseStop(v: Boolean) {
        stopOnClose = v
        sp?.edit()?.putBoolean("stop", v)?.apply()
    }

    fun chooseTheme(v: Int) {
        theme = v
        sp?.edit()?.putInt("theme", v)?.apply()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val usbInfo = remember { runCatching { UsbAudio.summary(ctx) }.getOrDefault("") }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
            Text("Ajustes", style = MaterialTheme.typography.titleLarge)
        }

        SectionTitle("Apariencia")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Sistema", "Claro", "Oscuro").forEachIndexed { i, t ->
                FilterChip(selected = AppPrefs.theme == i, onClick = { AppPrefs.chooseTheme(i) }, label = { Text(t) })
            }
        }

        SectionTitle("Audio")
        Text("Ganancia de salida: +${"%.1f".format(Dsp.outGain)} dB", Modifier.padding(horizontal = 16.dp))
        Slider(
            value = Dsp.outGain,
            onValueChange = { Dsp.outGain = it; Dsp.version++ },
            onValueChangeFinished = { Dsp.persist() },
            valueRange = 0f..12f,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "Sube el volumen cuando el del sistema ya está al máximo. Se aplica antes del limitador: si lo subes mucho, comprime en vez de distorsionar (mantén “Límite” activo en el EQ).",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("Normalización de volumen (ReplayGain)", Modifier.padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Desactivada", "Por pista", "Por álbum").forEachIndexed { i, t ->
                FilterChip(
                    selected = Dsp.rgMode == i,
                    onClick = { Dsp.rgMode = i; Dsp.version++; Dsp.persist() },
                    label = { Text(t) }
                )
            }
        }
        Text(
            "Usa las etiquetas ReplayGain de tus archivos. Las canciones sin etiqueta no cambian.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Fundido entre canciones: " + if (Dsp.fadeMs == 0) "desactivado" else "%.1f s".format(Dsp.fadeMs / 1000f),
            Modifier.padding(horizontal = 16.dp)
        )
        Slider(
            value = Dsp.fadeMs / 1000f,
            onValueChange = { Dsp.fadeMs = (it * 2).roundToInt() * 500 },
            onValueChangeFinished = { Dsp.persist() },
            valueRange = 0f..8f, steps = 15,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "Baja el volumen al final de cada canción y lo sube al inicio de la siguiente. No es una mezcla solapada de las dos.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)
        )
        ListItem(
            headlineContent = { Text("Audio y USB") },
            supportingContent = { Text(usbInfo.ifEmpty { "Diagnóstico del DAC y modo directo" }) },
            modifier = Modifier.clickable { onOpen("audio") }
        )

        SectionTitle("Letras")
        ListItem(
            headlineContent = { Text("Buscar letras en internet") },
            supportingContent = { Text("Usa lrclib.net. Se envían el título, artista, álbum y duración de la canción. Las letras encontradas se guardan en el teléfono.") },
            trailingContent = { Switch(checked = AppPrefs.lyricsOnline, onCheckedChange = { AppPrefs.chooseLyrics(it) }) }
        )
        OutlinedButton(onClick = { LyricsRepo.clearCache(ctx) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Borrar letras guardadas") }

        SectionTitle("Reproducción")
        var stopOnClose by remember { mutableStateOf(AppPrefs.stopOnClose) }
        ListItem(
            headlineContent = { Text("Detener al cerrar la app") },
            supportingContent = { Text("Si está desactivado, la música sigue sonando al quitar la app de recientes. Usa la ✕ de la tarjeta para cerrarla.") },
            trailingContent = { Switch(checked = stopOnClose, onCheckedChange = { stopOnClose = it; AppPrefs.chooseStop(it) }) }
        )

        SectionTitle("Biblioteca")
        ListItem(
            headlineContent = { Text("Carpetas de música") },
            supportingContent = { Text("Elige qué carpetas se muestran") },
            modifier = Modifier.clickable { onOpen("folders") }
        )

        SectionTitle("Acerca de")
        ListItem(headlineContent = { Text("Mi Música") }, supportingContent = { Text("Entrega 7 · motor de audio float propio") })
    }
}
