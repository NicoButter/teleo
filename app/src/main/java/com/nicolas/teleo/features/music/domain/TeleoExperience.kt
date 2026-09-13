package com.nicolas.teleo.features.music.domain

enum class TeleoChannelQuality { RELIABLE, UNRELIABLE, UNKNOWN }

data class TeleoDrumImpulse(
    val startMs: Long,
    val durationMs: Long,
    val type: MusicEventType,
    val intensity: Float
)

data class TeleoBassNote(
    val startMs: Long,
    val endMs: Long,
    val midi: Int?,
    val pitch: Float?,
    val intensity: Float,
    val confidence: Float
)

data class TeleoVocalFrame(
    val timestampMs: Long,
    val presence: Float,
    val intensity: Float,
    val pitchHz: Float?,
    val pitchNormalized: Float?,
    val pitchConfidence: Float,
    val spectralBrightness: Float
)

data class TeleoAmbientFrame(
    val timestampMs: Long,
    val lowEnergy: Float,
    val midEnergy: Float,
    val highEnergy: Float,
    val overallEnergy: Float
)

/** Validated protocol-domain data. It deliberately contains no renderer or JSON types. */
data class TeleoExperience(
    val drums: List<TeleoDrumImpulse> = emptyList(),
    val bassNotes: List<TeleoBassNote> = emptyList(),
    val vocalFrames: List<TeleoVocalFrame> = emptyList(),
    val visemes: List<VocalVisemeEvent> = emptyList(),
    val ambientFrames: List<TeleoAmbientFrame> = emptyList(),
    val timelineEvents: List<MusicEvent> = emptyList(),
    val channelQuality: Map<String, TeleoChannelQuality> = emptyMap()
) {
    fun isReliable(channel: String): Boolean {
        val quality = channelQuality[channel]
            ?: channelQuality["$channel.events"]
            ?: channelQuality["$channel.notes"]
            ?: channelQuality["$channel.frames"]
            ?: TeleoChannelQuality.RELIABLE
        return quality == TeleoChannelQuality.RELIABLE
    }
}
