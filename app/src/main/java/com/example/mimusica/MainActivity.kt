package com.example.mimusica

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Song(
    val id: Long, val title: String, val artist: String, val album: String,
    val uri: Uri, val item: MediaItem, val folder: String = "", val added: Long = 0L
)

fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

data class RawSong(
    val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long, val folder: String, val added: Long
)

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var songs by mutableStateOf<List<Song>>(emptyList())
    private var folders by mutableStateOf<Set<String>>(emptySet())
    private var granted by mutableStateOf(false)
    private val sp by lazy { getSharedPreferences("lib", Context.MODE_PRIVATE) }

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addFolder(uri)
        }

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var loadGen = 0
    private val cacheFile by lazy { java.io.File(filesDir, "songs.json") }

    private fun refresh() {
        granted = ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED
        val fs = folders
        val ok = granted
        val gen = ++loadGen
        loader.execute {
            if (!ok) {
                runOnUiThread { if (gen == loadGen) songs = emptyList() }
                return@execute
            }
            if (songs.isEmpty()) {
                val cached = readCache().filter { c -> fs.any { c.folder.startsWith(it) } }.map { build(it) }
                if (cached.isNotEmpty()) runOnUiThread { if (gen == loadGen && songs.isEmpty()) songs = cached }
            }
            val raws = queryRaw(fs)
            val list = raws.map { build(it) }
            runOnUiThread { if (gen == loadGen) songs = list }
            writeCache(raws)
        }
    }

    private fun requestPermissions() {
        val list = mutableListOf(audioPerm)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    private fun saveFolders(f: Set<String>) {
        folders = f
        sp.edit().putStringSet("folders", f).apply()
        refresh()
    }

    private fun addFolder(uri: Uri) {
        val path = DocumentsContract.getTreeDocumentId(uri).substringAfter(':', "").trim('/')
        if (path.isEmpty()) {
            Toast.makeText(this, "Elige una carpeta concreta, no todo el almacenamiento", Toast.LENGTH_LONG).show()
            return
        }
        saveFolders(folders + "$path/")
    }

    private fun queryRaw(fs: Set<String>): List<RawSong> {
        val list = fs.toList()
        if (list.isEmpty()) return emptyList()
        val result = ArrayList<RawSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND (" +
            list.joinToString(" OR ") { "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" } + ")"
        val args = list.map { "$it%" }.toTypedArray()
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, sel, args,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val tC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val arC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val alC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val aidC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val rpC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val daC = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                result += RawSong(
                    c.getLong(idC),
                    c.getString(tC) ?: "Sin título",
                    c.getString(arC)?.takeIf { it != "<unknown>" } ?: "Artista desconocido",
                    c.getString(alC) ?: "Álbum desconocido",
                    c.getLong(aidC), c.getString(rpC) ?: "", c.getLong(daC)
                )
            }
        }
        return result
    }

    private fun build(r: RawSong): Song {
        val uri = uriOf(r.id)
        val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), r.albumId)
        val item = MediaItem.Builder()
            .setMediaId(r.id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(r.title).setArtist(r.artist)
                    .setAlbumTitle(r.album).setArtworkUri(art).build()
            ).build()
        return Song(r.id, r.title, r.artist, r.album, uri, item, r.folder, r.added)
    }

    private fun writeCache(raws: List<RawSong>) {
        runCatching {
            val a = org.json.JSONArray()
            raws.forEach { r ->
                a.put(org.json.JSONArray().put(r.id).put(r.title).put(r.artist).put(r.album).put(r.albumId).put(r.folder).put(r.added))
            }
            cacheFile.writeText(a.toString())
        }
    }

    private fun readCache(): List<RawSong> = runCatching {
        val a = org.json.JSONArray(cacheFile.readText())
        List(a.length()) { i ->
            val r = a.getJSONArray(i)
            RawSong(r.getLong(0), r.getString(1), r.getString(2), r.getString(3), r.getLong(4), r.getString(5), r.getLong(6))
        }
    }.getOrDefault(emptyList())

    override fun onStart() {
        super.onStart()
        refresh()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folders = sp.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
        Store.init(this)
        Dsp.init(this)
        UsbAudio.init(this)
        AppPrefs.init(this)
        setContent {
            val sysDark = isSystemInDarkTheme()
            val dark = when (AppPrefs.theme) { 1 -> false; 2 -> true; else -> sysDark }
            val ctx = LocalContext.current
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            val view = LocalView.current
            SideEffect {
                val w = (view.context as android.app.Activity).window
                val ic = WindowCompat.getInsetsController(w, view)
                ic.isAppearanceLightStatusBars = !dark
                ic.isAppearanceLightNavigationBars = !dark
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    MusicApp(
                        songs, folders, controller, granted,
                        onPermission = { requestPermissions() },
                        onAddFolder = { folderLauncher.launch(null) },
                        onRemoveFolder = { saveFolders(folders - it) }
                    )
                }
            }
        }
    }
}

