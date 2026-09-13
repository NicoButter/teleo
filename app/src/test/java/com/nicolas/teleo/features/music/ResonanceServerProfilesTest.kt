package com.nicolas.teleo.features.music

import com.nicolas.teleo.features.music.data.OkHttpTeleoMusicRemoteDataSource
import com.nicolas.teleo.features.music.data.RemoteMusicCachePaths
import com.nicolas.teleo.features.music.data.ResonanceCredentialStore
import com.nicolas.teleo.features.music.data.ResonanceServerProfileManager
import com.nicolas.teleo.features.music.data.ResonanceServerProfileStore
import com.nicolas.teleo.features.music.domain.ResonanceAuthType
import com.nicolas.teleo.features.music.domain.ResonanceServerProfile
import com.nicolas.teleo.features.music.domain.ResonanceServerUrl
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResonanceServerProfilesTest {
    private val firstId = "11111111-1111-1111-1111-111111111111"

    @Test fun `normalizes only safe https server urls`() {
        assertEquals("https://music.example.com/catalog/", ResonanceServerUrl.normalize(" HTTPS://Music.Example.com/catalog/// "))
        assertFails { ResonanceServerUrl.normalize("http://music.example.com/") }
        assertFails { ResonanceServerUrl.normalize("https://user:secret@music.example.com/") }
        assertFails { ResonanceServerUrl.normalize("https:///missing-host") }
    }

    @Test fun `none deletes existing credential and bearer requires one`() = runBlocking {
        val profileStore = FakeProfileStore()
        val credentials = FakeCredentialStore().apply { tokens[firstId] = "old-token" }
        val manager = ResonanceServerProfileManager(profileStore, credentials) { }
        val none = profile(auth = ResonanceAuthType.NONE)
        manager.save(none, null)
        assertNull(credentials.tokens[firstId])
        assertFailsSuspend { manager.save(profile(auth = ResonanceAuthType.BEARER_TOKEN), null) }
        manager.save(profile(auth = ResonanceAuthType.BEARER_TOKEN), "new-token")
        assertEquals("new-token", credentials.tokens[firstId])
    }

    @Test fun `authorization is attached only for bearer and is absent from errors`() = runBlocking {
        val token = "never-leak-this-token"
        val credentials = FakeCredentialStore().apply { tokens[firstId] = token }
        var header: String? = "unexpected"
        val client = OkHttpClient.Builder().addInterceptor {
            header = it.request().header("Authorization")
            throw IOException("network down")
        }.build()
        val bearer = OkHttpTeleoMusicRemoteDataSource(profile(auth = ResonanceAuthType.BEARER_TOKEN), credentials, client)
        val error = runCatching { bearer.downloadCatalog() }.exceptionOrNull()!!
        assertEquals("Bearer $token", header)
        assertFalse(error.message.orEmpty().contains(token))
        val none = OkHttpTeleoMusicRemoteDataSource(profile(auth = ResonanceAuthType.NONE), credentials, client)
        runCatching { none.downloadCatalog() }
        assertNull(header)
    }

    @Test fun `remote cache paths isolate same track ids by server`() {
        val root = File(System.getProperty("java.io.tmpdir"), "teleo-cache-path-test")
        val secondId = "22222222-2222-2222-2222-222222222222"
        val first = File(RemoteMusicCachePaths.serverDirectory(root, firstId), "same-track/experience.json")
        val second = File(RemoteMusicCachePaths.serverDirectory(root, secondId), "same-track/experience.json")
        assertFalse(first.path == second.path)
        assertTrue(first.path.contains("/$firstId/"))
        assertTrue(second.path.contains("/$secondId/"))
    }

    private fun profile(auth: ResonanceAuthType) = ResonanceServerProfile(
        id = firstId, name = "Resonance", baseUrl = "https://music.example.com/", authType = auth
    )

    private class FakeProfileStore : ResonanceServerProfileStore {
        var profiles = emptyList<ResonanceServerProfile>()
        override suspend fun read() = profiles
        override suspend fun write(profiles: List<ResonanceServerProfile>) { this.profiles = profiles }
    }

    private class FakeCredentialStore : ResonanceCredentialStore {
        val tokens = mutableMapOf<String, String>()
        override suspend fun readBearerToken(serverId: String) = tokens[serverId]
        override suspend fun writeBearerToken(serverId: String, token: String) { tokens[serverId] = token }
        override suspend fun delete(serverId: String) { tokens.remove(serverId) }
    }

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("Expected failure") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun assertFailsSuspend(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected failure") } catch (_: IllegalArgumentException) { }
    }
}
