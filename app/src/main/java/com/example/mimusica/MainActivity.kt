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
    val uri: Uri, val item: MediaItem
)

fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

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

    private fun refresh() {
        granted = ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED
        songs = if (granted) loadSongs() else emptyList()
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

    private fun loadSongs(): List<Song> {
        val list = folders.toList()
        if (list.isEmpty()) return emptyList()
        val result = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID
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
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                val title = c.getString(tC) ?: "Sin título"
                val artist = c.getString(arC)?.takeIf { it != "<unknown>" } ?: "Artista desconocido"
                val album = c.getString(alC) ?: "Álbum desconocido"
                val uri = uriOf(id)
                val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), c.getLong(aidC))
                val item = MediaItem.Builder()
                    .setMediaId(id.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(title).setArtist(artist)
                            .setAlbumTitle(album).setArtworkUri(art).build()
                    ).build()
                result += Song(id, title, artist, album, uri, item)
            }
        }
        return result
    }

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
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
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

