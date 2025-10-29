package it.vfsfitvnm.vimusic.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.transaction
import it.vfsfitvnm.vimusic.utils.ActionReceiver
import it.vfsfitvnm.vimusic.utils.download
import it.vfsfitvnm.vimusic.utils.intent
import it.vfsfitvnm.vimusic.utils.toast
import it.vfsfitvnm.vimusic.models.Playlist
import it.vfsfitvnm.vimusic.models.SongPlaylistMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID


private fun logDebug(context: Context, message: String) {
    try {
        val logDir = File("/storage/emulated/0/ViMusic_logs")
        if (!logDir.exists()) logDir.mkdirs()

        val logFile = File(logDir, "ViMusic_log.txt")

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile.appendText("[$timestamp] $message\n")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private val executor = Executors.newCachedThreadPool()
private val coroutineScope = CoroutineScope(
    executor.asCoroutineDispatcher() +
        SupervisorJob() +
        CoroutineName("PrecacheService-Worker-Scope")
)

// While the class is not a singleton (lifecycle), there should only be one download state at a time
private val mutableDownloadState = MutableStateFlow(false)
val downloadState = mutableDownloadState.asStateFlow()

private const val DOWNLOAD_NOTIFICATION_UPDATE_INTERVAL = 1000L // default
private const val DOWNLOAD_WORK_NAME = "precacher-work"

@OptIn(UnstableApi::class)
class PrecacheService : DownloadService(
    /* foregroundNotificationId             = */ ServiceNotifications.download.notificationId!!,
    /* foregroundNotificationUpdateInterval = */ DOWNLOAD_NOTIFICATION_UPDATE_INTERVAL,
    /* channelId                            = */ ServiceNotifications.download.id,
    /* channelNameResourceId                = */ R.string.pre_cache,
    /* channelDescriptionResourceId         = */ 0
) {
    private val downloadQueue =
        Channel<DownloadManager>(onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val downloadNotificationHelper by lazy {
        DownloadNotificationHelper(
            /* context = */ this,
            /* channelId = */ ServiceNotifications.download.id
        )
    }
    
    private val notificationActionReceiver = NotificationActionReceiver()

    private val waiters = mutableListOf<() -> Unit>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service !is PlayerService.Binder) return
            bound = true
            binder = service
            waiters.forEach { it() }
            waiters.clear()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            binder = null
            waiters.forEach { it() }
            waiters.clear()
        }
    }

    inner class NotificationActionReceiver : ActionReceiver("it.vfsfitvnm.vimusic.precache") {
        val cancel by action { context, _ ->
            runCatching {
                sendPauseDownloads(
                    /* context         = */ context,
                    /* clazz           = */ PrecacheService::class.java,
                    /* foreground      = */ true
                )
            }.recoverCatching {
                sendPauseDownloads(
                    /* context         = */ context,
                    /* clazz           = */ PrecacheService::class.java,
                    /* foreground      = */ false
                )
            }
        }
    }

    @get:Synchronized
    @set:Synchronized
    private var bound = false
    private var binder: PlayerService.Binder? = null

    private var progressUpdaterJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        notificationActionReceiver.register()
        mutableDownloadState.update { false }
    }

    @kotlin.OptIn(FlowPreview::class)
    @OptIn(FlowPreview::class)
override fun getDownloadManager(): DownloadManager {
    logDebug(this, "getDownloadManager() dipanggil")

    runCatching {
        if (bound) {
            logDebug(this, "Service sebelumnya bound, unbind dulu")
            unbindService(serviceConnection)
        }
        bindService(intent<PlayerService>(), serviceConnection, BIND_AUTO_CREATE)
        logDebug(this, "Berhasil bind ke PlayerService")
    }.exceptionOrNull()?.let {
        logDebug(this, "Gagal bind service: ${it.stackTraceToString()}")
        toast(getString(R.string.error_pre_cache))
    }

    val cache = BlockingDeferredCache {
        suspendCoroutine { cont ->
            waiters += { cont.resume(Unit) }
        }
        logDebug(this@PrecacheService, "Menunggu binder PlayerService...")
        binder?.cache ?: run {
            logDebug(this@PrecacheService, "PlayerService binder NULL saat ambil cache")
            toast(getString(R.string.error_pre_cache))
            error("PlayerService failed to start, binder null")
        }
    }

    progressUpdaterJob?.cancel()
    progressUpdaterJob = coroutineScope.launch {
        logDebug(this@PrecacheService, "ProgressUpdaterJob mulai jalan")
        downloadQueue
            .receiveAsFlow()
            .debounce(100.milliseconds)
            .collect { downloadManager ->
                mutableDownloadState.update { !downloadManager.isIdle }
            }
    }

    logDebug(this, "DownloadManager berhasil dibuat")
    return DownloadManager(
        this,
        PlayerService.createDatabaseProvider(this),
        cache,
        PlayerService.createYouTubeDataSourceResolverFactory(this, cache, null),
        executor
    ).apply {
        maxParallelDownloads = 3
        minRetryCount = 1
        requirements = Requirements(Requirements.NETWORK)
        addListener(object : DownloadManager.Listener {
            override fun onIdle(downloadManager: DownloadManager) {
                logDebug(this@PrecacheService, "DownloadManager idle")
                mutableDownloadState.update { false }
            }
            override fun onDownloadChanged(
    downloadManager: DownloadManager,
    download: Download,
    finalException: Exception?
) {
    val id = download.request.id
    logDebug(this@PrecacheService, "onDownloadChanged: $id, state=${download.state}")

    if (download.state == Download.STATE_COMPLETED) {
        logDebug(this@PrecacheService, "✅ Download selesai untuk $id")

        // Jalankan kerja berat di coroutine (biar gak kena Room error)
        coroutineScope.launch {
            try {
                // Ambil instance cache (pakai dari PlayerService biar konsisten)
                val cacheInstance = PlayerService.cacheInstance ?: PlayerService.createCache(applicationContext)

                // Cek apakah file-nya beneran ada di cache
                val isCached = try {
                    val spans = cacheInstance.getCachedSpans(id)
                    spans != null && spans.isNotEmpty()
                } catch (e: Exception) {
                    logDebug(this@PrecacheService, "Error cek cache: ${e.stackTraceToString()}")
                    false
                }

                if (isCached) {
                    logDebug(this@PrecacheService, "🎵 Lagu $id ada di cache.")

                    // Update database dengan aman (Room gak boleh di main thread)
                    try {
                        val song = Database.instance.getSongById(id)
                        song?.let {
                            Database.instance.upsert(it)
                            logDebug(this@PrecacheService, "🗂️ DB updated: ${it.title} disimpan offline.")
                            // Cek apakah playlist "Offline Songs" sudah ada
var offlinePlaylist = Database.instance.getPlaylistByName("Offline")

// Kalau belum ada, buat baru
if (offlinePlaylist == null) {
    val playlistId = Database.instance.insert(
        Playlist(name = "Offline")
    )
    // Ambil kembali playlist dari database
    offlinePlaylist = Database.instance.getPlaylistByName("Offline")
}

offlinePlaylist?.let {
    Database.instance.insertSongPlaylistMaps(
        listOf(
            SongPlaylistMap(
                songId = song.id,
                playlistId = it.id, // 🔧 ubah ke String
                position = (Database.instance.getMaxPosition(it.id.toString()) ?: 0) + 1
            )
        )
    )
}

                        } ?: logDebug(this@PrecacheService, "⚠️ Song $id gak ketemu di DB.")
                    } catch (e: Exception) {
                        logDebug(this@PrecacheService, "DB error: ${e.stackTraceToString()}")
                    }
                } else {
                    logDebug(this@PrecacheService, "⚠️ Lagu $id gak ditemukan di cache folder.")
                }
            } catch (e: Exception) {
                logDebug(this@PrecacheService, "Error umum di coroutine: ${e.stackTraceToString()}")
            }
        }
    }

    if (download.state == Download.STATE_FAILED) {
        logDebug(this@PrecacheService, "❌ Download gagal: ${finalException?.stackTraceToString()}")
    }
}
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                logDebug(this@PrecacheService, "onDownloadRemoved: ${download.request.id}")
                downloadQueue.trySend(downloadManager)
            }
        })
    }
}

    override fun getScheduler() = WorkManagerScheduler(this, DOWNLOAD_WORK_NAME)

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ) = NotificationCompat
        .Builder(
            /* context = */ this,
            /* notification = */ downloadNotificationHelper.buildProgressNotification(
                /* context            = */ this,
                /* smallIcon          = */ R.drawable.download,
                /* contentIntent      = */ null,
                /* message            = */ null,
                /* downloads          = */ downloads,
                /* notMetRequirements = */ notMetRequirements
            )
        )
        .setChannelId(ServiceNotifications.download.id)
        .addAction(
            NotificationCompat.Action.Builder(
                /* icon = */ R.drawable.close,
                /* title = */ getString(R.string.cancel),
                /* intent = */ notificationActionReceiver.cancel.pendingIntent
            ).build()
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()

        runCatching {
            if (bound) unbindService(serviceConnection)
        }

        unregisterReceiver(notificationActionReceiver)
        mutableDownloadState.update { false }
    }
    
    companion object {
    @SuppressLint("UseKtx")
fun scheduleCache(context: Context, mediaItem: MediaItem) {
    if (mediaItem.isLocal) {
        logDebug(context, "Batal scheduleCache: mediaItem ${mediaItem.mediaId} adalah lokal")
        return
    }

    logDebug(context, "Mulai scheduleCache untuk ${mediaItem.mediaId}")

    val fallbackUri = ("https://youtube.com/watch?v=${mediaItem.mediaId}").toUri()

    // 🔹 Coba ambil cache dari PlayerService lewat binder
    val cache = runCatching {
        val appContext = context.applicationContext
        val playerServiceField = appContext
            .javaClass
            .classLoader
            ?.loadClass("it.vfsfitvnm.vimusic.service.PlayerService")
            ?.getDeclaredField("binder")
        playerServiceField?.isAccessible = true
        val binderInstance = playerServiceField?.get(null)
        binderInstance?.javaClass?.getMethod("getCache")?.invoke(binderInstance) as? Cache
    }.getOrNull()

    // 🔹 Buat resolverFactory hanya jika cache berhasil diambil
    val resolverFactory = cache?.let {
        runCatching {
            PlayerService.createYouTubeDataSourceResolverFactory(
                context = context,
                cache = it,
                chunkLength = null
            )
        }.getOrElse { err ->
            logDebug(context, "Gagal buat resolverFactory: ${err.stackTraceToString()}")
            null
        }
    }

    val resolvedUri = runCatching {
        if (resolverFactory != null) {
            val dataSpec = DataSpec.Builder()
                .setUri(fallbackUri)
                .setKey(mediaItem.mediaId)
                .build()
            val ds = resolverFactory.createDataSource()
            try {
                ds.open(dataSpec)
                ds.uri ?: fallbackUri
            } finally {
                runCatching { ds.close() }
            }
        } else fallbackUri
    }.getOrElse { ex ->
        logDebug(context, "Resolver gagal saat open: ${ex.stackTraceToString()}")
        fallbackUri
    }

    val downloadRequest = DownloadRequest.Builder(
        mediaItem.mediaId,
        resolvedUri
    )
        .setCustomCacheKey(mediaItem.mediaId)
        .setData(mediaItem.mediaId.encodeToByteArray())
        .build()

    try {
        transaction {
            runCatching {
                logDebug(context, "InsertPreserve mulai untuk ${mediaItem.mediaId}")
                Database.instance.insertPreserve(mediaItem)
                logDebug(context, "InsertPreserve sukses untuk ${mediaItem.mediaId}")
            }.onFailure {
                logDebug(context, "InsertPreserve gagal: ${it.stackTraceToString()}")
                return@transaction
            }

            coroutineScope.launch {
                logDebug(context, "Mulai download untuk ${mediaItem.mediaId} -> $resolvedUri")
                val result = context.download<PrecacheService>(downloadRequest)
                result.exceptionOrNull()?.let { err ->
                    logDebug(context, "Download error: ${err.stackTraceToString()}")
                    context.toast(context.getString(R.string.error_pre_cache))
                } ?: logDebug(context, "Download berhasil dimulai untuk ${mediaItem.mediaId}")
            }
        }
    } catch (e: Exception) {
        logDebug(context, "Exception di scheduleCache: ${e.stackTraceToString()}")
    }
}
    }
}
// =======================
//  BlockingDeferredCache
// =======================
@Suppress("TooManyFunctions")
@OptIn(UnstableApi::class)
class BlockingDeferredCache(private val cache: Deferred<Cache>) : Cache {
    constructor(init: suspend () -> Cache) : this(coroutineScope.async { init() })

    private val resolvedCache by lazy { runBlocking { cache.await() } }

    override fun getUid() = resolvedCache.uid
    override fun release() = resolvedCache.release()
    override fun addListener(key: String, listener: Cache.Listener) =
        resolvedCache.addListener(key, listener)

    override fun removeListener(key: String, listener: Cache.Listener) =
        resolvedCache.removeListener(key, listener)

    override fun getCachedSpans(key: String) = resolvedCache.getCachedSpans(key)
    override fun getKeys(): MutableSet<String> = resolvedCache.keys
    override fun getCacheSpace() = resolvedCache.cacheSpace
    override fun startReadWrite(key: String, position: Long, length: Long) =
        resolvedCache.startReadWrite(key, position, length)

    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long) =
        resolvedCache.startReadWriteNonBlocking(key, position, length)

    override fun startFile(key: String, position: Long, length: Long) =
        resolvedCache.startFile(key, position, length)

    override fun commitFile(file: File, length: Long) = resolvedCache.commitFile(file, length)
    override fun releaseHoleSpan(holeSpan: CacheSpan) = resolvedCache.releaseHoleSpan(holeSpan)
    override fun removeResource(key: String) = resolvedCache.removeResource(key)
    override fun removeSpan(span: CacheSpan) = resolvedCache.removeSpan(span)
    override fun isCached(key: String, position: Long, length: Long) =
        resolvedCache.isCached(key, position, length)

    override fun getCachedLength(key: String, position: Long, length: Long) =
        resolvedCache.getCachedLength(key, position, length)

    override fun getCachedBytes(key: String, position: Long, length: Long) =
        resolvedCache.getCachedBytes(key, position, length)

    override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) =
        resolvedCache.applyContentMetadataMutations(key, mutations)

    override fun getContentMetadata(key: String) = resolvedCache.getContentMetadata(key)
}
