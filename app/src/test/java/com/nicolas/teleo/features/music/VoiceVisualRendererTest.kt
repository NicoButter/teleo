package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.visual.voice.DefaultVoiceVisualRenderer
import com.nicolas.teleo.features.music.visual.voice.VisualVowel
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualFrame
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualQuality
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualSettings
import com.nicolas.teleo.features.music.visual.voice.VoiceVisualSmoothing
import com.nicolas.teleo.features.music.visual.voice.VowelProbabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceVisualRendererTest {
    private val voiced = VoiceVisualFrame.of(
        presence = 1f,
        intensity = 0.85f,
        pitchNormalized = 0.86f,
        vibrato = 0.7f,
        onsetStrength = 1f,
        vowelProbabilities = VowelProbabilities.of(a = 0.72f, o = 0.28f)
    )

    @Test
    fun `attack reacts faster than release and silence returns gradually`() {
        val renderer = DefaultVoiceVisualRenderer(
            smoothing = VoiceVisualSmoothing(attackSpeed = 12f, releaseSpeed = 2f)
        )
        renderer.update(voiced, 0.05f)
        val afterAttack = renderer.presence
        renderer.update(VoiceVisualFrame.SILENCE, 0.05f)
        val releaseDrop = afterAttack - renderer.presence

        assertTrue(afterAttack > 0.4f)
        assertTrue(releaseDrop in 0f..0.1f)
        assertTrue(renderer.presence > 0f)

        repeat(100) { renderer.update(VoiceVisualFrame.SILENCE, 0.05f) }
        assertTrue(renderer.presence < 0.0001f)
        assertTrue(renderer.scale >= renderer.tuning.silentPointScale)
    }

    @Test
    fun `particle pool respects quality cap and reuses expired objects`() {
        val renderer = DefaultVoiceVisualRenderer(
            VoiceVisualSettings(quality = VoiceVisualQuality.LOW, particleIntensity = 1.5f)
        )
        repeat(12) {
            renderer.update(voiced, 0.016f)
            renderer.update(VoiceVisualFrame.of(presence = 1f, intensity = 0.2f), 0.016f)
        }
        assertTrue(renderer.particleCount <= VoiceVisualQuality.LOW.particleLimit)
        val allocated = renderer.allocatedParticleCount

        repeat(30) { renderer.update(VoiceVisualFrame.SILENCE, 0.05f) }
        assertEquals(0, renderer.particleCount)
        assertTrue(renderer.recycledParticleCount > 0)

        renderer.update(voiced, 0.016f)
        assertTrue(renderer.particleCount > 0)
        assertEquals(allocated, renderer.allocatedParticleCount)
    }

    @Test
    fun `reduced motion limits displacement and particle load`() {
        val normal = DefaultVoiceVisualRenderer(VoiceVisualSettings(quality = VoiceVisualQuality.HIGH))
        val reduced = DefaultVoiceVisualRenderer(
            VoiceVisualSettings(quality = VoiceVisualQuality.HIGH, reducedMotion = true)
        )
        repeat(8) {
            normal.update(voiced, 0.05f)
            reduced.update(voiced, 0.05f)
        }

        assertTrue(kotlin.math.abs(reduced.centerY - 0.5f) < kotlin.math.abs(normal.centerY - 0.5f))
        assertTrue(reduced.particleCount < normal.particleCount)
        assertTrue(reduced.particleCount <= (VoiceVisualQuality.HIGH.particleLimit * 0.35f).toInt())
    }

    @Test
    fun `large frame delta remains finite and bounded`() {
        val renderer = DefaultVoiceVisualRenderer()
        renderer.update(voiced, 100f)

        assertTrue(renderer.presence.isFinite())
        assertTrue(renderer.centerY.isFinite())
        assertTrue(renderer.scale.isFinite())
        assertTrue(renderer.presence in 0f..1f)
        assertTrue(renderer.dominantBlend.progress in 0f..0.5f)
        assertTrue(renderer.particles.all { it.x.isFinite() && it.y.isFinite() })
    }

    @Test
    fun `reset clears transient state and active particles`() {
        val renderer = DefaultVoiceVisualRenderer()
        renderer.update(voiced, 0.05f)
        assertTrue(renderer.particleCount > 0)

        renderer.reset()

        assertEquals(0f, renderer.presence, 0f)
        assertEquals(0.5f, renderer.centerY, 0f)
        assertEquals(0, renderer.particleCount)
        assertEquals(VisualVowel.UNKNOWN, renderer.dominantBlend.primary)
    }
}
