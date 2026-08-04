package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.InMemoryMusicTimelineRepository
import com.nicolas.teleo.features.music.domain.MusicTimeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicTimelineRepositoryTest {
    @Test
    fun `timeline cache returns saved value and misses unknown hash`() = runBlocking {
        val repository = InMemoryMusicTimelineRepository()
        val timeline = MusicTimeline("track", 1_000, null, 1, emptyList(), emptyList())

        assertNull(repository.findByTrackHash("unknown"))
        repository.save("known", timeline)
        assertEquals(timeline, repository.findByTrackHash("known"))
    }
}
