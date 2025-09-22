package it.vfsfitvnm.vimusic.utils

import androidx.media3.common.MediaItem
import it.vfsfitvnm.vimusic.models.Song

fun MediaItem.toSong(): Song {
    return Song(
        id = mediaId,
        title = mediaMetadata.title?.toString() ?: "Unknown",
        artistsText = mediaMetadata.artist?.toString(),
        durationText = mediaMetadata.extras?.getString("duration") ?: "0:00",
        thumbnailUrl = mediaMetadata.artworkUri?.toString(),
        album = mediaMetadata.albumTitle?.toString()
    )
}
