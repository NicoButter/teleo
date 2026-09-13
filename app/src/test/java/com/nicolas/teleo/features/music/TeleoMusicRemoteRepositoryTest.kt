package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.DefaultTeleoMusicRepository
import com.nicolas.teleo.features.music.data.InMemoryTeleoExperienceLocalDataSource
import com.nicolas.teleo.features.music.data.MusicCatalogRemoteDataSource
import com.nicolas.teleo.features.music.data.RemoteDocument
import com.nicolas.teleo.features.music.data.TeleoExperienceRemoteDataSource
import com.nicolas.teleo.features.music.data.TeleoMusicDtoMapper
import com.nicolas.teleo.features.music.data.TeleoMusicRemoteException
import com.nicolas.teleo.features.music.data.resolveRelativeExperienceUrl
import com.nicolas.teleo.features.music.domain.AudioHashStatus
import com.nicolas.teleo.features.music.domain.AudioValidationResult
import com.nicolas.teleo.features.music.domain.MusicEventType
import com.nicolas.teleo.features.music.domain.RemoteCacheStatus
import com.nicolas.teleo.features.music.domain.ResonanceServerProfile
import com.nicolas.teleo.features.music.domain.VocalVisemeShape
import com.nicolas.teleo.features.music.domain.activeVisemeAt
import com.nicolas.teleo.features.music.domain.validateAudioDuration
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeleoMusicRemoteRepositoryTest {
    private val serverId = "11111111-1111-1111-1111-111111111111"
    private val profile = ResonanceServerProfile(id = serverId, name = "Test", baseUrl = "https://music.example.com/")
    private val catalogJson = """{
      "format":"teleo-music-catalog","version":1,"tracks":[{
      "id":"kinetra-fixture-01","title":"Fixture Resonance","artist":"Teleo Test",
      "durationMs":60000,"experienceVersion":2,"quality":"HUMAN_REVIEWED",
      "experienceUrl":"tracks/kinetra-fixture-01/experience.json"}]}"""
    private val experienceJson = javaClass.classLoader!!.getResource("teleo_experience_v1.json")!!.readText()
    private val mapper = TeleoMusicDtoMapper()

    @Test fun `catalog and experience map real channels without mock injection`() {
        val track = mapper.catalog(catalogJson, serverId).tracks.single()
        val parsed = mapper.experience(experienceJson, track)
        assertEquals(MusicEventType.KICK, parsed.timeline.events.first { it.timestampMs == 1_000L }.type)
        assertTrue(parsed.timeline.lyrics.isEmpty())
        assertEquals(1, parsed.timeline.hapticEvents.size)
        assertEquals(VocalVisemeShape.G, parsed.timeline.activeVisemeAt(10_100)?.shape)
        assertEquals(VocalVisemeShape.X, parsed.timeline.activeVisemeAt(10_300)?.shape)
        assertNull(parsed.timeline.activeVisemeAt(10_400))
        assertEquals("abcd", parsed.sourceHash?.value)
    }

    @Test fun `unsupported schemas and unsafe experience urls are rejected`() {
        assertThrows<TeleoMusicRemoteException.UnsupportedSchema> {
            mapper.catalog(catalogJson.replace("\"version\":1", "\"version\":99"), serverId)
        }
        val base = URI("https://music.example.com/releases/")
        assertEquals("https://music.example.com/releases/tracks/a/experience.json", resolveRelativeExperienceUrl(base, "tracks/a/experience.json"))
        assertThrows<TeleoMusicRemoteException.UnsafeUrl> { resolveRelativeExperienceUrl(base, "https://evil.example/x") }
        assertThrows<TeleoMusicRemoteException.UnsafeUrl> { resolveRelativeExperienceUrl(base, "../experience.json") }
    }

    @Test fun `repository caches offline and refreshes newer experience versions`() = runBlocking {
        val local = InMemoryTeleoExperienceLocalDataSource()
        var version = 1
        val catalog = object : MusicCatalogRemoteDataSource {
            override suspend fun downloadCatalog() = RemoteDocument(catalogJson.replace("\"experienceVersion\":2", "\"experienceVersion\":$version"), "https://music.example.com/catalog.json", 10)
        }
        val experience = object : TeleoExperienceRemoteDataSource {
            override suspend fun downloadExperience(path: String) = RemoteDocument(experienceJson.replace(Regex("\"experienceVersion\"\\s*:\\s*2"), "\"experienceVersion\":$version"), "https://music.example.com/$path", 99)
        }
        val repository = DefaultTeleoMusicRepository(profile, catalog, experience, local)
        val firstTrack = repository.loadCatalog().catalog.tracks.single()
        assertEquals(RemoteCacheStatus.MISS, repository.loadExperience(firstTrack).cacheStatus)
        assertEquals(RemoteCacheStatus.HIT, repository.loadExperience(firstTrack).cacheStatus)
        version = 2
        val updatedTrack = repository.loadCatalog().catalog.tracks.single()
        assertEquals(RemoteCacheStatus.UPDATED, repository.loadExperience(updatedTrack).cacheStatus)
    }

    @Test fun `audio duration validation accepts tolerance and rejects a different song`() {
        assertTrue(validateAudioDuration(60_000, 61_499) is AudioValidationResult.Valid)
        assertTrue(validateAudioDuration(60_000, 61_501) is AudioValidationResult.Mismatch)
        assertEquals(AudioHashStatus.NOT_AVAILABLE, (validateAudioDuration(60_000, 60_000) as AudioValidationResult.Valid).hashStatus)
    }

    @Test fun `mapper preserves protocol signal channels and optional arrays`() {
        val track = mapper.catalog(catalogJson, serverId).tracks.single()
        val raw = """{
          "format":"teleo-music","version":1,"experienceVersion":2,
          "track":{"id":"kinetra-fixture-01","title":"Fixture","durationMs":60000},
          "channelsQuality":{"drums":"reliable","bass":"unreliable","vocals":"reliable","other":"reliable"},
          "drums":{"events":[{"startMs":10,"durationMs":30,"type":"snare","intensity":0.8}]},
          "bass":{"notes":[{"startMs":20,"endMs":80,"midi":40,"intensity":0.7,"confidence":0.9}]},
          "vocals":{"frames":[{"timestampMs":15,"presence":0.6,"pitchNormalized":1.4,"pitchConfidence":0.9}],"visemes":[{"startMs":20,"endMs":40,"shape":"F"}]},
          "other":{"frames":[{"timestampMs":15,"lowEnergy":0.4,"overallEnergy":0.5}]},
          "timeline":{"events":[{"startMs":25,"type":"kick"}]}
        }"""
        val parsed = mapper.experience(raw, track).experience
        assertEquals(1, parsed.drums.size)
        assertEquals(40, parsed.bassNotes.single().midi)
        assertEquals(1f, parsed.vocalFrames.single().pitchNormalized)
        assertEquals(VocalVisemeShape.F, parsed.visemes.single().shape)
        assertEquals(1, parsed.ambientFrames.size)
        assertTrue(parsed.isReliable("drums"))
        assertTrue(!parsed.isReliable("bass"))
    }

    @Test fun `local import reuses protocol validation without a catalog or remote source`() {
        val imported = mapper.importedExperience(experienceJson)
        assertEquals("kinetra-fixture-01", imported.trackId)
        assertEquals(60_000L, imported.timeline.durationMs)
        assertEquals(MusicEventType.KICK, imported.timeline.events.first { it.timestampMs == 1_000L }.type)
        assertEquals("abcd", imported.sourceHash?.value)
        assertThrows<TeleoMusicRemoteException.UnsupportedSchema> {
            mapper.importedExperience(experienceJson.replace("\"version\": 1", "\"version\": 2"))
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName}")
        } catch (error: Throwable) {
            if (error is AssertionError) throw error
            assertTrue("Expected ${T::class.simpleName}, got ${error::class.simpleName}", error is T)
        }
    }
}
