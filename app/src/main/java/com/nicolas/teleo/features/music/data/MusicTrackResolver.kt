package com.nicolas.teleo.features.music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.nicolas.teleo.features.music.domain.MusicTrack
import java.security.MessageDigest

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
}
