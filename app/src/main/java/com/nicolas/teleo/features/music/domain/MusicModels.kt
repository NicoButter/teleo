package com.nicolas.teleo.features.music.domain

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val uri: String,
    val durationMs: Long?
) {
    init {
        require(id.isNotBlank()) { "Track id cannot be blank" }
        require(title.isNotBlank()) { "Track title cannot be blank" }
        require(uri.isNotBlank()) { "Track URI cannot be blank" }
        require(durationMs == null || durationMs >= 0) { "Track duration cannot be negative" }
    }
}

enum class MusicEventType {
    KICK,
    SNARE,
    HI_HAT,
    TOM,
    CYMBAL,
    BASS,
    GUITAR,
    PIANO,
    OTHER,
    VOCAL_START,
    VOCAL_END,
    MELODY_UP,
    MELODY_DOWN,
    SECTION_START,
    SECTION_END
}

data class MusicEvent(
    val timestampMs: Long,
    val durationMs: Long,
    val type: MusicEventType,
    val intensity: Float,
    val label: String? = null
) {
    init {
        require(timestampMs >= 0) { "Event timestamp cannot be negative" }
        require(durationMs >= 0) { "Event duration cannot be negative" }
        require(intensity in 0f..1f) { "Event intensity must be between 0 and 1" }
    }
}

data class MusicTimeline(
    val trackId: String,
    val durationMs: Long,
    val bpm: Float?,
    val analysisVersion: Int,
    val events: List<MusicEvent>,
    val lyrics: List<TimedLyricLine>,
    val featureFrames: List<MusicFeatureFrame> = emptyList(),
    val visemes: List<VocalVisemeEvent> = emptyList(),
    /** Empty means that this experience intentionally has no haptic channel. */
    val hapticEvents: List<MusicEvent> = events
) {
    init {
        require(trackId.isNotBlank()) { "Timeline track id cannot be blank" }
        require(durationMs >= 0) { "Timeline duration cannot be negative" }
        require(bpm == null || bpm > 0f) { "BPM must be positive" }
        require(analysisVersion > 0) { "Analysis version must be positive" }
        require(events.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs }) {
            "Timeline events must be ordered chronologically"
        }
        require(lyrics.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Timeline lyrics must be ordered chronologically"
        }
        require(featureFrames.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs }) {
            "Music feature frames must be ordered chronologically"
        }
        require(visemes.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Visemes must be ordered chronologically"
        }
        require(hapticEvents.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs }) {
            "Haptic events must be ordered chronologically"
        }
    }
}

enum class MusicAnalysisStage {
    PREPARING,
    READING_AUDIO,
    DETECTING_RHYTHM,
    PREPARING_VISUALS,
    PREPARING_HAPTICS,
    READY,
    FAILED
}

data class MusicAnalysisProgress(
    val stage: MusicAnalysisStage,
    val percentage: Int,
    val bufferedUntilMs: Long,
    val message: String
) {
    init {
        require(percentage in 0..100) { "Progress must be between 0 and 100" }
        require(bufferedUntilMs >= 0) { "Buffered position cannot be negative" }
    }
}

interface MusicAnalyzer {
    suspend fun analyze(
        track: MusicTrack,
        onProgress: (MusicAnalysisProgress) -> Unit
    ): MusicTimeline
}

data class MusicPlaybackState(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val errorMessage: String? = null
)

data class HapticSettings(
    val enabled: Boolean = true,
    val intensityMultiplier: Float = 1f,
    val kickEnabled: Boolean = true,
    val snareEnabled: Boolean = true,
    val hiHatEnabled: Boolean = false,
    val bassEnabled: Boolean = false
) {
    init {
        require(intensityMultiplier in 0f..1.5f) { "Haptic intensity multiplier is out of range" }
    }
}

enum class HapticIntensity(val multiplier: Float, val label: String) {
    SOFT(0.55f, "Suave"),
    MEDIUM(1f, "Media"),
    STRONG(1.35f, "Fuerte")
}

sealed interface MusicExperienceState {
    data object Idle : MusicExperienceState
    data object LoadingCatalog : MusicExperienceState
    data class CatalogReady(
        val catalog: TeleoMusicCatalog,
        val isOfflineCache: Boolean,
        val warning: String? = null
    ) : MusicExperienceState
    data class DownloadingExperience(val track: TeleoMusicCatalogTrack) : MusicExperienceState
    data class AwaitingAudio(val experience: RemoteMusicExperience, val validationError: String? = null) : MusicExperienceState
    data class ValidatingAudio(val experience: RemoteMusicExperience) : MusicExperienceState
    data class RemoteReady(val track: MusicTrack, val experience: RemoteMusicExperience) : MusicExperienceState
    data class TrackSelected(val track: MusicTrack) : MusicExperienceState
    data class Analyzing(val track: MusicTrack, val progress: MusicAnalysisProgress) : MusicExperienceState
    data class Countdown(val secondsRemaining: Int) : MusicExperienceState
    data class Playing(
        val track: MusicTrack,
        val timeline: MusicTimeline,
        val playbackPositionMs: Long,
        val bufferedUntilMs: Long,
        val isPlaying: Boolean,
        val hapticSettings: HapticSettings,
        val visualSettings: MusicVisualSettings,
        val syncOffsetMs: Int,
        val isRecoveringBuffer: Boolean,
        val source: MusicExperienceSource = MusicExperienceSource.MOCK,
        val remoteDebugInfo: RemotePlaybackDebugInfo? = null
    ) : MusicExperienceState
    data class Error(val message: String, val recoverable: Boolean) : MusicExperienceState
}

object MusicBufferConfig {
    const val INITIAL_BUFFER_MS = 10_000L
    const val MINIMUM_SAFE_BUFFER_MS = 5_000L
    const val ANALYSIS_WINDOW_MS = 12_000L
    const val OVERLAP_MS = 4_000L
}
