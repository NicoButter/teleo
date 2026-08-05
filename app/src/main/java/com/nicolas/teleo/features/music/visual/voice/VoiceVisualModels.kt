package com.nicolas.teleo.features.music.visual.voice

import kotlin.math.abs

enum class VisualVowel(val label: String) { A("A"), E("E"), I("I"), O("O"), U("U"), UNKNOWN("Neutral") }

@ConsistentCopyVisibility
data class VowelProbabilities private constructor(
    val a: Float,
    val e: Float,
    val i: Float,
    val o: Float,
    val u: Float,
    val unknown: Float
) {
    operator fun get(vowel: VisualVowel): Float = when (vowel) {
        VisualVowel.A -> a
        VisualVowel.E -> e
        VisualVowel.I -> i
        VisualVowel.O -> o
        VisualVowel.U -> u
        VisualVowel.UNKNOWN -> unknown
    }

    fun asList(): List<Pair<VisualVowel, Float>> = VisualVowel.entries.map { it to get(it) }

    companion object {
        val NEUTRAL = of(unknown = 1f)

        fun of(
            a: Float = 0f,
            e: Float = 0f,
            i: Float = 0f,
            o: Float = 0f,
            u: Float = 0f,
            unknown: Float = 0f
        ): VowelProbabilities {
            val values = floatArrayOf(a, e, i, o, u, unknown).map(::normalizedInput)
            val sum = values.sum()
            if (sum <= 0.0001f) return VowelProbabilities(0f, 0f, 0f, 0f, 0f, 1f)
            return VowelProbabilities(
                values[0] / sum,
                values[1] / sum,
                values[2] / sum,
                values[3] / sum,
                values[4] / sum,
                values[5] / sum
            )
        }
    }
}

@ConsistentCopyVisibility
data class VoiceVisualFrame private constructor(
    val presence: Float,
    val intensity: Float,
    val pitchNormalized: Float,
    val vibrato: Float,
    val onsetStrength: Float,
    val vowelProbabilities: VowelProbabilities
) {
    companion object {
        val SILENCE = of(vowelProbabilities = VowelProbabilities.NEUTRAL)

        fun of(
            presence: Float = 0f,
            intensity: Float = 0f,
            pitchNormalized: Float = 0.5f,
            vibrato: Float = 0f,
            onsetStrength: Float = 0f,
            vowelProbabilities: VowelProbabilities = VowelProbabilities.NEUTRAL
        ) = VoiceVisualFrame(
            normalizedInput(presence),
            normalizedInput(intensity),
            normalizedInput(pitchNormalized),
            normalizedInput(vibrato),
            normalizedInput(onsetStrength),
            vowelProbabilities
        )
    }
}

data class DominantVowelBlend(
    val primary: VisualVowel,
    val secondary: VisualVowel,
    val progress: Float
)

fun VowelProbabilities.dominantBlend(): DominantVowelBlend {
    val sorted = asList().sortedWith(compareByDescending<Pair<VisualVowel, Float>> { it.second }.thenBy { it.first.ordinal })
    val primary = sorted[0]
    val secondary = sorted[1]
    val combined = primary.second + secondary.second
    val progress = if (combined <= 0.0001f) 0f else (secondary.second / combined).coerceIn(0f, 0.5f)
    return DominantVowelBlend(primary.first, secondary.first, progress)
}

data class VoiceVisualSmoothing(
    val attackSpeed: Float = 10f,
    val releaseSpeed: Float = 4.5f,
    val shapeMorphSpeed: Float = 7f,
    val positionSpeed: Float = 5f
) {
    init {
        require(attackSpeed > 0f && releaseSpeed > 0f && shapeMorphSpeed > 0f && positionSpeed > 0f)
    }
}

data class VoiceVisualTuning(
    val pitchTravel: Float = 0.24f,
    val silentPointScale: Float = 0.018f,
    val presenceScale: Float = 0.17f,
    val intensityScale: Float = 0.13f,
    val vibratoAmplitude: Float = 0.055f,
    val particleBaseCount: Int = 5,
    val particleStrengthCount: Int = 18
) {
    init {
        require(pitchTravel in 0f..0.5f)
        require(silentPointScale > 0f && presenceScale > 0f && intensityScale >= 0f)
        require(vibratoAmplitude in 0f..0.2f)
        require(particleBaseCount >= 0 && particleStrengthCount >= 0)
    }
}

enum class VoiceVisualQuality(val particleLimit: Int) {
    AUTO(100),
    LOW(40),
    MEDIUM(100),
    HIGH(200)
}

data class VoiceVisualSettings(
    val enabled: Boolean = true,
    val quality: VoiceVisualQuality = VoiceVisualQuality.AUTO,
    val particleIntensity: Float = 1f,
    val motionIntensity: Float = 1f,
    val reducedMotion: Boolean = false,
    val particlesEnabled: Boolean = true,
    val flashesEnabled: Boolean = true
) {
    init {
        require(particleIntensity in 0f..1.5f)
        require(motionIntensity in 0f..1.5f)
    }
}

internal fun normalizedInput(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

internal fun VowelProbabilities.approximatelyNormalized(): Boolean =
    abs(asList().sumOf { it.second.toDouble() }.toFloat() - 1f) < 0.001f
