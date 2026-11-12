package com.example.mimusica

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

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

/** Anuncia siempre la orden de saltar dentro de la pista, para que el control del sistema no la vea apagarse entre canciones. */
class SeekablePlayer(p: Player) : ForwardingPlayer(p) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || super.isCommandAvailable(command)
}

private val CMD_SHUFFLE = SessionCommand("com.example.mimusica.SHUFFLE", Bundle.EMPTY)
private val CMD_CLOSE = SessionCommand("com.example.mimusica.CLOSE", Bundle.EMPTY)

/** Botones extra de la tarjeta del sistema: aleatorio (izquierda) y cerrar ✕ (derecha). */
private fun cardLayout(shuffleOn: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
    CommandButton.Builder().setDisplayName("Aleatorio")
        .setIconResId(if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
        .setSessionCommand(CMD_SHUFFLE).build(),
    CommandButton.Builder().setDisplayName("Cerrar")
        .setIconResId(R.drawable.ic_close_card)
        .setSessionCommand(CMD_CLOSE).build()
)

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(CMD_SHUFFLE).add(CMD_CLOSE).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(cmds)
                .setCustomLayout(cardLayout(session.player.shuffleModeEnabled))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SHUFFLE.customAction -> session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                CMD_CLOSE.customAction -> pauseAllPlayersAndStopSelf()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Dsp.init(this)
        AppPrefs.init(this)
        DeviceProfiles.start(this)
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
        FadeTicker.attach(player)
        ReplayGain.attach(player)
        SessionLog.attach(player)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, SeekablePlayer(player))
            .setSessionActivity(openApp)
            .setCallback(sessionCallback)
            .setCustomLayout(cardLayout(false))
            .build()
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                session?.setCustomLayout(cardLayout(shuffleModeEnabled))
            }
        })
        session?.let { SessionLog.attachSystemView(this, it) }
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
