package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.TeleoAmbientFrame
import com.nicolas.teleo.features.music.domain.TeleoBassNote
import com.nicolas.teleo.features.music.domain.TeleoDrumImpulse
import com.nicolas.teleo.features.music.domain.TeleoExperience
import com.nicolas.teleo.features.music.domain.TeleoVocalFrame
import com.nicolas.teleo.features.music.domain.VocalVisemeShape

data class TeleoVocalVisualSignal(
    val presence: Float = 0f,
    val intensity: Float = 0f,
    val pitchNormalized: Float? = null,
    val spectralBrightness: Float = 0f
)
data class TeleoBassVisualSignal(val midi: Int?, val pitch: Float?, val intensity: Float, val confidence: Float)
data class TeleoAmbientVisualSignal(val lowEnergy: Float = 0f, val midEnergy: Float = 0f, val highEnergy: Float = 0f, val overallEnergy: Float = 0f)
data class TeleoVisualImpulse(val type: com.nicolas.teleo.features.music.domain.MusicEventType, val intensity: Float, val durationMs: Long)
data class TeleoVisualState(
    val positionMs: Long,
    val vocals: TeleoVocalVisualSignal = TeleoVocalVisualSignal(),
    val articulation: VocalVisemeShape? = null,
    val bass: TeleoBassVisualSignal? = null,
    val ambient: TeleoAmbientVisualSignal = TeleoAmbientVisualSignal(),
    val activeDrumImpulses: List<TeleoVisualImpulse> = emptyList(),
    val crossedEvents: List<MusicEvent> = emptyList()
) {
    fun asFeatureFrame(): MusicFeatureFrame = MusicFeatureFrame(
        timestampMs = positionMs,
        beatPhase = 0f,
        beatStrength = activeDrumImpulses.maxOfOrNull { it.intensity } ?: 0f,
        lowEnergy = ambient.lowEnergy,
        midEnergy = ambient.midEnergy,
        highEnergy = ambient.highEnergy,
        vocalPresence = vocals.presence,
        melodicPitchNormalized = vocals.pitchNormalized,
        spectralBrightness = vocals.spectralBrightness,
        overallEnergy = ambient.overallEnergy,
        sectionId = null
    )
}

/** Timestamp-based sampler; it never owns or advances musical time. */
class TeleoExperienceSampler(private val experience: TeleoExperience) {
    fun sample(positionMs: Long, previousPositionMs: Long? = null): TeleoVisualState {
        val position = positionMs.coerceAtLeast(0)
        val vocals = if (experience.isReliable("vocals")) interpolateVocal(experience.vocalFrames, position) else TeleoVocalVisualSignal()
        val ambient = if (experience.isReliable("other")) interpolateAmbient(experience.ambientFrames, position) else TeleoAmbientVisualSignal()
        val bass = if (experience.isReliable("bass")) activeAt(experience.bassNotes, position, TeleoBassNote::startMs) { it.endMs }
            ?.let { TeleoBassVisualSignal(it.midi, it.pitch, it.intensity.clamp(), it.confidence.clamp()) } else null
        val articulation = if (experience.isReliable("vocals")) activeAt(experience.visemes, position, { it.startMs }) { it.endMs }?.shape else null
        val drums = if (experience.isReliable("drums")) experience.drums.filter { position in it.startMs..(it.startMs + it.durationMs) }
            .map { TeleoVisualImpulse(it.type, it.intensity.clamp(), it.durationMs) } else emptyList()
        val crossed = if (previousPositionMs != null && position >= previousPositionMs) experience.timelineEvents.filter {
            it.timestampMs > previousPositionMs && it.timestampMs <= position
        } else emptyList()
        return TeleoVisualState(position, vocals, articulation, bass, ambient, drums, crossed)
    }

    private fun interpolateVocal(frames: List<TeleoVocalFrame>, position: Long): TeleoVocalVisualSignal {
        val pair = bracket(frames, position, TeleoVocalFrame::timestampMs) ?: return TeleoVocalVisualSignal()
        val (a, b, fraction) = pair
        val confidence = lerp(a.pitchConfidence, b.pitchConfidence, fraction).clamp()
        val pitch = if (confidence >= MIN_PITCH_CONFIDENCE) nullableLerp(a.pitchNormalized, b.pitchNormalized, fraction)?.clamp() else null
        return TeleoVocalVisualSignal(lerp(a.presence, b.presence, fraction).clamp(), lerp(a.intensity, b.intensity, fraction).clamp(), pitch, lerp(a.spectralBrightness, b.spectralBrightness, fraction).clamp())
    }

    private fun interpolateAmbient(frames: List<TeleoAmbientFrame>, position: Long): TeleoAmbientVisualSignal {
        val pair = bracket(frames, position, TeleoAmbientFrame::timestampMs) ?: return TeleoAmbientVisualSignal()
        val (a, b, fraction) = pair
        return TeleoAmbientVisualSignal(lerp(a.lowEnergy, b.lowEnergy, fraction).clamp(), lerp(a.midEnergy, b.midEnergy, fraction).clamp(), lerp(a.highEnergy, b.highEnergy, fraction).clamp(), lerp(a.overallEnergy, b.overallEnergy, fraction).clamp())
    }

    private fun <T> bracket(items: List<T>, position: Long, timestamp: (T) -> Long): Triple<T, T, Float>? {
        if (items.isEmpty()) return null
        if (position <= timestamp(items.first())) return Triple(items.first(), items.first(), 0f)
        if (position >= timestamp(items.last())) return Triple(items.last(), items.last(), 0f)
        var low = 0; var high = items.lastIndex
        while (low <= high) { val middle = (low + high) ushr 1; if (timestamp(items[middle]) <= position) low = middle + 1 else high = middle - 1 }
        val left = items[high]; val right = items[low]
        return Triple(left, right, ((position - timestamp(left)).toFloat() / (timestamp(right) - timestamp(left)).coerceAtLeast(1)).coerceIn(0f, 1f))
    }

    private fun <T> activeAt(items: List<T>, position: Long, start: (T) -> Long, end: (T) -> Long): T? {
        var low = 0; var high = items.lastIndex
        while (low <= high) { val middle = (low + high) ushr 1; if (start(items[middle]) <= position) low = middle + 1 else high = middle - 1 }
        return items.getOrNull(high)?.takeIf { position < end(it) }
    }
    private fun nullableLerp(a: Float?, b: Float?, fraction: Float): Float? = when { a != null && b != null -> lerp(a, b, fraction); fraction < .5f -> a ?: b; else -> b ?: a }
    private fun lerp(a: Float, b: Float, fraction: Float) = a + (b - a) * fraction
    private fun Float.clamp() = coerceIn(0f, 1f)
    private companion object { const val MIN_PITCH_CONFIDENCE = .55f }
}
