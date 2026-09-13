package com.nicolas.teleo.features.music.domain

import java.net.URI
import java.util.UUID
import kotlinx.serialization.Serializable

enum class ResonanceAuthType { NONE, BEARER_TOKEN }

/** Configuration only. Secrets deliberately live in ResonanceCredentialStore, never here. */
@Serializable
data class ResonanceServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val authType: ResonanceAuthType = ResonanceAuthType.NONE,
    val enabled: Boolean = true,
    val lastSync: Long? = null
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid server id" }
        require(name.isNotBlank()) { "Server name is required" }
        ResonanceServerUrl.normalize(baseUrl)
    }
}

object ResonanceServerUrl {
    fun normalize(raw: String): String {
        val value = raw.trim()
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("La URL del servidor no es válida.") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "El servidor debe usar HTTPS y tener host."
        }
        require(uri.userInfo == null) { "La URL no puede incluir usuario o contraseña." }
        require(uri.query == null && uri.fragment == null) { "La URL no puede incluir consulta ni fragmento." }
        val normalizedPath = (uri.rawPath ?: "").trimEnd('/').let { if (it.isEmpty()) "/" else "$it/" }
        return URI("https", null, uri.host.lowercase(), uri.port, normalizedPath, null, null).toString()
    }
}
