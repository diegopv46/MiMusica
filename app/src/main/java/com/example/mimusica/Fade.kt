package com.example.mimusica

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Copia la posición/duración del reproductor para que el motor de audio calcule el fundido entre canciones. */
object FadeTicker {
    private val handler = Handler(Looper.getMainLooper())

    fun attach(p: Player) {
        fun update() {
            Dsp.posMs = p.currentPosition
            Dsp.durMs = if (p.duration > 0) p.duration else 0L
            Dsp.hasNext = p.hasNextMediaItem()
        }
        val tick = object : Runnable {
            override fun run() {
                if (Dsp.fadeMs > 0) update()
                if (p.isPlaying) handler.postDelayed(this, 40)
            }
        }
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(tick)
                if (isPlaying) handler.post(tick)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (Dsp.fadeMs > 0) update() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (Dsp.fadeMs > 0) update()
            }
        })
    }
}
