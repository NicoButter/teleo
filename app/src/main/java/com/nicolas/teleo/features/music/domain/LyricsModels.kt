package com.nicolas.teleo.features.music.domain

data class TimedLyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
) {
    init {
        require(text.isNotBlank()) { "Lyric word cannot be blank" }
        require(startMs >= 0) { "Lyric word start cannot be negative" }
        require(endMs >= startMs) { "Lyric word end cannot precede its start" }
    }
}

data class TimedLyricLine(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val sourceLanguage: String?,
    val translations: Map<String, String> = emptyMap(),
    val words: List<TimedLyricWord> = emptyList(),
    val isCustomTranslation: Boolean = false
) {
    constructor(startMs: Long, endMs: Long, text: String) : this(
        id = "line-$startMs-$endMs",
        startMs = startMs,
        endMs = endMs,
        originalText = text,
        sourceLanguage = null
    )

    val text: String get() = originalText

    init {
        require(id.isNotBlank()) { "Lyric id cannot be blank" }
        require(startMs >= 0) { "Lyric start cannot be negative" }
        require(endMs >= startMs) { "Lyric end cannot precede its start" }
        require(words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs }) {
            "Lyric words must be ordered chronologically"
        }
    }
}

typealias LyricLine = TimedLyricLine

fun TimedLyricLine.activeWordAt(positionMs: Long): TimedLyricWord? =
    words.firstOrNull { positionMs in it.startMs..it.endMs }

fun TimedLyricLine.displayTexts(mode: LyricsDisplayMode, targetLanguage: String): List<String> = when (mode) {
    LyricsDisplayMode.ORIGINAL -> listOf(originalText)
    LyricsDisplayMode.TRANSLATED -> translations[targetLanguage]?.let(::listOf) ?: listOf(originalText)
    LyricsDisplayMode.ORIGINAL_AND_TRANSLATED -> buildList {
        add(originalText)
        translations[targetLanguage]?.takeIf { it != originalText }?.let(::add)
    }
    LyricsDisplayMode.HIDDEN -> emptyList()
}

sealed interface LyricsResult {
    data class Found(val lines: List<TimedLyricLine>, val sourceName: String) : LyricsResult
    data object NotFound : LyricsResult
    data class Error(val message: String) : LyricsResult
}

interface LyricsSource {
    suspend fun findLyrics(track: MusicTrack): LyricsResult
}

interface LyricsTranslator {
    suspend fun identifyLanguage(text: String): String?
    suspend fun translate(
        lines: List<TimedLyricLine>,
        sourceLanguage: String?,
        targetLanguage: String
    ): List<TimedLyricLine>
    suspend fun isModelAvailable(sourceLanguage: String, targetLanguage: String): Boolean
}

data class TranslationCacheKey(
    val trackHash: String,
    val lyricsVersion: Int,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: String,
    val translatorVersion: Int
)

interface LyricsTranslationRepository {
    suspend fun find(key: TranslationCacheKey): List<TimedLyricLine>?
    suspend fun save(key: TranslationCacheKey, lines: List<TimedLyricLine>)
    suspend fun delete(trackHash: String, targetLanguage: String)
}
