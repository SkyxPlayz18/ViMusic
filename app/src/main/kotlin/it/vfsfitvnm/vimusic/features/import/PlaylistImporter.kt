package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.max
import kotlin.math.min

data class SongImportInfo(
    val title: String,
    val artist: String,
    val album: String?
)

sealed class ImportStatus {
    data object Idle : ImportStatus()
    data class InProgress(val processed: Int, val total: Int) : ImportStatus()
    data class Complete(
        val imported: Int,
        val failed: Int,
        val total: Int,
        val failedTracks: List<SongImportInfo>
    ) : ImportStatus()
    data class Error(val message: String) : ImportStatus()
}

class PlaylistImporter {
    companion object {
        private const val MATCH_THRESHOLD = 60
    }

    suspend fun import(
        songs: List<SongImportInfo>,
        playlistName: String,
        unknownErrorMessage: String,
        onProgressUpdate: (ImportStatus) -> Unit
    ) {
        try {
            val total = songs.size
            val addedSongs = mutableListOf<Song>()
            val failed = mutableListOf<SongImportInfo>()
            var processed = 0

            onProgressUpdate(ImportStatus.InProgress(0, total))

            songs.chunked(10).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { song ->
                        async(Dispatchers.IO) {
                            try {
                                val query = buildQuery(song)
                                val result = Innertube.searchPage(
                                    body = SearchBody(
                                        query = query,
                                        params = Innertube.SearchFilter.Song.value
                                    )
                                )

                                val items = result?.getOrNull()?.items ?: emptyList()

                                val match = findBestMatch(song, items)
                                if (match == null) {
                                    Log.w("PlaylistImporter", "❌ No match for ${song.title}")
                                    return@async null
                                }

                                Song(
                                    id = match.info?.endpoint?.videoId ?: "",
                                    title = match.info?.name ?: "",
                                    artistsText = match.authors?.joinToString(", ") { it.name ?: "" } ?: "",
                                    durationText = match.durationText,
                                    thumbnailUrl = match.thumbnail?.url,
                                    album = match.album?.name,
                                    explicit = match.explicit
                                )
                            } catch (t: Throwable) {
                                Log.e("PlaylistImporter", "Error ${song.title}: ${t.message}")
                                null
                            }
                        }
                    }

                    val results = deferred.awaitAll()
                    results.forEachIndexed { i, s ->
                        if (s != null && s.id.isNotBlank()) addedSongs.add(s)
                        else failed.add(batch[i])
                    }
                }

                processed += batch.size
                onProgressUpdate(ImportStatus.InProgress(processed, total))
            }

            if (addedSongs.isNotEmpty()) {
                transaction {
                    val playlist = Playlist(name = playlistName)
                    Database.instance.addMediaItemsToPlaylistAtTop(
                        playlist = playlist,
                        mediaItems = addedSongs.map { it.asMediaItem }
                    )
                }
            }

            onProgressUpdate(
                ImportStatus.Complete(
                    imported = addedSongs.size,
                    failed = failed.size,
                    total = total,
                    failedTracks = failed
                )
            )

        } catch (e: Exception) {
            Log.e("PlaylistImporter", "Import failed: ${e.message}")
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    private fun buildQuery(song: SongImportInfo): String {
        return listOf(song.title, song.artist, song.album)
            .filterNotNull()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\(.*?\\)|\\[.*?\\]|official|lyrics|audio|video"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findBestMatch(song: SongImportInfo, items: List<Innertube.SongItem>): Innertube.SongItem? {
        var bestScore = 0
        var best: Innertube.SongItem? = null

        for (item in items) {
            val score = matchScore(song, item)
            if (score > bestScore) {
                bestScore = score
                best = item
            }
        }

        return if (bestScore >= MATCH_THRESHOLD) best else null
    }

    private fun matchScore(song: SongImportInfo, item: Innertube.SongItem): Int {
        var score = 0

        val titleDist = levenshtein(song.title.lowercase(), item.info?.name?.lowercase() ?: "")
        val artistDist = levenshtein(song.artist.lowercase(), item.authors?.joinToString(" ") { it.name?.lowercase().orEmpty() } ?: "")
        val albumDist = levenshtein(song.album?.lowercase().orEmpty(), item.album?.name?.lowercase().orEmpty())

        if (song.artist.isNotBlank() && artistDist < 5) score += 40
        if (song.album != null && albumDist < 5) score += 20

        val titleScore = ((1.0 - (titleDist.toDouble() / max(song.title.length, 1))) * 60).toInt()
        score += titleScore

        return score
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = i - 1
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(
                    min(dp[j - 1] + 1, dp[j] + 1),
                    prev + cost
                )
                prev = temp
            }
        }
        return dp[b.length]
    }
}
