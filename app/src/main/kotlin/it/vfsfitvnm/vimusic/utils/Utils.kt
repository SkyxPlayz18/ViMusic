@file:OptIn(UnstableApi::class)

package it.vfsfitvnm.vimusic.utils

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Log
import android.content.Intent
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.models.Song
import it.vfsfitvnm.vimusic.preferences.AppearancePreferences
import it.vfsfitvnm.vimusic.service.LOCAL_KEY_PREFIX
import it.vfsfitvnm.vimusic.service.isLocal
import it.vfsfitvnm.core.ui.utils.SongBundleAccessor
import it.vfsfitvnm.vimusic.utils.logDebug
import it.vfsfitvnm.providers.innertube.Innertube
import it.vfsfitvnm.providers.innertube.models.bodies.ContinuationBody
import it.vfsfitvnm.providers.innertube.requests.playlistPage
import it.vfsfitvnm.providers.piped.models.Playlist
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import java.io.FileInputStream
import java.io.FileOutputStream
import it.vfsfitvnm.vimusic.Database
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.*



val Innertube.SongItem.asMediaItem: MediaItem
    get() = MediaItem.Builder()
        .setMediaId(key)
        .setUri(key)
        .setCustomCacheKey(key)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(info?.name)
                .setArtist(authors?.joinToString("") { it.name.orEmpty() })
                .setAlbumTitle(album?.name)
                .setArtworkUri(thumbnail?.url?.toUri())
                .setExtras(
                    SongBundleAccessor.bundle {
                        albumId = album?.endpoint?.browseId
                        durationText = this@asMediaItem.durationText
                        artistNames = authors
                            ?.filter { it.endpoint != null }
                            ?.mapNotNull { it.name }
                        artistIds = authors?.mapNotNull { it.endpoint?.browseId }
                        explicit = this@asMediaItem.explicit
                    }
                )
                .build()
        )
        .build()

val Innertube.VideoItem.asMediaItem: MediaItem
    get() = MediaItem.Builder()
        .setMediaId(key)
        .setUri(key)
        .setCustomCacheKey(key)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(info?.name)
                .setArtist(authors?.joinToString("") { it.name.orEmpty() })
                .setArtworkUri(thumbnail?.url?.toUri())
                .setExtras(
                    SongBundleAccessor.bundle {
                        durationText = this@asMediaItem.durationText
                        artistNames = if (isOfficialMusicVideo) authors
                            ?.filter { it.endpoint != null }
                            ?.mapNotNull { it.name }
                        else null
                        artistIds = if (isOfficialMusicVideo) authors
                            ?.mapNotNull { it.endpoint?.browseId }
                        else null
                    }
                )
                .build()
        )
        .build()

val Playlist.Video.asMediaItem: MediaItem?
    get() {
        val key = id ?: return null

        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(key)
            .setCustomCacheKey(key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(uploaderName)
                    .setArtworkUri(Uri.parse(thumbnailUrl.toString()))
                    .setExtras(
                        SongBundleAccessor.bundle {
                            durationText = duration.toComponents { minutes, seconds, _ ->
                                "$minutes:${seconds.toString().padStart(2, '0')}"
                            }
                            artistNames = listOf(uploaderName)
                            artistIds = uploaderId?.let { listOf(it) }
                        }
                    )
                    .build()
            )
            .build()
    }

val Song.asMediaItem: MediaItem
    get() = MediaItem.Builder()
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artistsText)
                .setArtworkUri(thumbnailUrl?.toUri())
                .setExtras(
                    SongBundleAccessor.bundle {
                        durationText = this@asMediaItem.durationText
                        explicit = this@asMediaItem.explicit
                    }
                )
                .build()
        )
        .setMediaId(id)
        .setUri(
            if (isLocal) ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id.substringAfter(LOCAL_KEY_PREFIX).toLong()
            ) else id.toUri()
        )
        .setCustomCacheKey(id)
        .build()

val Duration.formatted
    @Composable get() = toComponents { hours, minutes, _, _ ->
        when {
            hours == 0L -> stringResource(id = R.string.format_minutes, minutes)
            hours < 24L -> stringResource(id = R.string.format_hours, hours)
            else -> stringResource(id = R.string.format_days, hours / 24)
        }
    }

fun String?.thumbnail(
    size: Int,
    maxSize: Int = AppearancePreferences.maxThumbnailSize
): String? {
    val actualSize = size.coerceAtMost(maxSize)
    return when {
        this?.startsWith("https://lh3.googleusercontent.com") == true -> "$this-w$actualSize-h$actualSize"
        this?.startsWith("https://yt3.ggpht.com") == true -> "$this-w$actualSize-h$actualSize-s$actualSize"
        else -> this
    }
}

fun Uri?.thumbnail(size: Int) = toString().thumbnail(size)?.toUri()

fun formatAsDuration(millis: Long) = DateUtils.formatElapsedTime(millis / 1000).removePrefix("0")

@Suppress("LoopWithTooManyJumpStatements")
suspend fun Result<Innertube.PlaylistOrAlbumPage>.completed(
    maxDepth: Int = Int.MAX_VALUE,
    shouldDedup: Boolean = false
) = runCatching {
    val page = getOrThrow()
    val songs = page.songsPage?.items.orEmpty().toMutableList()

    if (songs.isEmpty()) return@runCatching page

    var continuation = page.songsPage?.continuation
    var depth = 0

    val context = currentCoroutineContext()

    while (continuation != null && depth++ < maxDepth && context.isActive) {
        val newSongs = Innertube
            .playlistPage(
                body = ContinuationBody(continuation = continuation)
            )
            ?.getOrNull()
            ?.takeUnless { it.items.isNullOrEmpty() } ?: break

        if (shouldDedup && newSongs.items?.any { it in songs } != false) break

        newSongs.items?.let { songs += it }
        continuation = newSongs.continuation
    }

    page.copy(
        songsPage = Innertube.ItemsPage(
            items = songs,
            continuation = null
        )
    )
}.also { it.exceptionOrNull()?.printStackTrace() }

fun <T> Flow<T>.onFirst(block: suspend (T) -> Unit): Flow<T> {
    var isFirst = true

    return onEach {
        if (!isFirst) return@onEach

        block(it)
        isFirst = false
    }
}

inline fun <reified T : Throwable> Throwable.findCause(): T? {
    if (this is T) return this

    var th = cause
    while (th != null) {
        if (th is T) return th
        th = th.cause
    }

    return null
}

fun copyCachedFileToPermanentStorage(
    context: Context,
    cacheDir: File,
    cacheKey: String
): File? {
    return try {
        logDebug(context, "🚀 Mulai salin file cache: key=$cacheKey")

        val srcFile = File(cacheDir, cacheKey)
        if (!srcFile.exists()) {
            logDebug(context, "❌ File cache tidak ditemukan: ${srcFile.path}")
            return null
        }

        val dstDir = context.getOfflineSongDir()
        if (!dstDir.exists()) {
            dstDir.mkdirs()
            logDebug(context, "📂 Folder offline dibuat: ${dstDir.path}")
        }

        val dstFile = File(dstDir, "$cacheKey.mp3")
        srcFile.copyTo(dstFile, overwrite = true)

        logDebug(context, "✅ File disalin ke: ${dstFile.path}")
        dstFile
    } catch (e: Exception) {
        logDebug(context, "💥 ERROR di copyCachedFileToPermanentStorage: ${e.stackTraceToString()}")
        null
    }
}

fun verifyOfflineFiles(context: Context) {
    try {
        val offlineDir = context.getOfflineSongDir()
        val db = Database.instance ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val songs = db.getDownloadedSongs().firstOrNull() ?: return@launch

                songs.forEach { song ->
                    val file = File(offlineDir, "${song.id}.mp3")
                    if (!file.exists()) {
                        val updated = song.copy(isDownloaded = false)
                        db.upsert(updated)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("verifyOfflineFiles", "Gagal memverifikasi offline files (dalam coroutine): ${e.message}")
                logDebug(context, "Gagal memverifikasi Offline Files (Dalam Coroutine): ${e.message}")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Log.e("verifyOfflineFiles", "Gagal memverifikasi offline files: ${e.message}")
        logDebug(context, "Gagal memverifikasi offline files: ${e.message}")
    }
}

fun deleteOfflineSong(context: Context, songId: String) {
    try {
        val offlineDir = context.getOfflineSongDir()
        val file = File(offlineDir, "$songId.mp3")
        if (file.exists()) {
            file.delete()
            Log.d("OfflineDelete", "🗑️ File ${file.name} berhasil dihapus")
        }

        // Update database
        CoroutineScope(Dispatchers.IO).launch {
            val db = Database.instance
            val song = db.getSongById(songId)
            song?.let {
                val updated = it.copy(isDownloaded = false)
                db.upsert(updated)
            }
        }

        // Kirim broadcast biar UI refresh
        val intent = Intent("it.vfsfitvnm.vimusic.DOWNLOAD_COMPLETED")
        intent.putExtra("songId", songId)
        context.sendBroadcast(intent)

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

        fun logDebug(context: Context, message: String) {
    try {
        val logDir = File("/storage/emulated/0/ViMusic_logs")
        if (!logDir.exists()) logDir.mkdirs()

        val logFile = File(logDir, "ViMusic_debug_utils.txt")

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile.appendText("[$timestamp] $message\n")
    } catch (e: Exception) {
        e.printStackTrace()
    }
        }
