package com.seekaudio.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.seekaudio.ui.MainActivity
import com.seekaudio.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service that owns the ExoPlayer instance.
 * Runs independently of the UI — music keeps playing when app is backgrounded.
 * Media3 automatically handles the media notification.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val closeNotificationCommand = SessionCommand(CUSTOM_COMMAND_CLOSE_NOTIFICATION, Bundle.EMPTY)
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(closeNotificationCommand)
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            val mediaButtons = ImmutableList.of(
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.next))
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .setIconResId(R.drawable.ic_skip_next)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.close))
                    .setSessionCommand(closeNotificationCommand)
                    .setIconResId(R.drawable.ic_close)
                    .build(),
            )

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(mediaButtons)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ) = if (customCommand.customAction == CUSTOM_COMMAND_CLOSE_NOTIFICATION) {
            stopPlaybackAndService()
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        } else {
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Build ExoPlayer with audio focus handling
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true) // auto-pause on headphone unplug
            .build()

        // Session activity: tapping notification opens app
        val sessionIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionIntent)
            .setCallback(mediaSessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User explicitly dismissed the app task: fully stop playback/service.
        stopPlaybackAndService()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopPlaybackAndService() {
        mediaSession?.player?.run {
            stop()
            clearMediaItems()
        }
        stopSelf()
    }

    private companion object {
        const val CUSTOM_COMMAND_CLOSE_NOTIFICATION = "com.seekaudio.command.CLOSE_NOTIFICATION"
    }
}
