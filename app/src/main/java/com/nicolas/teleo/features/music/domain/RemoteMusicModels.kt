package com.nicolas.teleo.features.music.domain

/** Provenance is explicit so a remote experience can never silently fall back to mock data. */
enum class MusicExperienceSource { MOCK, REMOTE }

enum class ExperienceQuality(val label: String) {
    AUTOMATIC("Automatic"),
    HUMAN_REVIEWED("Human Reviewed"),
    TELEO_MASTER("Teleo Master");

    companion object {
        fun fromWire(value: String?): ExperienceQuality = entries.firstOrNull {
            it.name.equals(value?.replace('-', '_')?.replace(' ', '_'), ignoreCase = true)
        } ?: AUTOMATIC
    }
}

data class SourceHash(val algorithm: String, val value: String) {
    init {
        require(algorithm.isNotBlank() && value.isNotBlank())
    }

    val isSha256: Boolean get() = algorithm.equals("sha256", ignoreCase = true)
}

data class TeleoMusicCatalog(
    val tracks: List<TeleoMusicCatalogTrack>,
    val schemaVersion: Int = 1
)

data class TeleoMusicCatalogTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val experienceVersion: Int,
    val quality: ExperienceQuality,
    val experiencePath: String,
    val sourceHash: SourceHash? = null
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && durationMs >= 0)
        require(experienceVersion > 0 && experiencePath.isNotBlank())
    }
}

enum class VocalVisemeShape { A, B, C, D, E, F, G, H, X;
    companion object {
        fun fromWire(value: String?): VocalVisemeShape = entries.firstOrNull {
            it.name.equals(value?.trim(), ignoreCase = true)
        } ?: X
    }
}

/** Rhubarb visual code, intentionally not a phoneme label. */
data class VocalVisemeEvent(
    val startMs: Long,
    val endMs: Long,
    val shape: VocalVisemeShape,
    val intensity: Float = 1f
) {
    init {
        require(startMs >= 0 && endMs >= startMs && intensity in 0f..1f)
    }
}

data class RemoteMusicExperience(
    val catalogTrack: TeleoMusicCatalogTrack,
    val schemaVersion: Int,
    val timeline: MusicTimeline,
    val sourceUrl: String,
    val downloadedBytes: Long,
    val cacheStatus: RemoteCacheStatus
)

enum class RemoteCacheStatus { HIT, MISS, UPDATED, OFFLINE }

data class RemotePlaybackDebugInfo(
    val trackId: String,
    val schemaVersion: Int,
    val experienceVersion: Int,
    val sourceUrl: String,
    val cacheStatus: RemoteCacheStatus,
    val downloadedBytes: Long,
    val audioDurationMs: Long?,
    val expectedDurationMs: Long,
    val audioHashStatus: AudioHashStatus
)

enum class AudioHashStatus { MATCH, MISMATCH, NOT_AVAILABLE }

object AudioValidationConfig {
    const val DURATION_TOLERANCE_MS = 1_500L
}

sealed interface AudioValidationResult {
    data class Valid(val hashStatus: AudioHashStatus) : AudioValidationResult
    data class Mismatch(val message: String) : AudioValidationResult
}

fun validateAudioDuration(expectedMs: Long, actualMs: Long?): AudioValidationResult {
    if (actualMs == null || actualMs <= 0L || kotlin.math.abs(expectedMs - actualMs) > AudioValidationConfig.DURATION_TOLERANCE_MS) {
        return AudioValidationResult.Mismatch("El audio seleccionado no corresponde a esta experiencia.")
    }
    return AudioValidationResult.Valid(AudioHashStatus.NOT_AVAILABLE)
}
