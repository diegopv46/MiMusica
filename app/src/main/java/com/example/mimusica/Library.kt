package com.example.mimusica

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

const val AUTO_TOP = "__auto_top__"
const val AUTO_PLAYED = "__auto_played__"
const val AUTO_ADDED = "__auto_added__"
const val AUTO_LONG = "__auto_long__"
const val AUTO_NEW = "__auto_new__"

fun autoTitle(key: String): String = when (key) {
    FAV -> "Favoritas"
    AUTO_TOP -> "Más reproducidas"
    AUTO_PLAYED -> "Reproducidas recientemente"
    AUTO_ADDED -> "Agregadas recientemente"
    AUTO_LONG -> "Pistas largas"
    AUTO_NEW -> "Sin reproducir"
    else -> key
}

fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")

val SORT_NAMES = listOf("Título", "Artista", "Álbum", "Año", "Agregadas", "Duración")

fun sortSongs(list: List<Song>, mode: Int): List<Song> = when (mode) {
    1 -> list.sortedWith(compareBy<Song>({ it.artist.lowercase() }, { it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
    2 -> list.sortedWith(compareBy<Song>({ it.album.lowercase() }, { it.track }, { it.title.lowercase() }))
    3 -> list.sortedWith(compareByDescending<Song> { it.year }.thenBy { it.title.lowercase() })
    4 -> list.sortedByDescending { it.added }
    5 -> list.sortedByDescending { it.duration }
    else -> list
}

/** Conteo de reproducciones: una canción cuenta cuando suena 30 s o la mitad de su duración. */
object Stats {
    class Entry(val count: Int, val last: Long)

    var plays by mutableStateOf<Map<Long, Entry>>(emptyMap())
    private var sp: SharedPreferences? = null
    private val handler = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (sp != null) return
        val s = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
        sp = s
        runCatching {
            val o = JSONObject(s.getString("p", "{}") ?: "{}")
            val m = HashMap<Long, Entry>()
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                m[k.toLong()] = Entry(a.getInt(0), a.getLong(1))
            }
            plays = m
        }
    }

    private fun save() {
        val o = JSONObject()
        plays.forEach { (k, e) -> o.put(k.toString(), JSONArray().put(e.count).put(e.last)) }
        sp?.edit()?.putString("p", o.toString())?.apply()
    }

    private fun bump(id: Long) {
        val e = plays[id]
        plays = plays + (id to Entry((e?.count ?: 0) + 1, System.currentTimeMillis()))
        save()
    }

    fun attach(p: Player) {
        var counted: String? = null
        val tick = object : Runnable {
            override fun run() {
                val cur = p.currentMediaItem
                if (cur != null && p.isPlaying) {
                    val d = p.duration
                    val need = if (d > 0) min(30_000L, d / 2) else 30_000L
                    if (counted != cur.mediaId && p.currentPosition >= need) {
                        counted = cur.mediaId
                        cur.mediaId.toLongOrNull()?.let { bump(it) }
                    }
                }
                if (p.isPlaying) handler.postDelayed(this, 1000)
            }
        }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(tick)
                if (isPlaying) handler.post(tick)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { counted = null }
        })
    }
}

/** Importar y exportar listas M3U. Las rutas se escriben como /storage/emulated/0/<carpeta>/<archivo>. */
object M3u {
    fun exportTo(ctx: Context, uri: Uri, list: List<Song>): Boolean = runCatching {
        val w = ctx.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8) ?: return@runCatching false
        w.use {
            it.write("#EXTM3U\n")
            list.forEach { s ->
                it.write("#EXTINF:${s.duration / 1000},${s.artist} - ${s.title}\n")
                it.write("/storage/emulated/0/${s.folder}${s.file}\n")
            }
        }
        true
    }.getOrDefault(false)

    /** Devuelve (nombre sugerido, ids de las canciones encontradas en tu biblioteca). */
    fun importFile(ctx: Context, uri: Uri, songs: List<Song>): Pair<String, List<Long>> {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Importada"
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()
        val byPath = songs.filter { it.file.isNotEmpty() }.associateBy { (it.folder + it.file).lowercase() }
        val byName = songs.filter { it.file.isNotEmpty() }.groupBy { it.file.lowercase() }
        val ids = ArrayList<Long>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.replace('\\', '/').lowercase()
            val hit = byPath.entries.firstOrNull { p.endsWith(it.key) }?.value
                ?: byName[p.substringAfterLast('/')]?.singleOrNull()
            if (hit != null && hit.id !in ids) ids += hit.id
        }
        return name to ids
    }
}

@Composable
fun SortBar() {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Sort, null)
            Spacer(Modifier.width(6.dp))
            Text("Orden: ${SORT_NAMES[AppPrefs.sortMode.coerceIn(0, SORT_NAMES.lastIndex)]}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SORT_NAMES.forEachIndexed { i, n ->
                DropdownMenuItem(text = { Text(n) }, onClick = { AppPrefs.chooseSort(i); open = false })
            }
        }
    }
}
