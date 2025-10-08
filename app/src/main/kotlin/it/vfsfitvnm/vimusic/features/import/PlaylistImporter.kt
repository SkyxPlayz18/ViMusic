package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.models.SongPlaylistMap
import it.vfsfitvnm.vimusic.models.Artist
import it.vfsfitvnm.vimusic.models.SongArtistMap
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
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
    private data class ProcessedSongInfo(
        val baseTitle: String,
        val primaryArtist: String,
        val allArtists: List<String>,
        val modifiers: Set<String>,
        val album: String?
    )

    companion object {
        private const val MINIMUM_SCORE_THRESHOLD = 50
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
            val songsToAdd = mutableListOf<Pair<Song, List<Innertube.Info<it.vfsfitvnm.providers.innertube.models.NavigationEndpoint.Endpoint.Browse>>>>()
            val failedTracks = mutableListOf<SongImportInfo>()
            var processedCount = 0

            onProgressUpdate(ImportStatus.InProgress(processed = 0, total = totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferredSongsInBatch = batch.map { track ->
                        async(Dispatchers.IO) {
                            val searchQuery = "${track.title} ${track.artist} ${track.album ?: ""}"

                            val searchCandidates = Innertube.searchPage(
                                body = SearchBody(query = searchQuery, params = Innertube.SearchFilter.Song.value)
                            ) { content ->
                                content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                            }?.getOrNull()?.items

                            if (searchCandidates.isNullOrEmpty()) {
                                return@async null
                            }

                            val bestMatch = findBestMatchInResults(track, searchCandidates)
                            bestMatch?.let {
                                // Extract artist information with IDs
                                val artistsWithEndpoints = it.authors?.let { authors ->
                                    val itemsWithEndpoints = authors.filter { author ->
                                        val name = author.name?.trim() ?: ""
                                        author.endpoint != null &&
                                            name.isNotEmpty() &&
                                            name != " • " &&
                                            !name.contains(":")
                                    }

                                    if (itemsWithEndpoints.size > 1) {
                                        itemsWithEndpoints.dropLast(1)
                                    } else {
                                        itemsWithEndpoints
                                    }
                                } ?: emptyList()

                                val artistsText = when (artistsWithEndpoints.size) {
                                    0 -> ""
                                    1 -> artistsWithEndpoints[0].name.toString().trim()
                                    2 -> "${artistsWithEndpoints[0].name.toString().trim()} & ${artistsWithEndpoints[1].name.toString().trim()}"
                                    else -> {
                                        val allButLast = artistsWithEndpoints.dropLast(1).joinToString(", ") { it.name.toString().trim() }
                                        val last = artistsWithEndpoints.last().name.toString().trim()
                                        "$allButLast & $last"
                                    }
                                }

                                Song(
                                    id = it.info?.endpoint?.videoId ?: "",
                                    title = it.info?.name ?: "",
                                    artistsText = artistsText,
                                    durationText = it.durationText,
                                    thumbnailUrl = it.thumbnail?.url,
                                    album = it.album?.name
                                ) to artistsWithEndpoints
                            }
                        }
                    }
                    val results = deferredSongsInBatch.awaitAll()
                    batch.zip(results).forEach { (originalTrack, result) ->
                        if (result != null) {
                            val (song, artistsWithEndpoints) = result
                            if (song.id.isNotBlank()) {
                                songsToAdd.add(song to artistsWithEndpoints)
                            } else {
                                failedTracks.add(originalTrack)
                            }
                        } else {
                            failedTracks.add(originalTrack)
                        }
                    }
                }

                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processed = processedCount, total = totalTracks))
            }

            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val newPlaylist = Playlist(name = playlistName)
                    val newPlaylistId = Database.instance.insert(newPlaylist)
                    if (newPlaylistId != -1L) {
                        songsToAdd.forEachIndexed { index, (song, artistsWithEndpoints) ->
                            // Use upsert to handle duplicates without crashing
                            Database.instance.upsert(song)

                            artistsWithEndpoints.forEach { artistInfo ->
                                val artistId = artistInfo.endpoint?.browseId
                                val artistName = artistInfo.name
                                if (artistId != null && artistName != null) {
                                    Database.instance.upsert(
                                        Artist(
                                            id = artistId,
                                            name = artistName
                                        )
                                    )
                                    // Use upsert to handle duplicate song-artist relationships
                                    Database.instance.upsert(
                                        SongArtistMap(
                                            songId = song.id,
                                            artistId = artistId
                                        )
                                    )
                                }
                            }

                            Database.instance.insert(
                                SongPlaylistMap(
                                    songId = song.id,
                                    playlistId = newPlaylistId,
                                    position = index
                                )
                            )
                        }
                    }
                }
            }

            onProgressUpdate(ImportStatus.Complete(
                imported = songsToAdd.size,
                failed = failedTracks.size,
                total = totalTracks,
                failedTracks = failedTracks
            ))

        } catch (e: Exception) {
            Log.e("PlaylistImporter", "An error occurred during the import process.", e)
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

        return scoredCandidates
            .filter { (_, score) -> score > MINIMUM_SCORE_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private fun parseSongInfo(title: String, artists: String, album: String?): ProcessedSongInfo {
        val normalizedTitle = title.lowercase()
        val modifierRegex = """[(\[].*?[)\]]|-.*""".toRegex()
        val foundModifiers = modifierRegex.findAll(normalizedTitle)
            .map { it.value.replace(Regex("[\\[\\]()\\-]"), "").trim() }
            .flatMap { it.split(" ") }
            .map { it.trim() }
            .filter { word -> KNOWN_MODIFIERS.any { modifier -> word.contains(modifier) } }
            .toSet()

        val baseTitle = modifierRegex.replace(normalizedTitle, "").trim()
        val allArtists = artists.lowercase().split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }

        return ProcessedSongInfo(baseTitle, allArtists.firstOrNull() ?: "", allArtists, foundModifiers, album?.lowercase()?.trim())
    }

    private fun calculateMatchScore(importInfo: ProcessedSongInfo, candidateInfo: ProcessedSongInfo, candidateAlbumName: String?): Int {
        var score = 0

        if (importInfo.primaryArtist.isNotEmpty() && candidateInfo.allArtists.any { it.contains(importInfo.primaryArtist) }) {
            score += PRIMARY_ARTIST_EXACT_MATCH_BONUS
        }
        val otherImportArtists = importInfo.allArtists.drop(1)
        score += otherImportArtists.count { importArtist ->
            candidateInfo.allArtists.any { candidateArtist -> candidateArtist.contains(importArtist) }
        } * OTHER_ARTIST_MATCH_BONUS
        if (score == 0) return 0

        val titleDistance = levenshtein(importInfo.baseTitle, candidateInfo.baseTitle)
        val maxLen = max(importInfo.baseTitle.length, candidateInfo.baseTitle.length)
        if (maxLen > 0) {
            score += ((1.0 - titleDistance.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()
        }

        importInfo.album?.let { importAlbum ->
            candidateAlbumName?.lowercase()?.let { candidateAlbum ->
                if (candidateAlbum.contains(importAlbum)) {
                    score += ALBUM_MATCH_BONUS
                }
            }
        }

        if (importInfo.modifiers.isNotEmpty() && importInfo.modifiers == candidateInfo.modifiers) {
            score += MODIFIER_MATCH_BONUS * importInfo.modifiers.size
        } else if (importInfo.modifiers.isEmpty() && candidateInfo.modifiers.isNotEmpty()) {
            score -= MODIFIER_MISMATCH_PENALTY
        } else if (importInfo.modifiers.isNotEmpty() && candidateInfo.modifiers.isEmpty()) {
            score -= MODIFIER_MISMATCH_PENALTY / 2
        }

        return score
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }
}
