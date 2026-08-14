package com.nicolas.teleo.features.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.nicolas.teleo.features.music.domain.MusicTrack
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MusicTrackResolver {
    fun resolve(context: Context, uri: Uri): MusicTrack {
        var displayName = "Canción seleccionada"
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    displayName = cursor.getString(it) ?: displayName
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                    if (!cursor.isNull(it)) size = cursor.getLong(it)
                }
            }
        }

        var duration: Long? = null
        var artist: String? = null
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            } finally {
                retriever.release()
            }
        }
        val stableMetadata = listOf(uri.toString(), displayName, size.toString(), duration.toString()).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(stableMetadata.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return MusicTrack(hash, displayName, artist, uri.toString(), duration)
    }

    /** Content hashing is intentionally opt-in and always runs off the main thread. */
    suspend fun sha256(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IllegalStateException("No se pudo abrir el audio seleccionado")
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
