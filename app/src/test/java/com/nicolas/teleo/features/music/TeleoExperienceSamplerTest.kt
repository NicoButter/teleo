package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.TeleoAmbientFrame
import com.nicolas.teleo.features.music.domain.TeleoBassNote
import com.nicolas.teleo.features.music.domain.TeleoChannelQuality
import com.nicolas.teleo.features.music.domain.TeleoExperience
import com.nicolas.teleo.features.music.domain.TeleoVocalFrame
import com.nicolas.teleo.features.music.domain.VocalVisemeEvent
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import com.nicolas.teleo.features.music.visual.TeleoExperienceSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleoExperienceSamplerTest {
    @Test fun `interpolates irregular timestamp frames and gates low confidence pitch`() {
        val sampler = TeleoExperienceSampler(TeleoExperience(
            vocalFrames = listOf(
                TeleoVocalFrame(100, 0f, .2f, null, .1f, .9f, .2f),
                TeleoVocalFrame(900, 1f, .8f, null, .9f, .9f, .8f)
            ),
            ambientFrames = listOf(TeleoAmbientFrame(100, 0f, .2f, .3f, .1f), TeleoAmbientFrame(900, 1f, .8f, .7f, .9f))
        ))
        val state = sampler.sample(300)
        assertEquals(.25f, state.vocals.presence, .001f)
        assertEquals(.3f, state.vocals.pitchNormalized!!, .001f)
        assertEquals(.25f, state.ambient.lowEnergy, .001f)
        val lowConfidence = TeleoExperienceSampler(TeleoExperience(vocalFrames = listOf(TeleoVocalFrame(0, 1f, 1f, null, .8f, .2f, 1f))))
        assertNull(lowConfidence.sample(0).vocals.pitchNormalized)
    }

    @Test fun `finds exact viseme and active bass note`() {
        val sampler = TeleoExperienceSampler(TeleoExperience(
            visemes = listOf(VocalVisemeEvent(100, 200, VocalVisemeShape.F)),
            bassNotes = listOf(TeleoBassNote(100, 200, 42, null, .7f, .8f))
        ))
        assertEquals(VocalVisemeShape.F, sampler.sample(150).articulation)
        assertEquals(42, sampler.sample(150).bass?.midi)
        assertNull(sampler.sample(200).articulation)
        assertNull(sampler.sample(200).bass)
    }

    @Test fun `crosses events forward and never replays them when seeking backwards`() {
        val sampler = TeleoExperienceSampler(TeleoExperience(timelineEvents = listOf(
            MusicEvent(100, 10, MusicEventType.KICK, 1f), MusicEvent(300, 10, MusicEventType.SNARE, 1f)
        )))
        assertEquals(listOf(MusicEventType.KICK, MusicEventType.SNARE), sampler.sample(350, 0).crossedEvents.map { it.type })
        assertTrue(sampler.sample(100, 350).crossedEvents.isEmpty())
    }

    @Test fun `unreliable channels and optional arrays stay silent without mock fallback`() {
        val sampler = TeleoExperienceSampler(TeleoExperience(
            vocalFrames = listOf(TeleoVocalFrame(0, 1f, 1f, 220f, .7f, 1f, 1f)),
            bassNotes = listOf(TeleoBassNote(0, 500, 40, null, 1f, 1f)),
            channelQuality = mapOf("vocals" to TeleoChannelQuality.UNRELIABLE, "bass" to TeleoChannelQuality.UNKNOWN)
        ))
        val state = sampler.sample(100)
        assertEquals(0f, state.vocals.presence, 0f)
        assertNull(state.bass)
        assertTrue(state.activeDrumImpulses.isEmpty())
        assertEquals(0f, TeleoExperienceSampler(TeleoExperience()).sample(20).ambient.overallEnergy, 0f)
    }
}
