package com.nicolas.teleo.features.music.data

import android.content.Context
import com.nicolas.teleo.features.music.domain.LyricLine
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.TimedLyricWord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface MusicTimelineRepository {
    suspend fun findByTrackHash(hash: String): MusicTimeline?
    suspend fun save(hash: String, timeline: MusicTimeline)
}

class FileMusicTimelineRepository(context: Context) : MusicTimelineRepository {
    private val cacheDirectory = File(context.filesDir, "music_timelines")

    override suspend fun findByTrackHash(hash: String): MusicTimeline? = withContext(Dispatchers.IO) {
        val file = fileFor(hash)
        if (!file.isFile) return@withContext null
        runCatching { MusicTimelineJson.decode(file.readText()) }.getOrNull()
    }

    override suspend fun save(hash: String, timeline: MusicTimeline) = withContext(Dispatchers.IO) {
        cacheDirectory.mkdirs()
        val destination = fileFor(hash)
        val temporary = File(cacheDirectory, "${destination.name}.tmp")
        temporary.writeText(MusicTimelineJson.encode(timeline))
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText())
            temporary.delete()
        }
    }

    private fun fileFor(hash: String): File {
        val safeHash = hash.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safeHash.isNotBlank()) { "Invalid track hash" }
        return File(cacheDirectory, "$safeHash.json")
    }
}

class InMemoryMusicTimelineRepository : MusicTimelineRepository {
    private val timelines = mutableMapOf<String, MusicTimeline>()

    override suspend fun findByTrackHash(hash: String): MusicTimeline? = timelines[hash]

    override suspend fun save(hash: String, timeline: MusicTimeline) {
        timelines[hash] = timeline
    }
}

object MusicTimelineJson {
    fun encode(timeline: MusicTimeline): String = JSONObject().apply {
        put("trackId", timeline.trackId)
        put("durationMs", timeline.durationMs)
        put("bpm", timeline.bpm ?: JSONObject.NULL)
        put("analysisVersion", timeline.analysisVersion)
        put("events", JSONArray().apply {
            timeline.events.forEach { event ->
                put(JSONObject().apply {
                    put("timestampMs", event.timestampMs)
                    put("durationMs", event.durationMs)
                    put("type", event.type.name)
                    put("intensity", event.intensity.toDouble())
                    put("label", event.label ?: JSONObject.NULL)
                })
            }
        })
        put("lyrics", JSONArray().apply {
            timeline.lyrics.forEach { line ->
                put(JSONObject().apply {
                    put("id", line.id)
                    put("startMs", line.startMs)
                    put("endMs", line.endMs)
                    put("originalText", line.originalText)
                    put("sourceLanguage", line.sourceLanguage ?: JSONObject.NULL)
                    put("translations", JSONObject().apply {
                        line.translations.forEach { (language, text) -> put(language, text) }
                    })
                    put("words", JSONArray().apply {
                        line.words.forEach { word ->
                            put(JSONObject().apply {
                                put("text", word.text)
                                put("startMs", word.startMs)
                                put("endMs", word.endMs)
                            })
                        }
                    })
                    put("isCustomTranslation", line.isCustomTranslation)
                })
            }
        })
        put("featureFrames", JSONArray().apply {
            timeline.featureFrames.forEach { frame ->
                put(JSONObject().apply {
                    put("timestampMs", frame.timestampMs)
                    put("beatPhase", frame.beatPhase.toDouble())
                    put("beatStrength", frame.beatStrength.toDouble())
                    put("lowEnergy", frame.lowEnergy.toDouble())
                    put("midEnergy", frame.midEnergy.toDouble())
                    put("highEnergy", frame.highEnergy.toDouble())
                    put("vocalPresence", frame.vocalPresence.toDouble())
                    put("melodicPitchNormalized", frame.melodicPitchNormalized?.toDouble() ?: JSONObject.NULL)
                    put("spectralBrightness", frame.spectralBrightness.toDouble())
                    put("overallEnergy", frame.overallEnergy.toDouble())
                    put("sectionId", frame.sectionId ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    fun decode(json: String): MusicTimeline {
        val root = JSONObject(json)
        val eventArray = root.getJSONArray("events")
        val events = buildList {
            for (index in 0 until eventArray.length()) {
                val item = eventArray.getJSONObject(index)
                add(
                    MusicEvent(
                        timestampMs = item.getLong("timestampMs"),
                        durationMs = item.getLong("durationMs"),
                        type = MusicEventType.valueOf(item.getString("type")),
                        intensity = item.getDouble("intensity").toFloat(),
                        label = item.optString("label").takeIf { !item.isNull("label") }
                    )
                )
            }
        }
        val lyricArray = root.getJSONArray("lyrics")
        val lyrics = buildList {
            for (index in 0 until lyricArray.length()) {
                val item = lyricArray.getJSONObject(index)
                val translationsObject = item.optJSONObject("translations")
                val translations = buildMap {
                    translationsObject?.keys()?.forEach { language -> put(language, translationsObject.getString(language)) }
                }
                val wordsArray = item.optJSONArray("words")
                val words = buildList {
                    if (wordsArray != null) for (wordIndex in 0 until wordsArray.length()) {
                        val word = wordsArray.getJSONObject(wordIndex)
                        add(TimedLyricWord(word.getString("text"), word.getLong("startMs"), word.getLong("endMs")))
                    }
                }
                val startMs = item.getLong("startMs")
                val endMs = item.getLong("endMs")
                add(
                    LyricLine(
                        id = item.optString("id", "line-$startMs-$endMs"),
                        startMs = startMs,
                        endMs = endMs,
                        originalText = if (item.has("originalText")) item.getString("originalText") else item.getString("text"),
                        sourceLanguage = item.optString("sourceLanguage").takeIf { !item.isNull("sourceLanguage") && it.isNotBlank() },
                        translations = translations,
                        words = words,
                        isCustomTranslation = item.optBoolean("isCustomTranslation", false)
                    )
                )
            }
        }
        val frameArray = root.optJSONArray("featureFrames")
        val featureFrames = buildList {
            if (frameArray != null) for (index in 0 until frameArray.length()) {
                val item = frameArray.getJSONObject(index)
                add(
                    MusicFeatureFrame(
                        timestampMs = item.getLong("timestampMs"),
                        beatPhase = item.getDouble("beatPhase").toFloat(),
                        beatStrength = item.getDouble("beatStrength").toFloat(),
                        lowEnergy = item.getDouble("lowEnergy").toFloat(),
                        midEnergy = item.getDouble("midEnergy").toFloat(),
                        highEnergy = item.getDouble("highEnergy").toFloat(),
                        vocalPresence = item.getDouble("vocalPresence").toFloat(),
                        melodicPitchNormalized = if (item.isNull("melodicPitchNormalized")) null else item.getDouble("melodicPitchNormalized").toFloat(),
                        spectralBrightness = item.getDouble("spectralBrightness").toFloat(),
                        overallEnergy = item.getDouble("overallEnergy").toFloat(),
                        sectionId = item.optString("sectionId").takeIf { !item.isNull("sectionId") && it.isNotBlank() }
                    )
                )
            }
        }
        return MusicTimeline(
            trackId = root.getString("trackId"),
            durationMs = root.getLong("durationMs"),
            bpm = if (root.isNull("bpm")) null else root.getDouble("bpm").toFloat(),
            analysisVersion = root.getInt("analysisVersion"),
            events = events,
            lyrics = lyrics,
            featureFrames = featureFrames
        )
    }
}
