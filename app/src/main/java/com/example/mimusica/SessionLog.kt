package com.example.mimusica

import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro en memoria de lo que le pasa al reproductor (saltos, estados, errores) para diagnosticar. */
object SessionLog {
    val lines = mutableStateListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(msg: String) {
        lines.add(fmt.format(Date()) + "  " + msg)
        while (lines.size > 120) lines.removeAt(0)
    }

    fun clear() = lines.clear()

    fun attach(p: Player) {
        var seekAt = 0L
        p.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                add("comandos: buscar dentro de la pista = ${availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                add("pista: ${mediaItem?.mediaMetadata?.title} · buscable=${p.isCurrentMediaItemSeekable} · dur=${p.duration / 1000}s")
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekAt = SystemClock.elapsedRealtime()
                    add("SALTO ${oldPosition.positionMs / 1000}s -> ${newPosition.positionMs / 1000}s")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val n = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                val extra = if (playbackState == Player.STATE_READY && seekAt > 0)
                    " (${SystemClock.elapsedRealtime() - seekAt} ms tras el último salto)" else ""
                add("estado $n$extra")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) { add("reproduciendo=$isPlaying") }

            override fun onPlayerError(error: PlaybackException) { add("ERROR ${error.errorCodeName}: ${error.message}") }
        })
    }
}
