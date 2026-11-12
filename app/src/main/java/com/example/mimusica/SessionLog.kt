package com.example.mimusica

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro en memoria de lo que le pasa al reproductor (saltos, estados, errores) para diagnosticar. */
object SessionLog {
    const val BUILD = "entrega 15"
    private var sys: android.media.session.MediaController? = null
    private var sysStatus = "sin iniciar"
    val lines = mutableStateListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(msg: String) {
        lines.add(fmt.format(Date()) + "  " + msg)
        while (lines.size > 120) lines.removeAt(0)
    }

    fun clear() = lines.clear()

    /** Observa la sesión como lo hace el sistema (tarjeta de la notificación) y registra lo que recibe. */
    fun attachSystemView(ctx: Context, session: MediaSession) {
        runCatching {
            val c = android.media.session.MediaController(ctx, session.platformToken)
            sys = c
            sysStatus = "observador activo"
            var last = ""
            c.registerCallback(object : android.media.session.MediaController.Callback() {
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    if (state == null) return
                    val seek = (state.actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
                    val msg = "SISTEMA ve: estado=${state.state} salto=${if (seek) "SI" else "NO"} velocidad=${state.playbackSpeed}"
                    if (msg != last) { last = msg; add(msg) }
                }

                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                    add("SISTEMA ve: duración=${metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)} ms")
                }
            })
        }.onFailure { sysStatus = "ERROR: ${it.message}"; add("no pude observar la sesión: ${it.message}") }
    }

    /** Lo que el sistema ve de la sesión en este instante, más las notificaciones de la app. */
    fun systemSnapshot(ctx: Context): String {
        val sb = StringBuilder()
        val c = sys
        if (c == null) {
            sb.appendLine("Controlador del sistema: NO DISPONIBLE ($sysStatus)")
        } else {
            val st = c.playbackState
            val md = c.metadata
            if (st == null) {
                sb.appendLine("PlaybackState: null")
            } else {
                val names = listOf(
                    1L to "STOP", 2L to "PAUSE", 4L to "PLAY", 8L to "REWIND", 16L to "ANTERIOR",
                    32L to "SIGUIENTE", 64L to "FAST_FORWARD", 256L to "SEEK_TO", 512L to "PLAY_PAUSE"
                )
                val acts = names.filter { (st.actions and it.first) != 0L }.joinToString(",") { it.second }
                sb.appendLine("estado=${st.state} posición=${st.position} velocidad=${st.playbackSpeed} buffer=${st.bufferedPosition}")
                sb.appendLine("acciones=$acts")
            }
            sb.appendLine("duración en metadatos=${md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)} título=${md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}")
        }
        runCatching {
            val ns = ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            sb.appendLine("Notificaciones activas de la app: ${ns.size}")
            ns.forEach { n ->
                val ex = n.notification.extras
                sb.appendLine(" · id=${n.id} plantilla=${ex.getString("android.template")} sesión=${ex.containsKey("android.mediaSession")} acciones=${n.notification.actions?.size ?: 0} ongoing=${n.isOngoing}")
            }
        }.onFailure { sb.appendLine("Notificaciones: error ${it.message}") }
        return sb.toString()
    }

    /** Pide un salto por el mismo camino que usaría la tarjeta del sistema. */
    fun testSeek(offsetMs: Long) {
        val c = sys
        val st = c?.playbackState
        if (c == null || st == null) { add("PRUEBA: no hay controlador del sistema"); return }
        val pos = st.position + if (st.state == android.media.session.PlaybackState.STATE_PLAYING)
            ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
        val target = (pos + offsetMs).coerceAtLeast(0L)
        c.transportControls.seekTo(target)
        add("PRUEBA: pedí saltar a ${target / 1000}s como lo haría el sistema")
    }

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
