package com.nasmusicdrop.app.data.queue

import android.content.Context
import android.util.Log
import com.nasmusicdrop.app.data.audio.AudioNormalizer
import com.nasmusicdrop.app.data.audio.Id3TagWriter
import com.nasmusicdrop.app.data.audio.YouTubeExtractor
import com.nasmusicdrop.app.data.metadata.ItunesService
import com.nasmusicdrop.app.data.metadata.LrclibService
import com.nasmusicdrop.app.data.model.QueueItem
import com.nasmusicdrop.app.data.model.QueueStatus
import com.nasmusicdrop.app.data.model.SongCandidate
import com.nasmusicdrop.app.data.model.UploadHistoryItem
import com.nasmusicdrop.app.data.nas.SmbNasService
import com.nasmusicdrop.app.data.repository.NasSettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton que gestiona la cola secuencial de descargas y subidas al NAS.
 * Totalmente thread-safe mediante MutableStateFlow.update {} y ejecución
 * secuencial garantizada sin condiciones de carrera.
 */
class DownloadQueueManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "DownloadQueueManager"

        @Volatile
        private var INSTANCE: DownloadQueueManager? = null

        fun getInstance(context: Context): DownloadQueueManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadQueueManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val repository = NasSettingsRepository.getInstance(appContext)
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _queueList = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueList: StateFlow<List<QueueItem>> = _queueList.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    private val queueMutex = Mutex()
    private val postProcessMutex = Mutex()
    private var isQueueWorkerRunning = false
    private var activePostProcessJob: Job? = null
    private val activeJobMap = ConcurrentHashMap<String, Job>()
    private val forceOverwriteItems = ConcurrentHashMap.newKeySet<String>()
    private val queueFile = File(appContext.filesDir, "queue_state.json")
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    init {
        com.nasmusicdrop.app.data.AppStrings.init(appContext)
        cleanOrphanCacheFiles()
        restoreQueueFromDisk()
        observeQueueForPersistence()
        registerWifiAutoResumeCallback()
    }

    private fun ensureActiveScope() {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            observeQueueForPersistence()
        }
    }

    fun shutdown() {
        try {
            networkCallback?.let { cb ->
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            }
        } catch (_: Exception) {}
        networkCallback = null
        activeJobMap.values.forEach { it.cancel() }
        activeJobMap.clear()
        activePostProcessJob?.cancel()
        activePostProcessJob = null
        scope.cancel()
    }

    private fun registerWifiAutoResumeCallback() {
        if (networkCallback != null) return
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    var resumed = false
                    _queueList.update { list ->
                        list.map { item ->
                            if (item.status == QueueStatus.WAITING_WIFI) {
                                resumed = true
                                item.copy(
                                    status = QueueStatus.QUEUED,
                                    progressPercent = 0,
                                    progressStatusText = "Wi‑Fi conectado · En cola..."
                                )
                            } else item
                        }
                    }
                    if (resumed) {
                        startQueueWorker()
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.d(TAG, "No se pudo registrar NetworkCallback Wi-Fi: ${e.message}")
        }
    }

    private fun observeQueueForPersistence() {
        scope.launch {
            var lastSignature = ""
            _queueList.collect { list ->
                val sig = list.joinToString("|") {
                    "${it.id}:${it.status}:${it.videoId}:${it.title}:${it.artist}:${it.album}:${it.errorMessage}:${it.lyrics?.hashCode()}:${it.isUserEdited}:${it.isLyricsUserEdited}:${it.startTimeSeconds}:${it.endTimeSeconds}"
                }
                if (sig != lastSignature) {
                    lastSignature = sig
                    saveQueueToDisk(list)
                }
            }
        }
    }

    private fun saveQueueToDisk(list: List<QueueItem>) {
        try {
            val arr = org.json.JSONArray()
            for (item in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("videoId", item.videoId)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("thumbnailUrl", item.thumbnailUrl)
                    put("status", item.status.name)
                    put("progressStatusText", item.progressStatusText)
                    if (item.errorMessage != null) put("errorMessage", item.errorMessage)
                    if (item.remotePath != null) put("remotePath", item.remotePath)
                    if (item.duplicatePath != null) put("duplicatePath", item.duplicatePath)
                    if (item.lyrics != null) put("lyrics", item.lyrics)
                    if (item.syncedLyrics != null) put("syncedLyrics", item.syncedLyrics)
                    put("isSyncedLyrics", item.isSyncedLyrics)
                    put("startTimeSeconds", item.startTimeSeconds)
                    put("endTimeSeconds", item.endTimeSeconds)
                    put("isUserEdited", item.isUserEdited)
                    put("isLyricsUserEdited", item.isLyricsUserEdited)
                    put("isExternalResolved", item.isExternalResolved)
                }
                arr.put(obj)
            }
            queueFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando cola en disco: ${e.message}")
        }
    }

    private fun restoreQueueFromDisk() {
        try {
            if (!queueFile.exists()) return
            val raw = queueFile.readText()
            if (raw.isBlank()) return
            val arr = org.json.JSONArray(raw)
            val restored = mutableListOf<QueueItem>()
            var hasQueued = false

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val savedStatus = try {
                    QueueStatus.valueOf(obj.optString("status", QueueStatus.QUEUED.name))
                } catch (_: Exception) {
                    QueueStatus.QUEUED
                }

                // Si la app se cerró mientras se procesaba, devolver a QUEUED para reanudarla
                val effectiveStatus = when (savedStatus) {
                    QueueStatus.ANALYZING,
                    QueueStatus.DOWNLOADING,
                    QueueStatus.NORMALIZING,
                    QueueStatus.UPLOADING -> QueueStatus.QUEUED
                    QueueStatus.WAITING_WIFI -> if (isWifiConnected(appContext)) QueueStatus.QUEUED else QueueStatus.WAITING_WIFI
                    else -> savedStatus
                }
                if (effectiveStatus == QueueStatus.QUEUED) hasQueued = true

                val statusText = when (effectiveStatus) {
                    QueueStatus.QUEUED -> "Reanudando en cola..."
                    QueueStatus.WAITING_WIFI -> appContext.getString(com.nasmusicdrop.app.R.string.queue_waiting_wifi)
                    else -> obj.optString("progressStatusText", "")
                }

                restored.add(
                    QueueItem(
                        id = obj.optString("id"),
                        url = obj.optString("url"),
                        videoId = obj.optString("videoId"),
                        title = obj.optString("title", "Cargando información..."),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        status = effectiveStatus,
                        progressPercent = if (effectiveStatus == QueueStatus.COMPLETED) 100 else 0,
                        progressStatusText = statusText,
                        errorMessage = obj.optString("errorMessage", "").takeIf { it.isNotBlank() },
                        remotePath = obj.optString("remotePath", "").takeIf { it.isNotBlank() },
                        duplicatePath = obj.optString("duplicatePath", "").takeIf { it.isNotBlank() },
                        lyrics = obj.optString("lyrics", "").takeIf { it.isNotBlank() },
                        syncedLyrics = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() },
                        isSyncedLyrics = obj.optBoolean("isSyncedLyrics", false),
                        startTimeSeconds = obj.optLong("startTimeSeconds", 0L),
                        endTimeSeconds = obj.optLong("endTimeSeconds", 0L),
                        isUserEdited = obj.optBoolean("isUserEdited", false),
                        isLyricsUserEdited = obj.optBoolean("isLyricsUserEdited", false),
                        isExternalResolved = obj.optBoolean("isExternalResolved", false)
                    )
                )
            }

            if (restored.isNotEmpty()) {
                _queueList.value = restored
                if (hasQueued) {
                    startQueueWorker()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restaurando cola desde disco: ${e.message}")
        }
    }

    private fun cleanOrphanCacheFiles() {
        try {
            val cache = appContext.cacheDir ?: return
            cache.listFiles()?.forEach { file ->
                if (file.name.startsWith("yt_raw_") ||
                    file.name.startsWith("raw_pcm_") ||
                    file.name.startsWith("norm_") ||
                    file.name.startsWith("tagged_")
                ) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun enqueue(
        rawUrl: String,
        startTimeSeconds: Long = 0L,
        endTimeSeconds: Long = 0L,
        initialTitle: String? = null,
        initialArtist: String? = null,
        initialAlbum: String? = null,
        initialThumbnailUrl: String? = null,
        isExternalResolved: Boolean = false,
        initialStatus: QueueStatus = QueueStatus.QUEUED,
        initialErrorMessage: String? = null
    ) {
        val trimmed = rawUrl.trim()
        val isSoundCloud = YouTubeExtractor.isSoundCloudUrl(trimmed)
        val extractedId = if (isSoundCloud) null else YouTubeExtractor.extractVideoId(trimmed)
        val videoId = when {
            isSoundCloud -> "sc_${trimmed.hashCode()}"
            extractedId != null -> extractedId
            initialStatus == QueueStatus.FAILED -> "unresolved_${java.util.UUID.randomUUID().toString().take(8)}"
            else -> null
        }
        if (videoId == null) {
            Log.w(TAG, "No se pudo extraer Video ID de: $rawUrl")
            return
        }
        val parsedStart = YouTubeExtractor.parseStartTimeSeconds(trimmed)
        val effectiveStart = if (startTimeSeconds > 0) startTimeSeconds else parsedStart
        val parsedEnd = YouTubeExtractor.parseEndTimeSeconds(trimmed)
        val effectiveEnd = if (endTimeSeconds > 0) endTimeSeconds else parsedEnd

        val defaultTitle = initialTitle?.takeIf { it.isNotBlank() } ?: "Cargando información..."
        val defaultArtist = initialArtist?.takeIf { it.isNotBlank() } ?: if (isSoundCloud) "SoundCloud" else "YouTube"
        val defaultAlbum = initialAlbum?.takeIf { it.isNotBlank() } ?: ""
        val defaultThumb = initialThumbnailUrl?.takeIf { it.isNotBlank() } ?: (if (extractedId != null) "https://img.youtube.com/vi/$extractedId/hqdefault.jpg" else "")

        // Actualización atómica de la lista para evitar condiciones de carrera
        var added = false
        var enqueuedItemId = ""
        _queueList.update { currentList ->
            val alreadyActive = currentList.any {
                it.videoId == videoId &&
                        it.status != QueueStatus.COMPLETED &&
                        it.status != QueueStatus.FAILED &&
                        it.status != QueueStatus.SKIPPED &&
                        it.status != QueueStatus.CANCELLED
            }
            if (alreadyActive) {
                currentList
            } else {
                added = true
                val newItem = QueueItem(
                    url = trimmed,
                    videoId = videoId,
                    title = defaultTitle,
                    artist = defaultArtist,
                    album = defaultAlbum,
                    thumbnailUrl = defaultThumb,
                    status = initialStatus,
                    progressPercent = 0,
                    progressStatusText = when (initialStatus) {
                        QueueStatus.FAILED -> initialErrorMessage ?: "Error"
                        QueueStatus.DUPLICATE_FOUND -> "Ya existe en tu biblioteca"
                        else -> "En cola de espera..."
                    },
                    errorMessage = initialErrorMessage,
                    startTimeSeconds = effectiveStart,
                    endTimeSeconds = effectiveEnd,
                    isUserEdited = isExternalResolved,
                    isExternalResolved = isExternalResolved
                )
                enqueuedItemId = newItem.id
                currentList + newItem
            }
        }

        if (added && initialStatus == QueueStatus.QUEUED) {
            Log.d(TAG, "Encolado nuevo vídeo: $videoId. Total en cola: ${_queueList.value.size}")

            // Si no se proporcionaron datos iniciales, resolverlos de forma inmediata en segundo plano
            if (initialTitle.isNullOrBlank() && !isExternalResolved) {
                scope.launch(Dispatchers.IO) {
                    val info = if (isSoundCloud) {
                        YouTubeExtractor.fetchSoundCloudInfo(trimmed)
                    } else {
                        YouTubeExtractor.fetchVideoInfo(videoId)
                    }
                    _queueList.update { list ->
                        list.map {
                            if (it.id == enqueuedItemId && !it.isUserEdited) {
                                it.copy(
                                    title = info.cleanTitle,
                                    artist = info.cleanArtist,
                                    thumbnailUrl = info.thumbnailUrl
                                )
                            } else it
                        }
                    }
                }
            }

            startQueueWorker()
        }
    }

    fun pauseQueue() {
        _isQueuePaused.value = true
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
        if (_queueList.value.any { it.status == QueueStatus.QUEUED }) {
            startQueueWorker()
        }
    }

    private fun startQueueWorker() {
        if (_isQueuePaused.value) return
        ensureActiveScope()
        registerWifiAutoResumeCallback()
        DownloadForegroundService.startService(appContext)

        scope.launch {
            val shouldStart = queueMutex.withLock {
                if (isQueueWorkerRunning || _isQueuePaused.value) {
                    false
                } else {
                    isQueueWorkerRunning = true
                    true
                }
            }

            if (!shouldStart) return@launch

            try {
                val downloadSemaphore = Semaphore(2)
                val dispatchedIds = ConcurrentHashMap.newKeySet<String>()
                coroutineScope {
                    while (true) {
                        if (_isQueuePaused.value) break
                        val nextItem = _queueList.value.firstOrNull {
                            it.status == QueueStatus.QUEUED && !dispatchedIds.contains(it.id)
                        } ?: break
                        dispatchedIds.add(nextItem.id)
                        downloadSemaphore.acquire()
                        launch {
                            try {
                                processQueueItem(nextItem)
                            } finally {
                                dispatchedIds.remove(nextItem.id)
                                downloadSemaphore.release()
                            }
                        }
                    }
                }
                activePostProcessJob?.join()
            } finally {
                queueMutex.withLock {
                    isQueueWorkerRunning = false
                }
                // Si entró una nueva canción justo antes de cerrar el bucle, relanzar (si no está en pausa)
                if (!_isQueuePaused.value && _queueList.value.any { it.status == QueueStatus.QUEUED }) {
                    startQueueWorker()
                }
            }
        }
    }

    private suspend fun processQueueItem(item: QueueItem) {
        val itemId = item.id
        var tempRawFile: File? = null
        var normalizedFile: File? = null
        var taggedFile: File? = null

        // Job hijo aislado para permitir cancelar esta descarga individual sin matar el worker de la cola
        val itemJob = Job()
        activeJobMap[itemId] = itemJob

        try {
            withContext(itemJob) {
                // 1. Análisis de vídeo y metadatos
                val finalTitle: String
                val finalArtist: String
                val finalAlbum: String
                val thumbUrl: String
                var itunesMatches: List<SongCandidate> = emptyList()
                var bestCandidate: SongCandidate? = null

                val isSoundCloudUrl = YouTubeExtractor.isSoundCloudUrl(item.url)

                val config = repository.getConfig()
                val isLocal = config.destinationMode == "LOCAL"
                val shouldCheckDuplicates = config.checkDuplicates && !forceOverwriteItems.contains(itemId)
                var preCheckDuplicateDeferred: kotlinx.coroutines.Deferred<String?>? = null

                if (item.isExternalResolved) {
                    updateItemStatus(itemId, QueueStatus.ANALYZING, 5, "Buscando metadatos completos de ${item.title}...")
                    // Paralelizar comprobación de duplicados en NAS/Local mientras consulta iTunes
                    if (shouldCheckDuplicates && item.title.isNotBlank() && item.artist.isNotBlank()) {
                        preCheckDuplicateDeferred = async(Dispatchers.IO) {
                            if (isLocal) {
                                com.nasmusicdrop.app.data.local.LocalStorageService.checkTrackExistsLocally(
                                    appContext, item.artist, item.title, item.album, config.organizeByAlbum, config.folderStructure
                                )
                            } else {
                                SmbNasService.checkTrackExistsOnNas(
                                    config, item.artist, item.title,
                                    if (config.folderStructure == "ARTIST_ALBUM" || config.organizeByAlbum) item.album else null
                                )
                            }
                        }
                    }
                    itunesMatches = ItunesService.searchCandidates(item.title, item.artist)
                    bestCandidate = itunesMatches.firstOrNull()
                    finalTitle = item.title.ifBlank { bestCandidate?.title ?: item.title }
                    finalArtist = item.artist.ifBlank { bestCandidate?.artist ?: item.artist }
                    finalAlbum = item.album.ifBlank { bestCandidate?.album ?: finalTitle }
                    thumbUrl = item.thumbnailUrl.ifBlank { bestCandidate?.artworkUrl600 ?: "" }
                } else if (isSoundCloudUrl) {
                    updateItemStatus(itemId, QueueStatus.ANALYZING, 5, "Analizando pista de SoundCloud...")
                    val scInfo = YouTubeExtractor.fetchSoundCloudInfo(item.url)
                    itunesMatches = ItunesService.searchCandidates(scInfo.cleanTitle, scInfo.cleanArtist)
                    // Para SoundCloud priorizamos el match exacto de iTunes solo si coincide con artista y título; si no, conservamos los de SoundCloud
                    val exactMatch = itunesMatches.firstOrNull {
                        it.artist.contains(scInfo.cleanArtist, ignoreCase = true) &&
                            it.title.contains(scInfo.cleanTitle, ignoreCase = true)
                    }
                    bestCandidate = exactMatch
                    finalTitle = exactMatch?.title ?: scInfo.cleanTitle
                    finalArtist = exactMatch?.artist ?: scInfo.cleanArtist
                    finalAlbum = exactMatch?.album ?: finalTitle
                    thumbUrl = exactMatch?.artworkUrl600?.ifBlank { scInfo.thumbnailUrl } ?: scInfo.thumbnailUrl
                } else {
                    updateItemStatus(itemId, QueueStatus.ANALYZING, 5, appContext.getString(com.nasmusicdrop.app.R.string.queue_analyzing_info))
                    val videoInfo = YouTubeExtractor.fetchVideoInfo(item.videoId)
                    itunesMatches = ItunesService.searchCandidates(videoInfo.cleanTitle, videoInfo.cleanArtist)
                    bestCandidate = if (itunesMatches.isNotEmpty()) itunesMatches[0] else null

                    finalTitle = bestCandidate?.title ?: videoInfo.cleanTitle
                    finalArtist = bestCandidate?.artist ?: videoInfo.cleanArtist
                    finalAlbum = bestCandidate?.album ?: finalTitle
                    thumbUrl = bestCandidate?.artworkUrl600?.ifBlank { videoInfo.thumbnailUrl } ?: videoInfo.thumbnailUrl
                }

                // Lanzar búsqueda de letras y carátula en paralelo mientras se comprueban duplicados y se descarga el audio
                val lyricsDeferred = async(Dispatchers.IO) { LrclibService.fetchLyrics(finalTitle, finalArtist) }
                val coverDeferred = async(Dispatchers.IO) {
                    if (thumbUrl.isNotBlank()) Id3TagWriter.downloadAndResizeArtwork(thumbUrl, 600) else null
                }

                val currentItemBeforeDetails = _queueList.value.firstOrNull { it.id == itemId }
                if (currentItemBeforeDetails?.isUserEdited == true) {
                    _queueList.update { list ->
                        list.map {
                            if (it.id == itemId) it.copy(allCandidates = itunesMatches) else it
                        }
                    }
                } else {
                    updateItemDetails(
                        itemId = itemId,
                        title = finalTitle,
                        artist = finalArtist,
                        album = finalAlbum,
                        thumbnailUrl = thumbUrl,
                        candidate = bestCandidate,
                        allCandidates = itunesMatches,
                        lyrics = currentItemBeforeDetails?.lyrics,
                        syncedLyrics = currentItemBeforeDetails?.syncedLyrics,
                        isSyncedLyrics = currentItemBeforeDetails?.isSyncedLyrics == true
                    )
                }

                // Comprobar si ya existe en la biblioteca antes de descargar
                if (shouldCheckDuplicates) {
                    updateItemStatus(itemId, QueueStatus.ANALYZING, 12, "Comprobando duplicados en la biblioteca...")
                    val currentBeforeCheck = _queueList.value.firstOrNull { it.id == itemId }
                    val artistToCheck = currentBeforeCheck?.artist ?: finalArtist
                    val titleToCheck = currentBeforeCheck?.title ?: finalTitle
                    val albumToCheck = currentBeforeCheck?.album ?: finalAlbum

                    val existingPath = if (preCheckDuplicateDeferred != null) {
                        preCheckDuplicateDeferred.await()
                    } else if (isLocal) {
                        com.nasmusicdrop.app.data.local.LocalStorageService.checkTrackExistsLocally(
                            appContext, artistToCheck, titleToCheck, albumToCheck, config.organizeByAlbum, config.folderStructure
                        )
                    } else {
                        SmbNasService.checkTrackExistsOnNas(
                            config, artistToCheck, titleToCheck, if (config.folderStructure == "ARTIST_ALBUM" || config.organizeByAlbum) albumToCheck else null
                        )
                    }

                    if (existingPath != null) {
                        _queueList.update { list ->
                            list.map {
                                if (it.id == itemId) {
                                    it.copy(
                                        status = QueueStatus.DUPLICATE_FOUND,
                                        duplicatePath = existingPath,
                                        progressPercent = 0,
                                        progressStatusText = appContext.getString(com.nasmusicdrop.app.R.string.queue_already_exists)
                                    )
                                } else it
                            }
                        }
                        return@withContext
                    }
                }

                // Comprobar restricción de Solo Wi-Fi antes de descargar audio
                if (config.wifiOnly && !isWifiConnected(appContext)) {
                    updateItemStatus(
                        itemId,
                        QueueStatus.WAITING_WIFI,
                        0,
                        appContext.getString(com.nasmusicdrop.app.R.string.queue_waiting_wifi_auto)
                    )
                    return@withContext
                }

                // 2. Descarga del stream de audio original con NewPipeExtractor
                updateItemStatus(
                    itemId,
                    QueueStatus.DOWNLOADING,
                    15,
                    if (isSoundCloudUrl) "Descargando audio directo de SoundCloud..." else "Descargando audio de YouTube..."
                )
                val downloadRes = if (isSoundCloudUrl) {
                    YouTubeExtractor.downloadSoundCloudAudio(appContext, item.url) { pct, msg ->
                        updateItemProgress(itemId, pct, msg)
                    }
                } else {
                    YouTubeExtractor.downloadAudioStream(appContext, item.videoId) { pct, msg ->
                        updateItemProgress(itemId, pct, msg)
                    }
                }

                if (!downloadRes.success || downloadRes.tempAudioFile == null) {
                    markItemFailed(itemId, downloadRes.error ?: "Error al descargar stream de audio.")
                    return@withContext
                }

                tempRawFile = downloadRes.tempAudioFile

                // Recoger letras y carátula que se estaban descargando en paralelo
                val lyricsRes = try { lyricsDeferred.await() } catch (_: Exception) { LrclibService.LyricsResult(lyrics = null, syncedLyrics = null, isSynced = false) }
                val preloadedCoverBytes = try { coverDeferred.await() } catch (_: Exception) { null }

                _queueList.update { list ->
                    list.map {
                        if (it.id == itemId && !it.isLyricsUserEdited) {
                            it.copy(
                                lyrics = it.lyrics ?: lyricsRes.lyrics,
                                syncedLyrics = it.syncedLyrics ?: lyricsRes.syncedLyrics,
                                isSyncedLyrics = it.isSyncedLyrics || lyricsRes.isSynced
                            )
                        } else it
                    }
                }

                // Esperar a que termine el post-procesado anterior (pipeline de 2 etapas acotado, thread-safe con descargas concurrentes)
                postProcessMutex.withLock {
                    activePostProcessJob?.join()

                    val rawFileForPost = tempRawFile
                        ?: throw IllegalStateException("Archivo temporal de audio no disponible")
                    tempRawFile = null // La responsabilidad de limpiar pasa al job de post-procesado

                    val postJob = Job(scope.coroutineContext[Job])
                    activeJobMap[itemId] = postJob
                    activePostProcessJob = scope.launch(postJob) {
                    var normOut: File? = null
                    var tagOut: File? = null
                    try {
                        // 3. Normalización EBU R128 (-14.0 LUFS) y codificación MP3 nativa
                        val currentItemForTime = _queueList.value.firstOrNull { it.id == itemId }
                        val startSec = currentItemForTime?.startTimeSeconds ?: 0L
                        val endSec = currentItemForTime?.endTimeSeconds ?: 0L

                        val statusNormMsg = when {
                            startSec > 0 && endSec > 0 ->
                                "Recortando (${YouTubeExtractor.formatTimestamp(startSec)} - ${YouTubeExtractor.formatTimestamp(endSec)}) y normalizando..."
                            startSec > 0 ->
                                "Recortando desde ${YouTubeExtractor.formatTimestamp(startSec)} y normalizando EBU R128 (-14 LUFS)..."
                            endSec > 0 ->
                                "Recortando hasta ${YouTubeExtractor.formatTimestamp(endSec)} y normalizando..."
                            else ->
                                "Normalizando volumen EBU R128 (-14 LUFS) a MP3 ${config.bitrateKbps}k..."
                        }
                        updateItemStatus(itemId, QueueStatus.NORMALIZING, 75, statusNormMsg)

                        val normRes = AudioNormalizer.processAndNormalize(
                            appContext,
                            rawFileForPost,
                            startTimeSeconds = startSec,
                            endTimeSeconds = endSec,
                            normalize = config.normalizeVolume,
                            targetLufs = config.targetLufs,
                            bitrateKbps = config.bitrateKbps
                        ) { pct, msg ->
                            updateItemProgress(itemId, pct, msg)
                        }

                        if (!normRes.success || normRes.outputFile == null) {
                            markItemFailed(itemId, normRes.error ?: appContext.getString(com.nasmusicdrop.app.R.string.queue_norm_error))
                            return@launch
                        }

                        normOut = normRes.outputFile

                        // 4. Incrustar etiquetas ID3v2.3 y carátula estándar JPEG 600x600
                        updateItemStatus(itemId, QueueStatus.UPLOADING, 90, appContext.getString(com.nasmusicdrop.app.R.string.queue_tagging))
                        val currentQueueItem = _queueList.value.firstOrNull { it.id == itemId }

                        val finalTitleToUse = currentQueueItem?.title?.ifBlank { finalTitle } ?: finalTitle
                        val finalArtistToUse = currentQueueItem?.artist?.ifBlank { finalArtist } ?: finalArtist
                        val finalAlbumToUse = currentQueueItem?.album?.ifBlank { finalTitleToUse } ?: finalTitleToUse
                        val currentCandidate = currentQueueItem?.candidate ?: bestCandidate
                        val artUrlToUse = currentQueueItem?.thumbnailUrl?.ifBlank { currentCandidate?.artworkUrl600 ?: thumbUrl } ?: thumbUrl
                        val lyricsToUse = if (currentQueueItem?.isLyricsUserEdited == true) {
                            currentQueueItem.lyrics
                        } else {
                            currentQueueItem?.lyrics ?: lyricsRes.lyrics
                        }

                        val coverBytes = if (artUrlToUse == thumbUrl && preloadedCoverBytes != null) {
                            preloadedCoverBytes
                        } else if (artUrlToUse.isNotBlank()) {
                            Id3TagWriter.downloadAndResizeArtwork(artUrlToUse, 600)
                        } else null

                        tagOut = Id3TagWriter.applyId3v23Tags(
                            normOut,
                            Id3TagWriter.TagData(
                                title = finalTitleToUse,
                                artist = finalArtistToUse,
                                album = finalAlbumToUse,
                                year = currentCandidate?.year ?: "",
                                trackNumber = currentCandidate?.trackNumber ?: 1,
                                genre = currentCandidate?.genre ?: "",
                                artworkUrl = artUrlToUse,
                                artworkBytes = coverBytes,
                                lyrics = lyricsToUse
                            )
                        )

                        // 5. Guardar en destino: Almacenamiento Local o SMB NAS
                        val finalFileToStore = tagOut ?: normOut
                        val saveSuccess: Boolean
                        val finalStoragePath: String
                        val finalStorageSizeBytes: Long
                        val saveErrorMsg: String?

                        if (isLocal) {
                            updateItemStatus(itemId, QueueStatus.UPLOADING, 94, "Guardando en el dispositivo...")
                            val localResult = com.nasmusicdrop.app.data.local.LocalStorageService.saveTrackToDevice(
                                context = appContext,
                                localFile = finalFileToStore,
                                artistName = finalArtistToUse,
                                trackTitle = finalTitleToUse,
                                albumName = finalAlbumToUse,
                                organizeByAlbum = config.organizeByAlbum,
                                coverBytes = coverBytes,
                                saveFolderCoverJpg = config.saveFolderCoverJpg,
                                fileNameFormat = config.fileNameFormat,
                                folderStructure = config.folderStructure
                            ) { pct, msg ->
                                updateItemProgress(itemId, pct, msg)
                            }
                            saveSuccess = localResult.success
                            finalStoragePath = localResult.localDisplayPath
                            finalStorageSizeBytes = localResult.fileSizeBytes
                            saveErrorMsg = localResult.errorMessage
                        } else {
                            updateItemStatus(itemId, QueueStatus.UPLOADING, 94, "Subiendo por SMB a ${config.shareName}/${config.baseAudioPath}...")
                            val uploadResult = SmbNasService.uploadTrack(
                                config = config,
                                localFile = finalFileToStore,
                                artistName = finalArtistToUse,
                                songTitle = finalTitleToUse,
                                albumName = finalAlbumToUse,
                                artworkBytes = coverBytes
                            ) { pct, msg ->
                                updateItemProgress(itemId, pct, msg)
                            }
                            saveSuccess = uploadResult.success
                            finalStoragePath = uploadResult.remotePath
                            finalStorageSizeBytes = uploadResult.fileSizeBytes
                            saveErrorMsg = uploadResult.error
                        }

                        if (saveSuccess) {
                            repository.addHistoryItem(
                                UploadHistoryItem(
                                    title = finalTitleToUse,
                                    artist = finalArtistToUse,
                                    album = finalAlbumToUse,
                                    year = currentCandidate?.year ?: "",
                                    genre = currentCandidate?.genre ?: "",
                                    trackNumber = currentCandidate?.trackNumber ?: 1,
                                    artworkUrl = artUrlToUse,
                                    remotePath = finalStoragePath,
                                    fileSizeBytes = finalStorageSizeBytes,
                                    status = "SUCCESS",
                                    bitrateKbps = normRes.bitrateKbps,
                                    originalLufs = normRes.originalLufs,
                                    targetLufs = normRes.targetLufs,
                                    appliedGainDb = normRes.measuredGainDb,
                                    hasLyrics = !lyricsToUse.isNullOrBlank(),
                                    coverResolution = if (coverBytes != null) "600x600 px (JPEG)" else appContext.getString(com.nasmusicdrop.app.R.string.queue_cover_yt),
                                    lyrics = lyricsToUse
                                )
                            )

                            _queueList.update { list ->
                                if (repository.getConfig().autoClearCompleted) {
                                    list.filterNot { it.id == itemId }
                                } else {
                                    list.map {
                                        if (it.id == itemId) {
                                            it.copy(
                                                status = QueueStatus.COMPLETED,
                                                progressPercent = 100,
                                                progressStatusText = "✓ Guardado en: $finalStoragePath",
                                                remotePath = finalStoragePath,
                                                title = finalTitleToUse,
                                                artist = finalArtistToUse,
                                                album = finalAlbumToUse,
                                                thumbnailUrl = artUrlToUse
                                            )
                                        } else it
                                    }
                                }
                            }
                        } else {
                            markItemFailed(itemId, saveErrorMsg ?: "Error al guardar el archivo.")
                        }
                    } catch (e: CancellationException) {
                        updateItemStatus(itemId, QueueStatus.CANCELLED, 0, "Descarga cancelada por el usuario")
                    } catch (e: Exception) {
                        markItemFailed(itemId, formatGeneralError(e))
                    } finally {
                        activeJobMap.remove(itemId)
                        try { rawFileForPost.delete() } catch (_: Exception) {}
                        try { normOut?.delete() } catch (_: Exception) {}
                        try { tagOut?.delete() } catch (_: Exception) {}
                    }
                }
                }
            }

        } catch (e: CancellationException) {
            updateItemStatus(itemId, QueueStatus.CANCELLED, 0, "Descarga cancelada por el usuario")
        } catch (e: Exception) {
            markItemFailed(itemId, formatGeneralError(e))
        } finally {
            if (activeJobMap[itemId] === itemJob) {
                activeJobMap.remove(itemId)
            }
            try { tempRawFile?.delete() } catch (_: Exception) {}
            try { normalizedFile?.delete() } catch (_: Exception) {}
            try { taggedFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun updateItemStatus(itemId: String, status: QueueStatus, percent: Int, text: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(status = status, progressPercent = percent, progressStatusText = text)
                } else it
            }
        }
    }

    private fun updateItemProgress(itemId: String, percent: Int, text: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(progressPercent = percent, progressStatusText = text)
                } else it
            }
        }
    }

    private fun updateItemDetails(
        itemId: String,
        title: String,
        artist: String,
        album: String,
        thumbnailUrl: String,
        candidate: com.nasmusicdrop.app.data.model.SongCandidate?,
        allCandidates: List<com.nasmusicdrop.app.data.model.SongCandidate> = emptyList(),
        lyrics: String? = null,
        syncedLyrics: String? = null,
        isSyncedLyrics: Boolean = false
    ) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        title = title,
                        artist = artist,
                        album = album,
                        thumbnailUrl = thumbnailUrl,
                        candidate = candidate,
                        allCandidates = allCandidates,
                        lyrics = lyrics,
                        syncedLyrics = syncedLyrics,
                        isSyncedLyrics = isSyncedLyrics
                    )
                } else it
            }
        }
    }

    fun selectCandidate(itemId: String, candidate: com.nasmusicdrop.app.data.model.SongCandidate) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        title = candidate.title,
                        artist = candidate.artist,
                        album = candidate.album,
                        thumbnailUrl = candidate.artworkUrl600.ifBlank { it.thumbnailUrl },
                        candidate = candidate,
                        isUserEdited = true
                    )
                } else it
            }
        }
        scope.launch {
            val newLyrics = LrclibService.fetchLyrics(candidate.title, candidate.artist)
            _queueList.update { list ->
                list.map {
                    if (it.id == itemId) {
                        it.copy(
                            lyrics = newLyrics.lyrics ?: it.lyrics,
                            syncedLyrics = newLyrics.syncedLyrics ?: it.syncedLyrics,
                            isSyncedLyrics = newLyrics.isSynced
                        )
                    } else it
                }
            }
        }
    }

    fun updateItemMetadata(
        itemId: String,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        startTimeSeconds: Long? = null,
        endTimeSeconds: Long? = null
    ) {
        val cleanT = YouTubeExtractor.cleanTrackTitle(newTitle, newArtist)
        val cleanA = YouTubeExtractor.cleanArtistName(newArtist)
        val cleanAl = newAlbum.trim().ifBlank { cleanT }

        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        title = cleanT,
                        artist = cleanA,
                        album = cleanAl,
                        startTimeSeconds = startTimeSeconds ?: it.startTimeSeconds,
                        endTimeSeconds = endTimeSeconds ?: it.endTimeSeconds,
                        isUserEdited = true
                    )
                } else it
            }
        }

        // Buscar nueva carátula y letras para este nombre corregido
        scope.launch {
            val matches = ItunesService.searchCandidates(cleanT, cleanA)
            val best = matches.firstOrNull()
            val newLyrics = LrclibService.fetchLyrics(cleanT, cleanA)
            _queueList.update { list ->
                list.map {
                    if (it.id == itemId) {
                        it.copy(
                            thumbnailUrl = best?.artworkUrl600?.ifBlank { it.thumbnailUrl } ?: it.thumbnailUrl,
                            candidate = best ?: it.candidate,
                            allCandidates = matches,
                            lyrics = if (it.isLyricsUserEdited) it.lyrics else newLyrics.lyrics,
                            syncedLyrics = if (it.isLyricsUserEdited) it.syncedLyrics else newLyrics.syncedLyrics,
                            isSyncedLyrics = if (it.isLyricsUserEdited) it.isSyncedLyrics else newLyrics.isSynced
                        )
                    } else it
                }
            }
        }
    }

    fun updateItemLyrics(itemId: String, newLyrics: String?, newSyncedLyrics: String? = null) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    val cleanLyrics = newLyrics?.takeIf { l -> l.isNotBlank() }
                    it.copy(
                        lyrics = cleanLyrics,
                        syncedLyrics = if (cleanLyrics == null) null else (newSyncedLyrics ?: it.syncedLyrics),
                        isSyncedLyrics = if (cleanLyrics == null) false else (newSyncedLyrics != null || it.isSyncedLyrics),
                        isLyricsUserEdited = true,
                        isUserEdited = true
                    )
                } else it
            }
        }
    }

    private fun formatGeneralError(e: Throwable): String {
        val chain = buildList {
            var curr: Throwable? = e
            while (curr != null) {
                curr.message?.let { add(it) }
                add(curr.javaClass.simpleName)
                curr = curr.cause
            }
        }.joinToString(" ").lowercase()

        return when {
            chain.contains("connection reset") || chain.contains("socketexception") || chain.contains("broken pipe") ->
                appContext.getString(com.nasmusicdrop.app.R.string.queue_conn_interrupted)
            chain.contains("timeout") || chain.contains("timed out") ->
                appContext.getString(com.nasmusicdrop.app.R.string.queue_timeout)
            chain.contains("no space left") || chain.contains("disk full") ->
                "Espacio insuficiente en el almacenamiento del dispositivo o del NAS."
            else ->
                e.localizedMessage?.lines()?.firstOrNull()?.take(120) ?: "Error inesperado en el procesamiento."
        }
    }

    private fun markItemFailed(itemId: String, error: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        status = QueueStatus.FAILED,
                        progressPercent = 0,
                        progressStatusText = "Error",
                        errorMessage = error
                    )
                } else it
            }
        }
    }

    fun retry(itemId: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        status = QueueStatus.QUEUED,
                        progressPercent = 0,
                        progressStatusText = "En cola de espera...",
                        errorMessage = null
                    )
                } else it
            }
        }
        startQueueWorker()
    }

    fun retryAllFailed() {
        var anyRetried = false
        _queueList.update { list ->
            list.map {
                if (it.status == QueueStatus.FAILED && !it.videoId.startsWith("unresolved_")) {
                    anyRetried = true
                    it.copy(
                        status = QueueStatus.QUEUED,
                        progressPercent = 0,
                        progressStatusText = "En cola de espera...",
                        errorMessage = null
                    )
                } else it
            }
        }
        if (anyRetried) {
            startQueueWorker()
        }
    }

    fun resolveWithYouTubeVideo(itemId: String, newVideoId: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        url = "https://youtu.be/$newVideoId",
                        videoId = newVideoId,
                        status = QueueStatus.QUEUED,
                        progressPercent = 0,
                        progressStatusText = "En cola de espera...",
                        errorMessage = null,
                        isExternalResolved = true
                    )
                } else it
            }
        }
        startQueueWorker()
    }

    fun enqueuePlaylistItems(items: List<com.nasmusicdrop.app.data.model.PlaylistItem>) {
        for (item in items) {
            enqueue(
                rawUrl = "https://www.youtube.com/watch?v=${item.videoId}",
                initialTitle = item.title,
                initialArtist = item.uploader,
                initialThumbnailUrl = item.thumbnailUrl
            )
        }
    }

    fun cancel(itemId: String) {
        val runningJob = activeJobMap[itemId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancel()
        } else {
            updateItemStatus(itemId, QueueStatus.CANCELLED, 0, "Descarga cancelada")
        }
    }

    fun skipDuplicate(itemId: String) {
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        status = QueueStatus.SKIPPED,
                        progressStatusText = "Omitida (Ya estaba en el NAS)"
                    )
                } else it
            }
        }
        startQueueWorker()
    }

    fun overwriteDuplicate(itemId: String) {
        forceOverwriteItems.add(itemId)
        _queueList.update { list ->
            list.map {
                if (it.id == itemId) {
                    it.copy(
                        status = QueueStatus.QUEUED,
                        progressPercent = 0,
                        progressStatusText = "En cola para sobrescribir..."
                    )
                } else it
            }
        }
        startQueueWorker()
    }

    fun remove(itemId: String) {
        cancel(itemId)
        _queueList.update { list ->
            list.filter { it.id != itemId }
        }
    }

    fun clearCompleted() {
        _queueList.update { list ->
            list.filter { it.status != QueueStatus.COMPLETED && it.status != QueueStatus.SKIPPED }
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            true
        }
    }
}
