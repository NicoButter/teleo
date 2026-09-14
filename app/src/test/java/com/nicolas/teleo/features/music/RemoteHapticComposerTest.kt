package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.TeleoChannelQuality
import com.nicolas.teleo.features.music.haptics.RemoteHapticComposer
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteHapticComposerTest {
    @Test fun `empty protocol haptics derive tactile cues only from reliable remote channels`() {
        val events = listOf(
            event(10, MusicEventType.KICK),
            event(20, MusicEventType.SNARE),
            event(30, MusicEventType.HI_HAT),
            event(40, MusicEventType.BASS),
            event(50, MusicEventType.GUITAR),
            event(60, MusicEventType.SECTION_START),
            event(70, MusicEventType.CYMBAL, .8f),
            event(80, MusicEventType.CYMBAL, .4f)
        )

        val result = RemoteHapticComposer.compose(
            explicitEvents = emptyList(),
            protocolEvents = events,
            channelQuality = mapOf("drums" to TeleoChannelQuality.RELIABLE, "bass" to TeleoChannelQuality.UNRELIABLE)
        )

        assertEquals(
            listOf(MusicEventType.KICK, MusicEventType.SNARE, MusicEventType.HI_HAT, MusicEventType.SECTION_START, MusicEventType.CYMBAL),
            result.map(MusicEvent::type)
        )
    }

    @Test fun `explicit protocol haptics remain authoritative`() {
        val explicit = listOf(event(100, MusicEventType.SECTION_START))
        val result = RemoteHapticComposer.compose(explicit, listOf(event(10, MusicEventType.KICK)), emptyMap())
        assertEquals(explicit, result)
    }

    private fun event(timestampMs: Long, type: MusicEventType, intensity: Float = .8f) = MusicEvent(timestampMs, 40, type, intensity)
}
