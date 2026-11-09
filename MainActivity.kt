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

object ArtCache { val cache = LruCache<Uri, Bitmap>(150) }

@Composable
fun Art(uri: Uri?, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(uri?.let { ArtCache.cache.get(it) }) }
    LaunchedEffect(uri) {
        if (uri != null && bmp == null) {
            val b = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
            }
            if (b != null) { ArtCache.cache.put(uri, b); bmp = b }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("♪", style = MaterialTheme.typography.titleLarge)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SongList(list: List<Song>, currentId: String?, onPlay: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            ListItem(
                leadingContent = { Art(s.uri, Modifier.size(48.dp)) },
                headlineContent = {
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (s.id.toString() == currentId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = { Text("${s.artist} • ${s.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.clickable { onPlay(i) }
            )
        }
    }
}

@Composable
fun MusicApp(
    songs: List<Song>, folders: Set<String>, controller: MediaController?, granted: Boolean,
    onPermission: () -> Unit, onAddFolder: () -> Unit, onRemoveFolder: (String) -> Unit
) {
    var screen by remember { mutableStateOf("home") }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }

    var isPlaying by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            fun sync() {
                isPlaying = c.isPlaying; current = c.currentMediaItem
                shuffle = c.shuffleModeEnabled; repeat = c.repeatMode
            }
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = sync()
            }
            c.addListener(l); sync()
            onDispose { c.removeListener(l) }
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { position = it.currentPosition; duration = it.duration.coerceAtLeast(0L) }
            delay(500)
        }
    }

    BackHandler(screen != "home") { screen = "home" }
    BackHandler(screen == "home" && detail != null) { detail = null }

    val play: (List<Song>, Int) -> Unit = { l, i ->
        controller?.let { it.setMediaItems(l.map { s -> s.item }, i, 0L); it.prepare(); it.play() }
    }

    when (screen) {
        "now" -> NowPlaying(controller, current, isPlaying, shuffle, repeat, position, duration,
            onBack = { screen = "home" }, onEq = { screen = "eq" })
        "eq" -> EqScreen(onBack = { screen = "home" })
        "folders" -> FoldersScreen(folders, onAddFolder, onRemoveFolder, onBack = { screen = "home" })
        else -> {
            val q = query.trim().lowercase()
            val base = remember(songs, q) {
                if (q.isEmpty()) songs else songs.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
                }
            }
            val albums = remember(base) { base.groupBy { it.album }.toList().sortedBy { it.first.lowercase() } }
            val artists = remember(base) { base.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() } }
            val shown = remember(detail, base) {
                detail?.let { (k, v) -> base.filter { if (k == "album") it.album == v else it.artist == v } } ?: base
            }
            val curId = current?.mediaId

            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mi Música", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { screen = "folders" }) { Text("Carpetas") }
                    TextButton(onClick = { screen = "eq" }) { Text("EQ") }
                }
                if (!granted) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Necesito permiso para leer tu música.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onPermission) { Text("Dar permiso") }
                    }
                } else if (folders.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Elige la carpeta donde está tu música.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onAddFolder) { Text("Elegir carpeta") }
                    }
                } else {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Buscar canción, artista o álbum") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    if (detail != null) {
                        TextButton(onClick = { detail = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("← ${detail!!.second}") }
                        Box(Modifier.weight(1f)) { SongList(shown, curId) { play(shown, it) } }
                    } else {
                        TabRow(selectedTabIndex = tab) {
                            listOf("Canciones", "Álbumes", "Artistas").forEachIndexed { i, t ->
                                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> SongList(base, curId) { play(base, it) }
                                1 -> LazyVerticalGrid(
                                    GridCells.Fixed(2), contentPadding = PaddingValues(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    gridItems(albums) { (name, list) ->
                                        Column(Modifier.clickable { detail = "album" to name }) {
                                            Art(list[0].uri, Modifier.fillMaxWidth().aspectRatio(1f))
                                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                                            Text(list[0].artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                else -> LazyColumn(Modifier.fillMaxSize()) {
                                    items(artists) { (name, list) ->
                                        ListItem(
                                            leadingContent = { Art(list[0].uri, Modifier.size(48.dp)) },
                                            headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            supportingContent = { Text("${list.size} canciones") },
                                            modifier = Modifier.clickable { detail = "artist" to name }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (current != null) {
                    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable { screen = "now" }) {
                        Row(Modifier.navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Art(current?.mediaId?.toLongOrNull()?.let { uriOf(it) }, Modifier.size(44.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(current?.mediaMetadata?.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(current?.mediaMetadata?.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { controller?.seekToPrevious() }) { Text("⏮") }
                            FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) { Text(if (isPlaying) "⏸" else "▶") }
                            TextButton(onClick = { controller?.seekToNext() }) { Text("⏭") }
                        }
                    }
                } else {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

@Composable
fun NowPlaying(
    c: MediaController?, item: MediaItem?, isPlaying: Boolean, shuffle: Boolean, repeat: Int,
    position: Long, duration: Long, onBack: () -> Unit, onEq: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth()) { TextButton(onClick = onBack) { Text("↓ Cerrar") } }
        Art(item?.mediaId?.toLongOrNull()?.let { uriOf(it) }, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(20.dp))
        Text(item?.mediaMetadata?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item?.mediaMetadata?.artist?.toString() ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Slider(
            value = if (dragging) dragValue else position.toFloat(),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { c?.seekTo(dragValue.toLong()); dragging = false },
            valueRange = 0f..maxOf(duration.toFloat(), 1f)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(if (dragging) dragValue.toLong() else position), style = MaterialTheme.typography.labelMedium)
            Text(fmt(duration), style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c?.shuffleModeEnabled = !shuffle }) { Text(if (shuffle) "🔀 sí" else "🔀 no") }
            TextButton(onClick = { c?.seekToPrevious() }) { Text("⏮", style = MaterialTheme.typography.headlineSmall) }
            FilledIconButton(onClick = { c?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(64.dp)) {
                Text(if (isPlaying) "⏸" else "▶", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = { c?.seekToNext() }) { Text("⏭", style = MaterialTheme.typography.headlineSmall) }
            TextButton(onClick = {
                c?.repeatMode = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) { Text(when (repeat) { Player.REPEAT_MODE_ALL -> "🔁 todo"; Player.REPEAT_MODE_ONE -> "🔂 1"; else -> "🔁 no" }) }
        }
        TextButton(onClick = onEq) { Text("Ecualizador") }
    }
}

@Composable
fun FoldersScreen(folders: Set<String>, onAdd: () -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Atrás") }
            Text("Carpetas de música", style = MaterialTheme.typography.titleLarge)
        }
        Text("Solo se mostrará la música dentro de estas carpetas.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(folders.toList()) { f ->
                ListItem(
                    headlineContent = { Text(f) },
                    trailingContent = { TextButton(onClick = { onRemove(f) }) { Text("Quitar") } }
                )
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Agregar carpeta") }
    }
}

@Composable
fun EqScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("eq", Context.MODE_PRIVATE) }
    val eq = Eq.equalizer
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Atrás") }
            Text("Ecualizador", style = MaterialTheme.typography.titleLarge)
        }
        if (eq == null) {
            Text("Reproduce una canción para activar el ecualizador.", modifier = Modifier.padding(16.dp))
        } else {
            val n = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            var on by remember { mutableStateOf(eq.enabled) }
            val levels = remember { mutableStateListOf<Float>().apply { for (b in 0 until n) add(eq.getBandLevel(b.toShort()).toFloat()) } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Activado", modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { on = it; eq.enabled = it; prefs.edit().putBoolean("on", it).apply() })
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((0 until eq.numberOfPresets.toInt()).toList()) { p ->
                    AssistChip(
                        onClick = {
                            eq.usePreset(p.toShort())
                            on = true; eq.enabled = true
                            val e = prefs.edit().putBoolean("on", true)
                            for (b in 0 until n) { levels[b] = eq.getBandLevel(b.toShort()).toFloat(); e.putInt("b$b", levels[b].toInt()) }
                            e.apply()
                        },
                        label = { Text(eq.getPresetName(p.toShort())) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            for (b in 0 until n) {
                Text("${eq.getCenterFreq(b.toShort()) / 1000} Hz   ${(levels[b] / 100).toInt()} dB", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = levels[b],
                    onValueChange = { levels[b] = it; eq.setBandLevel(b.toShort(), it.toInt().toShort()) },
                    onValueChangeFinished = { prefs.edit().putInt("b$b", levels[b].toInt()).apply() },
                    valueRange = range[0].toFloat()..range[1].toFloat()
                )
            }
        }
    }
}
