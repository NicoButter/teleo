package com.nicolas.teleo.features.music.data

import android.content.Context
import com.nicolas.teleo.features.music.domain.LyricLine
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicTimeline
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
                    put("startMs", line.startMs)
                    put("endMs", line.endMs)
                    put("text", line.text)
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
                add(LyricLine(item.getLong("startMs"), item.getLong("endMs"), item.getString("text")))
            }
        }
        return MusicTimeline(
            trackId = root.getString("trackId"),
            durationMs = root.getLong("durationMs"),
            bpm = if (root.isNull("bpm")) null else root.getDouble("bpm").toFloat(),
            analysisVersion = root.getInt("analysisVersion"),
            events = events,
            lyrics = lyrics
        )
    }
}
