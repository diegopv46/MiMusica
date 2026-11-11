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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun techInfo(ctx: Context, uri: Uri): String {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongList(
    list: List<Song>, currentId: String?, selected: Set<Long>,
    onPlay: (Int) -> Unit, onToggle: (Song) -> Unit, onMenu: (Song) -> Unit
) {
    val selecting = selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
            val sel = s.id in selected
            ListItem(
                colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                leadingContent = {
                    if (selecting) Checkbox(checked = sel, onCheckedChange = { onToggle(s) })
                    else Art(s.uri, Modifier.size(52.dp))
                },
                headlineContent = {
                    Text(
                        s.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (s.id.toString() == currentId) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = { Text("${s.artist} • ${s.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { if (!selecting) IconButton(onClick = { onMenu(s) }) { Icon(Icons.Rounded.MoreVert, "Opciones") } },
                modifier = Modifier.combinedClickable(
                    onClick = { if (selecting) onToggle(s) else onPlay(i) },
                    onLongClick = { onToggle(s) }
                )
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
fun PlayRow(list: List<Song>, play: (List<Song>, Int) -> Unit, shuffle: (List<Song>) -> Unit) {
    if (list.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { play(list, 0) }) {
            Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Reproducir")
        }
        OutlinedButton(onClick = { shuffle(list) }) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("Aleatorio")
        }
    }
}

@Composable
fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Atrás") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SelectionBar(count: Int, onClose: () -> Unit, onAll: () -> Unit, onPlay: () -> Unit, onQueue: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Cancelar") }
        Text("$count seleccionadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onAll) { Icon(Icons.Rounded.SelectAll, "Todo") }
        IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Reproducir") }
        IconButton(onClick = onQueue) { Icon(Icons.Rounded.QueueMusic, "A la cola") }
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.PlaylistAdd, "Agregar a lista") }
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
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var showTimer by remember { mutableStateOf(false) }
    var showNewPl by remember { mutableStateOf(false) }
    var showAddTo by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<List<Long>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(nav, tab, detail, plOpen) { selected = emptySet() }

    val ctx = LocalContext.current
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(controller, songs) {
        val c = controller
        if (c == null || restored || songs.isEmpty()) return@LaunchedEffect
        restored = true
        if (c.mediaItemCount > 0) return@LaunchedEffect
        val r = Resume.load(ctx) ?: return@LaunchedEffect
        val by = songs.associateBy { it.id.toString() }
        val items = r.ids.mapNotNull { by[it]?.item }
        if (items.isEmpty()) return@LaunchedEffect
        val curId0 = r.ids.getOrNull(r.index)
        val idx = items.indexOfFirst { it.mediaId == curId0 }.coerceAtLeast(0)
        c.shuffleModeEnabled = r.shuffle
        c.repeatMode = r.repeat
        c.setMediaItems(items, idx, r.pos)
        c.prepare()
    }

    BackHandler(overlay != null) { overlay = if (overlay == "audio" || overlay == "folders") "settings" else null }
    BackHandler(overlay == null && selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(overlay == null && selected.isEmpty() && nav == 0 && plOpen != null) { plOpen = null }
    BackHandler(overlay == null && selected.isEmpty() && nav == 0 && detail != null) { detail = null }
    BackHandler(overlay == null && selected.isEmpty() && nav != 0) { nav = 0 }

    val play: (List<Song>, Int) -> Unit = { l, i ->
        controller?.let { it.setMediaItems(l.map { s -> s.item }, i, 0L); it.prepare(); it.play() }
    }
    val playShuffle: (List<Song>) -> Unit = { l ->
        if (l.isNotEmpty()) { controller?.shuffleModeEnabled = true; play(l, l.indices.random()) }
    }
    val enqueueMany: (List<Song>) -> Unit = { l ->
        controller?.let { c ->
            if (c.mediaItemCount == 0) { c.setMediaItems(l.map { it.item }); c.prepare(); c.play() }
            else c.addMediaItems(l.map { it.item })
        }
    }
    val addToQueue: (Song, Boolean) -> Unit = { s, next ->
        controller?.let {
            if (it.mediaItemCount == 0) { it.setMediaItem(s.item); it.prepare(); it.play() }
            else if (next) it.addMediaItem(it.currentMediaItemIndex + 1, s.item)
            else it.addMediaItem(s.item)
        }
    }
    val toggle: (Song) -> Unit = { s -> selected = if (s.id in selected) selected - s.id else selected + s.id }
    val remainingMs = if (SleepTimer.endAt > 0) (SleepTimer.endAt - nowMs).coerceAtLeast(0L) else 0L
    val curId = current?.mediaId

    val q = query.trim().lowercase()
    val searchRes = remember(songs, q) {
        if (q.isEmpty()) emptyList() else songs.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }
    val recents = remember(songs) { songs.sortedByDescending { it.added }.take(100) }
    val albums = remember(songs) { songs.groupBy { it.album }.toList().sortedBy { it.first.lowercase() } }
    val artists = remember(songs) { songs.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() } }
    val folderGroups = remember(songs) { songs.groupBy { it.folder }.toList().sortedBy { it.first.lowercase() } }
    val detailList = remember(detail, songs) {
        detail?.let { (k, v) ->
            songs.filter { when (k) { "album" -> it.album == v; "artist" -> it.artist == v; else -> it.folder == v } }
        } ?: emptyList()
    }
    val plList = remember(plOpen, songs, Store.playlists, Store.favorites) {
        val po = plOpen
        if (po == null) emptyList() else {
            val ids = if (po == FAV) Store.favorites.toList() else Store.playlists[po].orEmpty()
            val by = songs.associateBy { it.id }
            ids.mapNotNull { by[it] }
        }
    }
    val visible: List<Song> = when {
        nav == 1 -> searchRes
        nav == 0 && detail != null -> detailList
        nav == 0 && plOpen != null -> plList
        nav == 0 && tab == 0 -> songs
        nav == 0 && tab == 4 -> recents
        else -> emptyList()
    }
    val selBar: @Composable () -> Unit = {
        SelectionBar(
            selected.size,
            onClose = { selected = emptySet() },
            onAll = { selected = visible.map { it.id }.toSet() },
            onPlay = { val l = visible.filter { it.id in selected }; selected = emptySet(); play(l, 0) },
            onQueue = { val l = visible.filter { it.id in selected }; selected = emptySet(); enqueueMany(l) },
            onAdd = { showAddTo = true }
        )
    }

    if (overlay == "now") {
        NowPlaying(controller, current, isPlaying, shuffle, repeat, position, duration, remainingMs,
            onBack = { overlay = null }, onEq = { overlay = null; nav = 3 }, onTimer = { showTimer = true })
    } else if (overlay == "settings") {
        SettingsScreen(onBack = { overlay = null }, onOpen = { overlay = it })
    } else if (overlay == "audio") {
        AudioInfoScreen(onBack = { overlay = "settings" })
    } else if (overlay == "folders") {
        FoldersScreen(folders, onAddFolder, onRemoveFolder, onBack = { overlay = "settings" })
    } else {
        Column(Modifier.fillMaxSize().background(if (nav == 3) Color.Black else Color.Transparent)) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                when (nav) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        if (selected.isNotEmpty()) selBar() else Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Mi Música", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { overlay = "settings" }) { Icon(Icons.Rounded.Settings, "Ajustes") }
                        }
                        val d = detail
                        val po = plOpen
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
                            if (d == null && po == null) {
                                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                                    listOf("Canciones", "Álbumes", "Artistas", "Carpetas", "Recientes", "Listas").forEachIndexed { i, t ->
                                        Tab(selected = tab == i, onClick = { tab = i; detail = null; plOpen = null }, text = { Text(t) })
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (d != null) {
                                    Column(Modifier.fillMaxSize()) {
                                        DetailHeader(if (d.first == "folder") d.second.trimEnd('/').substringAfterLast('/') else d.second) { detail = null }
                                        PlayRow(detailList, play, playShuffle)
                                        Box(Modifier.weight(1f)) { SongList(detailList, curId, selected, { play(detailList, it) }, toggle, { menuSong = it }) }
                                    }
                                } else if (po != null) {
                                    Column(Modifier.fillMaxSize()) {
                                        DetailHeader(if (po == FAV) "Favoritas" else po) { plOpen = null }
                                        PlayRow(plList, play, playShuffle)
                                        if (plList.isEmpty()) Text("Aún no hay canciones aquí. Selecciónalas con una pulsación larga en la biblioteca y usa “Agregar a lista”.", Modifier.padding(16.dp))
                                        else Box(Modifier.weight(1f)) { SongList(plList, curId, selected, { play(plList, it) }, toggle, { menuSong = it }) }
                                    }
                                } else when (tab) {
                                    0 -> Column(Modifier.fillMaxSize()) {
                                        PlayRow(songs, play, playShuffle)
                                        Box(Modifier.weight(1f)) { SongList(songs, curId, selected, { play(songs, it) }, toggle, { menuSong = it }) }
                                    }
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
                                    2 -> LazyColumn(Modifier.fillMaxSize()) {
                                        items(artists) { (name, list) ->
                                            ListItem(
                                                leadingContent = { Art(list[0].uri, Modifier.size(52.dp)) },
                                                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                supportingContent = { Text("${list.size} canciones") },
                                                modifier = Modifier.clickable { detail = "artist" to name }
                                            )
                                        }
                                    }
                                    3 -> LazyColumn(Modifier.fillMaxSize()) {
                                        items(folderGroups) { (path, list) ->
                                            ListItem(
                                                leadingContent = { Icon(Icons.Rounded.Folder, null) },
                                                headlineContent = { Text(path.trimEnd('/').substringAfterLast('/').ifEmpty { "Raíz" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                supportingContent = { Text("$path • ${list.size} canciones", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                modifier = Modifier.clickable { detail = "folder" to path }
                                            )
                                        }
                                    }
                                    4 -> Column(Modifier.fillMaxSize()) {
                                        PlayRow(recents, play, playShuffle)
                                        Box(Modifier.weight(1f)) { SongList(recents, curId, selected, { play(recents, it) }, toggle, { menuSong = it }) }
                                    }
                                    else -> LazyColumn(Modifier.fillMaxSize()) {
                                        item {
                                            ListItem(
                                                leadingContent = { Icon(Icons.Rounded.Add, null) },
                                                headlineContent = { Text("Nueva lista") },
                                                modifier = Modifier.clickable { pendingAdd = emptyList(); showNewPl = true }
                                            )
                                        }
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
                                                trailingContent = { IconButton(onClick = { confirmDelete = name }) { Icon(Icons.Rounded.Delete, "Eliminar") } },
                                                modifier = Modifier.clickable { plOpen = name }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> Column(Modifier.fillMaxSize()) {
                        if (selected.isNotEmpty()) selBar() else Text("Buscar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            placeholder = { Text("Canción, artista o álbum") },
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        if (q.isEmpty()) Text("Escribe para buscar en tu música.", Modifier.padding(16.dp))
                        else Box(Modifier.weight(1f)) { SongList(searchRes, curId, selected, { play(searchRes, it) }, toggle, { menuSong = it }) }
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
                                    trailingContent = {
                                        Row {
                                            IconButton(onClick = { if (i > 0) controller?.moveMediaItem(i, i - 1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir") }
                                            IconButton(onClick = { if (i < queue.lastIndex) controller?.moveMediaItem(i, i + 1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Bajar") }
                                            IconButton(onClick = { controller?.removeMediaItem(i) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "Quitar") }
                                        }
                                    },
                                    modifier = Modifier.clickable { controller?.let { c -> c.seekToDefaultPosition(i); c.play() } }
                                )
                            }
                        }
                    }
                    else -> EqScreen()
                }
            }
            BottomDock(current, isPlaying, position, duration, nav, { nav = it }, controller) { overlay = "now" }
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
                    if (nav == 0 && po != null && po != FAV) MenuItem("Quitar de esta lista") { Store.removeFrom(po, s.id); menuSong = null }
                }
            }
        )
    }
    if (showAddTo) {
        AlertDialog(
            onDismissRequest = { showAddTo = false },
            title = { Text("Agregar ${selected.size} canciones a…") },
            confirmButton = { TextButton(onClick = { showAddTo = false }) { Text("Cerrar") } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MenuItem("Nueva lista…") { pendingAdd = selected.toList(); showAddTo = false; showNewPl = true }
                    MenuItem("Favoritas") { Store.addFavs(selected.toList()); selected = emptySet(); showAddTo = false }
                    Store.playlists.keys.forEach { n ->
                        MenuItem(n) { Store.addMany(n, selected.toList()); selected = emptySet(); showAddTo = false }
                    }
                }
            }
        )
    }
    if (showNewPl) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPl = false },
            title = { Text("Nueva lista") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Nombre") }) },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        Store.create(n)
                        if (pendingAdd.isNotEmpty()) { Store.addMany(n, pendingAdd); selected = emptySet() }
                    }
                    pendingAdd = emptyList(); showNewPl = false
                }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { pendingAdd = emptyList(); showNewPl = false }) { Text("Cancelar") } }
        )
    }
    confirmDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("¿Eliminar la lista “$n”?") },
            text = { Text("Las canciones no se borran del teléfono.") },
            confirmButton = { TextButton(onClick = { Store.delete(n); confirmDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } }
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

