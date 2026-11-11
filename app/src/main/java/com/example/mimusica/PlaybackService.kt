package com.example.mimusica

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
        Dsp.init(this)
        AppPrefs.init(this)
        UsbAudio.init(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? = DspAudioSink(
                context.applicationContext,
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            )
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        SleepTimer.player = player
        Resume.attach(this, player)
        ReplayGain.attach(player)
        SessionLog.attach(player)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (AppPrefs.stopOnClose) pauseAllPlayersAndStopSelf() else super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        SleepTimer.set(0)
        SleepTimer.player = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
