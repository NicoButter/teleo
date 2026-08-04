package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VisualPreset
import com.nicolas.teleo.features.music.domain.VisualQuality
import com.nicolas.teleo.features.music.visual.DeterministicMusicVisualEngine
import com.nicolas.teleo.features.music.visual.LinearMusicFeatureInterpolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVisualEngineTest {
    @Test
    fun `feature frames interpolate smoothly`() {
        val result = LinearMusicFeatureInterpolator().interpolate(
            listOf(frame(0, energy = 0f), frame(1_000, energy = 1f)),
            500
        )
        assertEquals(0.5f, result.overallEnergy, 0.001f)
        assertEquals(0.5f, result.lowEnergy, 0.001f)
        assertEquals(500, result.timestampMs)
    }

    @Test
    fun `fixed seed creates deterministic particles`() {
        val first = engine("track")
        val second = engine("track")
        val event = kick()
        first.update(100, 0f, frame(100), listOf(event))
        second.update(100, 0f, frame(100), listOf(event))
        val a = first.particles().first()
        val b = second.particles().first()
        assertEquals(a.velocityX, b.velocityX, 0.00001f)
        assertEquals(a.velocityY, b.velocityY, 0.00001f)
        assertEquals(a.size, b.size, 0.00001f)
    }

    @Test
    fun `particle pool never exceeds quality limit`() {
        val engine = engine("track", MusicVisualSettings(quality = VisualQuality.LOW, particleIntensity = 1.5f))
        repeat(50) { index -> engine.update(index * 100L, 0f, frame(index * 100L), listOf(kick(index * 100L))) }
        assertTrue(engine.particles().size <= VisualQuality.LOW.particleLimit)
    }

    @Test
    fun `expired particles return to pool`() {
        val engine = engine("track")
        engine.update(0, 0f, frame(0), listOf(kick(0)))
        assertTrue(engine.particles().isNotEmpty())
        repeat(40) { engine.update(10L + it, 0.05f, frame(10L + it), emptyList()) }
        assertTrue(engine.particles().isEmpty())
    }

    @Test
    fun `changing preset resets the scene`() {
        val engine = engine("track")
        engine.update(100, 0f, frame(100), listOf(kick()))
        engine.setPreset(VisualPreset.LANES)
        assertTrue(engine.particles().isEmpty())
    }

    @Test
    fun `reduced motion lowers the maximum visual load`() {
        val normal = engine("track-normal", MusicVisualSettings(quality = VisualQuality.LOW))
        val reduced = engine("track-reduced", MusicVisualSettings(quality = VisualQuality.LOW, reducedMotion = true))
        repeat(20) { index ->
            val event = kick(index * 100L)
            normal.update(index * 100L, 0f, frame(index * 100L), listOf(event))
            reduced.update(index * 100L, 0f, frame(index * 100L), listOf(event))
        }
        assertTrue(reduced.particles().size < normal.particles().size)
    }

    @Test
    fun `seek reconstruction is repeatable`() {
        val engine = engine("seek-track")
        val events = listOf(kick(0), kick(400))
        engine.rebuild(500, frame(500), events)
        val first = engine.particles().map { Triple(it.x, it.y, it.size) }
        engine.update(600, 0.05f, frame(600), listOf(kick(600)))
        engine.rebuild(500, frame(500), events)
        val rebuilt = engine.particles().map { Triple(it.x, it.y, it.size) }
        assertEquals(first, rebuilt)
    }

    @Test
    fun `changing song changes deterministic composition`() {
        val first = engine("song-a")
        val second = engine("song-b")
        first.update(100, 0f, frame(100), listOf(kick()))
        second.update(100, 0f, frame(100), listOf(kick()))
        assertNotEquals(first.particles().first().velocityX, second.particles().first().velocityX)
    }

    @Test
    fun `automatic quality degrades after sustained slow frames`() {
        val engine = engine("auto", MusicVisualSettings(quality = VisualQuality.AUTO))
        repeat(12) { index -> engine.update(index * 16L, 0.03f, frame(index * 16L), emptyList()) }
        assertEquals(VisualQuality.MEDIUM, engine.metrics().effectiveQuality)
    }

    private fun engine(track: String, settings: MusicVisualSettings = MusicVisualSettings()) =
        DeterministicMusicVisualEngine(track, 2, settings)

    private fun kick(timestamp: Long = 100) = MusicEvent(timestamp, 100, MusicEventType.KICK, 0.9f, "Bombo")

    private fun frame(timestamp: Long, energy: Float = 0.7f) = MusicFeatureFrame(
        timestamp, 0.2f, 0.8f, energy, 0.5f, 0.4f, 0.6f, 0.5f, 0.6f, energy, "chorus"
    )
}
