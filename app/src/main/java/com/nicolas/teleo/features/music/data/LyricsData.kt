package com.nicolas.teleo.features.music.data

import com.nicolas.teleo.features.music.domain.LyricsResult
import com.nicolas.teleo.features.music.domain.LyricsSource
import com.nicolas.teleo.features.music.domain.LyricsTranslationRepository
import com.nicolas.teleo.features.music.domain.LyricsTranslator
import com.nicolas.teleo.features.music.domain.MusicTrack
import com.nicolas.teleo.features.music.domain.TimedLyricLine
import com.nicolas.teleo.features.music.domain.TranslationCacheKey

class LyricsTranslationUnavailableException(message: String) : IllegalStateException(message)

class MockLyricsTranslator : LyricsTranslator {
    private val translations = mapOf(
        "Light draws the rhythm" to "La luz dibuja el ritmo",
        "Every pulse finds a shape" to "Cada pulso encuentra una forma",
        "Movement marks the way" to "El movimiento marca el camino",
        "And the song becomes an image" to "Y la canción se vuelve imagen"
    )

    override suspend fun identifyLanguage(text: String): String? = when {
        text.isBlank() -> null
        text.any { it in 'á'..'ú' || it == 'ñ' } -> "es"
        else -> "en"
    }

    override suspend fun translate(
        lines: List<TimedLyricLine>,
        sourceLanguage: String?,
        targetLanguage: String
    ): List<TimedLyricLine> {
        val source = sourceLanguage ?: lines.firstNotNullOfOrNull { it.sourceLanguage } ?: "en"
        if (!isModelAvailable(source, targetLanguage)) {
            throw LyricsTranslationUnavailableException("La traducción simulada no está disponible para $source → $targetLanguage")
        }
        return lines.map { line ->
            line.copy(translations = line.translations + (targetLanguage to (translations[line.originalText] ?: "[ES] ${line.originalText}")))
        }
    }

    override suspend fun isModelAvailable(sourceLanguage: String, targetLanguage: String): Boolean =
        sourceLanguage == "en" && targetLanguage == "es"
}

class InMemoryLyricsTranslationRepository : LyricsTranslationRepository {
    private val entries = mutableMapOf<TranslationCacheKey, List<TimedLyricLine>>()

    override suspend fun find(key: TranslationCacheKey): List<TimedLyricLine>? = entries[key]

    override suspend fun save(key: TranslationCacheKey, lines: List<TimedLyricLine>) {
        entries[key] = lines
    }

    override suspend fun delete(trackHash: String, targetLanguage: String) {
        entries.keys.removeAll { it.trackHash == trackHash && it.targetLanguage == targetLanguage }
    }
}

class LrcLyricsSource(
    private val contentProvider: suspend (MusicTrack) -> String?
) : LyricsSource {
    override suspend fun findLyrics(track: MusicTrack): LyricsResult {
        val content = contentProvider(track) ?: return LyricsResult.NotFound
        val lines = LrcParser.parse(content)
        return if (lines.isEmpty()) LyricsResult.NotFound else LyricsResult.Found(lines, "Archivo LRC")
    }
}

class ManualLyricsSource(
    private val textProvider: suspend (MusicTrack) -> String?
) : LyricsSource {
    override suspend fun findLyrics(track: MusicTrack): LyricsResult {
        val text = textProvider(track)?.trim().orEmpty()
        if (text.isBlank()) return LyricsResult.NotFound
        val lines = text.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
            TimedLyricLine(
                id = "manual-$index",
                startMs = 0,
                endMs = Long.MAX_VALUE,
                originalText = line.trim(),
                sourceLanguage = null
            )
        }
        return LyricsResult.Found(lines, "Texto manual sin sincronización")
    }
}

class MockLyricsSource(private val lines: List<TimedLyricLine>) : LyricsSource {
    override suspend fun findLyrics(track: MusicTrack): LyricsResult =
        if (lines.isEmpty()) LyricsResult.NotFound else LyricsResult.Found(lines, "Demostración Teleo")
}

object LrcParser {
    private val timestampRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]\\s*(.*)")

    fun parse(content: String, sourceLanguage: String? = null): List<TimedLyricLine> {
        val startsAndText = content.lineSequence().mapNotNull { rawLine ->
            val match = timestampRegex.matchEntire(rawLine.trim()) ?: return@mapNotNull null
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fractionText = match.groupValues[3]
            val fractionMs = when (fractionText.length) {
                1 -> fractionText.toLongOrNull()?.times(100) ?: 0
                2 -> fractionText.toLongOrNull()?.times(10) ?: 0
                3 -> fractionText.toLongOrNull() ?: 0
                else -> 0
            }
            val text = match.groupValues[4].trim()
            if (text.isBlank()) return@mapNotNull null
            Triple(minutes * 60_000 + seconds * 1_000 + fractionMs, text, sourceLanguage)
        }.sortedBy { it.first }.toList()
        return startsAndText.mapIndexed { index, (start, text, language) ->
            val nextStart = startsAndText.getOrNull(index + 1)?.first ?: start + 4_000
            TimedLyricLine(
                id = "lrc-$index-$start",
                startMs = start,
                endMs = (nextStart - 1).coerceAtLeast(start),
                originalText = text,
                sourceLanguage = language
            )
        }
    }
}
