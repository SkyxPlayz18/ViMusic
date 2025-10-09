package it.vfsfitvnm.vimusic.features.import

import android.util.Log
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.models.*
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.SearchBody
import it.vfsfitvnm.providers.innertube.requests.searchPage
import it.vfsfitvnm.providers.innertube.utils.from
import kotlinx.coroutines.*
import kotlin.math.*

data class SongImportInfo(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long? = null,
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

    companion object {
        private const val MINIMUM_SCORE_THRESHOLD = 60
        private const val PRIMARY_ARTIST_EXACT_MATCH_BONUS = 40
        private const val OTHER_ARTIST_MATCH_BONUS = 10
        private const val TITLE_SIMILARITY_WEIGHT = 50
        private const val ALBUM_MATCH_BONUS = 30
        private const val MODIFIER_MATCH_BONUS = 25
        private const val MODIFIER_MISMATCH_PENALTY = 40
        private const val EXACT_TITLE_BONUS = 80
        private const val DURATION_CLOSE_BONUS = 40
        private const val DURATION_MEDIUM_BONUS = 15

        private val KNOWN_MODIFIERS = setOf(
            "remix", "edit", "mix", "live", "cover", "instrumental", "karaoke",
            "acoustic", "unplugged", "reverb", "slowed", "sped", "chopped",
            "screwed", "deluxe", "version", "edition", "ultra"
        )
    }

    suspend fun import(
        songList: List<SongImportInfo>,
        playlistName: String,
        unknownErrorMessage: String,
        onProgressUpdate: (ImportStatus) -> Unit,
        logAppend: (String) -> Unit = {}
    ) {
        try {
            val totalTracks = songList.size
            val songsToAdd = mutableListOf<Pair<Song, List<Innertube.Info<it.vfsfitvnm.providers.innertube.models.NavigationEndpoint.Endpoint.Browse>>>>()
            val failedTracks = mutableListOf<SongImportInfo>()
            var processedCount = 0

            onProgressUpdate(ImportStatus.InProgress(0, totalTracks))

            val batchSize = 10
            songList.chunked(batchSize).forEach { batch ->
                coroutineScope {
                    val deferredSongsInBatch = batch.map { track ->
                        async(Dispatchers.IO) {
                            val q = "${track.title} ${track.artist} ${track.album ?: ""}"
                            logAppend("🔍 Searching: \"$q\"")

                            val searchCandidates = Innertube.searchPage(
                                body = SearchBody(query = q, params = Innertube.SearchFilter.Song.value)
                            ) { content ->
                                content.musicResponsiveListItemRenderer?.let(Innertube.SongItem::from)
                            }?.getOrNull()?.items

                            if (searchCandidates.isNullOrEmpty()) {
                                logAppend("❌ No results for ${track.title}")
                                return@async null
                            }

                            val bestMatch = findBestMatchInResults(track, searchCandidates, logAppend)
                            bestMatch?.let {
                                val artistsWithEndpoints = it.authors?.filter { a ->
                                    val name = a.name?.trim() ?: ""
                                    a.endpoint != null && name.isNotEmpty() && !name.contains(":")
                                } ?: emptyList()

                                val artistsText = artistsWithEndpoints.joinToString(" & ") { it.name ?: "" }

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
                onProgressUpdate(ImportStatus.InProgress(processedCount, totalTracks))
            }

            if (songsToAdd.isNotEmpty()) {
                transaction {
                    val playlist = Playlist(name = playlistName)
                    val playlistId = Database.instance.insert(playlist)
                    if (playlistId != -1L) {
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
                            Database.instance.insert(SongPlaylistMap(songId = song.id, playlistId = playlistId, position = index))
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
            logAppend("✅ Import complete: ${songsToAdd.size} ok, ${failedTracks.size} failed")

        } catch (e: Exception) {
            Log.e("PlaylistImporter", "Error importing", e)
            onProgressUpdate(ImportStatus.Error(e.message ?: unknownErrorMessage))
        }
    }

    private fun findBestMatchInResults(
        importTrack: SongImportInfo,
        candidates: List<Innertube.SongItem>,
        logAppend: (String) -> Unit
    ): Innertube.SongItem? {
        val importInfo = parseSongInfo(importTrack.title, importTrack.artist, importTrack.album)
        val normalizedImportTitle = normalize(importTrack.title)
        var best: Pair<Innertube.SongItem, Int>? = null

        candidates.forEach { candidate ->
            val candidateTitleRaw = candidate.info?.name ?: ""
            val candidateTitle = normalize(candidateTitleRaw)
            val candidateArtists = candidate.authors?.joinToString(" ") { it.name ?: "" } ?: ""
            val candidateAlbum = candidate.album?.name
            val candidateInfo = parseSongInfo(candidateTitleRaw, candidateArtists, candidateAlbum)
            var score = calculateMatchScore(importInfo, candidateInfo, candidateAlbum)

            if (normalizedImportTitle == candidateTitle) score += EXACT_TITLE_BONUS

            importTrack.durationMs?.let { csvDur ->
                val candDurMs = parseDurationToMs(candidate.durationText)
                if (candDurMs != null) {
                    val diff = abs(csvDur - candDurMs)
                    when {
                        diff <= 3000L -> score += DURATION_CLOSE_BONUS
                        diff <= 10000L -> score += DURATION_MEDIUM_BONUS
                    }
                }
            }

            if (best == null || score > best!!.second) best = candidate to score
        }

        best?.let { (song, score) ->
            logAppend("🎯 Best: ${song.info?.name} (score=$score)")
            if (score >= MINIMUM_SCORE_THRESHOLD) return song
        }

        logAppend("⚠️ No strong match for ${importTrack.title}")
        return null
    }

    private fun parseSongInfo(title: String, artists: String, album: String?): ProcessedSongInfo {
        val normalizedTitle = title.lowercase()
        val modRegex = """[(\[].*?[)\]]|-.*""".toRegex()
        val foundMods = modRegex.findAll(normalizedTitle)
            .map { it.value.replace(Regex("[\\[\\]()\\-]"), "").trim() }
            .flatMap { it.split(" ") }
            .map { it.trim() }
            .filter { word -> KNOWN_MODIFIERS.any { it in word } }
            .toSet()

        val baseTitle = modRegex.replace(normalizedTitle, "").trim()
        val allArtists = artists.lowercase().split(Regex(",|&|feat\\.?|ft\\.?|with")).map { it.trim() }.filter { it.isNotEmpty() }

        return ProcessedSongInfo(baseTitle, allArtists.firstOrNull() ?: "", allArtists, foundMods, album?.lowercase()?.trim())
    }

    private fun calculateMatchScore(a: ProcessedSongInfo, b: ProcessedSongInfo, candAlbum: String?): Int {
        var s = 0
        if (a.primaryArtist.isNotEmpty() && b.allArtists.any { it.contains(a.primaryArtist) }) s += PRIMARY_ARTIST_EXACT_MATCH_BONUS
        val others = a.allArtists.drop(1)
        s += others.count { imp -> b.allArtists.any { it.contains(imp) } } * OTHER_ARTIST_MATCH_BONUS
        if (s == 0) return 0

        val dist = levenshtein(a.baseTitle, b.baseTitle)
        val maxLen = max(a.baseTitle.length, b.baseTitle.length)
        if (maxLen > 0) s += ((1.0 - dist.toDouble() / maxLen) * TITLE_SIMILARITY_WEIGHT).toInt()

        a.album?.let { alb ->
            candAlbum?.lowercase()?.let { ca ->
                if (ca.contains(alb)) s += ALBUM_MATCH_BONUS
            }
        }

        if (a.modifiers.isNotEmpty() && a.modifiers == b.modifiers) s += MODIFIER_MATCH_BONUS * a.modifiers.size
        else if (a.modifiers.isEmpty() && b.modifiers.isNotEmpty()) s -= MODIFIER_MISMATCH_PENALTY
        else if (a.modifiers.isNotEmpty() && b.modifiers.isEmpty()) s -= MODIFIER_MISMATCH_PENALTY / 2

        return s
    }

    private fun normalize(input: String?): String {
        return input?.lowercase()
            ?.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            ?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim() ?: ""
    }

    private fun parseDurationToMs(t: String?): Long? {
        if (t == null) return null
        val s = t.trim().replace(",", "")
        s.toLongOrNull()?.let { return it }
        val p = s.split(":").map { it.trim() }
        return try {
            when (p.size) {
                2 -> ((p[0].toLong() * 60) + p[1].toDouble()) * 1000
                3 -> ((p[0].toLong() * 3600) + (p[1].toLong() * 60) + p[2].toDouble()) * 1000
                else -> null
            }?.toLong()
        } catch (_: Exception) { null }
    }

    private data class ProcessedSongInfo(
        val baseTitle: String,
        val primaryArtist: String,
        val allArtists: List<String>,
        val modifiers: Set<String>,
        val album: String?
    )

    private fun levenshtein(a: CharSequence, b: CharSequence): Int {
        val la = a.length
        val lb = b.length
        var cost = IntArray(la + 1) { it }
        var newCost = IntArray(la + 1)
        for (i in 1..lb) {
            newCost[0] = i
            for (j in 1..la) {
                val match = if (a[j - 1] == b[i - 1]) 0 else 1
                val rep = cost[j - 1] + match
                val ins = cost[j] + 1
                val del = newCost[j - 1] + 1
                newCost[j] = min(min(rep, ins), del)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[la]
    }
}
