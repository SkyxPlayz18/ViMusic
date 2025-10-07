package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.*
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
    private data class ProcessedSongInfo(
        val baseTitle: String,
        val primaryArtist: String,
        val allArtists: List<String>,
        val modifiers: Set<String>,
        val album: String?
    )

    companion object {
        private const val MINIMUM_SCORE_THRESHOLD = 45
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 40
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val TITLE_SIMILARITY_WEIGHT = 50
        private const val ALBUM_MATCH_BONUS = 30
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40

        private val KNOWN_MODIFIERS = setOf(
            "remix", "edit", "mix", "live", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "reverb", "slowed", "sped up", "chopped",
            "screwed", "deluxe", "version", "edition", "ultra"
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
                            try {
                                val cleanedQuery = track.title
                                    .replace(Regex("\\(.*?\\)"), "")
                                    .replace(Regex("\\[.*?\\]"), "")
                                    .replace(Regex("(?i)\\b(official|lyrics|audio|video)\\b"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()

                                val queries = listOf(
                                    "$cleanedQuery ${track.artist} ${track.album ?: ""}".trim(),
                                    "$cleanedQuery ${track.artist}".trim(),
                                    "${track.title} ${track.artist}".trim(),
                                    "$cleanedQuery".trim(),
                                    track.title.trim()
                                ).distinct()

                                var searchCandidates: List<Innertube.SongItem>? = null

                                for (q in queries) {
                                    if (q.isBlank()) continue
                                    Log.d("Importer", "Search: \"$q\"")

                                    val result = Innertube.searchPage(
                                        body = SearchBody(query = q)
                                    ) { content ->
                                        // pakai SongItem.from(content) biar gak error unresolved reference
                                        Innertube.SongItem.from(content)
                                    }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()

                                    if (!result.isNullOrEmpty()) {
                                        searchCandidates = result
                                        break
                                    }
                                }

                                if (searchCandidates.isNullOrEmpty()) {
                                    Log.w("ImporterDebug", "❌ No result for ${track.title}")
                                    return@async null
                                }

                                val bestMatch = findBestMatchInResults(track, searchCandidates)
                                if (bestMatch == null) {
                                    Log.w("ImporterDebug", "⚠️ No match above threshold for ${track.title}")
                                    return@async null
                                }

                                val artists = bestMatch.authors?.joinToString(", ") { it.name ?: "" } ?: ""
                                return@async Song(
                                    id = bestMatch.info?.endpoint?.videoId ?: "",
                                    title = bestMatch.info?.name ?: "",
                                    artistsText = artists,
                                    durationText = bestMatch.durationText,
                                    thumbnailUrl = bestMatch.thumbnail?.url,
                                    album = bestMatch.album?.name
                                )
                            } catch (t: Throwable) {
                                Log.e("ImporterErr", "Error ${track.title}: ${t.message}")
                                return@async null
                            }
                        }
                    }

                    val results = deferredSongs.awaitAll()
                    results.forEachIndexed { i, song ->
                        if (song != null && song.id.isNotBlank()) songsToAdd.add(song)
                        else failedTracks.add(batch[i])
                    }
                }

                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processedCount, totalTracks))
            }

            // gunakan addMediaItemsToPlaylistAtTop biar lagu baru di atas, tanpa bentrok sama queue
            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val playlist = Playlist(name = playlistName)
                    Database.instance.addMediaItemsToPlaylistAtTop(
                        playlist = playlist,
                        mediaItems = songsToAdd.map { it.asMediaItem } // pakai properti, bukan function
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
            Log.e("PlaylistImporter", "Import failed: ${e.message}")
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    // ===== Matching logic =====
    private fun findBestMatchInResults(
        importTrack: SongImportInfo,
        candidates: List<Innertube.SongItem>
    ): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)

        val scored = candidates.map { candidate ->
            val title = candidate.info?.name ?: ""
            val artists = candidate.authors?.joinToString { it.name.toString() } ?: ""
            val album = candidate.album?.name
            val info = parseSongInfo(title, artists, album)
            val score = calculateMatchScore(importInfo, info, album)
            candidate to score
        }

        return scored.filter { it.second > MINIMUM_SCORE_THRESHOLD }
            .maxByOrNull { it.second }?.first
    }

    private fun parseSongInfo(title: String, artists: String, album: String?): ProcessedSongInfo {
        val normalizedTitle = title.lowercase()
        val modRegex = """[(\[].*?[)\]]|-.*""".toRegex()
        val foundMods = modRegex.findAll(normalizedTitle)
            .map { it.value.replace(Regex("[\\[\\]()\\-]"), "").trim() }
            .flatMap { it.split(" ") }
            .filter { word -> KNOWN_MODIFIERS.any { mod -> word.contains(mod) } }
            .toSet()

        val baseTitle = modRegex.replace(normalizedTitle, "").trim()
        val allArtists = artists.lowercase()
            .split(Regex(",|&|feat\\.?|ft\\.?|with"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return ProcessedSongInfo(
            baseTitle = baseTitle,
            primaryArtist = allArtists.firstOrNull() ?: "",
            allArtists = allArtists,
            modifiers = foundMods,
            album = album?.lowercase()?.trim()
        )
    }

    private fun calculateMatchScore(a: ProcessedSongInfo, b: ProcessedSongInfo, candidateAlbum: String?): Int {
        var score = 0
        if (a.primaryArtist.isNotEmpty() && b.allArtists.any { it.contains(a.primaryArtist) })
            score += PRIMARY_ARTIST_EXACT_MATCH_BONUS

        score += a.allArtists.drop(1).count { importArtist ->
            b.allArtists.any { it.contains(importArtist) }
        } * OTHER_ARTIST_MATCH_BONUS

        if (score == 0) return 0

        val dist = levenshtein(a.baseTitle, b.baseTitle)
        val maxLen = max(a.baseTitle.length, b.baseTitle.length)
        if (maxLen > 0) score += ((1.0 - dist.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()

        a.album?.let { impAlbum ->
            candidateAlbum?.lowercase()?.let { candAlbum ->
                if (candAlbum.contains(impAlbum)) score += ALBUM_MATCH_BONUS
            }
        }

        if (a.modifiers == b.modifiers && a.modifiers.isNotEmpty())
            score += MODIFIER_MATCH_BONUS * a.modifiers.size
        else if (a.modifiers.isNotEmpty() && b.modifiers.isEmpty())
            score -= MODIFIER_MISMATCH_PENALTY / 2
        else if (a.modifiers.isEmpty() && b.modifiers.isNotEmpty())
            score -= MODIFIER_MISMATCH_PENALTY

        return score
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val l = lhs.length
        val r = rhs.length
        var cost = IntArray(l + 1) { it }
        var newCost = IntArray(l + 1)
        for (i in 1..r) {
            newCost[0] = i
            for (j in 1..l) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                newCost[j] = min(
                    min(cost[j] + 1, newCost[j - 1] + 1),
                    cost[j - 1] + match
                )
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[l]
    }
}
