package com.nicolas.teleo.features.music.visual

import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicFeatureInterpolator

class LinearMusicFeatureInterpolator : MusicFeatureInterpolator {
    override fun interpolate(frames: List<MusicFeatureFrame>, positionMs: Long): MusicFeatureFrame {
        if (frames.isEmpty()) return MusicFeatureFrame.EMPTY.copy(timestampMs = positionMs.coerceAtLeast(0))
        if (positionMs <= frames.first().timestampMs) return frames.first().copy(timestampMs = positionMs.coerceAtLeast(0))
        if (positionMs >= frames.last().timestampMs) return frames.last().copy(timestampMs = positionMs)

        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (frames[middle].timestampMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        val left = frames[high.coerceAtLeast(0)]
        val right = frames[low.coerceAtMost(frames.lastIndex)]
        val span = (right.timestampMs - left.timestampMs).coerceAtLeast(1)
        val fraction = ((positionMs - left.timestampMs).toFloat() / span).coerceIn(0f, 1f)
        return MusicFeatureFrame(
            timestampMs = positionMs,
            beatPhase = interpolateBeatPhase(left.beatPhase, right.beatPhase, fraction),
            beatStrength = lerp(left.beatStrength, right.beatStrength, fraction),
            lowEnergy = lerp(left.lowEnergy, right.lowEnergy, fraction),
            midEnergy = lerp(left.midEnergy, right.midEnergy, fraction),
            highEnergy = lerp(left.highEnergy, right.highEnergy, fraction),
            vocalPresence = lerp(left.vocalPresence, right.vocalPresence, fraction),
            melodicPitchNormalized = interpolateNullable(left.melodicPitchNormalized, right.melodicPitchNormalized, fraction),
            spectralBrightness = lerp(left.spectralBrightness, right.spectralBrightness, fraction),
            overallEnergy = lerp(left.overallEnergy, right.overallEnergy, fraction),
            sectionId = if (fraction < 0.5f) left.sectionId else right.sectionId
        )
    }

    private fun interpolateBeatPhase(start: Float, end: Float, fraction: Float): Float {
        val adjustedEnd = if (end < start && start - end > 0.5f) end + 1f else end
        return lerp(start, adjustedEnd, fraction) % 1f
    }

    private fun interpolateNullable(start: Float?, end: Float?, fraction: Float): Float? = when {
        start != null && end != null -> lerp(start, end, fraction)
        fraction < 0.5f -> start ?: end
        else -> end ?: start
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        (start + (end - start) * fraction).coerceIn(0f, 1f)
}
