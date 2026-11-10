package com.example.mimusica

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** Guarda cola, canción y posición para retomar la reproducción si Android cierra el servicio. */
object Resume {
    class State(val ids: List<String>, val index: Int, val pos: Long, val shuffle: Boolean, val repeat: Int)

    private val handler = Handler(Looper.getMainLooper())

    fun attach(ctx: Context, p: Player) {
        val sp = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)
        fun saveQueue() {
            val ids = List(p.mediaItemCount) { p.getMediaItemAt(it).mediaId }.joinToString(",")
            sp.edit().putString("ids", ids).apply()
        }
        fun saveState() {
            if (p.mediaItemCount == 0) return
            sp.edit().putInt("index", p.currentMediaItemIndex).putLong("pos", p.currentPosition)
                .putBoolean("shuffle", p.shuffleModeEnabled).putInt("repeat", p.repeatMode).apply()
        }
        val tick = object : Runnable {
            override fun run() { saveState(); if (p.isPlaying) handler.postDelayed(this, 10_000) }
        }
        p.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) { saveQueue(); saveState() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { saveState() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveState(); handler.removeCallbacks(tick)
                if (isPlaying) handler.postDelayed(tick, 10_000)
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { saveState() }
            override fun onRepeatModeChanged(repeatMode: Int) { saveState() }
        })
    }

    fun load(ctx: Context): State? {
        val sp = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)
        val ids = (sp.getString("ids", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return State(ids, sp.getInt("index", 0), sp.getLong("pos", 0L), sp.getBoolean("shuffle", false), sp.getInt("repeat", 0))
    }
}
