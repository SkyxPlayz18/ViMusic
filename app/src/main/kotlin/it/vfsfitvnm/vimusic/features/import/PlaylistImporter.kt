package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.models.Artist
import it.vfsfitvnm.vimusic.models.SongArtistMap
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.models.NavigationEndpoint
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
import it.vfsfitvnm.vimusic.utils.asMediaItem // penting biar bisa konversi ke MediaItem
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
    data class Complete(val imported: Int, val failed: Int, val total: Int, val failedTracks: List<SongImportInfo>) : ImportStatus()
    data class Error(val message: String) : ImportStatus()
}

class PlaylistImporter {

    companion object {
        private const val MINIMUM_SCORE_THRESHOLD = 60
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 40
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val TITLE_SIMILARITY_WEIGHT = 50
        private const val ALBUM_MATCH_BONUS = 30
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40
        private val KNOWN_MODIFIERS = setOf(
            "remix", "edit", "mix", "live", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "reverb", "slowed", "sped up", "chopped", "screwed",
            "deluxe", "version", "edition", "ultra"
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
            val songsToAdd = mutableListOf<Song>()
            val failedTracks = mutableListOf<SongImportInfo>()
            var processedCount = 0

            onProgressUpdate(ImportStatus.InProgress(0, totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferredSongs = batch.map { track ->
                        async(Dispatchers.IO) {
                            val cleanedQuery = track.title
                                .replace(Regex("\\(.*?\\)"), "")
                                .replace(Regex("\\[.*?\\]"), "")
                                .replace(Regex("(?i)\\b(official|lyrics|audio|video|feat\\.?|ft\\.?|remix|live)\\b"), "")
                                .replace(Regex("\\s+"), " ")
                                .trim()

                            val query = "$cleanedQuery ${track.artist} ${track.album ?: ""}".trim()

                            val searchResult = Innertube.searchPage(
                                body = SearchBody(query = query, params = Innertube.SearchFilter.Song.value)
                            ) { content ->
                                content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                            }?.getOrNull()?.items

                            if (searchResult.isNullOrEmpty()) return@async null

                            val bestMatch = findBestMatchInResults(track, searchResult)
                            bestMatch?.let {
                                Song(
                                    id = it.info?.endpoint?.videoId ?: "",
                                    title = it.info?.name ?: track.title,
                                    artistsText = it.authors?.joinToString(", ") { a -> a.name ?: "" } ?: track.artist,
                                    durationText = it.durationText,
                                    thumbnailUrl = it.thumbnail?.url,
                                    album = it.album?.name ?: track.album
                                )
                            }
                        }
                    }

                    val results = deferredSongs.awaitAll()
                    results.forEachIndexed { i, song ->
                        if (song != null && song.id.isNotBlank()) {
                            songsToAdd.add(song)
                        } else {
                            failedTracks.add(batch[i])
                        }
                    }
                }

                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processedCount, totalTracks))
            }

            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val playlist = Playlist(name = playlistName)
                    val playlistId = Database.instance.insert(playlist).takeIf { it != -1L } ?: playlist.id

                    // Tambahkan lagu ke atas playlist, bukan insert manual
                    Database.instance.addMediaItemsToPlaylistAtTop(
                        playlist = Playlist(id = playlistId, name = playlistName),
                        mediaItems = songsToAdd.map { it.asMediaItem }
                    )
                }
            }

            onProgressUpdate(
                ImportStatus.Complete(
                    imported = songsToAdd.size,
                    failed = failedTracks.size,
                    total = totalTracks,
                    failedTracks = failedTracks
                )
            )

        } catch (e: Exception) {
            Log.e("PlaylistImporter", "Error importing playlist", e)
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    private fun findBestMatchInResults(importTrack: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)

        val scoredCandidates = candidates.map { candidate ->
            val candidateTitle = candidate.info?.name ?: ""
            val candidateArtists = candidate.authors?.joinToString { it.name.toString() } ?: ""
            val candidateAlbum = candidate.album?.name
            val candidateInfo = parseSongInfo(candidateTitle, candidateArtists, candidateAlbum)
            val score = calculateMatchScore(importInfo, candidateInfo, candidateAlbum)
            candidate to score
        }

        return scoredCandidates.filter { it.second > MINIMUM_SCORE_THRESHOLD }.maxByOrNull { it.second }?.first
    }

    private data class ProcessedSongInfo(
        val baseTitle: String,
        val allArtists: List<String>,
        val album: String?,
        val modifiers: Set<String>
    )

    private fun parseSongInfo(title: String, artists: String, album: String?): ProcessedSongInfo {
        val normalized = title.lowercase()
        val modifiers = KNOWN_MODIFIERS.filter { normalized.contains(it) }.toSet()
        val cleanTitle = normalized.replace(Regex("[-()\\[\\]]"), "").trim()
        val allArtists = artists.lowercase().split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }
        return ProcessedSongInfo(cleanTitle, allArtists, album?.lowercase(), modifiers)
    }

    private fun calculateMatchScore(importInfo: ProcessedSongInfo, candidateInfo: ProcessedSongInfo, candidateAlbum: String?): Int {
        var score = 0
        score += importInfo.allArtists.count { artist -> candidateInfo.allArtists.any { it.contains(artist) } } * 20
        val titleDistance = levenshtein(importInfo.baseTitle, candidateInfo.baseTitle)
        val maxLen = max(importInfo.baseTitle.length, candidateInfo.baseTitle.length)
        score += ((1.0 - titleDistance.toDouble() / maxLen) * 50).toInt()
        if (candidateAlbum?.contains(importInfo.album ?: "") == true) score += 20
        if (importInfo.modifiers == candidateInfo.modifiers) score += 10
        return score
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val len0 = lhs.length + 1
        val len1 = rhs.length + 1
        val cost = IntArray(len0) { it }
        val newCost = IntArray(len0)
        for (i in 1 until len1) {
            newCost[0] = i
            for (j in 1 until len0) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val replace = cost[j - 1] + match
                val insert = cost[j] + 1
                val delete = newCost[j - 1] + 1
                newCost[j] = min(min(insert, delete), replace)
            }
            for (j in cost.indices) cost[j] = newCost[j]
        }
        return cost[len0 - 1]
    }
}
