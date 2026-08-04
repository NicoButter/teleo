package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.domain.LyricLine
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.activeEventsAt
import com.nicolas.teleo.features.music.domain.activeLyricAt
import com.nicolas.teleo.features.music.domain.adjustedTimelinePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDomainTest {
    @Test(expected = IllegalArgumentException::class)
    fun `event intensity above one is rejected`() {
        MusicEvent(0, 10, MusicEventType.KICK, 1.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `event intensity below zero is rejected`() {
        MusicEvent(0, 10, MusicEventType.KICK, -0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unordered events are rejected`() {
        timeline(
            events = listOf(
                MusicEvent(200, 50, MusicEventType.SNARE, 0.5f),
                MusicEvent(100, 50, MusicEventType.KICK, 0.8f)
            )
        )
    }

    @Test
    fun `active event uses the playback position`() {
        val timeline = timeline(events = listOf(MusicEvent(100, 100, MusicEventType.KICK, 0.8f)))
        assertEquals(MusicEventType.KICK, timeline.activeEventsAt(150).single().type)
        assertTrue(timeline.activeEventsAt(250).isEmpty())
    }

    @Test
    fun `active lyric is found and absent gaps return null`() {
        val timeline = timeline(lyrics = listOf(LyricLine(100, 200, "Texto de prueba")))
        assertEquals("Texto de prueba", timeline.activeLyricAt(150)?.text)
        assertNull(timeline.activeLyricAt(250))
    }

    @Test
    fun `sync offset is applied and clamped at zero`() {
        assertEquals(1_200, adjustedTimelinePosition(1_000, 200))
        assertEquals(0, adjustedTimelinePosition(100, -250))
    }

    @Test
    fun `empty timeline and song without lyrics are supported`() {
        val empty = timeline()
        assertTrue(empty.activeEventsAt(0).isEmpty())
        assertNull(empty.activeLyricAt(0))
    }

    private fun timeline(
        events: List<MusicEvent> = emptyList(),
        lyrics: List<LyricLine> = emptyList()
    ) = MusicTimeline("track", 1_000, 120f, 1, events, lyrics)
}
