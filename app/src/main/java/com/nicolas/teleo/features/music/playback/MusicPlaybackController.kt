package com.nicolas.teleo.features.music.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.net.toUri
import com.nicolas.teleo.features.music.domain.MusicPlaybackState
import com.nicolas.teleo.features.music.domain.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface MusicPlaybackController {
    val playbackState: StateFlow<MusicPlaybackState>
    fun load(track: MusicTrack)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

class ExoPlayerMusicPlaybackController(context: Context) : MusicPlaybackController {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutablePlaybackState = MutableStateFlow(MusicPlaybackState())
    override val playbackState: StateFlow<MusicPlaybackState> = mutablePlaybackState.asStateFlow()
    private var positionJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = publishState()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishState()
                if (isPlaying) startPositionUpdates() else positionJob?.cancel()
            }
            override fun onPlayerError(error: PlaybackException) {
                publishState(error.localizedMessage ?: "No se pudo reproducir esta canción")
            }
        })
    }

    override fun load(track: MusicTrack) {
        player.setMediaItem(MediaItem.fromUri(track.uri.toUri()))
        player.prepare()
        publishState()
    }

    override fun play() {
        player.play()
        publishState()
    }

    override fun pause() {
        player.pause()
        publishState()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
        publishState()
    }

    override fun release() {
        positionJob?.cancel()
        player.release()
        scope.cancel()
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive && player.isPlaying) {
                publishState()
                delay(50)
            }
        }
    }

    private fun publishState(error: String? = null) {
        val duration = player.duration.takeUnless { it == C.TIME_UNSET || it < 0 } ?: 0L
        mutablePlaybackState.value = MusicPlaybackState(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            isPlaying = player.isPlaying,
            isReady = player.playbackState == Player.STATE_READY,
            errorMessage = error
        )
    }
}
