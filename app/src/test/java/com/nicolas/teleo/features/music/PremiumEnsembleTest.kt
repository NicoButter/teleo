package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.TeleoMusicDtoMapper
import com.nicolas.teleo.features.music.domain.*
import com.nicolas.teleo.features.music.visual.*
import org.junit.Assert.*
import org.junit.Test

class PremiumEnsembleTest {
    @Test fun `real instrument notes reach premium together with voice and drums`() {
        val imported = TeleoMusicDtoMapper().importedExperience("""{
            "format":"teleo-music","version":1,
            "track":{"id":"ensemble","title":"Ensemble","durationMs":3000},
            "drums":{"events":[{"timeMs":100,"type":"kick","intensity":0.9}]},
            "bass":{"notes":[{"timeMs":100,"durationMs":500,"intensity":0.8}]},
            "guitar":{"notes":[{"timeMs":100,"durationMs":400,"intensity":0.6}]},
            "piano":{"notes":[{"timeMs":100,"durationMs":300,"intensity":0.7}]},
            "vocals":{"visemes":[{"startMs":0,"endMs":500,"shape":"H"}]},
            "sections":[{"startMs":0,"name":"Intro"},{"startMs":1000,"name":"Estribillo"}]
        }""")
        val sampler = TeleoExperienceSampler(imported.teleoExperience)
        val scene = PremiumMusicScene()
        val state = scene.update(sampler.sample(200), emptyList(), 0f, MusicVisualSettings())
        assertEquals(.8f, state.ensemble.bass.intensity, .001f)
        assertEquals(.6f, state.ensemble.guitar.intensity, .001f)
        assertEquals(.7f, state.ensemble.piano.intensity, .001f)
        assertEquals("Intro", state.ensemble.section)
        assertEquals(VocalVisemeShape.H, state.voice.viseme)
        assertEquals(100L, state.kick.timestampMs)
        assertEquals("Estribillo", sampler.sample(1100).ensemble.section)
        assertEquals("Intro", sampler.sample(200).ensemble.section)
    }

    @Test fun `held chord survives shorter overlapping notes and seek rebuilds envelopes`() {
        val experience = TeleoExperience(timelineEvents = listOf(
            MusicEvent(100, 1000, MusicEventType.PIANO, .7f),
            MusicEvent(200, 100, MusicEventType.PIANO, 1f)))
        val sampler = PremiumEnsembleSampler(experience)
        assertEquals(1f, sampler.sample(250).piano.intensity, 0f)
        val held = sampler.sample(600)
        assertEquals(.7f, held.piano.intensity, 0f)
        assertEquals(PremiumEnsembleState(), sampler.sample(3000))
        assertEquals(held, sampler.sample(600))
        assertEquals(held, sampler.sample(600))
        assertEquals(0f, sampler.sample(99).piano.intensity, 0f)
    }

    @Test fun `missing and unreliable channels never borrow other instruments or mock energy`() {
        val experience = TeleoExperience(timelineEvents = listOf(
            MusicEvent(100, 1000, MusicEventType.BASS, 1f),
            MusicEvent(100, 1000, MusicEventType.GUITAR, 1f),
            MusicEvent(100, 1000, MusicEventType.PIANO, 1f),
            MusicEvent(100, 0, MusicEventType.SECTION_START, 1f, "Private")),
            channelQuality = mapOf("bass" to TeleoChannelQuality.UNRELIABLE,
                "guitar" to TeleoChannelQuality.UNKNOWN, "piano" to TeleoChannelQuality.UNRELIABLE,
                "sections" to TeleoChannelQuality.UNRELIABLE))
        assertEquals(PremiumEnsembleState(), PremiumEnsembleSampler(experience).sample(200))
        val missing = TeleoExperience(vocalFrames = listOf(TeleoVocalFrame(0, 1f, 1f, null, null, 0f, 1f)))
        assertEquals(PremiumEnsembleState(), PremiumEnsembleSampler(missing).sample(200))
    }
}
