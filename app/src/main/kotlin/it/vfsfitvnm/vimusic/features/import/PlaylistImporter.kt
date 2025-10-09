package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.models.SongPlaylistMap
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
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
    object Idle : ImportStatus()
    data class InProgress(val processed: Int, val total: Int) : ImportStatus()
    data class Complete(val imported: Int, val failed: Int, val total: Int, val failedTracks: List<SongImportInfo>) : ImportStatus()
    data class Error(val message: String) : ImportStatus()
}

class PlaylistImporter {
    companion object {
        private const val MINIMUM_SCORE_THRESHOLD = 80
        private const val EXACT_TITLE_BONUS = 60
        private val EXCLUDED_KEYWORDS = listOf(
            "live", "remix", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "sped up", "slowed", "version", "edit",
            "reverb", "japanese", "performance", "concert"
        )
    }

    suspend fun import(
        songList: List<SongImportInfo>,
        playlistName: String,
        unknownErrorMessage: String,
        onProgressUpdate: (ImportStatus) -> Unit
    ) {
        try {
            val totalTracks = songList.size
            val importedSongs = mutableListOf<Song>()
            val failedTracks = mutableListOf<SongImportInfo>()
            var processed = 0

            onProgressUpdate(ImportStatus.InProgress(0, totalTracks))

            songList.chunked(10).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { song ->
                        async(Dispatchers.IO) {
                            try {
                                val query = "${song.title} ${song.artist} ${song.album ?: ""}".trim()
                                val response = Innertube.searchPage(
                                    body = SearchBody(query = query, params = Innertube.SearchFilter.Song.value)
                                ) { content ->
                                    content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                                }?.getOrNull()?.items ?: return@async null

                                val candidates = response.filterIsInstance<Innertube.SongItem>()
                                    .filterNot { s ->
                                        val title = s.info?.name?.lowercase() ?: ""
                                        EXCLUDED_KEYWORDS.any { kw -> title.contains(kw) }
                                    }

                                val bestMatch = findBestMatch(song, candidates)
                                bestMatch?.let {
                                    Song(
                                        id = it.info?.endpoint?.videoId ?: "",
                                        title = it.info?.name ?: "",
                                        artistsText = it.authors?.joinToString(", ") { a -> a.name ?: "" } ?: "",
                                        durationText = it.durationText,
                                        thumbnailUrl = it.thumbnail?.url,
                                        album = it.album?.name
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e("PlaylistImporter", "Error importing ${song.title}: ${t.message}")
                                null
                            }
                        }
                    }

                    val results = deferred.awaitAll()
                    results.forEachIndexed { i, s ->
                        if (s != null && s.id.isNotBlank()) importedSongs.add(s)
                        else failedTracks.add(batch[i])
                    }
                }

                processed += batch.size
                onProgressUpdate(ImportStatus.InProgress(processed, totalTracks))
            }

            transaction {
                val playlist = Playlist(name = playlistName)
                val playlistId = Database.instance.insert(playlist)
                if (playlistId != -1L) {
                    importedSongs.forEachIndexed { index, s ->
                        Database.instance.upsert(s)
                        Database.instance.insert(SongPlaylistMap(s.id, playlistId, index))
                    }
                }
            }

            onProgressUpdate(
                ImportStatus.Complete(importedSongs.size, failedTracks.size, totalTracks, failedTracks)
            )
        } catch (e: Exception) {
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    private fun findBestMatch(import: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val importTitle = normalize(import.title)
        val importArtist = normalize(import.artist)
        val importAlbum = normalize(import.album ?: "")

        val scored = candidates.map { c ->
            val title = normalize(c.info?.name ?: "")
            val artist = normalize(c.authors?.joinToString(" ") { it.name ?: "" } ?: "")
            val album = normalize(c.album?.name ?: "")

            var score = 0
            if (title == importTitle) score += EXACT_TITLE_BONUS
            if (artist.contains(importArtist)) score += 40
            if (album == importAlbum) score += 30
            val dist = levenshtein(importTitle, title)
            val sim = ((1.0 - dist.toDouble() / max(importTitle.length, title.length)) * 100).toInt()
            score += sim / 2
            c to score
        }

        return scored.maxByOrNull { it.second }?.first
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[m][n]
    }
}
