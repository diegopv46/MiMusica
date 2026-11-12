package com.example.mimusica

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** status: 0 cargando · 1 encontrada · 2 no encontrada · 3 sin conexión o búsqueda desactivada */
class Lyr(val status: Int, val synced: List<Pair<Long, String>> = emptyList(), val plain: String = "")

object LyricsRepo {
    private const val UA = "MiMusica/1.0 (reproductor personal para Android)"

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").also { it.mkdirs() }

    fun clearCache(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    private fun http(url: String): Pair<Int, String?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        return try {
            val code = c.responseCode
            code to (if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else null)
        } finally {
            c.disconnect()
        }
    }

    fun parseLrc(text: String): List<Pair<Long, String>> {
        val re = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<Pair<Long, String>>()
        for (raw in text.lines()) {
            val ms = re.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val txt = raw.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val frac = m.groupValues[3]
                val fr = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out += (m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 + fr) to txt
            }
        }
        return out.sortedBy { it.first }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun hasLyrics(o: JSONObject) =
        (!o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()) ||
            (!o.isNull("plainLyrics") && o.optString("plainLyrics").isNotBlank())

    /** Búsqueda exacta por título, artista, álbum y duración; si falla, búsqueda aproximada con comprobación estricta. */
    private fun fetch(title: String, artist: String, album: String, durSec: Long): JSONObject? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val (code, body) = http("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(album)}&duration=$durSec")
        if (code == 200 && body != null) {
            val o = JSONObject(body)
            if (hasLyrics(o)) return o
        }
        val (c2, b2) = http("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (c2 == 200 && b2 != null) {
            val arr = JSONArray(b2)
            val ok = (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                hasLyrics(it) && abs(it.optDouble("duration", -100.0) - durSec) <= 3.0 &&
                    norm(it.optString("trackName")) == norm(title)
            }
            return ok.firstOrNull { it.optString("syncedLyrics").isNotBlank() } ?: ok.firstOrNull()
        }
        return null
    }

    suspend fun load(ctx: Context, id: Long, title: String, artist: String, album: String, durSec: Long): Lyr =
        withContext(Dispatchers.IO) {
            val f = File(dir(ctx), "$id.json")
            runCatching {
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    if (o.optBoolean("nf", false)) {
                        if (System.currentTimeMillis() - o.optLong("t", 0L) < 7L * 24 * 3600 * 1000) return@withContext Lyr(2)
                    } else {
                        return@withContext Lyr(1, parseLrc(o.optString("s")), o.optString("p"))
                    }
                }
            }
            if (!AppPrefs.lyricsOnline) return@withContext Lyr(3)
            try {
                val o = fetch(title, artist, album, durSec)
                if (o == null) {
                    runCatching { f.writeText(JSONObject().put("nf", true).put("t", System.currentTimeMillis()).toString()) }
                    Lyr(2)
                } else {
                    val s = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
                    val p = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
                    runCatching { f.writeText(JSONObject().put("s", s).put("p", p).put("t", System.currentTimeMillis()).toString()) }
                    Lyr(1, parseLrc(s), p)
                }
            } catch (e: IOException) {
                Lyr(3)
            } catch (e: Exception) {
                Lyr(2)
            }
        }
}

@Composable
fun LyricsPanel(c: MediaController?, item: MediaItem?, position: Long, duration: Long, modifier: Modifier) {
    val ctx = LocalContext.current
    val id = item?.mediaId?.toLongOrNull()
    val title = item?.mediaMetadata?.title?.toString() ?: ""
    val artist = item?.mediaMetadata?.artist?.toString() ?: ""
    val album = item?.mediaMetadata?.albumTitle?.toString() ?: ""
    var lyr by remember(id) { mutableStateOf(Lyr(0)) }
    LaunchedEffect(id, duration > 0) {
        if (id != null && duration > 0) {
            lyr = Lyr(0)
            lyr = LyricsRepo.load(ctx, id, title, artist, album, duration / 1000)
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        val l = lyr
        when {
            l.status == 0 -> CircularProgressIndicator()
            l.status == 2 -> Text("No encontré la letra de esta canción.", textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            l.status == 3 -> Text(
                "Sin conexión, o la búsqueda de letras está desactivada en Ajustes.",
                textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)
            )
            l.synced.isNotEmpty() -> {
                val cur = l.synced.indexOfLast { it.first <= position }
                val ls = rememberLazyListState()
                LaunchedEffect(cur) { if (cur >= 0) ls.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
                LazyColumn(state = ls, contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(l.synced) { i, line ->
                        Text(
                            line.second.ifEmpty { "♪" },
                            style = if (i == cur) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            fontWeight = if (i == cur) FontWeight.Bold else FontWeight.Normal,
                            color = if (i == cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable { c?.seekTo(line.first) }.padding(vertical = 8.dp)
                        )
                    }
                }
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp), modifier = Modifier.fillMaxSize()) {
                itemsIndexed(l.plain.lines()) { _, line ->
                    Text(line.ifEmpty { " " }, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                }
            }
        }
    }
}
