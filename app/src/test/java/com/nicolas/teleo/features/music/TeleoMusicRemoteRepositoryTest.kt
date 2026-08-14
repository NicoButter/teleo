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
    private val catalogJson = """{
      "format":"teleo-music-catalog","version":1,"tracks":[{
      "id":"kinetra-fixture-01","title":"Fixture Resonance","artist":"Teleo Test",
      "durationMs":60000,"experienceVersion":2,"quality":"HUMAN_REVIEWED",
      "experienceUrl":"tracks/kinetra-fixture-01/experience.json"}]}"""
    private val experienceJson = javaClass.classLoader!!.getResource("teleo_experience_v1.json")!!.readText()
    private val mapper = TeleoMusicDtoMapper()

    @Test fun `catalog and experience map real channels without mock injection`() {
        val track = mapper.catalog(catalogJson).tracks.single()
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
            mapper.catalog(catalogJson.replace("\"version\":1", "\"version\":99"))
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
        val repository = DefaultTeleoMusicRepository(catalog, experience, local)
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
