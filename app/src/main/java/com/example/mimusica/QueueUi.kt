package com.example.mimusica

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import kotlin.math.roundToInt

/** Cola reordenable: mantén pulsada una canción y arrástrala; al soltar se mueve a esa posición. */
@Composable
fun QueueList(queue: List<MediaItem>, curIndex: Int, controller: MediaController?) {
    val state = rememberLazyListState()
    var dragIdx by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun itemHeight(): Float =
        (state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == dragIdx }?.size ?: 1).toFloat()

    fun targetIndex(): Int =
        if (dragIdx < 0) -1 else (dragIdx + (dragOffset / itemHeight()).roundToInt()).coerceIn(0, queue.lastIndex)

    Column(Modifier.fillMaxSize()) {
        Text(
            "Mantén pulsada una canción y arrástrala para moverla.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            itemsIndexed(queue) { i, item ->
                val dragging = i == dragIdx
                val isTarget = dragIdx >= 0 && !dragging && i == targetIndex()
                Box(
                    Modifier.zIndex(if (dragging) 1f else 0f).pointerInput(i, queue.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragIdx = i; dragOffset = 0f },
                            onDrag = { change, delta -> change.consume(); dragOffset += delta.y },
                            onDragEnd = {
                                val t = targetIndex()
                                if (t >= 0 && t != dragIdx) controller?.moveMediaItem(dragIdx, t)
                                dragIdx = -1; dragOffset = 0f
                            },
                            onDragCancel = { dragIdx = -1; dragOffset = 0f }
                        )
                    }
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(
                            containerColor = when {
                                dragging -> MaterialTheme.colorScheme.secondaryContainer
                                isTarget -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> Color.Transparent
                            }
                        ),
                        leadingContent = { Art(item.mediaId.toLongOrNull()?.let { id -> uriOf(id) }, Modifier.size(48.dp)) },
                        headlineContent = {
                            Text(
                                item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (i == curIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        supportingContent = {
                            Text(item.mediaMetadata.artist?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.DragHandle, "Mover")
                                IconButton(onClick = { controller?.removeMediaItem(i) }, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Rounded.Close, "Quitar")
                                }
                            }
                        },
                        modifier = Modifier
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                            .clickable { if (dragIdx < 0) controller?.let { c -> c.seekToDefaultPosition(i); c.play() } }
                    )
                }
            }
        }
    }
}
