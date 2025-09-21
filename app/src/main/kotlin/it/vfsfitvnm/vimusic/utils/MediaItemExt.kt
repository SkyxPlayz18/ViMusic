package it.vfsfitvnm.vimusic.utils

import androidx.media3.common.MediaItem
import it.vfsfitvnm.vimusic.models.Song

fun MediaItem.asSong(): Song {
    return Song(
        id = mediaId.ifBlank { "unknown_${System.currentTimeMillis()}" },
        title = mediaMetadata.title?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown Title",
        artistsText = mediaMetadata.artist?.toString().takeUnless { it.isNullOrBlank() } ?: "Unknown Artist",
        durationText = mediaMetadata.extras?.getString("durationText") 
            ?: mediaMetadata.extras?.getLong("duration")?.toString()
            ?: "0",
        thumbnailUrl = mediaMetadata.artworkUri?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
        likedAt = null,
        totalPlayTimeMs = 0, // biarin 0, nanti bisa diupdate player
        loudnessBoost = null,
        blacklisted = false,
        explicit = false
    )
}
