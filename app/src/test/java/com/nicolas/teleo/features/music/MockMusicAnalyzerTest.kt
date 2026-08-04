package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.MockMusicAnalyzer
import com.nicolas.teleo.features.music.domain.MusicAnalysisStage
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicTrack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockMusicAnalyzerTest {
    @Test
    fun `mock analyzer generates ordered multi-lane timeline and progress`() = runBlocking {
        val progressStages = mutableListOf<MusicAnalysisStage>()
        val track = MusicTrack("id", "Demo", null, "content://demo", 20_000)
        val timeline = MockMusicAnalyzer(bpm = 120f, simulatedStageDelayMs = 0).analyze(track) {
            progressStages += it.stage
        }

        assertEquals(MusicAnalysisStage.READY, progressStages.last())
        assertEquals(120f, timeline.bpm)
        assertEquals(20_000, timeline.durationMs)
        assertTrue(timeline.events.zipWithNext().all { (a, b) -> a.timestampMs <= b.timestampMs })
        assertTrue(timeline.events.any { it.type == MusicEventType.KICK })
        assertTrue(timeline.events.any { it.type == MusicEventType.SNARE })
        assertTrue(timeline.events.any { it.type == MusicEventType.BASS })
        assertTrue(timeline.events.any { it.type == MusicEventType.VOCAL_START })
        assertTrue(timeline.events.any { it.type == MusicEventType.MELODY_UP })
        assertTrue(timeline.lyrics.isNotEmpty())
    }
}
