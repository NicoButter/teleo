package com.nicolas.teleo.features.music.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nicolas.teleo.features.music.domain.ResonanceAuthType
import com.nicolas.teleo.features.music.domain.ResonanceServerProfile
import com.nicolas.teleo.features.music.domain.ResonanceServerUrl
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface ResonanceServerProfileStore {
    suspend fun read(): List<ResonanceServerProfile>
    suspend fun write(profiles: List<ResonanceServerProfile>)
}

class FileResonanceServerProfileStore(context: Context) : ResonanceServerProfileStore {
    private val file = File(context.filesDir, "resonance_servers/profiles.json")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): List<ResonanceServerProfile> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString(ProfileList.serializer(), file.readText()).profiles }.getOrElse { emptyList() }
    }

    override suspend fun write(profiles: List<ResonanceServerProfile>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(json.encodeToString(ProfileList.serializer(), ProfileList(profiles)))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    @Serializable private data class ProfileList(val profiles: List<ResonanceServerProfile>)
}

interface ResonanceCredentialStore {
    suspend fun readBearerToken(serverId: String): String?
    suspend fun writeBearerToken(serverId: String, token: String)
    suspend fun delete(serverId: String)
}

/** Stores only AES-GCM ciphertext in private SharedPreferences; the AES key remains Android Keystore-backed. */
class AndroidKeystoreResonanceCredentialStore(context: Context) : ResonanceCredentialStore {
    private val preferences = context.getSharedPreferences("resonance_credentials", Context.MODE_PRIVATE)

    override suspend fun readBearerToken(serverId: String): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString(serverId, null) ?: return@withContext null
        runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override suspend fun writeBearerToken(serverId: String, token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "El token es obligatorio." }
        val nonce = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        val encrypted = nonce + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(serverId, Base64.getEncoder().encodeToString(encrypted)).commit())
    }

    override suspend fun delete(serverId: String) = withContext(Dispatchers.IO) {
        preferences.edit().remove(serverId).commit()
        Unit
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object { const val KEY_ALIAS = "teleo_resonance_bearer_v1" }
}

class ResonanceServerProfileManager(
    private val profiles: ResonanceServerProfileStore,
    private val credentials: ResonanceCredentialStore,
    private val clearCache: suspend (String) -> Unit
) {
    suspend fun load(): List<ResonanceServerProfile> = profiles.read()

    suspend fun save(draft: ResonanceServerProfile, bearerToken: String?): ResonanceServerProfile {
        val normalized = draft.copy(name = draft.name.trim(), baseUrl = ResonanceServerUrl.normalize(draft.baseUrl))
        val all = profiles.read()
        val previous = all.firstOrNull { it.id == normalized.id }
        when (normalized.authType) {
            ResonanceAuthType.NONE -> credentials.delete(normalized.id)
            ResonanceAuthType.BEARER_TOKEN -> {
                if (!bearerToken.isNullOrBlank()) credentials.writeBearerToken(normalized.id, bearerToken)
                else require(credentials.readBearerToken(normalized.id) != null) { "El token es obligatorio." }
            }
        }
        val updated = if (previous?.baseUrl != null && previous.baseUrl != normalized.baseUrl) {
            clearCache(normalized.id)
            normalized.copy(lastSync = null)
        } else normalized
        profiles.write(all.filterNot { it.id == updated.id } + updated)
        return updated
    }

    suspend fun delete(profile: ResonanceServerProfile) {
        credentials.delete(profile.id)
        clearCache(profile.id)
        profiles.write(profiles.read().filterNot { it.id == profile.id })
    }

    suspend fun setEnabled(profile: ResonanceServerProfile, enabled: Boolean) {
        profiles.write(profiles.read().map { if (it.id == profile.id) it.copy(enabled = enabled) else it })
    }

    suspend fun markSynced(profile: ResonanceServerProfile) {
        profiles.write(profiles.read().map { if (it.id == profile.id) it.copy(lastSync = System.currentTimeMillis()) else it })
    }
}
