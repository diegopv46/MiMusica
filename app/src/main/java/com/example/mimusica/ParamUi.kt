package com.example.mimusica

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

private val Accent = Color(0xFFC6FF00)

fun freqLabel(f: Float): String = if (f >= 1000f) "%.2f kHz".format(f / 1000f) else "${f.roundToInt()} Hz"

@Composable
fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (selected) Color.White else Color(0xFF8E8E93),
        modifier = Modifier.clip(shape)
            .background(if (selected) Color(0xFF2A2A2D) else Color.Transparent)
            .border(1.dp, if (selected) Color(0xFF2A2A2D) else Color(0xFF3A3A3E), shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** Editor de bandas libres: tipo de filtro, frecuencia, ganancia y Q, con importar/exportar AutoEq. */
@Composable
fun ParamEditor(modifier: Modifier) {
    var sel by remember { mutableIntStateOf(0) }
    var importDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val bands = Dsp.pbands
    if (bands.isNotEmpty() && sel > bands.lastIndex) sel = bands.lastIndex
    val sliderColors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val txt = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val n = Dsp.importApo(txt)
            if (n > 0) { sel = 0; copied = false; importDialog = false; importMsg = "" }
            else { importText = txt; importMsg = "No encontré filtros válidos en ese archivo."; importDialog = true }
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(bands) { i, b ->
                FilterChip(
                    selected = sel == i, onClick = { sel = i },
                    label = { Text("${i + 1} · ${Dsp.typeName(b.type)} ${freqLabel(b.freq)}", maxLines = 1, fontSize = 11.sp) }
                )
            }
            item { AssistChip(onClick = { Dsp.addBand(); sel = Dsp.pbands.lastIndex }, label = { Text("＋ Banda", fontSize = 11.sp) }) }
        }
        if (bands.isEmpty()) {
            Text("Sin bandas. Toca “＋ Banda” o importa un ajuste.", color = Color(0xFFB0B0B0), modifier = Modifier.padding(vertical = 16.dp))
        } else {
            val b = bands[sel]
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Dsp.T_PK to "Campana", Dsp.T_LS to "Graves", Dsp.T_HS to "Agudos", Dsp.T_LP to "Paso bajo", Dsp.T_HP to "Paso alto").forEach { (t, n) ->
                    FilterChip(
                        selected = b.type == t,
                        onClick = { Dsp.updateBand(sel, b.copy(type = t)); Dsp.persist() },
                        label = { Text(n, fontSize = 11.sp, maxLines = 1) }
                    )
                }
            }
            Text("Frecuencia: ${freqLabel(b.freq)}", fontSize = 12.sp, color = Color(0xFFB0B0B0), modifier = Modifier.padding(top = 6.dp))
            Slider(
                value = (ln(b.freq / 20f) / ln(1200f)).coerceIn(0f, 1f),
                onValueChange = { v ->
                    val f = 20f * 1200f.pow(v)
                    Dsp.updateBand(sel, b.copy(freq = if (f < 100f) round(f * 10f) / 10f else round(f)))
                },
                onValueChangeFinished = { Dsp.persist() }, colors = sliderColors
            )
            if (b.type <= Dsp.T_HS) {
                Text("Ganancia: ${"%.1f".format(b.gain)} dB", fontSize = 12.sp, color = Color(0xFFB0B0B0))
                Slider(
                    value = b.gain, valueRange = -15f..15f,
                    onValueChange = { Dsp.updateBand(sel, b.copy(gain = (it * 10f).roundToInt() / 10f)) },
                    onValueChangeFinished = { Dsp.persist() }, colors = sliderColors
                )
            }
            Text("Q: ${"%.2f".format(b.q)}", fontSize = 12.sp, color = Color(0xFFB0B0B0))
            Slider(
                value = (ln(b.q / 0.1f) / ln(120f)).coerceIn(0f, 1f),
                onValueChange = { v -> Dsp.updateBand(sel, b.copy(q = round(0.1f * 120f.pow(v) * 100f) / 100f)) },
                onValueChangeFinished = { Dsp.persist() }, colors = sliderColors
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = b.on, onCheckedChange = { Dsp.updateBand(sel, b.copy(on = it)); Dsp.persist() })
                Text("  Activa", Modifier.weight(1f))
                TextButton(onClick = { Dsp.removeBand(sel) }) { Text("Eliminar") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = { importMsg = ""; importDialog = true }) { Text("Importar") }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(Dsp.exportApo())); copied = true }) { Text("Exportar") }
        }
        if (copied) Text("Copiado al portapapeles (formato EqualizerAPO / AutoEq).", fontSize = 11.sp, color = Color(0xFFB0B0B0))
    }

    if (importDialog) {
        AlertDialog(
            onDismissRequest = { importDialog = false },
            title = { Text("Importar AutoEq / EqualizerAPO") },
            text = {
                Column {
                    Text(
                        "Pega el texto con líneas como “Filter 1: ON PK Fc 31 Hz Gain 5.8 dB Q 1.41” y “Preamp: -6.2 dB”.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(value = importText, onValueChange = { importText = it }, minLines = 5, maxLines = 9, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Row {
                        TextButton(onClick = { importText = clip.getText()?.text ?: "" }) { Text("Pegar") }
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Elegir archivo…") }
                    }
                    if (importMsg.isNotEmpty()) Text(importMsg, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = Dsp.importApo(importText)
                    if (n > 0) { importDialog = false; importText = ""; sel = 0; copied = false }
                    else importMsg = "No encontré filtros válidos en ese texto."
                }) { Text("Importar") }
            },
            dismissButton = { TextButton(onClick = { importDialog = false }) { Text("Cancelar") } }
        )
    }
}
