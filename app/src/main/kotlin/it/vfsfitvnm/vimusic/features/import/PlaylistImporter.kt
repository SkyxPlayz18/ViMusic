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
import java.util.Locale
import java.util.regex.Pattern

data class SongImportInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val trackUri: String? = null // optional: may contain youtube url/id or spotify uri (spotify won't help)
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
        private const val MINIMUM_SCORE_THRESHOLD = 60 // lebih ketat
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 50
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val TITLE_SIMILARITY_WEIGHT = 40
        private const val ALBUM_MATCH_BONUS = 40
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40
        private const val EXACT_TITLE_BONUS = 60 // besar: exact title match after normalize => strong prefer
        private val KNOWN_MODIFIERS = setOf(
            "remix", "edit", "mix", "live", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "reverb", "slowed", "sped", "sped up", "chopped", "screwed",
            "deluxe", "version", "edition", "ultra", "japanese", "jpn", "korean", "kr"
        )
        private val YT_ID_REGEX = Pattern.compile("(?:v=|/)([A-Za-z0-9_-]{11})") // simple extractor for yt urls/ids
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
                    // --- START REPLACE BLOCK ---
val deferred: List<kotlinx.coroutines.Deferred<Pair<Song, List<Innertube.Info<it.vfsfitvnm.providers.innertube.models.NavigationEndpoint.Endpoint.Browse>>>?>> =
    batch.map { track ->
        async<Pair<Song, List<Innertube.Info<it.vfsfitvnm.providers.innertube.models.NavigationEndpoint.Endpoint.Browse>>>?>(Dispatchers.IO) {
            try {
                // 1) If CSV contains a YouTube id/url use it directly
                val maybeYoutubeId = extractYoutubeId(track.trackUri)

                val searchQuery = "${track.title} ${track.artist} ${track.album ?: ""}".trim()

                var searchCandidates = Innertube.searchPage(
                    body = SearchBody(query = searchQuery, params = Innertube.SearchFilter.Song.value)
                ) { content ->
                    content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()

                // If no candidates, don't immediately bail out — try small fallbacks (safe)
                if (searchCandidates.isNullOrEmpty()) {
                    // fallback 1: normalized (strip diacritics) query
                    val normalizedQuery = normalizeUnicode(searchQuery)
                    if (normalizedQuery != searchQuery) {
                        searchCandidates = Innertube.searchPage(
                            body = SearchBody(query = normalizedQuery, params = Innertube.SearchFilter.Song.value)
                        ) { content ->
                            content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                        }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()
                    }
                }

                // fallback 2: try artist-only search if still empty
                if (searchCandidates.isNullOrEmpty() && !track.artist.isNullOrBlank()) {
                    val artistOnlyQuery = normalizeUnicode(track.artist)
                    searchCandidates = Innertube.searchPage(
                        body = SearchBody(query = artistOnlyQuery, params = Innertube.SearchFilter.Song.value)
                    ) { content ->
                        content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                    }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()
                }

                if (searchCandidates.isNullOrEmpty()) {
                    // nothing we can do
                    return@async null
                }

                // 2) If CSV had YouTube id, prefer candidate with same videoId
                maybeYoutubeId?.let { id ->
                    val direct = searchCandidates.firstOrNull { it.info?.endpoint?.videoId == id }
                    if (direct != null) return@async buildSongFromInnertube(direct)
                }

                // 3) Try strict matching: normalized title exact + primary artist contains
                val strict = findStrictMatch(track, searchCandidates)
                if (strict != null) return@async buildSongFromInnertube(strict)

                // 4) fallback to best scored candidate (fuzzy) using existing scoring
                val best = findBestMatchInResults(track, searchCandidates)
                if (best != null) return@async buildSongFromInnertube(best)

                null
            } catch (t: Throwable) {
                Log.e("PlaylistImporter", "Error while processing ${track.title}: ${t.message}")
                null
            }
        }
    }

val results = deferred.awaitAll()
batch.zip(results).forEach { (originalTrack, resultPair) ->
    if (resultPair != null) {
        val (song, artistsWithEndpoints) = resultPair
        if (song.id.isNotBlank()) {
            songsToAdd.add(song to artistsWithEndpoints)
        } else {
            failedTracks.add(originalTrack)
        }
    } else {
        failedTracks.add(originalTrack)
    }
}
// --- END REPLACE BLOCK ---

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
                                if (artistId != null && artistName != null) {
                                    Database.instance.upsert(Artist(id = artistId, name = artistName))
                                    Database.instance.upsert(SongArtistMap(songId = song.id, artistId = artistId))
                                }
                            }
                            // position: keep same order as CSV (index)
                            Database.instance.insert(SongPlaylistMap(songId = song.id, playlistId = newPlaylistId, position = index))
                        }
                    }
                }
            }

            onProgressUpdate(ImportStatus.Complete(imported = songsToAdd.size, failed = failedTracks.size, total = totalTracks, failedTracks = failedTracks))

        } catch (e: Exception) {
            Log.e("PlaylistImporter", "An error occurred during the import process.", e)
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    /** Build Song + artist infos tuple from Innertube.SongItem */
    private fun buildSongFromInnertube(item: Innertube.SongItem): Pair<Song, List<Innertube.Info<it.vfsfitvnm.providers.innertube.models.NavigationEndpoint.Endpoint.Browse>>> {
        val artistsWithEndpoints = item.authors ?: emptyList()
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

        val song = Song(
            id = item.info?.endpoint?.videoId ?: "",
            title = item.info?.name ?: "",
            artistsText = artistsText,
            durationText = item.durationText,
            thumbnailUrl = item.thumbnail?.url,
            album = item.album?.name
        )
        return song to artistsWithEndpoints
    }

    /** Try strict matching: normalized title exact + primary artist contains; also prefer album match if available */
    private fun findStrictMatch(importTrack: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val normTitle = normalize(importTrack.title)
        val importPrimaryArtist = importTrack.artist.lowercase(Locale.getDefault()).trim()
        val importAlbum = importTrack.album?.lowercase(Locale.getDefault())?.trim()

        val exactTitleCandidates = candidates.filter { normalize(it.info?.name ?: "") == normTitle }
        if (exactTitleCandidates.isEmpty()) return null

        // prefer candidates whose primary artist contains import primary artist
        val artistMatching = exactTitleCandidates.filter { candidate ->
            val candidateArtists = (candidate.authors?.joinToString(" ") { it.name ?: "" } ?: "").lowercase(Locale.getDefault())
            candidateArtists.contains(importPrimaryArtist)
        }
        val albumMatched = artistMatching.firstOrNull { candidate ->
            importAlbum != null && (candidate.album?.name?.lowercase(Locale.getDefault())?.contains(importAlbum) == true)
        }
        if (albumMatched != null) return albumMatched
        if (artistMatching.isNotEmpty()) return artistMatching.first()

        // fallback to exact title candidates even if artist not perfect — but prefer those with album match
        val albumFallback = exactTitleCandidates.firstOrNull { candidate ->
            importAlbum != null && (candidate.album?.name?.lowercase(Locale.getDefault())?.contains(importAlbum) == true)
        }
        if (albumFallback != null) return albumFallback

        // else return first exact title candidate (less ideal)
        return exactTitleCandidates.firstOrNull()
    }

    private fun findBestMatchInResults(importTrack: SongImportInfo, candidates: List<Innertube.SongItem>): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)

        val scoredCandidates = candidates.map { candidate ->
            val candidateTitle = candidate.info?.name ?: ""
            val candidateArtists = candidate.authors?.joinToString(" ") { it.name ?: "" } ?: ""
            val candidateAlbum = candidate.album?.name
            val candidateInfo = parseSongInfo(candidateTitle, candidateArtists, candidateAlbum)
            var score = calculateMatchScore(importInfo, candidateInfo, candidateAlbum)

            // exact normalized title bonus
            if (normalize(importTrack.title) == normalize(candidateTitle)) score += EXACT_TITLE_BONUS

            candidate to score
        }

        val best = scoredCandidates.maxByOrNull { (_, score) -> score }
        return best?.takeIf { it.second >= MINIMUM_SCORE_THRESHOLD }?.first
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
        val allArtists = artists.lowercase(Locale.getDefault()).split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }

        return ProcessedSongInfo(baseTitle, allArtists.firstOrNull() ?: "", allArtists, foundModifiers, album?.lowercase(Locale.getDefault())?.trim())
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

        // if artist score is zero, early exit (require at least some artist match)
        if (score == 0) return 0

        val titleDistance = levenshtein(importInfo.baseTitle, candidateInfo.baseTitle)
        val maxLen = max(importInfo.baseTitle.length, candidateInfo.baseTitle.length)
        if (maxLen > 0) {
            score += ((1.0 - titleDistance.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()
        }

        importInfo.album?.let { importAlbum ->
            candidateAlbumName?.lowercase(Locale.getDefault())?.let { candidateAlbum ->
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

    private fun normalize(input: String?): String {
        if (input == null) return ""
        var s = input.lowercase(Locale.getDefault()).trim()
        // remove common noise words and punctuation used in YouTube titles
        s = s.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        s = s.replace(Regex("(?i)\\b(official|lyrics|audio|video|feat\\.?|ft\\.?|remix|live|hd|mv)\\b"), " ")
        s = s.replace(Regex("[^\\p{L}0-9\\s]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private fun normalizeUnicode(input: String): String {
    // hapus diakritik — aman untuk latin + sebagian karakter,
    // ini nggak transliterate Kanji/Kana, tapi bantu untuk accented latin
    val ascii = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    return ascii.replace(Regex("[^\\p{L}0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim().lowercase(Locale.getDefault())
    }

    private fun extractYoutubeId(uri: String?): String? {
        if (uri == null) return null
        // If the CSV has a bare 11-char id, accept it; if a youtube url, extract
        val trimmed = uri.trim()
        if (trimmed.length == 11 && trimmed.matches(Regex("^[A-Za-z0-9_-]{11}\$"))) return trimmed
        val matcher = YT_ID_REGEX.matcher(trimmed)
        return if (matcher.find()) matcher.group(1) else null
    }
}
