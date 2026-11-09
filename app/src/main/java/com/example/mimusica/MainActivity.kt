package com.example.mimusica

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var songs by mutableStateOf<List<MediaItem>>(emptyList())
    private var granted by mutableStateOf(false)

    private val audioPerm: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermission()
        }

    private fun refreshPermission() {
        granted = ContextCompat.checkSelfPermission(this, audioPerm) == PackageManager.PERMISSION_GRANTED
        if (granted) songs = loadSongs()
    }

    private fun requestPermissions() {
        val list = mutableListOf(audioPerm)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    private fun loadSongs(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                result += MediaItem.Builder()
                    .setMediaId(id.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(c.getString(titleCol))
                            .setArtist(c.getString(artistCol))
                            .setAlbumTitle(c.getString(albumCol))
                            .build()
                    )
                    .build()
            }
        }
        return result
    }

    override fun onStart() {
        super.onStart()
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
        refreshPermission()
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    MusicApp(songs, controller, granted) { requestPermissions() }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MusicApp(
    songs: List<MediaItem>,
    controller: MediaController?,
    granted: Boolean,
    onRequest: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            fun sync() {
                isPlaying = c.isPlaying
                current = c.currentMediaItem
                shuffle = c.shuffleModeEnabled
                repeat = c.repeatMode
            }
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            }
            c.addListener(l)
            sync()
            onDispose { c.removeListener(l) }
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                if (!dragging) position = it.currentPosition
                duration = it.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Mi Música", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!granted) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Necesito permiso para leer tu música.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequest) { Text("Dar permiso") }
                }
            } else if (songs.isEmpty()) {
                Text("No se encontró música en el dispositivo.", Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(songs, key = { _, s -> s.mediaId }) { index, song ->
                        val selected = song.mediaId == current?.mediaId
                        ListItem(
                            headlineContent = {
                                Text(
                                    song.mediaMetadata.title?.toString() ?: "Sin título",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Text(
                                    song.mediaMetadata.artist?.toString() ?: "Artista desconocido",
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.clickable {
                                controller?.let {
                                    it.setMediaItems(songs, index, 0L)
                                    it.prepare()
                                    it.play()
                                }
                            }
                        )
                    }
                }
            }
        }

        if (current != null) {
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.navigationBarsPadding().padding(12.dp)) {
                    Text(
                        current?.mediaMetadata?.title?.toString() ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        current?.mediaMetadata?.artist?.toString() ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Slider(
                        value = if (dragging) dragValue else position.toFloat(),
                        onValueChange = { dragging = true; dragValue = it },
                        onValueChangeFinished = {
                            controller?.seekTo(dragValue.toLong())
                            position = dragValue.toLong()
                            dragging = false
                        },
                        valueRange = 0f..maxOf(duration.toFloat(), 1f)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(if (dragging) dragValue.toLong() else position), style = MaterialTheme.typography.labelSmall)
                        Text(fmt(duration), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { controller?.shuffleModeEnabled = !shuffle }) {
                            Text(if (shuffle) "Aleatorio: sí" else "Aleatorio: no")
                        }
                        TextButton(onClick = { controller?.seekToPrevious() }) { Text("⏮") }
                        FilledIconButton(onClick = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        }) { Text(if (isPlaying) "⏸" else "▶") }
                        TextButton(onClick = { controller?.seekToNext() }) { Text("⏭") }
                        TextButton(onClick = {
                            controller?.repeatMode = when (repeat) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        }) {
                            Text(
                                when (repeat) {
                                    Player.REPEAT_MODE_ALL -> "Repetir: todo"
                                    Player.REPEAT_MODE_ONE -> "Repetir: 1"
                                    else -> "Repetir: no"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
