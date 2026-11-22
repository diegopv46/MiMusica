package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Ediciones de etiquetas guardadas dentro de la app. No modifican los archivos de música. */
object Tags {
    class Edit(val title: String, val artist: String, val album: String, val genre: String, val year: Int)

    var edits by mutableStateOf<Map<Long, Edit>>(emptyMap())
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("tags", Context.MODE_PRIVATE)
        sp = s
        runCatching {
            val o = JSONObject(s.getString("t", "{}") ?: "{}")
            val m = HashMap<Long, Edit>()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                m[k.toLong()] = Edit(a.getString(0), a.getString(1), a.getString(2), a.getString(3), a.getInt(4))
            }
            edits = m
        }
    }

    private fun save() {
        val o = JSONObject()
        edits.forEach { (k, e) -> o.put(k.toString(), JSONArray().put(e.title).put(e.artist).put(e.album).put(e.genre).put(e.year)) }
        sp?.edit()?.putString("t", o.toString())?.apply()
    }

    fun set(id: Long, e: Edit) { edits = edits + (id to e); save() }
    fun clear(id: Long) { edits = edits - id; save() }

    fun apply(s: Song): Song {
        val e = edits[s.id] ?: return s
        val md = s.item.mediaMetadata.buildUpon().setTitle(e.title).setArtist(e.artist).setAlbumTitle(e.album)
            .setArtworkUri(Covers.artUri(s.id, e.artist, e.album)).build()
        return s.copy(
            title = e.title, artist = e.artist, album = e.album, genre = e.genre, year = e.year,
            item = s.item.buildUpon().setMediaMetadata(md).build()
        )
    }
}

@Composable
fun EditTagsDialog(s: Song, onClose: () -> Unit) {
    var title by remember { mutableStateOf(s.title) }
    var artist by remember { mutableStateOf(s.artist) }
    var album by remember { mutableStateOf(s.album) }
    var genre by remember { mutableStateOf(s.genre) }
    var year by remember { mutableStateOf(if (s.year > 0) s.year.toString() else "") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Editar etiquetas") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Los cambios se guardan solo dentro de Mi Música. No modifican el archivo ni se ven en otros reproductores.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true)
                OutlinedTextField(genre, { genre = it }, label = { Text("Género") }, singleLine = true)
                OutlinedTextField(year, { year = it.filter { c -> c.isDigit() }.take(4) }, label = { Text("Año") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Tags.set(
                    s.id,
                    Tags.Edit(title.trim().ifEmpty { s.title }, artist.trim().ifEmpty { s.artist }, album.trim().ifEmpty { s.album }, genre.trim(), year.toIntOrNull() ?: 0)
                )
                onClose()
            }) { Text("Guardar") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { Tags.clear(s.id); onClose() }) { Text("Restaurar") }
                TextButton(onClick = onClose) { Text("Cancelar") }
            }
        }
    )
}
