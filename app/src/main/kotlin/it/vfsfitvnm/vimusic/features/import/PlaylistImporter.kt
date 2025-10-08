package it.vfsfitvnm.vimusic.features.import

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.MusicShelfRenderer
import it.vfsfitvnm.providers.innertube.models.NavigationEndpoint
import it.vfsfitvnm.providers.innertube.models.Runs
import it.vfsfitvnm.providers.innertube.models.Thumbnail
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
        // Sesuaikan threshold kalau mau lebih longgar/ketat
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

            onProgressUpdate(ImportStatus.InProgress(processed = 0, total = totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { track ->
                        async(Dispatchers.IO) {
                            try {
                                // cleanup query
                                val cleanedQuery = track.title
                                    .replace(Regex("\\(.*?\\)"), "")
                                    .replace(Regex("\\[.*?\\]"), "")
                                    .replace(Regex("(?i)\\b(official|lyrics|audio|video|feat\\.?|ft\\.?|remix|live)\\b"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()

                                val queries = listOf(
                                    "$cleanedQuery ${track.artist} ${track.album ?: ""}".trim(),
                                    "$cleanedQuery ${track.artist}".trim(),
                                    "${track.title} ${track.artist}".trim(),
                                    "$cleanedQuery".trim(),
                                    track.title.trim()
                                ).filter { it.isNotBlank() }.distinct()

                                var searchCandidates: List<Innertube.SongItem>? = null
                                var usedQuery: String? = null

                                // sequential fallbacks
                                for (q in queries) {
                                    usedQuery = q
                                    Log.d("PlaylistImporter", "Searching: \"$q\"")

                                    val res = Innertube.searchPage(
                                        body = SearchBody(query = q, params = Innertube.SearchFilter.Song.value)
                                    ) { content: MusicShelfRenderer.Content ->
                                        // mapper: MusicShelfRenderer.Content -> Innertube.SongItem?
                                        // Try to build SongItem from the content. Field names follow repo models.
                                        content.musicResponsiveListItemRenderer?.let { mr ->
                                            try {
                                                // title run (first flex column)
                                                val titleRun = mr.flexColumns
                                                    ?.firstOrNull()
                                                    ?.musicResponsiveListItemFlexColumnRenderer
                                                    ?.text
                                                    ?.runs
                                                    ?.firstOrNull()

                                                val info = titleRun?.let {
                                                    // explicitly specify Watch endpoint type
                                                    Innertube.Info<NavigationEndpoint.Endpoint.Watch>(it)
                                                } ?: mr.navigationEndpoint?.endpoint?.let {
                                                    // fallback: can't build Info from run, leave null
                                                    null
                                                }

                                                // authors: try flex/fixed columns runs -> map to Browse infos
                                                val authors = mr.flexColumns
                                                    ?.mapNotNull { col ->
                                                        col.musicResponsiveListItemFlexColumnRenderer
                                                            ?.text
                                                            ?.runs
                                                            ?.firstOrNull()
                                                            ?.let { run -> Innertube.Info<NavigationEndpoint.Endpoint.Browse>(run) }
                                                    }
                                                    ?: mr.fixedColumns
                                                        ?.mapNotNull { col ->
                                                            col.musicResponsiveListItemFlexColumnRenderer
                                                                ?.text
                                                                ?.runs
                                                                ?.firstOrNull()
                                                                ?.let { run -> Innertube.Info<NavigationEndpoint.Endpoint.Browse>(run) }
                                                        }

                                                // duration is often in fixedColumns[1]
                                                val duration = mr.fixedColumns
                                                    ?.getOrNull(1)
                                                    ?.musicResponsiveListItemFlexColumnRenderer
                                                    ?.text
                                                    ?.runs
                                                    ?.firstOrNull()
                                                    ?.text

                                                // thumbnail: try to pick first thumbnail object (model Thumbnail or ThumbnailRenderer)
                                                val thumbnail: Thumbnail? = try {
    mr.thumbnail?.let { it as? Thumbnail }
        ?: mr.thumbnailRenderer?.thumbnail?.let { it as? Thumbnail }
} catch (_: Throwable) { null }

                                                Innertube.SongItem(
                                                    info = info,
                                                    authors = authors,
                                                    album = null,
                                                    durationText = duration,
                                                    explicit = false,
                                                    thumbnail = thumbnail
                                                )
                                            } catch (t: Throwable) {
                                                // mapping failed for this content
                                                null
                                            }
                                        }
                                    }?.getOrNull()?.items

                                    if (!res.isNullOrEmpty()) {
                                        // filter to SongItem
                                        searchCandidates = res.filterIsInstance<Innertube.SongItem>()
                                        if (!searchCandidates.isNullOrEmpty()) break
                                    }

                                    // fallback: try without Song filter
                                    val res2 = Innertube.searchPage(
                                        body = SearchBody(query = q, params = null)
                                    ) { content: MusicShelfRenderer.Content ->
                                        content.musicResponsiveListItemRenderer?.let { mr ->
                                            try {
                                                val titleRun = mr.flexColumns
                                                    ?.firstOrNull()
                                                    ?.musicResponsiveListItemFlexColumnRenderer
                                                    ?.text
                                                    ?.runs
                                                    ?.firstOrNull()
                                                val info = titleRun?.let { Innertube.Info<NavigationEndpoint.Endpoint.Watch>(it) }
                                                val authors = mr.flexColumns
                                                    ?.mapNotNull { col ->
                                                        col.musicResponsiveListItemFlexColumnRenderer
                                                            ?.text
                                                            ?.runs
                                                            ?.firstOrNull()
                                                            ?.let { run -> Innertube.Info<NavigationEndpoint.Endpoint.Browse>(run) }
                                                    } ?: mr.fixedColumns
                                                    ?.mapNotNull { col ->
                                                        col.musicResponsiveListItemFlexColumnRenderer
                                                            ?.text
                                                            ?.runs
                                                            ?.firstOrNull()
                                                            ?.let { run -> Innertube.Info<NavigationEndpoint.Endpoint.Browse>(run) }
                                                    }
                                                val duration = mr.fixedColumns
                                                    ?.getOrNull(1)
                                                    ?.musicResponsiveListItemFlexColumnRenderer
                                                    ?.text
                                                    ?.runs
                                                    ?.firstOrNull()
                                                    ?.text
                                                val thumbnail: Thumbnail? = try {
    mr.thumbnail?.let { it as? Thumbnail }
        ?: mr.thumbnailRenderer?.thumbnail?.let { it as? Thumbnail }
} catch (_: Throwable) { null }
                                                Innertube.SongItem(
                                                    info = info,
                                                    authors = authors,
                                                    album = null,
                                                    durationText = duration,
                                                    explicit = false,
                                                    thumbnail = thumbnail
                                                )
                                            } catch (t: Throwable) {
                                                null
                                            }
                                        }
                                    }?.getOrNull()?.items?.filterIsInstance<Innertube.SongItem>()

                                    if (!res2.isNullOrEmpty()) {
                                        searchCandidates = res2
                                        break
                                    }
                                }

                                if (searchCandidates.isNullOrEmpty()) {
                                    Log.w("PlaylistImporter", "No candidates for: ${track.title} — query used: $usedQuery")
                                    return@async null
                                }

                                val bestMatch = findBestMatchInResults(track, searchCandidates)
                                if (bestMatch == null) {
                                    Log.w("PlaylistImporter", "No match above threshold for: ${track.title}")
                                    return@async null
                                }

                                // build Song model
                                val artistsText = bestMatch.authors?.joinToString(", ") { it.name ?: "" } ?: ""
                                Song(
                                    id = bestMatch.info?.endpoint?.videoId ?: "",
                                    title = bestMatch.info?.name ?: "",
                                    artistsText = artistsText,
                                    durationText = bestMatch.durationText,
                                    thumbnailUrl = bestMatch.thumbnail?.url,
                                    album = bestMatch.album?.name,
                                    explicit = bestMatch.explicit
                                )
                            } catch (t: Throwable) {
                                Log.e("PlaylistImporter", "Error while processing ${track.title}: ${t.message}")
                                null
                            }
                        }
                    }

                    val results = deferred.awaitAll()
                    results.forEachIndexed { i, song ->
                        if (song != null && song.id.isNotBlank()) songsToAdd.add(song)
                        else failedTracks.add(batch[i])
                    }
                }

                processedCount += batch.size
                onProgressUpdate(ImportStatus.InProgress(processed = processedCount, total = totalTracks))
            }

            if (songsToAdd.isNotEmpty()) {
                transaction {
                    // keep existing DB behaviour — insert playlist then add songs.
                    val newPlaylist = Playlist(name = playlistName)
                    val newPlaylistId = Database.instance.insert(newPlaylist)
                    if (newPlaylistId != -1L) {
                        songsToAdd.forEachIndexed { index, s ->
                            Database.instance.upsert(s)
                            Database.instance.insert(
                                it.vfsfitvnm.vimusic.models.SongPlaylistMap(
                                    songId = s.id,
                                    playlistId = newPlaylistId,
                                    position = index
                                )
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
        val allArtists = artists.lowercase().split(Regex(",|&|feat\\.?|ft\\.?|with"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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

// ---------------------------
// Extension: convert Song -> MediaItem
// letakkan **di luar** class (akhir file)
val Song.asMediaItem: MediaItem
    get() = MediaItem.Builder()
        .setMediaId(this.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(this.title)
                .setArtist(this.artistsText)
                .setArtworkUri(this.thumbnailUrl?.let { Uri.parse(it) })
                .build()
        )
        .build()
