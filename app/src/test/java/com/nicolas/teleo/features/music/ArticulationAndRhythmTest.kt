package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.TeleoMusicDtoMapper
import com.nicolas.teleo.features.music.domain.*
import com.nicolas.teleo.features.music.visual.*
import org.junit.Assert.*
import org.junit.Test

class ArticulationAndRhythmTest {
    @Test fun `key poses retain closure contact protrusion and tongue regardless of intensity`() {
        for (shape in VocalVisemeShape.entries) {
            val base = RhubarbMouthPoseMapper.poseFor(shape)
            val scene = PremiumMusicScene()
            for (intensity in listOf(0f, .5f, 1f)) {
                val voice = scene.update(TeleoVisualState(100, articulation = shape,
                    vocals = TeleoVocalVisualSignal(1f, intensity, 1f, 1f)), emptyList(), .016f, MusicVisualSettings()).voice
                assertEquals(base, voice.pose)
            }
        }
        val a = RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.A)
        val f = RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.F)
        val g = RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.G)
        val h = RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.H)
        assertTrue(MouthGeometry.from(a).isClosed)
        assertEquals(0f, a.upperTeethVisible + a.lowerTeethVisible, 0f)
        assertTrue(a.lipPressure > MouthPose.REST.lipPressure)
        assertTrue(MouthGeometry.from(f).halfWidth < MouthGeometry.from(g).halfWidth * .6f)
        assertTrue(f.lipPucker > .9f && f.lipOpen < .3f)
        val uw = MouthGeometry.from(f)
        assertTrue(2f * uw.apertureHalfWidth / (uw.upperOpening + uw.lowerOpening) in .8f..1.6f)
        assertEquals(1f, g.labiodentalContact, 0f)
        assertEquals(1f, g.lowerLipRaise, 0f)
        assertTrue(h.tongueRaise > .9f && h.tongueVisible > .9f)
    }

    @Test fun `coarticulation is continuous across adjacent cues gaps and short cues`() {
        val cues = listOf(
            VocalVisemeEvent(100, 300, VocalVisemeShape.A),
            VocalVisemeEvent(300, 500, VocalVisemeShape.F),
            VocalVisemeEvent(550, 750, VocalVisemeShape.G),
            VocalVisemeEvent(760, 785, VocalVisemeShape.H),
            VocalVisemeEvent(785, 1000, VocalVisemeShape.D))
        val timeline = MouthArticulationTimeline(cues)
        for (cue in cues) assertEquals(RhubarbMouthPoseMapper.poseFor(cue.shape), timeline.poseAt((cue.startMs + cue.endMs) / 2))
        for (position in 1L..1100L) {
            val before = values(timeline.poseAt(position - 1))
            val after = values(timeline.poseAt(position))
            before.zip(after).forEach { (a, b) -> assertTrue("Jump at $position: $a -> $b", kotlin.math.abs(a - b) < .17f) }
            after.forEach { assertTrue(it in 0f..1f) }
        }
        assertEquals(MouthPose.REST, timeline.poseAt(0))
        assertEquals(MouthPose.REST, timeline.poseAt(1100))
        assertNotEquals(RhubarbMouthPoseMapper.poseFor(VocalVisemeShape.A), timeline.poseAt(290))
        assertEquals(VocalVisemeShape.A, cues.first().shape)
        assertEquals(300L, cues.first().endMs)
    }

    @Test fun `pause repeated sample and seeks reconstruct identical mouth and five drum families`() {
        val types = listOf(MusicEventType.KICK, MusicEventType.SNARE, MusicEventType.HI_HAT, MusicEventType.TOM, MusicEventType.CYMBAL)
        val experience = TeleoExperience(
            drums = types.mapIndexed { index, type -> TeleoDrumImpulse(100L + index * 10, 10, type, .8f) },
            visemes = listOf(VocalVisemeEvent(100, 300, VocalVisemeShape.A), VocalVisemeEvent(300, 600, VocalVisemeShape.H)))
        val sampler = TeleoExperienceSampler(experience)
        val original = sampler.sample(290)
        sampler.sample(10000)
        assertEquals(original, sampler.sample(290))
        assertEquals(original, sampler.sample(290))
        val drums = original.drumAccents!!
        assertEquals(listOf(100L, 110L, 120L, 130L, 140L), listOf(drums.kick, drums.snare, drums.hiHat, drums.tom, drums.cymbal).map { it.timestampMs })
        assertEquals(RhythmicBackgroundState(), sampler.sample(0).drumAccents)
        val silent = TeleoExperienceSampler(experience.copy(channelQuality = mapOf("drums" to TeleoChannelQuality.UNRELIABLE, "vocals" to TeleoChannelQuality.UNKNOWN))).sample(290)
        assertEquals(MouthPose.REST, silent.articulatedPose)
        assertEquals(RhythmicBackgroundState(), silent.drumAccents)
    }

    @Test fun `indexed active impulses preserve overlapping long notes and exact zero duration hits`() {
        val sampler = TeleoExperienceSampler(TeleoExperience(drums = listOf(
            TeleoDrumImpulse(0, 1000, MusicEventType.CYMBAL, .5f),
            TeleoDrumImpulse(100, 0, MusicEventType.KICK, 1f),
            TeleoDrumImpulse(200, 10, MusicEventType.SNARE, .8f))))
        assertEquals(2, sampler.sample(100).activeDrumImpulses.size)
        assertEquals(1, sampler.sample(101).activeDrumImpulses.size)
        assertEquals(2, sampler.sample(205).activeDrumImpulses.size)
        assertTrue(sampler.sample(1001).activeDrumImpulses.isEmpty())
    }

    @Test fun `pressure has distinct families silence reduced motion and deterministic seek`() {
        val field = RhythmicPressureField()
        val hit = PremiumDrumAccent(100, 1f)
        field.update(160, RhythmicBackgroundState(), 1f)
        val restX = field.x.copyOf(); val restY = field.y.copyOf()
        val patterns = listOf(RhythmicBackgroundState(kick = hit), RhythmicBackgroundState(snare = hit), RhythmicBackgroundState(tom = hit)).map {
            field.update(160, it, 1f)
            field.y.copyOf()
        }
        assertFalse(patterns[0].contentEquals(patterns[1]))
        assertFalse(patterns[1].contentEquals(patterns[2]))
        field.update(160, RhythmicBackgroundState(kick = hit), .18f)
        val reduced = field.y.indices.sumOf { kotlin.math.abs(field.y[it] - restY[it]).toDouble() }
        val full = patterns[0].indices.sumOf { kotlin.math.abs(patterns[0][it] - restY[it]).toDouble() }
        assertTrue(reduced < full * .2)
        field.update(160, RhythmicBackgroundState(kick = hit), 1f)
        assertArrayEquals(patterns[0], field.y, 0f)
        field.update(2000, RhythmicBackgroundState(kick = hit, snare = hit, tom = hit), 1f)
        assertArrayEquals(restX, field.x, 0f); assertArrayEquals(restY, field.y, 0f)
        assertEquals(0f, drumEnvelope(99, hit, 150), 0f)
        assertTrue(drumEnvelope(120, hit, 150) > 0f)
        assertEquals(0f, drumEnvelope(250, hit, 150), 0f)
    }

    @Test fun `wire drum variants agree in domain and timeline and unknown stays unknown`() {
        val variants = mapOf("bass-drum" to MusicEventType.KICK, "snare" to MusicEventType.SNARE,
            "closed_hi_hat" to MusicEventType.HI_HAT, "floor_tom" to MusicEventType.TOM,
            "crash" to MusicEventType.CYMBAL, "splash" to MusicEventType.CYMBAL, "ride" to MusicEventType.CYMBAL,
            "unknown" to MusicEventType.OTHER)
        variants.forEach { (wire, expected) ->
            val json = """{"format":"teleo-music","version":1,"track":{"id":"test","title":"Test","durationMs":1000},"drums":{"events":[{"timeMs":100,"type":"$wire","intensity":0.8}]}}"""
            val parsed = TeleoMusicDtoMapper().importedExperience(json)
            assertEquals(expected, parsed.teleoExperience.drums.single().type)
            assertEquals(expected, parsed.timeline.events.single().type)
        }
    }
    private fun values(p: MouthPose) = listOf(p.jawOpen, p.lipOpen, p.lipWidth, p.lipRound, p.lipPucker, p.lipSpread,
        p.lipClosure, p.lipPressure, p.upperTeethVisible, p.lowerTeethVisible, p.lowerLipRaise, p.labiodentalContact,
        p.tongueVisible, p.tongueRaise, p.tongueForward)
}
