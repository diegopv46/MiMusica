package com.example.mimusica

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Net {
    const val UA = "MiMusica/1.0 (reproductor personal para Android)"

    /** GET con redirecciones a mano (y http -> https). Devuelve (código, cuerpo si fue 200). */
    fun get(url: String): Pair<Int, ByteArray?> {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return code to null
                    u = URL(URL(u), loc).toString().replaceFirst("http://", "https://")
                } else {
                    return code to (if (code == 200) c.inputStream.use { it.readBytes() } else null)
                }
            } finally {
                c.disconnect()
            }
        }
        return 310 to null
    }
}

/** Busca ajustes de ecualizador en el repositorio público de AutoEq y descarga el archivo ParametricEQ. */
object AutoEq {
    class Entry(val name: String, val path: String, val source: String, val rig: String)

    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/"
    private var cache: List<Entry>? = null

    private fun parse(text: String): List<Entry> {
        val re = Regex("^- \\[(.+)\\]\\(\\./(.+?)\\) by (.+?)(?: on (.+))?$")
        return text.lineSequence().mapNotNull { line ->
            val m = re.find(line.trim()) ?: return@mapNotNull null
            Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        }.toList()
    }

    suspend fun loadIndex(ctx: Context): List<Entry> = withContext(Dispatchers.IO) {
        cache?.let { return@withContext it }
        val f = File(ctx.filesDir, "autoeq_index.md")
        var text: String? = null
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 14L * 24 * 3600 * 1000
        if (fresh) text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            val (code, body) = runCatching { Net.get(BASE + "INDEX.md") }.getOrDefault(0 to null)
            if (code == 200 && body != null) {
                text = String(body, Charsets.UTF_8)
                runCatching { f.writeText(text) }
            } else if (f.exists()) {
                text = runCatching { f.readText() }.getOrNull()
            }
        }
        val list = if (text == null) emptyList() else parse(text)
        if (list.isNotEmpty()) cache = list
        list
    }

    fun search(index: List<Entry>, query: String): List<Entry> {
        val tokens = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return index.filter { e -> val n = e.name.lowercase(); tokens.all { n.contains(it) } }
            .sortedWith(compareBy<Entry>({ it.name.length }, { it.source })).take(40)
    }

    suspend fun fetchParametric(e: Entry): String? = withContext(Dispatchers.IO) {
        val last = e.path.substringAfterLast('/')
        val (code, body) = runCatching { Net.get(BASE + e.path + "/" + last + "%20ParametricEQ.txt") }.getOrDefault(0 to null)
        if (code == 200 && body != null) String(body, Charsets.UTF_8) else null
    }
}

/** Carátulas propias: se buscan en MusicBrainz / Cover Art Archive y se guardan dentro de la app. */
object Covers {
    var version by mutableIntStateOf(0)
    var index by mutableStateOf<Map<Long, String>>(emptyMap())

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").also { it.mkdirs() }

    fun key(artist: String, album: String): String = (artist + "|" + album).lowercase().hashCode().toString()

    fun setIndex(songs: List<Song>) {
        index = songs.associate { it.id to key(it.artist, it.album) }
    }

    fun fileFor(ctx: Context, id: Long): File? {
        val k = index[id] ?: return null
        val f = File(dir(ctx), "$k.jpg")
        return if (f.exists()) f else null
    }

    suspend fun download(ctx: Context, artist: String, album: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val q = "artist:\"${artist.replace("\"", "")}\" AND release:\"${album.replace("\"", "")}\""
            val (c1, b1) = Net.get("https://musicbrainz.org/ws/2/release/?query=${URLEncoder.encode(q, "UTF-8")}&fmt=json&limit=3")
            if (c1 != 200 || b1 == null) return@runCatching false
            val arr = JSONObject(String(b1, Charsets.UTF_8)).optJSONArray("releases") ?: return@runCatching false
            for (i in 0 until arr.length()) {
                val mbid = arr.getJSONObject(i).optString("id")
                if (mbid.isEmpty()) continue
                val (c2, img) = Net.get("https://coverartarchive.org/release/$mbid/front-500")
                if (c2 == 200 && img != null && BitmapFactory.decodeByteArray(img, 0, img.size) != null) {
                    File(dir(ctx), key(artist, album) + ".jpg").writeBytes(img)
                    ArtCache.cache.evictAll()
                    version++
                    return@runCatching true
                }
                Thread.sleep(1100)
            }
            false
        }.getOrDefault(false)
    }
}
