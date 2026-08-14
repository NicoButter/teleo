package com.nicolas.teleo.features.music.data

import android.content.Context
import com.nicolas.teleo.BuildConfig
import com.nicolas.teleo.features.music.domain.ExperienceQuality
import com.nicolas.teleo.features.music.domain.MusicEvent
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.MusicFeatureFrame
import com.nicolas.teleo.features.music.domain.MusicTimeline
import com.nicolas.teleo.features.music.domain.RemoteCacheStatus
import com.nicolas.teleo.features.music.domain.RemoteMusicExperience
import com.nicolas.teleo.features.music.domain.SourceHash
import com.nicolas.teleo.features.music.domain.TeleoMusicCatalog
import com.nicolas.teleo.features.music.domain.TeleoMusicCatalogTrack
import com.nicolas.teleo.features.music.domain.TimedLyricLine
import com.nicolas.teleo.features.music.domain.TimedLyricWord
import com.nicolas.teleo.features.music.domain.VocalVisemeEvent
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

private const val REMOTE_LOG = "MUSIC_REMOTE"
private const val CATALOG_FILE = "catalog.json"
private const val METADATA_FILE = "metadata.json"
private const val EXPERIENCE_FILE = "experience.json"
private const val SUPPORTED_SCHEMA_VERSION = 1

sealed class TeleoMusicRemoteException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : TeleoMusicRemoteException("No se pudo conectar con experiencias Teleo.", cause)
    class Http(val statusCode: Int) : TeleoMusicRemoteException("El servidor de experiencias respondió $statusCode.")
    class InvalidCatalog(cause: Throwable) : TeleoMusicRemoteException("El catálogo de experiencias no es válido.", cause)
    class InvalidExperience(cause: Throwable) : TeleoMusicRemoteException("La experiencia descargada no es válida.", cause)
    class UnsupportedSchema : TeleoMusicRemoteException("Esta experiencia requiere una versión más reciente de Teleo.")
    class UnsafeUrl : TeleoMusicRemoteException("La URL de la experiencia no es válida.")
    class CacheCorrupted : TeleoMusicRemoteException("La caché de esta experiencia está dañada.")
}

data class RemoteDocument(val body: String, val sourceUrl: String, val bytes: Long)

interface MusicCatalogRemoteDataSource {
    suspend fun downloadCatalog(): RemoteDocument
}

interface TeleoExperienceRemoteDataSource {
    suspend fun downloadExperience(relativePath: String): RemoteDocument
}

interface TeleoExperienceLocalDataSource {
    suspend fun readCatalog(): String?
    suspend fun writeCatalog(json: String)
    suspend fun readExperience(trackId: String): CachedExperience?
    suspend fun writeExperience(trackId: String, metadata: ExperienceCacheMetadata, json: String)
    suspend fun deleteExperience(trackId: String)
}

data class CachedExperience(val metadata: ExperienceCacheMetadata, val json: String)

@Serializable
data class ExperienceCacheMetadata(
    val trackId: String,
    val schemaVersion: Int,
    val experienceVersion: Int,
    val downloadedAt: Long,
    val sourceUrl: String
)

class OkHttpTeleoMusicRemoteDataSource(
    private val baseUrl: String = BuildConfig.TELEO_MUSIC_BASE_URL,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) : MusicCatalogRemoteDataSource, TeleoExperienceRemoteDataSource {
    private val baseUri: URI = validatedBaseUri(baseUrl)

    override suspend fun downloadCatalog(): RemoteDocument = get(resolveRelativeExperienceUrl(baseUri, "catalog.json"))

    override suspend fun downloadExperience(relativePath: String): RemoteDocument =
        get(resolveRelativeExperienceUrl(baseUri, relativePath))

    private suspend fun get(url: String): RemoteDocument = withContext(Dispatchers.IO) {
        android.util.Log.d(REMOTE_LOG, "Request $url")
        val call = client.newCall(Request.Builder().url(url).get().build())
        try {
            val response = call.execute()
            response.use {
                if (!it.isSuccessful) throw TeleoMusicRemoteException.Http(it.code)
                val body = it.body?.string() ?: throw TeleoMusicRemoteException.InvalidExperience(IllegalStateException("Empty response"))
                RemoteDocument(body, url, body.toByteArray(Charsets.UTF_8).size.toLong())
            }
        } catch (error: TeleoMusicRemoteException) {
            throw error
        } catch (error: Exception) {
            throw TeleoMusicRemoteException.Network(error)
        }
    }
}

/** Only relative paths are accepted in v1, preventing a catalog from redirecting requests to another host. */
fun resolveRelativeExperienceUrl(baseUri: URI, path: String): String {
    if (path.isBlank() || path.contains("\\") || path.startsWith("/") || path.contains("://")) throw TeleoMusicRemoteException.UnsafeUrl()
    val relative = URI(path)
    if (relative.isAbsolute || relative.host != null || relative.path.split('/').any { it == ".." }) throw TeleoMusicRemoteException.UnsafeUrl()
    return baseUri.resolve(relative).toString()
}

private fun validatedBaseUri(raw: String): URI = try {
    URI(raw).also { require(it.scheme == "https" && !it.host.isNullOrBlank()) }
} catch (_: Exception) {
    throw IllegalArgumentException("TELEO_MUSIC_BASE_URL must be an HTTPS URL with host")
}

class FileTeleoExperienceLocalDataSource(context: Context) : TeleoExperienceLocalDataSource {
    private val root = File(context.filesDir, "music_timelines/remote")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun readCatalog(): String? = withContext(Dispatchers.IO) {
        File(root, CATALOG_FILE).takeIf(File::isFile)?.readText()
    }

    override suspend fun writeCatalog(jsonText: String) = withContext(Dispatchers.IO) {
        writeAtomically(File(root, CATALOG_FILE), jsonText)
    }

    override suspend fun readExperience(trackId: String): CachedExperience? = withContext(Dispatchers.IO) {
        val directory = trackDirectory(trackId)
        val document = File(directory, EXPERIENCE_FILE)
        val metadata = File(directory, METADATA_FILE)
        if (!document.isFile || !metadata.isFile) return@withContext null
        runCatching {
            CachedExperience(json.decodeFromString(ExperienceCacheMetadata.serializer(), metadata.readText()), document.readText())
        }.getOrElse {
            directory.deleteRecursively()
            null
        }
    }

    override suspend fun writeExperience(trackId: String, metadata: ExperienceCacheMetadata, jsonText: String) = withContext(Dispatchers.IO) {
        val directory = trackDirectory(trackId)
        writeAtomically(File(directory, EXPERIENCE_FILE), jsonText)
        writeAtomically(File(directory, METADATA_FILE), json.encodeToString(ExperienceCacheMetadata.serializer(), metadata))
    }

    override suspend fun deleteExperience(trackId: String): Unit = withContext(Dispatchers.IO) {
        trackDirectory(trackId).deleteRecursively()
        Unit
    }

    private fun trackDirectory(trackId: String): File {
        val safeId = trackId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        require(safeId.isNotBlank()) { "Invalid track id" }
        return File(root, safeId)
    }

    private fun writeAtomically(destination: File, content: String) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(destination)) {
            destination.writeText(temporary.readText())
            temporary.delete()
        }
    }
}

class InMemoryTeleoExperienceLocalDataSource : TeleoExperienceLocalDataSource {
    var catalog: String? = null
    val experiences = mutableMapOf<String, CachedExperience>()
    override suspend fun readCatalog(): String? = catalog
    override suspend fun writeCatalog(json: String) { catalog = json }
    override suspend fun readExperience(trackId: String): CachedExperience? = experiences[trackId]
    override suspend fun writeExperience(trackId: String, metadata: ExperienceCacheMetadata, json: String) {
        experiences[trackId] = CachedExperience(metadata, json)
    }
    override suspend fun deleteExperience(trackId: String) { experiences.remove(trackId) }
}

interface TeleoMusicRepository {
    suspend fun loadCatalog(): CatalogLoad
    suspend fun loadExperience(track: TeleoMusicCatalogTrack): RemoteMusicExperience
}

data class CatalogLoad(val catalog: TeleoMusicCatalog, val offlineCache: Boolean, val warning: String? = null)

class DefaultTeleoMusicRepository(
    private val catalogRemote: MusicCatalogRemoteDataSource,
    private val experienceRemote: TeleoExperienceRemoteDataSource,
    private val local: TeleoExperienceLocalDataSource,
    private val mapper: TeleoMusicDtoMapper = TeleoMusicDtoMapper()
) : TeleoMusicRepository {
    override suspend fun loadCatalog(): CatalogLoad {
        return try {
            val remote = catalogRemote.downloadCatalog()
            val catalog = try { mapper.catalog(remote.body) } catch (error: Exception) { throw TeleoMusicRemoteException.InvalidCatalog(error) }
            local.writeCatalog(remote.body)
            CatalogLoad(catalog, offlineCache = false)
        } catch (network: TeleoMusicRemoteException) {
            val cached = local.readCatalog() ?: throw network
            val catalog = try { mapper.catalog(cached) } catch (_: Exception) { throw TeleoMusicRemoteException.CacheCorrupted() }
            CatalogLoad(catalog, offlineCache = true, warning = "Sin conexión. Usando catálogo descargado.")
        }
    }

    override suspend fun loadExperience(track: TeleoMusicCatalogTrack): RemoteMusicExperience {
        val cached = local.readExperience(track.id)
        val cachedValid = cached?.takeIf { it.metadata.experienceVersion >= track.experienceVersion }
        if (cachedValid != null) {
            runCatching { parseExperience(track, cachedValid.json, cachedValid.metadata.sourceUrl, 0, RemoteCacheStatus.HIT) }
                .onSuccess { return it }
            local.deleteExperience(track.id)
        }
        try {
            val remote = experienceRemote.downloadExperience(track.experiencePath)
            val parsed = parseExperience(track, remote.body, remote.sourceUrl, remote.bytes, if (cached == null) RemoteCacheStatus.MISS else RemoteCacheStatus.UPDATED)
            local.writeExperience(track.id, ExperienceCacheMetadata(track.id, parsed.schemaVersion, track.experienceVersion, System.currentTimeMillis(), remote.sourceUrl), remote.body)
            return parsed
        } catch (network: TeleoMusicRemoteException) {
            if (cached != null) return parseExperience(track, cached.json, cached.metadata.sourceUrl, 0, RemoteCacheStatus.OFFLINE)
            throw network
        }
    }

    private fun parseExperience(track: TeleoMusicCatalogTrack, raw: String, url: String, bytes: Long, status: RemoteCacheStatus): RemoteMusicExperience = try {
        val parsed = mapper.experience(raw, track)
        RemoteMusicExperience(track.copy(sourceHash = parsed.sourceHash ?: track.sourceHash), parsed.schemaVersion, parsed.timeline, url, bytes, status)
    } catch (error: TeleoMusicRemoteException) { throw error }
    catch (error: Exception) { throw TeleoMusicRemoteException.InvalidExperience(error) }
}

@Serializable
data class TeleoMusicCatalogDto(
    val format: String,
    val version: Int,
    val tracks: List<TeleoMusicCatalogTrackDto>
)

@Serializable
data class TeleoMusicCatalogTrackDto(
    val id: String,
    val title: String,
    val artist: String? = null,
    val durationMs: Long,
    val experienceVersion: Int,
    val quality: String = "AUTOMATIC",
    val experienceUrl: String,
    val sourceHash: SourceHashDto? = null
)

@Serializable data class SourceHashDto(val algorithm: String, val value: String)

@Serializable
data class TeleoExperienceDto(
    val format: String,
    val version: Int,
    val experienceVersion: Int? = null,
    val track: ExperienceTrackDto,
    val drums: TimedChannelDto = TimedChannelDto(),
    val bass: TimedChannelDto = TimedChannelDto(),
    val guitar: TimedChannelDto = TimedChannelDto(),
    val piano: TimedChannelDto = TimedChannelDto(),
    val vocals: VocalsChannelDto = VocalsChannelDto(),
    val other: TimedChannelDto = TimedChannelDto(),
    val lyrics: List<LyricDto> = emptyList(),
    val sections: List<SectionDto> = emptyList(),
    val haptics: List<TimedEventDto> = emptyList(),
    val featureFrames: List<FeatureFrameDto> = emptyList()
)

@Serializable data class ExperienceTrackDto(val id: String, val title: String, val artist: String? = null, val durationMs: Long, val sourceHash: SourceHashDto? = null)
@Serializable data class TimedChannelDto(val events: List<TimedEventDto> = emptyList())
@Serializable data class VocalsChannelDto(val events: List<TimedEventDto> = emptyList(), val visemes: List<VisemeDto> = emptyList())
@Serializable data class TimedEventDto(val startMs: Long, val durationMs: Long = 0, val endMs: Long? = null, val type: String? = null, val intensity: Float = 1f, val label: String? = null)
@Serializable data class VisemeDto(val startMs: Long, val endMs: Long, val shape: String, val intensity: Float = 1f)
@Serializable data class LyricDto(val id: String? = null, val startMs: Long, val endMs: Long, val text: String, val language: String? = null, val translations: Map<String, String> = emptyMap(), val words: List<LyricWordDto> = emptyList())
@Serializable data class LyricWordDto(val text: String, val startMs: Long, val endMs: Long)
@Serializable data class SectionDto(val startMs: Long, val endMs: Long? = null, val name: String)
@Serializable data class FeatureFrameDto(val timestampMs: Long, val beatPhase: Float = 0f, val beatStrength: Float = 0f, val lowEnergy: Float = 0f, val midEnergy: Float = 0f, val highEnergy: Float = 0f, val vocalPresence: Float = 0f, val melodicPitchNormalized: Float? = null, val spectralBrightness: Float = 0f, val overallEnergy: Float = 0f, val sectionId: String? = null)

class TeleoMusicDtoMapper(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun catalog(raw: String): TeleoMusicCatalog {
        val dto = json.decodeFromString(TeleoMusicCatalogDto.serializer(), raw)
        validateProtocol(dto.format, dto.version, "teleo-music-catalog")
        return TeleoMusicCatalog(dto.tracks.map {
            TeleoMusicCatalogTrack(it.id, it.title, it.artist, it.durationMs, it.experienceVersion, ExperienceQuality.fromWire(it.quality), it.experienceUrl, it.sourceHash?.toDomain())
        }, dto.version)
    }

    fun experience(raw: String, catalogTrack: TeleoMusicCatalogTrack): ParsedExperience {
        val dto = json.decodeFromString(TeleoExperienceDto.serializer(), raw)
        validateProtocol(dto.format, dto.version, "teleo-music")
        require(dto.track.id == catalogTrack.id) { "Catalog and experience track IDs differ" }
        require(dto.track.durationMs == catalogTrack.durationMs) { "Catalog and experience durations differ" }
        dto.experienceVersion?.let { require(it == catalogTrack.experienceVersion) { "Experience version differs from catalog" } }
        val events = buildList {
            addAll(dto.drums.events.map { it.toEvent(drumType(it.type)) })
            addAll(dto.bass.events.map { it.toEvent(MusicEventType.BASS) })
            addAll(dto.guitar.events.map { it.toEvent(MusicEventType.GUITAR) })
            addAll(dto.piano.events.map { it.toEvent(MusicEventType.PIANO) })
            addAll(dto.vocals.events.map { it.toEvent(vocalType(it.type)) })
            addAll(dto.other.events.map { it.toEvent(MusicEventType.OTHER) })
            addAll(dto.sections.map { MusicEvent(it.startMs, 0, MusicEventType.SECTION_START, 1f, it.name) })
        }.sortedBy(MusicEvent::timestampMs)
        val haptics = dto.haptics.map { it.toEvent(hapticType(it.type)) }.sortedBy(MusicEvent::timestampMs)
        val lyrics = dto.lyrics.mapIndexed { index, item ->
            TimedLyricLine(item.id ?: "remote-$index-${item.startMs}", item.startMs, item.endMs, item.text, item.language, item.translations,
                item.words.map { TimedLyricWord(it.text, it.startMs, it.endMs) })
        }.sortedBy(TimedLyricLine::startMs)
        val frames = dto.featureFrames.map { it.toDomain() }.sortedBy(MusicFeatureFrame::timestampMs)
        val visemes = dto.vocals.visemes.map { VocalVisemeEvent(it.startMs, it.endMs, VocalVisemeShape.fromWire(it.shape), it.intensity.coerceIn(0f, 1f)) }.sortedBy(VocalVisemeEvent::startMs)
        return ParsedExperience(dto.version, MusicTimeline(catalogTrack.id, dto.track.durationMs, null, catalogTrack.experienceVersion, events, lyrics, frames, visemes, haptics), dto.track.sourceHash?.toDomain())
    }

    private fun validateProtocol(format: String, version: Int, expected: String) {
        if (format != expected) throw TeleoMusicRemoteException.InvalidExperience(IllegalArgumentException("Unexpected format"))
        if (version != SUPPORTED_SCHEMA_VERSION) throw TeleoMusicRemoteException.UnsupportedSchema()
    }

    private fun TimedEventDto.toEvent(fallback: MusicEventType) = MusicEvent(startMs, endMs?.minus(startMs)?.coerceAtLeast(0) ?: durationMs, type?.let(::eventType) ?: fallback, intensity.coerceIn(0f, 1f), label)
    private fun drumType(type: String?): MusicEventType = when (type?.lowercase()?.replace('-', '_')) {
        "kick", "bass_drum" -> MusicEventType.KICK
        "snare" -> MusicEventType.SNARE
        "hi_hat", "hihat", "hat" -> MusicEventType.HI_HAT
        "tom", "toms" -> MusicEventType.TOM
        "cymbal", "crash", "ride" -> MusicEventType.CYMBAL
        else -> MusicEventType.OTHER
    }
    private fun vocalType(type: String?): MusicEventType = if (type.equals("end", true) || type.equals("vocal_end", true)) MusicEventType.VOCAL_END else MusicEventType.VOCAL_START
    private fun hapticType(type: String?): MusicEventType = type?.let(::eventType) ?: MusicEventType.OTHER
    private fun eventType(type: String): MusicEventType = when (type.lowercase().replace('-', '_')) {
        "kick", "bass_drum" -> MusicEventType.KICK; "snare" -> MusicEventType.SNARE; "hi_hat", "hihat", "hat" -> MusicEventType.HI_HAT
        "bass" -> MusicEventType.BASS; "section", "section_start" -> MusicEventType.SECTION_START; else -> MusicEventType.OTHER
    }
    private fun SourceHashDto.toDomain() = SourceHash(algorithm, value)
    private fun FeatureFrameDto.toDomain() = MusicFeatureFrame(timestampMs, beatPhase.coerceIn(0f, 1f), beatStrength.coerceIn(0f, 1f), lowEnergy.coerceIn(0f, 1f), midEnergy.coerceIn(0f, 1f), highEnergy.coerceIn(0f, 1f), vocalPresence.coerceIn(0f, 1f), melodicPitchNormalized?.coerceIn(0f, 1f), spectralBrightness.coerceIn(0f, 1f), overallEnergy.coerceIn(0f, 1f), sectionId)
}

data class ParsedExperience(val schemaVersion: Int, val timeline: MusicTimeline, val sourceHash: SourceHash? = null)
