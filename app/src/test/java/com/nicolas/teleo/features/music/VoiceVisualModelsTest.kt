package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.visual.voice.VisualVowel
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualFrame
import com.nicolas.teleo.features.music.visual.voice.VowelProbabilities
import com.nicolas.teleo.features.music.visual.voice.dominantBlend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVisualModelsTest {
    @Test
    fun `vowel probabilities sanitize and normalize every input`() {
        val probabilities = VowelProbabilities.of(a = 2f, e = -1f, i = Float.NaN, o = 0.5f)

        assertEquals(1f, probabilities.asList().sumOf { it.second.toDouble() }.toFloat(), 0.0001f)
        assertEquals(2f / 3f, probabilities.a, 0.0001f)
        assertEquals(1f / 3f, probabilities.o, 0.0001f)
        assertEquals(0f, probabilities.e, 0f)
        assertEquals(0f, probabilities.i, 0f)
    }

    @Test
    fun `empty probability set falls back to neutral`() {
        val probabilities = VowelProbabilities.of()

        assertEquals(1f, probabilities.unknown, 0f)
        assertEquals(VisualVowel.UNKNOWN, probabilities.dominantBlend().primary)
    }

    @Test
    fun `voice frame clamps normalized fields and rejects non finite values`() {
        val frame = VoiceVisualFrame.of(
            presence = 1.8f,
            intensity = -0.5f,
            pitchNormalized = Float.POSITIVE_INFINITY,
            vibrato = 0.42f,
            onsetStrength = 4f
        )

        assertEquals(1f, frame.presence, 0f)
        assertEquals(0f, frame.intensity, 0f)
        assertEquals(0f, frame.pitchNormalized, 0f)
        assertEquals(0.42f, frame.vibrato, 0f)
        assertEquals(1f, frame.onsetStrength, 0f)
    }

    @Test
    fun `dominant blend chooses two strongest vowels with bounded progress`() {
        val blend = VowelProbabilities.of(a = 0.62f, e = 0.09f, o = 0.29f).dominantBlend()

        assertEquals(VisualVowel.A, blend.primary)
        assertEquals(VisualVowel.O, blend.secondary)
        assertTrue(blend.progress in 0f..0.5f)
        assertEquals(0.29f / 0.91f, blend.progress, 0.0001f)
    }
}
