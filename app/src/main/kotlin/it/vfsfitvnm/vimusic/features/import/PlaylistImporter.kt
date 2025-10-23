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
import it.vfsfitvnm.providers.innertube.models.NavigationEndpoint
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.Normalizer
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
        // Tweak ini kalau mau lebih longgar/ketat:
        private const val MINIMUM_SCORE_THRESHOLD = 50

        // Bonus/weight
        private const val EXACT_TITLE_BONUS = 120
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 60
        private const val OTHER_ARTIST_MATCH_BONUS = 12
        private const val TITLE_SIMILARITY_WEIGHT = 60
        private const val ALBUM_MATCH_BONUS = 35
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40

        private val KNOWN_MODIFIERS = setOf(
            "remix", "edit", "mix", "live", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "reverb", "slowed", "sped", "sped up", "chopped", "screwed",
            "deluxe", "version", "edition"
        )
    }

    // ---------- PUBLIC ----------
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

            onProgressUpdate(ImportStatus.InProgress(processed = 0, total = totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { track ->
                        async(Dispatchers.IO) {
                            try {
                                // build several queries (fallbacks)
                                val cleanedBase = normalizeForQuery(track.title)
                                val queries = listOfNotNull(
                                    "$cleanedBase ${track.artist} ${track.album ?: ""}".trim(),
                                    "$cleanedBase ${track.artist}".trim(),
                                    "${track.artist} $cleanedBase".trim(),
                                    "$cleanedBase".trim(),
                                    track.title.trim()
                                ).distinct()

                                var usedQuery: String? = null
                                var candidates: List<Innertube.SongItem>? = null

                                // Try with Song filter first, then fallback to no filter
                                for (q in queries) {
                                    if (q.isBlank()) continue
                                    usedQuery = q
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

                                if (candidates.isNullOrEmpty()) {
                                    // try again without Song filter (wider)
                                    for (q in queries) {
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
                                }

                                if (candidates.isNullOrEmpty()) {
                                    Log.w("PlaylistImporter", "No candidates for '${track.title}' (used: $usedQuery)")
                                    return@async null
                                }

                                // Find best candidate
                                val best = findBestMatchInResults(track, candidates)
                                if (best == null) {
                                    // SECOND PASS (looser): try searching by title only with very high title-similarity requirement
                                    val titleOnlyQuery = track.title.trim()
                                    val fallbackRes = Innertube.searchPage(
                                        body = SearchBody(query = titleOnlyQuery, params = Innertube.SearchFilter.Song.value)
                                    ) { content ->
                                        content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                                    }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()

                                    if (!fallbackRes.isNullOrEmpty()) {
                                        val best2 = fallbackRes.maxByOrNull { candidate ->
                                            scoreForLogging(track, candidate)
                                        }
                                        if (best2 != null) {
                                            val tSim = titleSimilarity(normalize(track.title), normalize(best2.info?.name ?: ""))
                                            val hasArtist = best2.authors?.any { it.name?.contains(track.artist, ignoreCase = true) == true } ?: false
                                            if (tSim >= 0.92 && (hasArtist || track.artist.isBlank())) {
                                                // accept high title similarity
                                                return@async Pair(
                                                    Song(
                                                        id = best2.info?.endpoint?.videoId ?: "",
                                                        title = best2.info?.name ?: "",
                                                        artistsText = best2.authors?.joinToString(", ") { it.name ?: "" } ?: "",
                                                        durationText = best2.durationText,
                                                        thumbnailUrl = best2.thumbnail?.url,
                                                        album = best2.album?.name
                                                    ),
                                                    best2.authors ?: emptyList()
                                                )
                                            }
                                        }
                                    }

                                    Log.w("PlaylistImporter", "No confident match for '${track.title}' (used: $usedQuery)")
                                    return@async null
                                }

                                // Build song + artist infos for DB upsert
                                val artistsWithEndpoints = best.authors?.filter { info ->
                                    val name = info.name?.trim() ?: ""
                                    info.endpoint != null && name.isNotEmpty() && name != " • " && !name.contains(":")
                                } ?: emptyList()

                                val artistsText = when (artistsWithEndpoints.size) {
                                    0 -> best.authors?.joinToString(", ") { it.name ?: "" } ?: ""
                                    1 -> artistsWithEndpoints[0].name.toString()
                                    2 -> "${artistsWithEndpoints[0].name} & ${artistsWithEndpoints[1].name}"
                                    else -> {
                                        val allButLast = artistsWithEndpoints.dropLast(1).joinToString(", ") { it.name.toString() }
                                        val last = artistsWithEndpoints.last().name.toString()
                                        "$allButLast & $last"
                                    }
                                }

                                Pair(
                                    Song(
                                        id = best.info?.endpoint?.videoId ?: "",
                                        title = best.info?.name ?: "",
                                        artistsText = artistsText,
                                        durationText = best.durationText,
                                        thumbnailUrl = best.thumbnail?.url,
                                        album = best.album?.name
                                    ),
                                    artistsWithEndpoints
                                )
                            } catch (t: Throwable) {
                                Log.e("PlaylistImporter", "Exception processing '${track.title}': ${t.message}")
                                null
                            }
                        }
                    } // end map async

                    val results = deferred.awaitAll()
                    batch.zip(results).forEach { (original, res) ->
                        if (res != null) {
                            val (song, artists) = res
                            if (song.id.isNotBlank()) songsToAdd.add(song to artists) else failedTracks.add(original)
                        } else {
                            failedTracks.add(original)
                        }
                    }
                } // end coroutineScope

                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processed = processedCount, total = totalTracks))
            } // end chunked batches

            // Persist to DB (maintain original behaviour: position = index)
            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val newPlaylist = Playlist(name = playlistName)
                    val newPlaylistId = Database.instance.insert(newPlaylist)
                    if (newPlaylistId != -1L) {
                        songsToAdd.forEachIndexed { index, (song, artistsWithEndpoints) ->
                            Database.instance.upsert(song)
                            artistsWithEndpoints.forEach { artistInfo ->
                                val artistId = artistInfo.endpoint?.browseId
                                val artistName = artistInfo.name
                                if (!artistId.isNullOrBlank() && !artistName.isNullOrBlank()) {
                                    Database.instance.upsert(Artist(id = artistId, name = artistName))
                                    Database.instance.upsert(SongArtistMap(songId = song.id, artistId = artistId))
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

            onProgressUpdate(ImportStatus.Complete(imported = songsToAdd.size, failed = failedTracks.size, total = totalTracks, failedTracks = failedTracks))
        } catch (e: Exception) {
            Log.e("PlaylistImporter", "Import failed", e)
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    // ---------- Matching helpers ----------

    private fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val n = Normalizer.normalize(input, Normalizer.Form.NFKC)
        // remove combining marks (keeps CJK intact)
        val noDiacritics = n.replace(Regex("\\p{M}"), "")
        return noDiacritics.trim().lowercase()
    }

    private fun normalizeForQuery(input: String?): String {
        return normalize(input)
            .replace(Regex("[\"'`·•]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseSongInfo(title: String, artists: String, album: String?): ProcessedSongInfo {
        val normalizedTitle = normalize(title)
        val modifierRegex = """[(\[].*?[)\]]|-.*""".toRegex()
        val foundModifiers = modifierRegex.findAll(normalizedTitle)
            .map { it.value.replace(Regex("[\\[\\]()\\-]"), "").trim() }
            .flatMap { it.split(" ") }
            .map { it.trim() }
            .filter { word -> KNOWN_MODIFIERS.any { modifier -> word.contains(modifier) } }
            .toSet()

        val baseTitle = modifierRegex.replace(normalizedTitle, "").trim()
        val allArtists = artists.lowercase().split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }

        return ProcessedSongInfo(baseTitle, allArtists.firstOrNull() ?: "", allArtists, foundModifiers, album?.let { normalize(it) })
    }

    private fun titleSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        val maxLen = max(a.length, b.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    private fun tokenOverlap(a: String, b: String): Double {
        val sa = a.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        val sb = b.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size.toDouble()
        val uni = sa.union(sb).size.toDouble()
        return if (uni == 0.0) 0.0 else inter / uni
    }

    private fun scoreForLogging(importTrack: SongImportInfo, candidate: Innertube.SongItem): Int {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)
        val candTitle = normalize(candidate.info?.name ?: "")
        val candArtists = candidate.authors?.joinToString(" ") { it.name ?: "" } ?: ""
        val candInfo = parseSongInfo(candTitle, candArtists, candidate.album?.name)
        return calculateMatchScore(importInfo, candInfo, candidate.album?.name)
    }

    private fun findBestMatchInResults(importTrack: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)

        val scored = candidates.map { candidate ->
            val candidateTitle = normalize(candidate.info?.name ?: "")
            val candidateArtists = candidate.authors?.joinToString(" ") { it.name ?: "" } ?: ""
            val candidateAlbum = candidate.album?.name
            val candidateInfo = parseSongInfo(candidateTitle, candidateArtists, candidateAlbum)
            val score = calculateMatchScore(importInfo, candidateInfo, candidateAlbum)

            // extra: if title exactly equals (after normalize) give big bonus
            val exactTitle = if (normalize(importTrack.title) == candidateTitle) EXACT_TITLE_BONUS else 0
            (candidate to (score + exactTitle))
        }

        val bestPair = scored.maxByOrNull { it.second } ?: return null
        val best = bestPair.first
        val bestScore = bestPair.second

        // Acceptance rules:
        // - exact normalized title OR score >= threshold => accept
        // - else reject
        val exactTitleMatch = normalize(importTrack.title) == normalize(best.info?.name ?: "")
        if (exactTitleMatch) return best
        if (bestScore >= MINIMUM_SCORE_THRESHOLD) return best

        // otherwise reject
        return null
    }

    private fun calculateMatchScore(importInfo: ProcessedSongInfo, candidateInfo: ProcessedSongInfo, candidateAlbumName: String?): Int {
        var score = 0

        // primary artist strong bonus
        if (importInfo.primaryArtist.isNotEmpty() && candidateInfo.allArtists.any { it.contains(importInfo.primaryArtist) }) {
            score += PRIMARY_ARTIST_EXACT_MATCH_BONUS
        }

        // other artists
        score += importInfo.allArtists.drop(1).count { importArtist ->
            candidateInfo.allArtists.any { candidateArtist -> candidateArtist.contains(importArtist) }
        } * OTHER_ARTIST_MATCH_BONUS

        // If no artist overlap at all, we still allow title-based match later, but penalize zero artist overlap
        if (score == 0) {
            // keep going — don't early return 0 yet, allow title similarity to rescue
        }

        // title similarity (levenshtein-based)
        val dist = levenshtein(importInfo.baseTitle, candidateInfo.baseTitle)
        val maxLen = max(importInfo.baseTitle.length, candidateInfo.baseTitle.length)
        if (maxLen > 0) {
            val titleScore = ((1.0 - dist.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()
            score += titleScore
        } else if (importInfo.baseTitle.isEmpty() && candidateInfo.baseTitle.isEmpty()) {
            score += TITLE_SIMILARITY_WEIGHT / 2
        }

        // album match bonus
        importInfo.album?.let { impAlbum ->
            candidateAlbumName?.lowercase()?.let { candAlbum ->
                if (candAlbum.contains(impAlbum)) score += ALBUM_MATCH_BONUS
            }
        }

        // modifiers
        if (importInfo.modifiers.isNotEmpty() && importInfo.modifiers == candidateInfo.modifiers) {
            score += MODIFIER_MATCH_BONUS * importInfo.modifiers.size
        } else if (importInfo.modifiers.isEmpty() && candidateInfo.modifiers.isNotEmpty()) {
            score -= MODIFIER_MISMATCH_PENALTY
        } else if (importInfo.modifiers.isNotEmpty() && candidateInfo.modifiers.isEmpty()) {
            score -= MODIFIER_MISMATCH_PENALTY / 2
        }

        return score
    }

    // standard Levenshtein
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


private fun normalizeUnicode(input: String): String {
        val ascii = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return ascii.replace(Regex("[^\\p{L}0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.getDefault())
}
