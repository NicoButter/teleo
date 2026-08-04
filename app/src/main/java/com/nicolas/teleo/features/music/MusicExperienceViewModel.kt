package com.nicolas.teleo.features.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nicolas.teleo.features.music.data.FileMusicTimelineRepository
import com.nicolas.teleo.features.music.data.MockMusicAnalyzer
import com.nicolas.teleo.features.music.data.MusicTimelineRepository
import com.nicolas.teleo.features.music.domain.HapticIntensity
import com.nicolas.teleo.features.music.domain.HapticSettings
import com.nicolas.teleo.features.music.domain.MusicAnalyzer
import com.nicolas.teleo.features.music.domain.MusicBufferConfig
import com.nicolas.teleo.features.music.domain.MusicExperienceState
import com.nicolas.teleo.features.music.domain.MusicPlaybackState
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.MusicTrack
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.LyricsDisplayMode
import com.nicolas.teleo.features.music.domain.VisualPreset
import com.nicolas.teleo.features.music.domain.VisualQuality
import com.nicolas.teleo.features.music.domain.adjustedTimelinePosition
import com.nicolas.teleo.features.music.domain.eventsBetween
import com.nicolas.teleo.features.music.haptics.AndroidHapticMusicEngine
import com.nicolas.teleo.features.music.haptics.HapticMusicEngine
import com.nicolas.teleo.features.music.playback.ExoPlayerMusicPlaybackController
import com.nicolas.teleo.features.music.playback.MusicPlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MusicExperienceViewModel(application: Application) : AndroidViewModel(application) {
    private val analyzer: MusicAnalyzer = MockMusicAnalyzer()
    private val repository: MusicTimelineRepository = FileMusicTimelineRepository(application)
    private val playbackController: MusicPlaybackController = ExoPlayerMusicPlaybackController(application)
    private val hapticEngine: HapticMusicEngine = AndroidHapticMusicEngine(application)

    private val mutableState = MutableStateFlow<MusicExperienceState>(MusicExperienceState.Idle)
    val state: StateFlow<MusicExperienceState> = mutableState.asStateFlow()

    private var preparationJob: Job? = null
    private var bufferJob: Job? = null
    private var currentTrack: MusicTrack? = null
    private var currentTimeline: MusicTimeline? = null
    private var hapticSettings = HapticSettings()
    private var visualSettings = MusicVisualSettings()
    private var bufferedUntilMs = 0L
    private var lastHapticPositionMs = 0L
    private var syncOffsetMs = 0

    init {
        hapticEngine.updateSettings(hapticSettings)
        viewModelScope.launch {
            playbackController.playbackState.collectLatest(::onPlaybackState)
        }
    }

    fun selectTrack(track: MusicTrack) {
        preparationJob?.cancel()
        bufferJob?.cancel()
        playbackController.pause()
        hapticEngine.stop()
        currentTrack = track
        currentTimeline = null
        bufferedUntilMs = 0
        mutableState.value = MusicExperienceState.TrackSelected(track)
    }

    fun prepare() {
        val track = currentTrack ?: return
        preparationJob?.cancel()
        preparationJob = viewModelScope.launch {
            try {
                val cached = repository.findByTrackHash(track.id)
                    ?.takeIf { it.analysisVersion >= 2 && it.featureFrames.isNotEmpty() }
                val timeline = cached ?: analyzer.analyze(track) { progress ->
                    bufferedUntilMs = progress.bufferedUntilMs
                    mutableState.value = MusicExperienceState.Analyzing(track, progress)
                }.also { repository.save(track.id, it) }
                currentTimeline = timeline
                bufferedUntilMs = if (cached != null) timeline.durationMs else {
                    minOf(timeline.durationMs, maxOf(bufferedUntilMs, MusicBufferConfig.INITIAL_BUFFER_MS))
                }
                runCountdownAndPlay(track, timeline, cached != null)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancellation is a normal result of leaving the preparation screen.
            } catch (error: Exception) {
                mutableState.value = MusicExperienceState.Error(
                    error.localizedMessage ?: "No se pudo preparar la experiencia.",
                    recoverable = true
                )
            }
        }
    }

    fun cancelPreparation() {
        preparationJob?.cancel()
        bufferJob?.cancel()
        hapticEngine.stop()
        currentTrack?.let { mutableState.value = MusicExperienceState.TrackSelected(it) }
            ?: run { mutableState.value = MusicExperienceState.Idle }
    }

    fun togglePlayPause() {
        val playing = mutableState.value as? MusicExperienceState.Playing ?: return
        if (playing.isPlaying) {
            playbackController.pause()
            hapticEngine.stop()
        } else {
            lastHapticPositionMs = adjustedTimelinePosition(playing.playbackPositionMs, syncOffsetMs)
            playbackController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        lastHapticPositionMs = adjustedTimelinePosition(positionMs, syncOffsetMs)
        hapticEngine.stop()
        playbackController.seekTo(positionMs)
    }

    fun restart() = seekTo(0)

    fun playbackPositionNow(): Long = playbackController.currentPositionMs()

    fun setHapticsEnabled(enabled: Boolean) {
        updateHaptics(hapticSettings.copy(enabled = enabled))
    }

    fun setHapticIntensity(intensity: HapticIntensity) {
        updateHaptics(hapticSettings.copy(intensityMultiplier = intensity.multiplier))
    }

    fun setVisualPreset(preset: VisualPreset) = updateVisuals(visualSettings.copy(preset = preset))

    fun setVisualQuality(quality: VisualQuality) = updateVisuals(visualSettings.copy(quality = quality))

    fun setReducedMotion(enabled: Boolean) = updateVisuals(
        visualSettings.copy(
            reducedMotion = enabled,
            motionIntensity = if (enabled) 0.25f else 1f,
            particleIntensity = if (enabled) minOf(visualSettings.particleIntensity, 0.55f) else 1f
        )
    )

    fun setFlashesEnabled(enabled: Boolean) = updateVisuals(visualSettings.copy(flashesEnabled = enabled))

    fun setStableLyrics(enabled: Boolean) = updateVisuals(visualSettings.copy(stableLyrics = enabled))

    fun setIntenseVisualWarningEnabled(enabled: Boolean) =
        updateVisuals(visualSettings.copy(intenseVisualWarningEnabled = enabled))

    fun setLyricsDisplayMode(mode: LyricsDisplayMode) = updateVisuals(visualSettings.copy(lyricsDisplayMode = mode))

    fun setLyricsTextScale(scale: Float) = updateVisuals(visualSettings.copy(lyricsTextScale = scale.coerceIn(0.8f, 1.5f)))

    fun setSyncOffset(offsetMs: Int) {
        syncOffsetMs = offsetMs.coerceIn(-250, 250)
        lastHapticPositionMs = adjustedTimelinePosition(
            (mutableState.value as? MusicExperienceState.Playing)?.playbackPositionMs ?: 0,
            syncOffsetMs
        )
        refreshPlayingState(playbackController.playbackState.value)
    }

    fun resetForExit() {
        preparationJob?.cancel()
        bufferJob?.cancel()
        playbackController.pause()
        playbackController.seekTo(0)
        hapticEngine.stop()
        currentTrack = null
        currentTimeline = null
        bufferedUntilMs = 0
        mutableState.value = MusicExperienceState.Idle
    }

    private suspend fun runCountdownAndPlay(track: MusicTrack, timeline: MusicTimeline, fullyBuffered: Boolean) {
        for (seconds in 3 downTo 1) {
            mutableState.value = MusicExperienceState.Countdown(seconds)
            delay(1_000)
        }
        playbackController.load(track)
        lastHapticPositionMs = 0
        mutableState.value = playingState(track, timeline, playbackController.playbackState.value)
        playbackController.play()
        if (!fullyBuffered) startProgressiveBuffer(timeline.durationMs)
    }

    private fun startProgressiveBuffer(durationMs: Long) {
        bufferJob?.cancel()
        bufferJob = viewModelScope.launch {
            while (bufferedUntilMs < durationMs) {
                delay(2_000)
                bufferedUntilMs = minOf(
                    durationMs,
                    bufferedUntilMs + MusicBufferConfig.ANALYSIS_WINDOW_MS - MusicBufferConfig.OVERLAP_MS
                )
                refreshPlayingState(playbackController.playbackState.value)
            }
        }
    }

    private fun onPlaybackState(playback: MusicPlaybackState) {
        if (playback.errorMessage != null) {
            hapticEngine.stop()
            mutableState.value = MusicExperienceState.Error(playback.errorMessage, recoverable = true)
            return
        }
        val playing = mutableState.value as? MusicExperienceState.Playing ?: return
        val timelinePosition = adjustedTimelinePosition(playback.positionMs, syncOffsetMs)
        if (playback.isPlaying) {
            val distance = timelinePosition - lastHapticPositionMs
            if (distance in 0..500) {
                playing.timeline.eventsBetween(lastHapticPositionMs, timelinePosition).forEach(hapticEngine::playEvent)
            }
            lastHapticPositionMs = timelinePosition
        } else {
            hapticEngine.stop()
            lastHapticPositionMs = timelinePosition
        }
        mutableState.value = playingState(playing.track, playing.timeline, playback)
    }

    private fun refreshPlayingState(playback: MusicPlaybackState) {
        val playing = mutableState.value as? MusicExperienceState.Playing ?: return
        mutableState.value = playingState(playing.track, playing.timeline, playback)
    }

    private fun playingState(
        track: MusicTrack,
        timeline: MusicTimeline,
        playback: MusicPlaybackState
    ): MusicExperienceState.Playing {
        val remainingBuffer = bufferedUntilMs - playback.positionMs
        return MusicExperienceState.Playing(
            track = track,
            timeline = timeline,
            playbackPositionMs = playback.positionMs.coerceIn(0, timeline.durationMs),
            bufferedUntilMs = bufferedUntilMs,
            isPlaying = playback.isPlaying,
            hapticSettings = hapticSettings,
            visualSettings = visualSettings,
            syncOffsetMs = syncOffsetMs,
            isRecoveringBuffer = bufferedUntilMs < timeline.durationMs && remainingBuffer < MusicBufferConfig.MINIMUM_SAFE_BUFFER_MS
        )
    }

    private fun updateHaptics(settings: HapticSettings) {
        hapticSettings = settings
        hapticEngine.updateSettings(settings)
        refreshPlayingState(playbackController.playbackState.value)
    }

    private fun updateVisuals(settings: MusicVisualSettings) {
        visualSettings = settings
        refreshPlayingState(playbackController.playbackState.value)
    }

    override fun onCleared() {
        hapticEngine.stop()
        playbackController.release()
        super.onCleared()
    }
}
