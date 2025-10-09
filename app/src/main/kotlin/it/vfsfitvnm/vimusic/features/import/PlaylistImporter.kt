package it.vfsfitvnm.vimusic.features.import

import android.os.Environment
import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.*
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
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
        private const val MINIMUM_SCORE_THRESHOLD = 60
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 40
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val EXACT_TITLE_BONUS = 50
        private const val ALBUM_MATCH_BONUS = 30
        private const val TITLE_SIMILARITY_WEIGHT = 50
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

            writeLog("=== IMPORT STARTED ===")
            writeLog("Playlist name: $playlistName")
            writeLog("Total tracks: $totalTracks")

            onProgressUpdate(ImportStatus.InProgress(processed = 0, total = totalTracks))
            val batchSize = 10

            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferred = batch.map { track ->
                        async(Dispatchers.IO) {
                            try {
                                writeLog("🔍 Searching for: ${track.title} - ${track.artist}")

                                val queries = listOf(
                                    "${track.title} ${track.artist} ${track.album ?: ""}",
                                    "${track.title} ${track.artist}",
                                    track.title
                                ).filter { it.isNotBlank() }

                                var foundItem: Innertube.SongItem? = null

                                for (q in queries) {
                                    val searchResults = Innertube.searchPage(
                                        body = SearchBody(query = q, params = Innertube.SearchFilter.Song.value)
                                    ) { content ->
                                        content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                                    }?.getOrNull()?.items

                                    if (!searchResults.isNullOrEmpty()) {
                                        val best = findBestMatchInResults(track, searchResults)
                                        if (best != null) {
                                            foundItem = best
                                            break
                                        }
                                    }
                                }

                                if (foundItem == null) {
                                    writeLog("❌ Failed: ${track.title} - ${track.artist}")
                                    return@async null
                                }

                                val artists = foundItem.authors ?: emptyList()
                                val artistsText = artists.joinToString(", ") { it.name ?: "" }

                                val song = Song(
                                    id = foundItem.info?.endpoint?.videoId ?: "",
                                    title = foundItem.info?.name ?: "",
                                    artistsText = artistsText,
                                    durationText = foundItem.durationText,
                                    thumbnailUrl = foundItem.thumbnail?.url,
                                    album = foundItem.album?.name
                                )

                                writeLog("✅ Matched: ${song.title} - ${song.artistsText}")
                                song to artists
                            } catch (e: Exception) {
                                writeLog("💀 Exception on ${track.title}: ${e.message}")
                                null
                            }
                        }
                    }

                    val results = deferred.awaitAll()
                    batch.zip(results).forEach { (originalTrack, result) ->
                        if (result != null) {
                            songsToAdd.add(result)
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
                    val playlist = Playlist(name = playlistName)
                    val playlistId = Database.instance.insert(playlist)

                    if (playlistId != -1L) {
                        songsToAdd.forEachIndexed { index, (song, artists) ->
                            Database.instance.upsert(song)
                            artists.forEach { artist ->
                                val id = artist.endpoint?.browseId
                                val name = artist.name
                                if (id != null && name != null) {
                                    Database.instance.upsert(Artist(id = id, name = name))
                                    Database.instance.upsert(SongArtistMap(songId = song.id, artistId = id))
                                }
                            }
                            Database.instance.insert(SongPlaylistMap(songId = song.id, playlistId = playlistId, position = index))
                        }
                    }
                }
            }

            writeLog("=== IMPORT COMPLETE ===")
            writeLog("Imported: ${songsToAdd.size}, Failed: ${failedTracks.size}")

            onProgressUpdate(
                ImportStatus.Complete(
                    imported = songsToAdd.size,
                    failed = failedTracks.size,
                    total = totalTracks,
                    failedTracks = failedTracks
                )
            )

        } catch (e: Exception) {
            writeLog("❗ Error during import: ${e.message}")
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    private fun findBestMatchInResults(
        importTrack: SongImportInfo,
        candidates: List<Innertube.SongItem>
    ): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)

        val scored = candidates.map { c ->
            val title = normalize(c.info?.name)
            val artists = c.authors?.joinToString(" ") { it.name ?: "" } ?: ""
            val album = c.album?.name
            val info = parseSongInfo(title, artists, album)

            var score = calculateMatchScore(importInfo, info, album)
            if (normalize(importTrack.title) == title) score += EXACT_TITLE_BONUS
            if (album != null && importTrack.album != null && normalize(album) == normalize(importTrack.album)) score += 40
            c to score
        }

        val bestPair = scored.maxByOrNull { it.second } ?: return null
        val best = bestPair.first
        val bestScore = bestPair.second
        writeLog("Match score for ${best.info?.name}: $bestScore")
        return if (bestScore >= MINIMUM_SCORE_THRESHOLD) best else null
    }

    private fun parseSongInfo(title: String?, artists: String?, album: String?): ProcessedSongInfo {
        val normalizedTitle = normalize(title)
        val baseTitle = normalizedTitle.replace("""[(\[].*?[)\]]|-.*""".toRegex(), "").trim()
        val allArtists = normalize(artists).split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }
        val modifiers = KNOWN_MODIFIERS.filter { normalizedTitle.contains(it) }.toSet()
        return ProcessedSongInfo(baseTitle, allArtists.firstOrNull() ?: "", allArtists, modifiers, normalize(album))
    }

    private fun calculateMatchScore(
        importInfo: ProcessedSongInfo,
        candidateInfo: ProcessedSongInfo,
        candidateAlbumName: String?
    ): Int {
        var score = 0
        if (importInfo.primaryArtist.isNotEmpty() && candidateInfo.allArtists.any { it.contains(importInfo.primaryArtist) })
            score += PRIMARY_ARTIST_EXACT_MATCH_BONUS
        score += importInfo.allArtists.drop(1).count { a -> candidateInfo.allArtists.any { it.contains(a) } } * OTHER_ARTIST_MATCH_BONUS
        val titleDistance = levenshtein(importInfo.baseTitle, candidateInfo.baseTitle)
        val maxLen = max(importInfo.baseTitle.length, candidateInfo.baseTitle.length)
        if (maxLen > 0) score += ((1.0 - titleDistance.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()
        importInfo.album?.let { impAlbum ->
            candidateAlbumName?.lowercase()?.let { if (it.contains(impAlbum)) score += ALBUM_MATCH_BONUS }
        }
        return score
    }

    private fun normalize(input: String?): String =
        input?.lowercase()
            ?.replace(Regex("[^a-z0-9 ]"), "")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim() ?: ""

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)
        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                newCost[j] = min(min(cost[j] + 1, newCost[j - 1] + 1), cost[j - 1] + match)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    private fun writeLog(message: String) {
        try {
            val logDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ViMusicDebugLogs")
            if (!logDir.exists()) logDir.mkdirs()
            val logFile = File(
                logDir,
                "import_log_${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}.txt"
            )
            val writer = FileWriter(logFile, true)
            writer.appendLine("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $message")
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            Log.e("PlaylistImporter", "Failed to write log: ${e.message}")
        }
    }
}
