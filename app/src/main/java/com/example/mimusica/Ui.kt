package com.example.mimusica

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ArtCache { val cache = LruCache<Uri, Bitmap>(150) }

suspend fun loadArt(ctx: Context, uri: Uri): Bitmap? {
    ArtCache.cache.get(uri)?.let { return it }
    val b = withContext(Dispatchers.IO) {
        runCatching { ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
    }
    if (b != null) ArtCache.cache.put(uri, b)
    return b
}

@Composable
fun Art(uri: Uri?, modifier: Modifier) {
    val ctx = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(uri?.let { ArtCache.cache.get(it) }) }
    LaunchedEffect(uri) { if (uri != null && bmp == null) bmp = loadArt(ctx, uri) }
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.LibraryMusic, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun techInfo(ctx: Context, uri: Uri): String {
    val ex = MediaExtractor()
    try {
        ex.setDataSource(ctx, uri, null)
        val f = ex.getTrackFormat(0)
        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
        val sr = if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
        var br = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) f.getInteger(MediaFormat.KEY_BIT_RATE) else 0
        if (br == 0 && f.containsKey(MediaFormat.KEY_DURATION)) {
            val dur = f.getLong(MediaFormat.KEY_DURATION)
            val size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (dur > 0 && size > 0) br = (size * 8_000_000L / dur).toInt()
        }
        val codec = when {
            mime.contains("flac") -> "FLAC"; mime.contains("mpeg") -> "MP3"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"; mime.contains("opus") -> "OPUS"
            mime.contains("vorbis") -> "OGG"; mime.contains("alac") -> "ALAC"
            mime.contains("raw") || mime.contains("wav") -> "WAV"
            else -> mime.substringAfter('/').uppercase()
        }
        return listOfNotNull(
            if (sr > 0) "%.1f kHz".format(sr / 1000.0) else null,
            if (br > 0) "${br / 1000} kbps" else null,
            codec.ifEmpty { null }
        ).joinToString(" · ")
    } finally {
        ex.release()
    }
}

@Composable
fun SongList(list: List<Song>, currentId: String?, onPlay: (Int) -> Unit, onMenu: (Song) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            ListItem(
                leadingContent = { Art(s.uri, Modifier.size(52.dp)) },
                headlineContent = {
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (s.id.toString() == currentId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = { Text("${s.artist} • ${s.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { IconButton(onClick = { onMenu(s) }) { Icon(Icons.Rounded.MoreVert, "Opciones") } },
                modifier = Modifier.clickable { onPlay(i) }
            )
        }
    }
}

@Composable
fun MenuItem(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MusicApp(
    songs: List<Song>, folders: Set<String>, controller: MediaController?, granted: Boolean,
    onPermission: () -> Unit, onAddFolder: () -> Unit, onRemoveFolder: (String) -> Unit
) {
    var nav by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var plOpen by remember { mutableStateOf<String?>(null) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showNewPl by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<MediaItem?>(null) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var queue by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var curIndex by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            fun sync(rebuildQueue: Boolean) {
                isPlaying = c.isPlaying; current = c.currentMediaItem
                shuffle = c.shuffleModeEnabled; repeat = c.repeatMode
                curIndex = c.currentMediaItemIndex
                if (rebuildQueue) queue = List(c.mediaItemCount) { c.getMediaItemAt(it) }
            }
            val l = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) =
                    sync(events.contains(Player.EVENT_TIMELINE_CHANGED))
            }
            c.addListener(l); sync(true)
            onDispose { c.removeListener(l) }
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { position = it.currentPosition; duration = it.duration.coerceAtLeast(0L) }
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    BackHandler(overlay != null) { overlay = null }
    BackHandler(overlay == null && nav == 1 && plOpen != null) { plOpen = null }
    BackHandler(overlay == null && nav == 0 && detail != null) { detail = null }
    BackHandler(overlay == null && nav != 0 && plOpen == null) { nav = 0 }

    val play: (List<Song>, Int) -> Unit = { l, i ->
        controller?.let { it.setMediaItems(l.map { s -> s.item }, i, 0L); it.prepare(); it.play() }
    }
    val addToQueue: (Song, Boolean) -> Unit = { s, next ->
        controller?.let {
            if (it.mediaItemCount == 0) { it.setMediaItem(s.item); it.prepare(); it.play() }
            else if (next) it.addMediaItem(it.currentMediaItemIndex + 1, s.item)
            else it.addMediaItem(s.item)
        }
    }
    val remainingMs = if (SleepTimer.endAt > 0) (SleepTimer.endAt - nowMs).coerceAtLeast(0L) else 0L
    val curId = current?.mediaId
    val curUri = curId?.toLongOrNull()?.let { uriOf(it) }

    if (overlay == "now") {
        NowPlaying(controller, current, isPlaying, shuffle, repeat, position, duration, remainingMs,
            onBack = { overlay = null }, onEq = { overlay = null; nav = 3 }, onTimer = { showTimer = true })
    } else if (overlay == "folders") {
        FoldersScreen(folders, onAddFolder, onRemoveFolder, onBack = { overlay = null })
    } else {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when (nav) {
                    0 -> {
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
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Mi Música", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                IconButton(onClick = { overlay = "folders" }) { Icon(Icons.Rounded.Folder, "Carpetas") }
                            }
                            if (!granted) {
                                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Necesito permiso para leer tu música.")
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = onPermission) { Text("Dar permiso") }
                                }
                            } else if (folders.isEmpty()) {
                                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Elige la carpeta donde está tu música.")
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = onAddFolder) { Text("Elegir carpeta") }
                                }
                            } else {
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it }, singleLine = true,
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    placeholder = { Text("Buscar canción, artista o álbum") },
                                    shape = RoundedCornerShape(28.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                                if (detail != null) {
                                    TextButton(onClick = { detail = null }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("← ${detail!!.second}") }
                                    Box(Modifier.weight(1f)) { SongList(shown, curId, { play(shown, it) }, { menuSong = it }) }
                                } else {
                                    TabRow(selectedTabIndex = tab) {
                                        listOf("Canciones", "Álbumes", "Artistas").forEachIndexed { i, t ->
                                            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                                        }
                                    }
                                    Box(Modifier.weight(1f)) {
                                        when (tab) {
                                            0 -> SongList(base, curId, { play(base, it) }, { menuSong = it })
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
                                                        leadingContent = { Art(list[0].uri, Modifier.size(52.dp)) },
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
                        }
                    }
                    1 -> {
                        val byId = remember(songs) { songs.associateBy { it.id } }
                        val open = plOpen
                        if (open == null) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Playlists", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { showNewPl = true }) { Icon(Icons.Rounded.Add, "Nueva playlist") }
                                }
                                LazyColumn(Modifier.fillMaxSize()) {
                                    item {
                                        ListItem(
                                            leadingContent = { Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                                            headlineContent = { Text("Favoritas") },
                                            supportingContent = { Text("${Store.favorites.size} canciones") },
                                            modifier = Modifier.clickable { plOpen = FAV }
                                        )
                                    }
                                    items(Store.playlists.keys.toList()) { name ->
                                        ListItem(
                                            leadingContent = { Icon(Icons.Rounded.PlaylistPlay, null) },
                                            headlineContent = { Text(name) },
                                            supportingContent = { Text("${Store.playlists[name]?.size ?: 0} canciones") },
                                            trailingContent = { IconButton(onClick = { Store.delete(name) }) { Icon(Icons.Rounded.Delete, "Eliminar") } },
                                            modifier = Modifier.clickable { plOpen = name }
                                        )
                                    }
                                }
                            }
                        } else {
                            val ids = if (open == FAV) Store.favorites.toList() else Store.playlists[open].orEmpty()
                            val list = ids.mapNotNull { byId[it] }
                            Column(Modifier.fillMaxSize()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                    IconButton(onClick = { plOpen = null }) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
                                    Text(if (open == FAV) "Favoritas" else open, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (list.isNotEmpty()) FilledTonalButton(onClick = { play(list, 0) }) { Text("Reproducir") }
                                }
                                if (list.isEmpty()) Text("Aún no hay canciones aquí. Agrégalas desde el menú ⋮ de cada canción.", Modifier.padding(16.dp))
                                else SongList(list, curId, { play(list, it) }, { menuSong = it })
                            }
                        }
                    }
                    2 -> Column(Modifier.fillMaxSize()) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Cola (${queue.size})", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (queue.isNotEmpty()) TextButton(onClick = { controller?.clearMediaItems() }) { Text("Limpiar") }
                        }
                        if (queue.isEmpty()) Text("La cola está vacía. Reproduce algo o usa “Añadir a la cola” en el menú ⋮ de una canción.", Modifier.padding(16.dp))
                        else LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(queue) { i, it ->
                                ListItem(
                                    leadingContent = { Art(it.mediaId.toLongOrNull()?.let { id -> uriOf(id) }, Modifier.size(48.dp)) },
                                    headlineContent = {
                                        Text(it.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (i == curIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    },
                                    supportingContent = { Text(it.mediaMetadata.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingContent = { IconButton(onClick = { controller?.removeMediaItem(i) }) { Icon(Icons.Rounded.Close, "Quitar") } },
                                    modifier = Modifier.clickable { controller?.let { c -> c.seekToDefaultPosition(i); c.play() } }
                                )
                            }
                        }
                    }
                    else -> EqScreen()
                }
            }
            if (current != null) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable { overlay = "now" }) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Art(curUri, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(current?.mediaMetadata?.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text(current?.mediaMetadata?.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Anterior") }
                        FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa")
                        }
                        IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, "Siguiente") }
                    }
                }
            }
            NavigationBar {
                listOf(
                    "Biblioteca" to Icons.Rounded.LibraryMusic, "Playlists" to Icons.Rounded.PlaylistPlay,
                    "Cola" to Icons.Rounded.QueueMusic, "EQ" to Icons.Rounded.Tune
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = nav == i, onClick = { nav = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                }
            }
        }
    }

    menuSong?.let { s ->
        AlertDialog(
            onDismissRequest = { menuSong = null },
            confirmButton = { TextButton(onClick = { menuSong = null }) { Text("Cerrar") } },
            title = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MenuItem("Reproducir a continuación") { addToQueue(s, true); menuSong = null }
                    MenuItem("Añadir a la cola") { addToQueue(s, false); menuSong = null }
                    MenuItem(if (s.id in Store.favorites) "Quitar de favoritas" else "Añadir a favoritas") { Store.toggleFav(s.id); menuSong = null }
                    Store.playlists.keys.forEach { n -> MenuItem("Agregar a: $n") { Store.addTo(n, s.id); menuSong = null } }
                    val po = plOpen
                    if (nav == 1 && po != null && po != FAV) MenuItem("Quitar de esta playlist") { Store.removeFrom(po, s.id); menuSong = null }
                }
            }
        )
    }
    if (showNewPl) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPl = false },
            title = { Text("Nueva playlist") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Nombre") }) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) Store.create(name.trim()); showNewPl = false }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { showNewPl = false }) { Text("Cancelar") } }
        )
    }
    if (showTimer) {
        AlertDialog(
            onDismissRequest = { showTimer = false },
            title = { Text("Apagar música en…") },
            confirmButton = { TextButton(onClick = { showTimer = false }) { Text("Cerrar") } },
            text = {
                Column {
                    listOf(15, 30, 45, 60, 90).forEach { m -> MenuItem("$m minutos") { SleepTimer.set(m); showTimer = false } }
                    if (SleepTimer.endAt > 0) MenuItem("Cancelar temporizador") { SleepTimer.set(0); showTimer = false }
                }
            }
        )
    }
}

@Composable
fun NowPlaying(
    c: MediaController?, item: MediaItem?, isPlaying: Boolean, shuffle: Boolean, repeat: Int,
    position: Long, duration: Long, remainingMs: Long,
    onBack: () -> Unit, onEq: () -> Unit, onTimer: () -> Unit
) {
    val ctx = LocalContext.current
    val id = item?.mediaId?.toLongOrNull()
    val uri = id?.let { uriOf(it) }
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(uri) {
        tint = uri?.let { u -> loadArt(ctx, u)?.let { b -> Color(Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)) } }
    }
    var info by remember(id) { mutableStateOf("") }
    LaunchedEffect(id) {
        info = if (uri == null) "" else withContext(Dispatchers.IO) { runCatching { techInfo(ctx, uri) }.getOrDefault("") }
    }
    val surface = MaterialTheme.colorScheme.surface
    val top by animateColorAsState(tint ?: surface, label = "bg")
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(surface, top, 0.6f), surface)))
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", Modifier.size(32.dp)) }
            Text("Reproduciendo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Art(uri, Modifier.fillMaxSize())
            if (id != null) {
                FilledTonalIconButton(onClick = { Store.toggleFav(id) }, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Icon(if (id in Store.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorita")
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(item?.mediaMetadata?.title?.toString() ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item?.mediaMetadata?.artist?.toString() ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.labelMedium, color = idle, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEq) { Icon(Icons.Rounded.Tune, "Ecualizador") }
            IconButton(onClick = onTimer) { Icon(Icons.Rounded.Timer, "Temporizador", tint = if (remainingMs > 0) active else idle) }
            if (remainingMs > 0) Text("${remainingMs / 60000 + 1} min", style = MaterialTheme.typography.labelMedium, color = active)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                c?.repeatMode = when (repeat) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir",
                    tint = if (repeat == Player.REPEAT_MODE_OFF) idle else active)
            }
            IconButton(onClick = { c?.shuffleModeEnabled = !shuffle }) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (shuffle) active else idle)
            }
        }
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
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { c?.seekToPrevious() }, modifier = Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(40.dp)) }
            FilledIconButton(onClick = { c?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(84.dp)) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pausa", Modifier.size(48.dp))
            }
            IconButton(onClick = { c?.seekToNext() }, modifier = Modifier.size(64.dp)) { Icon(Icons.Rounded.SkipNext, "Siguiente", Modifier.size(40.dp)) }
        }
    }
}

@Composable
fun FoldersScreen(folders: Set<String>, onAdd: () -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
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
fun EqScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("eq", Context.MODE_PRIVATE) }
    val eq = Eq.equalizer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Ecualizador", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (eq == null) {
            Text("Reproduce una canción para activar el ecualizador.", modifier = Modifier.padding(vertical = 16.dp))
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
