package com.example.mimusica

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

object Eq {
    var equalizer: Equalizer? = null

    fun attach(ctx: Context, session: Int) {
        runCatching { equalizer?.release() }
        equalizer = null
        if (session == 0) return
        runCatching {
            val e = Equalizer(0, session)
            val p = ctx.getSharedPreferences("eq", Context.MODE_PRIVATE)
            val r = e.bandLevelRange
            for (b in 0 until e.numberOfBands.toInt()) {
                val v = p.getInt("b$b", 0).coerceIn(r[0].toInt(), r[1].toInt())
                e.setBandLevel(b.toShort(), v.toShort())
            }
            e.enabled = p.getBoolean("on", false)
            equalizer = e
        }
    }
}

object SleepTimer {
    var endAt by mutableLongStateOf(0L)
    var player: Player? = null
    private val handler = Handler(Looper.getMainLooper())
    private val task = Runnable { player?.pause(); endAt = 0L }

    fun set(minutes: Int) {
        handler.removeCallbacks(task)
        if (minutes <= 0) { endAt = 0L; return }
        endAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(task, minutes * 60_000L)
    }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Eq.attach(this@PlaybackService, audioSessionId)
            }
        })
        Eq.attach(this, player.audioSessionId)
        SleepTimer.player = player
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        SleepTimer.set(0)
        SleepTimer.player = null
        runCatching { Eq.equalizer?.release() }
        Eq.equalizer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
