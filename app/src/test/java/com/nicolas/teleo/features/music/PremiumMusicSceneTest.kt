package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicVisualSettings
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import com.nicolas.teleo.features.music.visual.MouthPose
import com.nicolas.teleo.features.music.visual.PremiumMusicScene
import com.nicolas.teleo.features.music.visual.RhubarbMouthPoseMapper
import com.nicolas.teleo.features.music.visual.TeleoAmbientVisualSignal
import com.nicolas.teleo.features.music.visual.TeleoVisualState
import com.nicolas.teleo.features.music.visual.TeleoVocalVisualSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumMusicSceneTest {
    @Test fun `every Rhubarb group maps to a deliberate articulatory pose`() {
        val poses = VocalVisemeShape.entries.associateWith(RhubarbMouthPoseMapper::poseFor)
        assertEquals(MouthPose.REST, poses[VocalVisemeShape.X])
        assertTrue(poses.getValue(VocalVisemeShape.D).lipOpen > poses.getValue(VocalVisemeShape.A).lipOpen)
        assertTrue(poses.getValue(VocalVisemeShape.F).lipRound > poses.getValue(VocalVisemeShape.C).lipRound)
        assertTrue(poses.getValue(VocalVisemeShape.G).upperTeethVisible > poses.getValue(VocalVisemeShape.F).upperTeethVisible)
        assertTrue(poses.getValue(VocalVisemeShape.H).tongueVisible > 0f)
        assertEquals(VocalVisemeShape.entries.size, poses.values.distinct().size)
    }

    @Test fun `articulation dominates intensity while vocal features ease without replacing musical time`() {
        val scene = PremiumMusicScene()
        scene.update(sample(1_000, VocalVisemeShape.X, .1f), emptyList(), 0f, MusicVisualSettings())
        val first = scene.update(sample(1_016, VocalVisemeShape.D, 1f), emptyList(), .016f, MusicVisualSettings())
        val target = RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.D).lipOpen

        assertEquals(1_016L, first.positionMs)
        assertTrue(first.voice.pose.lipOpen > MouthPose.REST.lipOpen)
        assertEquals(target, first.voice.pose.lipOpen, 0f)
        assertTrue(first.voice.presence in .1f..1f)
        assertEquals(.8f, first.ambientEnergy, .001f)
    }

    @Test fun `drum accents keep distinct type position and intensity state`() {
        val scene = PremiumMusicScene()
        val events = listOf(
            MusicEvent(100, 80, MusicEventType.KICK, .9f),
            MusicEvent(120, 60, MusicEventType.SNARE, .6f),
            MusicEvent(140, 100, MusicEventType.CYMBAL, .8f)
        )
        val state = scene.update(sample(150, VocalVisemeShape.A, .4f), events, 0f, MusicVisualSettings())

        assertEquals(100L, state.kick.timestampMs)
        assertEquals(120L, state.snare.timestampMs)
        assertEquals(140L, state.cymbal.timestampMs)
        assertNotEquals(state.kick.intensity, state.snare.intensity)
    }

    private fun sample(position: Long, viseme: VocalVisemeShape, intensity: Float) = TeleoVisualState(
        positionMs = position,
        vocals = TeleoVocalVisualSignal(
            presence = intensity,
            intensity = intensity,
            pitchNormalized = .72f,
            pitchConfidence = .9f,
            spectralBrightness = .65f
        ),
        articulation = viseme,
        ambient = TeleoAmbientVisualSignal(.6f, .7f, .5f, .8f)
    )
}
