package com.nicolas.teleo.features.music.domain

data class MusicFeatureFrame(
    val timestampMs: Long,
    val beatPhase: Float,
    val beatStrength: Float,
    val lowEnergy: Float,
    val midEnergy: Float,
    val highEnergy: Float,
    val vocalPresence: Float,
    val melodicPitchNormalized: Float?,
    val spectralBrightness: Float,
    val overallEnergy: Float,
    val sectionId: String?
) {
    init {
        require(timestampMs >= 0) { "Feature frame timestamp cannot be negative" }
        listOf(
            beatPhase,
            beatStrength,
            lowEnergy,
            midEnergy,
            highEnergy,
            vocalPresence,
            spectralBrightness,
            overallEnergy
        ).forEach { require(it in 0f..1f) { "Music features must be normalized" } }
        require(melodicPitchNormalized == null || melodicPitchNormalized in 0f..1f) {
            "Normalized pitch must be null or between zero and one"
        }
    }

    companion object {
        val EMPTY = MusicFeatureFrame(0, 0f, 0f, 0f, 0f, 0f, 0f, null, 0f, 0f, null)
    }
}

interface MusicFeatureInterpolator {
    fun interpolate(frames: List<MusicFeatureFrame>, positionMs: Long): MusicFeatureFrame
}

enum class VisualPreset(val label: String) {
    PARTICLES("Partículas"),
    WAVES("Ondas"),
    LANES("Carriles"),
    MINIMAL("Minimal"),
    IMMERSIVE("Inmersivo")
}

enum class VisualQuality(val label: String, val particleLimit: Int) {
    AUTO("Auto", 350),
    LOW("Baja", 80),
    MEDIUM("Media", 180),
    HIGH("Alta · 4K", 350)
}

enum class LyricsDisplayMode(val label: String) {
    ORIGINAL("Original"),
    TRANSLATED("Español"),
    ORIGINAL_AND_TRANSLATED("Ambas"),
    HIDDEN("Oculta")
}

data class MusicVisualSettings(
    val preset: VisualPreset = VisualPreset.PARTICLES,
    val quality: VisualQuality = VisualQuality.AUTO,
    val particleIntensity: Float = 1f,
    val motionIntensity: Float = 1f,
    val flashesEnabled: Boolean = true,
    val limitBrightnessChanges: Boolean = true,
    val lyricsDisplayMode: LyricsDisplayMode = LyricsDisplayMode.ORIGINAL_AND_TRANSLATED,
    val targetTranslationLanguage: String = "es",
    val stableLyrics: Boolean = false,
    val reducedMotion: Boolean = false,
    val intenseVisualWarningEnabled: Boolean = true,
    val lyricsTextScale: Float = 1f
) {
    init {
        require(particleIntensity in 0f..1.5f) { "Particle intensity is out of range" }
        require(motionIntensity in 0f..1.5f) { "Motion intensity is out of range" }
        require(lyricsTextScale in 0.8f..1.5f) { "Lyrics text scale is out of range" }
        require(targetTranslationLanguage.isNotBlank()) { "Target language cannot be blank" }
    }
}

data class MusicVisualFrame(
    val playbackPositionMs: Long,
    val deltaTimeSeconds: Float,
    val features: MusicFeatureFrame,
    val activeEvents: List<MusicEvent>,
    val settings: MusicVisualSettings
)

data class MusicFrameMetrics(
    val averageFrameTimeMs: Float = 0f,
    val slowFrameCount: Int = 0,
    val effectiveQuality: VisualQuality = VisualQuality.MEDIUM
)
