package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.*
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.NavigationEndpoint
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
import kotlinx.coroutines.*
import java.text.Normalizer
import kotlin.math.*

data class SongImportInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val trackUri: String? = null
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
        private const val MINIMUM_SCORE_THRESHOLD = 55
        private const val EXACT_TITLE_BONUS = 100
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 50
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val TITLE_SIMILARITY_WEIGHT = 50
        private const val ALBUM_MATCH_BONUS = 40
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40
    }

    suspend fun import(
        songList: List<SongImportInfo>,
        playlistName: String,
        unknownErrorMessage: String,
        onProgressUpdate: (ImportStatus) -> Unit
    ) {
        try {
            val totalTracks = songList.size
            val songsToAdd = mutableListOf<Pair<Song, List<Innertube.Info<NavigationEndpoint.Endpoint.Browse>>>>()
            val failedTracks = mutableListOf<SongImportInfo>()
            var processedCount = 0

            onProgressUpdate(ImportStatus.InProgress(0, totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { track ->
                        async(Dispatchers.IO) {
                            try {
                                val cleanedBase = normalizeUnicode(track.title)
                                val queries = listOfNotNull(
                                    "$cleanedBase ${track.artist} ${track.album ?: ""}".trim(),
                                    "$cleanedBase ${track.artist}".trim(),
                                    "${track.artist} $cleanedBase".trim(),
                                    "$cleanedBase".trim(),
                                    track.title.trim()
                                ).distinct()

                                var candidates: List<Innertube.SongItem>? = null

                                // 🔹 Primary search (multi-query)
                                for (q in queries) {
                                    if (q.isBlank()) continue
                                    val res = Innertube.searchPage(
                                        body = SearchBody(query = q, params = Innertube.SearchFilter.Song.value)
                                    ) { content ->
                                        content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                                    }?.getOrNull()?.items

                                    if (!res.isNullOrEmpty()) {
                                        candidates = res.filterIsInstance<Innertube.SongItem>()
                                        break
                                    }
                                }

                                // 🔹 Fallback 1: Search with Unicode-normalized (for kanji/hanzi)
                                if (candidates.isNullOrEmpty() && isMostlyCJK(track.title)) {
                                    val unicodeQuery = normalizeUnicode("${track.title} ${track.artist}")
                                    val res = Innertube.searchPage(
                                        body = SearchBody(query = unicodeQuery, params = Innertube.SearchFilter.Song.value)
                                    ) { content ->
                                        content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                                    }?.getOrNull()?.items
                                    if (!res.isNullOrEmpty()) candidates = res.filterIsInstance<Innertube.SongItem>()
                                }

                                // 🔹 Fallback 2: album + artist search (for instrumental/soundtrack)
                                if (candidates.isNullOrEmpty()) {
                                    val albumArtistResults = albumArtistFallbackSearch(track)
                                    if (!albumArtistResults.isNullOrEmpty()) candidates = albumArtistResults
                                }

                                if (candidates.isNullOrEmpty()) {
                                    Log.w("PlaylistImporter", "❌ No results for ${track.title}")
                                    return@async null
                                }

                                // Find best match
                                val best = findBestMatchInResults(track, candidates)
                                if (best == null) {
                                    Log.w("PlaylistImporter", "⚠️ No confident match for ${track.title}")
                                    return@async null
                                }

                                // Build song
                                val artists = best.authors?.filter { it.name?.isNotBlank() == true } ?: emptyList()
                                val song = Song(
                                    id = best.info?.endpoint?.videoId ?: "",
                                    title = best.info?.name ?: "",
                                    artistsText = artists.joinToString(", ") { it.name ?: "" },
                                    durationText = best.durationText,
                                    thumbnailUrl = best.thumbnail?.url,
                                    album = best.album?.name
                                )
                                song to artists
                            } catch (t: Throwable) {
                                Log.e("PlaylistImporter", "Error: ${t.message}")
                                null
                            }
                        }
                    }

                    val results = deferred.awaitAll()
                    batch.zip(results).forEach { (original, res) ->
                        if (res != null) {
                            val (song, artists) = res
                            if (song.id.isNotBlank()) songsToAdd.add(song to artists) else failedTracks.add(original)
                        } else failedTracks.add(original)
                    }
                }
                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processedCount, totalTracks))
            }

            // save playlist
            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val newPlaylist = Playlist(name = playlistName)
                    val playlistId = Database.instance.insert(newPlaylist)
                    if (playlistId != -1L) {
                        songsToAdd.forEachIndexed { index, (song, artists) ->
                            Database.instance.upsert(song)
                            artists.forEach {
                                val id = it.endpoint?.browseId ?: return@forEach
                                val name = it.name ?: return@forEach
                                Database.instance.upsert(Artist(id = id, name = name))
                                Database.instance.upsert(SongArtistMap(songId = song.id, artistId = id))
                            }
                            Database.instance.insert(
                                SongPlaylistMap(songId = song.id, playlistId = playlistId, position = index)
                            )
                        }
                    }
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
            Log.e("PlaylistImporter", "❌ Import failed: ${e.message}")
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    // --------- 🔹 HELPERS ---------

    /** Fallback untuk soundtrack Jepang/Cina yg gagal via title search */
    private suspend fun albumArtistFallbackSearch(track: SongImportInfo): List<Innertube.SongItem>? {
        val query = "${track.artist} ${track.album ?: ""}".trim()
        if (query.isBlank()) return null
        val res = Innertube.searchPage(body = SearchBody(query = query)) { content ->
            content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
        }?.getOrNull()?.items
        return res?.filterIsInstance<Innertube.SongItem>()
    }

    private fun isMostlyCJK(text: String): Boolean {
        if (text.isBlank()) return false
        val total = text.length
        val cjk = text.count { it in '\u4E00'..'\u9FFF' || it in '\u3040'..'\u30FF' || it in '\uAC00'..'\uD7AF' }
        return cjk > total / 3
    }

    private fun normalizeUnicode(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val n = Normalizer.normalize(input, Normalizer.Form.NFKC)
        return n.replace(Regex("\\p{M}"), "").trim().lowercase()
    }

    // ---------- MATCH SCORING ----------

    private fun findBestMatchInResults(track: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val normTitle = normalizeUnicode(track.title)
        val importArtist = normalizeUnicode(track.artist)

        val scored = candidates.map { c ->
            val t = normalizeUnicode(c.info?.name ?: "")
            val a = c.authors?.joinToString(" ") { it.name ?: "" } ?: ""
            val sim = titleSimilarityUnicode(normTitle, t)
            val artistMatch = a.contains(importArtist, ignoreCase = true)
            val score = (sim * 100).toInt() + if (artistMatch) 50 else 0
            c to score
        }
        val best = scored.maxByOrNull { it.second }
        return best?.takeIf { it.second > 60 }?.first
    }

    private fun titleSimilarityUnicode(a: String, b: String): Double {
        if (a.isBlank() && b.isBlank()) return 1.0
        val dist = levenshtein(a, b)
        val maxLen = max(a.length, b.length).coerceAtLeast(1)
        return 1.0 - dist.toDouble() / maxLen
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
                newCost[j] = min(min(cost[j] + 1, newCost[j - 1] + 1), cost[j - 1] + match)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[l]
    }
}
