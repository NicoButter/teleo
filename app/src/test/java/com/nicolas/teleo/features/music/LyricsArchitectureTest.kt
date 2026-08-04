package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.InMemoryLyricsTranslationRepository
import com.nicolas.teleo.features.music.data.LrcParser
import com.nicolas.teleo.features.music.data.LyricsTranslationUnavailableException
import com.nicolas.teleo.features.music.data.MockLyricsTranslator
import com.nicolas.teleo.features.music.domain.LyricsDisplayMode
import com.nicolas.teleo.features.music.domain.TimedLyricLine
import com.nicolas.teleo.features.music.domain.TimedLyricWord
import com.nicolas.teleo.features.music.domain.TranslationCacheKey
import com.nicolas.teleo.features.music.domain.activeWordAt
import com.nicolas.teleo.features.music.domain.displayTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsArchitectureTest {
    @Test
    fun `active word follows its own timestamps`() {
        val line = TimedLyricLine(
            "id", 0, 1_000, "Light moves", "en", words = listOf(
                TimedLyricWord("Light", 0, 499),
                TimedLyricWord("moves", 500, 1_000)
            )
        )
        assertEquals("moves", line.activeWordAt(750)?.text)
        assertNull(line.activeWordAt(1_500))
    }

    @Test
    fun `lyrics mode selects original translation both or hidden`() {
        val line = TimedLyricLine("id", 0, 1_000, "Light", "en", mapOf("es" to "Luz"))
        assertEquals(listOf("Light"), line.displayTexts(LyricsDisplayMode.ORIGINAL, "es"))
        assertEquals(listOf("Luz"), line.displayTexts(LyricsDisplayMode.TRANSLATED, "es"))
        assertEquals(listOf("Light", "Luz"), line.displayTexts(LyricsDisplayMode.ORIGINAL_AND_TRANSLATED, "es"))
        assertTrue(line.displayTexts(LyricsDisplayMode.HIDDEN, "es").isEmpty())
    }

    @Test
    fun `translation cache is isolated by target language`() = runBlocking {
        val repository = InMemoryLyricsTranslationRepository()
        val spanish = key("es")
        val french = key("fr")
        val lines = listOf(TimedLyricLine(0, 100, "Light"))
        repository.save(spanish, lines)
        assertEquals(lines, repository.find(spanish))
        assertNull(repository.find(french))
    }

    @Test(expected = LyricsTranslationUnavailableException::class)
    fun `mock translator reports unsupported language pair`() {
        runBlocking {
            MockLyricsTranslator().translate(listOf(TimedLyricLine(0, 100, "Light")), "en", "de")
        }
    }

    @Test
    fun `lrc source parses synchronized lines`() {
        val lines = LrcParser.parse("[00:01.20]First line\n[00:03.50]Second line", "en")
        assertEquals(1_200, lines.first().startMs)
        assertEquals(3_499, lines.first().endMs)
        assertEquals("Second line", lines.last().originalText)
    }

    private fun key(language: String) = TranslationCacheKey("track", 1, "en", language, "mock", 1)
}
