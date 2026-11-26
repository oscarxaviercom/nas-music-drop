package com.nasmusicdrop.app.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusicdrop.app.R
import com.nasmusicdrop.app.data.external.*
import com.nasmusicdrop.app.data.model.*
import com.nasmusicdrop.app.data.nas.SmbNasService
import com.nasmusicdrop.app.data.queue.DownloadQueueManager
import com.nasmusicdrop.app.data.repository.NasSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = NasSettingsRepository.getInstance(application)
    private val queueManager = DownloadQueueManager.getInstance(application)

    val nasConfig: StateFlow<NasConfig> = repository.configFlow
    val uploadHistory: StateFlow<List<UploadHistoryItem>> = repository.historyFlow
    val queueList: StateFlow<List<QueueItem>> = queueManager.queueList

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    fun selectTab(index: Int) {
        _selectedTabIndex.value = index
    }

    // Estado para listas de reproducción de YouTube
    private val _isAnalyzingPlaylist = MutableStateFlow(false)
    val isAnalyzingPlaylist: StateFlow<Boolean> = _isAnalyzingPlaylist.asStateFlow()

    private val _playlistDialogData = MutableStateFlow<PlaylistData?>(null)
    val playlistDialogData: StateFlow<PlaylistData?> = _playlistDialogData.asStateFlow()

    // Estado para plataformas externas (Spotify, etc.)
    private val _isResolvingExternal = MutableStateFlow(false)
    val isResolvingExternal: StateFlow<Boolean> = _isResolvingExternal.asStateFlow()

    private val _externalBatchProgress = MutableStateFlow<String?>(null)
    val externalBatchProgress: StateFlow<String?> = _externalBatchProgress.asStateFlow()

    private val _externalCollectionDialogData = MutableStateFlow<ExternalCollection?>(null)
    val externalCollectionDialogData: StateFlow<ExternalCollection?> = _externalCollectionDialogData.asStateFlow()

    private val _externalErrorMessage = MutableStateFlow<Pair<String, String>?>(null)
    val externalErrorMessage: StateFlow<Pair<String, String>?> = _externalErrorMessage.asStateFlow()

    private val _playlistSummaryBanner = MutableStateFlow<String?>(null)
    val playlistSummaryBanner: StateFlow<String?> = _playlistSummaryBanner.asStateFlow()

    private var lastExternalUrl: String? = null

    fun dismissExternalError() {
        _externalErrorMessage.value = null
    }

    fun retryLastExternalUrl() {
        val url = lastExternalUrl ?: return
        _externalErrorMessage.value = null
        handleExternalPlatformUrl(url)
    }

    fun dismissPlaylistSummary() {
        _playlistSummaryBanner.value = null
    }

    // Estado del buscador de YouTube
    val previewPlayer = com.nasmusicdrop.app.data.audio.AudioPreviewManager
    val isQueuePaused: StateFlow<Boolean> = queueManager.isQueuePaused

    init {
        previewPlayer.onTrackCompleted = {
            playNextHistoryItem(autoAdvance = true)
        }
    }

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun searchYouTube(query: String) {
        val trimmed = query.trim()
        _searchQuery.value = trimmed
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            val results = com.nasmusicdrop.app.data.audio.YouTubeExtractor.searchYouTube(trimmed)
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        previewPlayer.stopPreview()
    }

    // Test de conexión NAS
    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<ConnectionTestResult?>(null)
    val connectionTestResult: StateFlow<ConnectionTestResult?> = _connectionTestResult.asStateFlow()

    fun handleInputUrl(rawText: String) {
        val trimmed = rawText.trim()

        // 1. Comprobar si es un enlace de plataforma externa (Spotify, Apple Music, Deezer, Amazon Music)
        val externalService = ExternalPlatformManager.findService(trimmed)
        if (externalService != null) {
            handleExternalPlatformUrl(trimmed)
            return
        }

        // 2. Comprobar si es un enlace de SoundCloud (descarga directa)
        if (com.nasmusicdrop.app.data.audio.YouTubeExtractor.isSoundCloudUrl(trimmed)) {
            viewModelScope.launch {
                _isAnalyzingPlaylist.value = true
                val resolved = com.nasmusicdrop.app.data.audio.YouTubeExtractor.resolveSoundCloudUrl(trimmed)
                if (resolved.contains("/sets/")) {
                    val data = com.nasmusicdrop.app.data.audio.YouTubeExtractor.fetchSoundCloudPlaylist(resolved)
                    _isAnalyzingPlaylist.value = false
                    if (data != null && data.items.isNotEmpty()) {
                        val enriched = enrichPlaylistDuplicateStatus(data)
                        _isAnalyzingPlaylist.value = false
                        _playlistDialogData.value = enriched
                        return@launch
                    }
                    _isAnalyzingPlaylist.value = false
                } else {
                    _isAnalyzingPlaylist.value = false
                }
                enqueueUrl(resolved)
            }
            return
        }

        // 2. Comprobar si es una playlist de YouTube
        val playlistId = com.nasmusicdrop.app.data.audio.YouTubeExtractor.extractPlaylistId(trimmed)
        if (playlistId != null) {
            viewModelScope.launch {
                _isAnalyzingPlaylist.value = true
                val data = com.nasmusicdrop.app.data.audio.YouTubeExtractor.fetchPlaylist(playlistId)
                if (data != null && data.items.isNotEmpty()) {
                    val enriched = enrichPlaylistDuplicateStatus(data)
                    _isAnalyzingPlaylist.value = false
                    _playlistDialogData.value = enriched
                } else {
                    _isAnalyzingPlaylist.value = false
                    enqueueUrl(trimmed)
                }
            }
        } else {
            enqueueUrl(trimmed)
        }
    }

    private suspend fun enrichPlaylistDuplicateStatus(data: PlaylistData): PlaylistData {
        val config = repository.getConfig()
        if (!config.checkDuplicates || data.items.isEmpty()) return data
        return try {
            val pseudoTracks = data.items.map { item ->
                val cleanArtist = com.nasmusicdrop.app.data.audio.YouTubeExtractor.cleanArtistName(item.uploader)
                val cleanTitle = com.nasmusicdrop.app.data.audio.YouTubeExtractor.cleanTrackTitle(item.title, cleanArtist)
                ExternalTrack(
                    title = cleanTitle,
                    artist = cleanArtist,
                    platform = "YouTube"
                )
            }
            val checked = ExternalPlatformManager.checkTracksAlreadyInLibrary(config, getApplication(), pseudoTracks)
            val updatedItems = data.items.mapIndexed { idx, item ->
                item.copy(isAlreadyInLibrary = checked.getOrNull(idx)?.isAlreadyInLibrary == true)
            }
            data.copy(items = updatedItems)
        } catch (_: Exception) {
            data
        }
    }

    private fun handleExternalPlatformUrl(url: String) {
        lastExternalUrl = url
        val app = getApplication<Application>()
        viewModelScope.launch {
            _isResolvingExternal.value = true
            _externalBatchProgress.value = app.getString(R.string.vm_connecting_platform)

            val result = ExternalPlatformManager.resolve(url)

            when (result) {
                is ExternalResult.Track -> {
                    _externalBatchProgress.value = app.getString(R.string.vm_checking_duplicates)
                    resolveAndEnqueueSingleExternalTrack(result.track)
                    _isResolvingExternal.value = false
                    _externalBatchProgress.value = null
                }
                is ExternalResult.Collection -> {
                    _externalBatchProgress.value = app.getString(R.string.vm_checking_library, result.collection.tracks.size)
                    val config = repository.getConfig()
                    val processedTracks = ExternalPlatformManager.checkTracksAlreadyInLibrary(
                        config = config,
                        context = app,
                        tracks = result.collection.tracks
                    )
                    _isResolvingExternal.value = false
                    _externalBatchProgress.value = null
                    _externalCollectionDialogData.value = result.collection.copy(tracks = processedTracks)
                }
                is ExternalResult.Error -> {
                    _isResolvingExternal.value = false
                    _externalBatchProgress.value = null
                    _externalErrorMessage.value = Pair(app.getString(R.string.vm_link_load_error), result.message)
                }
            }
        }
    }

    private suspend fun resolveAndEnqueueSingleExternalTrack(track: ExternalTrack) {
        val app = getApplication<Application>()
        val config = repository.getConfig()

        // Comprobación anticipada en la biblioteca
        val isDuplicate = ExternalPlatformManager.checkTrackAlreadyInLibrary(config, app, track)
        if (isDuplicate) {
            queueManager.enqueue(
                rawUrl = track.originalUrl.ifBlank { "https://music.drop" },
                initialTitle = track.title,
                initialArtist = track.artist,
                initialAlbum = track.album,
                initialThumbnailUrl = track.coverUrl,
                isExternalResolved = true,
                initialStatus = QueueStatus.DUPLICATE_FOUND
            )
            return
        }

        // Buscar coincidencia en YouTube
        _externalBatchProgress.value = app.getString(R.string.vm_searching_yt, track.artist, track.title)
        val match = ExternalPlatformManager.searchBestYouTubeMatch(track, config.durationToleranceSeconds.toLong())

        when (match) {
            is YouTubeMatchResult.Success -> {
                queueManager.enqueue(
                    rawUrl = "https://youtu.be/${match.videoId}",
                    initialTitle = track.title,
                    initialArtist = track.artist,
                    initialAlbum = track.album,
                    initialThumbnailUrl = track.coverUrl.ifBlank { match.matchedItem.thumbnailUrl },
                    isExternalResolved = true
                )
            }
            is YouTubeMatchResult.Failure -> {
                queueManager.enqueue(
                    rawUrl = track.originalUrl.ifBlank { "https://music.drop" },
                    initialTitle = track.title,
                    initialArtist = track.artist,
                    initialAlbum = track.album,
                    initialThumbnailUrl = track.coverUrl,
                    isExternalResolved = true,
                    initialStatus = QueueStatus.FAILED,
                    initialErrorMessage = match.reason
                )
            }
        }
    }

    fun enqueueExternalCollection(selectedTrackIds: List<String>) {
        val collection = _externalCollectionDialogData.value ?: return
        _externalCollectionDialogData.value = null

        viewModelScope.launch {
            _isResolvingExternal.value = true
            val config = repository.getConfig()
            val toleranceSec = config.durationToleranceSeconds.toLong()
            val selectedTracks = collection.tracks.filter { it.id in selectedTrackIds }
            val total = selectedTracks.size
            var successCount = 0
            var failedCount = 0

            val app = getApplication<Application>()
            for ((index, track) in selectedTracks.withIndex()) {
                _externalBatchProgress.value = app.getString(R.string.vm_locating_yt_batch, index + 1, total, track.title)

                val match = ExternalPlatformManager.searchBestYouTubeMatch(track, toleranceSec)
                when (match) {
                    is YouTubeMatchResult.Success -> {
                        successCount++
                        queueManager.enqueue(
                            rawUrl = "https://youtu.be/${match.videoId}",
                            initialTitle = track.title,
                            initialArtist = track.artist,
                            initialAlbum = track.album,
                            initialThumbnailUrl = track.coverUrl.ifBlank { match.matchedItem.thumbnailUrl },
                            isExternalResolved = true
                        )
                    }
                    is YouTubeMatchResult.Failure -> {
                        failedCount++
                        queueManager.enqueue(
                            rawUrl = "https://open.spotify.com",
                            initialTitle = track.title,
                            initialArtist = track.artist,
                            initialAlbum = track.album,
                            initialThumbnailUrl = track.coverUrl,
                            isExternalResolved = true,
                            initialStatus = QueueStatus.FAILED,
                            initialErrorMessage = match.reason
                        )
                    }
                }

                // Pausa preventiva de 700 ms entre peticiones para evitar bloqueos
                if (index < total - 1) {
                    kotlinx.coroutines.delay(700)
                }
            }

            _isResolvingExternal.value = false
            _externalBatchProgress.value = null
            val skippedDuplicates = collection.tracks.count { it.isAlreadyInLibrary && it.id !in selectedTrackIds }
            val parts = mutableListOf<String>()
            if (successCount > 0) parts.add(app.getString(R.string.vm_batch_added, successCount))
            if (skippedDuplicates > 0) parts.add(app.getString(R.string.vm_batch_in_library, skippedDuplicates))
            if (failedCount > 0) parts.add(app.getString(R.string.vm_batch_no_match, failedCount))
            if (parts.isNotEmpty()) {
                _playlistSummaryBanner.value = "${collection.title}: ${parts.joinToString(" · ")}"
            }
        }
    }

    fun playHistoryItem(item: com.nasmusicdrop.app.data.model.UploadHistoryItem) {
        if (previewPlayer.currentTrackId.value == item.id) {
            if (previewPlayer.isPlaying.value) previewPlayer.pausePreview() else previewPlayer.resumePreview()
            return
        }

        val reqId = previewPlayer.setLoadingState(item.id, "${item.artist} - ${item.title}")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val fileToPlay: java.io.File? = if (item.remotePath.startsWith("Music/")) {
                val musicDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
                val rel = item.remotePath.removePrefix("Music/")
                val f = java.io.File(musicDir, rel)
                if (f.exists() && f.length() > 0) f else null
            } else {
                val config = repository.getConfig()
                com.nasmusicdrop.app.data.nas.SmbNasService.downloadTrackToCache(
                    config = config,
                    remotePath = item.remotePath,
                    cacheDir = getApplication<android.app.Application>().cacheDir
                )
            }

            if (fileToPlay != null) {
                previewPlayer.playFile(item.id, "${item.artist} - ${item.title}", fileToPlay.absolutePath, reqId)
            } else {
                previewPlayer.stopPreview()
            }
        }
    }

    fun playNextHistoryItem(autoAdvance: Boolean = false) {
        val currentId = previewPlayer.currentTrackId.value ?: return
        val history = uploadHistory.value
        val currentIndex = history.indexOfFirst { it.id == currentId }
        if (currentIndex == -1 || history.size <= 1) return

        val nextIndex = if (currentIndex + 1 < history.size) {
            currentIndex + 1
        } else if (autoAdvance) {
            return // Al llegar al final del historial en reproducción continua, detener
        } else {
            0
        }
        playHistoryItem(history[nextIndex])
    }

    fun playPreviousHistoryItem() {
        val currentId = previewPlayer.currentTrackId.value ?: return
        val history = uploadHistory.value
        val currentIndex = history.indexOfFirst { it.id == currentId }
        if (currentIndex == -1 || history.size <= 1) return

        val prevIndex = if (currentIndex > 0) currentIndex - 1 else history.lastIndex
        playHistoryItem(history[prevIndex])
    }

    fun togglePauseQueue() {
        if (queueManager.isQueuePaused.value) {
            queueManager.resumeQueue()
        } else {
            queueManager.pauseQueue()
        }
    }

    fun dismissExternalCollectionDialog() {
        _externalCollectionDialogData.value = null
    }

    fun enqueuePlaylistItems(selectedVideoIds: List<String>) {
        val playlist = _playlistDialogData.value
        selectedVideoIds.forEach { vid ->
            val trackInfo = playlist?.items?.firstOrNull { it.videoId == vid }
            queueManager.enqueue(
                rawUrl = if (vid.startsWith("http")) vid else "https://youtu.be/$vid",
                initialTitle = trackInfo?.title,
                initialArtist = trackInfo?.uploader,
                initialThumbnailUrl = trackInfo?.thumbnailUrl
            )
        }
        _playlistDialogData.value = null
    }

    fun dismissPlaylistDialog() {
        _playlistDialogData.value = null
    }

    fun selectCandidate(itemId: String, candidate: SongCandidate) {
        queueManager.selectCandidate(itemId, candidate)
    }

    fun updateItemMetadata(
        itemId: String,
        title: String,
        artist: String,
        album: String,
        startTimeSeconds: Long? = null,
        endTimeSeconds: Long? = null
    ) {
        queueManager.updateItemMetadata(itemId, title, artist, album, startTimeSeconds, endTimeSeconds)
    }

    fun enqueueUrl(
        rawUrl: String,
        startTimeSeconds: Long = 0L,
        endTimeSeconds: Long = 0L,
        initialTitle: String? = null,
        initialArtist: String? = null,
        initialThumbnailUrl: String? = null
    ) {
        queueManager.enqueue(rawUrl, startTimeSeconds, endTimeSeconds, initialTitle, initialArtist, initialThumbnailUrl)
    }

    fun retryQueueItem(itemId: String) {
        queueManager.retry(itemId)
    }

    fun retryAllFailed() {
        queueManager.retryAllFailed()
    }

    fun resolveFailedWithYouTubeVideo(itemId: String, videoId: String) {
        queueManager.resolveWithYouTubeVideo(itemId, videoId)
    }

    fun cancelQueueItem(itemId: String) {
        queueManager.cancel(itemId)
    }

    fun skipDuplicate(itemId: String) {
        queueManager.skipDuplicate(itemId)
    }

    fun overwriteDuplicate(itemId: String) {
        queueManager.overwriteDuplicate(itemId)
    }

    fun removeQueueItem(itemId: String) {
        queueManager.remove(itemId)
    }

    fun updateQueueItemLyrics(itemId: String, newLyrics: String?, newSyncedLyrics: String? = null) {
        queueManager.updateItemLyrics(itemId, newLyrics, newSyncedLyrics)
    }

    suspend fun searchAlternativeLyrics(query: String): List<com.nasmusicdrop.app.data.metadata.LrclibService.LyricsCandidate> {
        return com.nasmusicdrop.app.data.metadata.LrclibService.searchLyricsCandidates(query)
    }

    fun clearCompleted() {
        queueManager.clearCompleted()
    }

    fun clearHistory() {
        repository.clearHistory()
    }

    fun deleteHistoryItem(id: String) {
        repository.deleteHistoryItem(id)
    }

    fun getCacheSizeBytes(): Long {
        return try {
            getApplication<android.app.Application>().cacheDir
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    fun clearAppCache(onDone: (Long) -> Unit = {}) {
        previewPlayer.stopPreview()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val cacheDir = getApplication<android.app.Application>().cacheDir
            val before = getCacheSizeBytes()
            try {
                cacheDir.listFiles()?.forEach { file ->
                    // No borrar archivos activos en curso si existieran, pero el resto sí
                    if (file.isDirectory) {
                        file.deleteRecursively()
                    } else {
                        file.delete()
                    }
                }
            } catch (_: Exception) {}
            val after = getCacheSizeBytes()
            val freed = (before - after).coerceAtLeast(0L)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onDone(freed)
            }
        }
    }

    fun requeueFromHistory(item: UploadHistoryItem, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val query = "${item.artist} ${item.title}".trim()
            val results = com.nasmusicdrop.app.data.audio.YouTubeExtractor.searchYouTube(query)
            val best = results.firstOrNull()
            if (best != null) {
                queueManager.enqueue(
                    rawUrl = "https://youtu.be/${best.videoId}",
                    initialTitle = item.title,
                    initialArtist = item.artist,
                    initialAlbum = item.album,
                    initialThumbnailUrl = item.artworkUrl.ifBlank { best.thumbnailUrl },
                    isExternalResolved = true
                )
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    fun testNasConnection(config: NasConfig? = null) {
        val targetConfig = config ?: nasConfig.value
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestResult.value = null
            val result = SmbNasService.testConnection(targetConfig)
            _connectionTestResult.value = result
            _isTestingConnection.value = false
        }
    }

    fun dismissConnectionTestDialog() {
        _connectionTestResult.value = null
    }

    fun saveConfig(newConfig: NasConfig) {
        repository.saveConfig(newConfig)
    }

    sealed class RecognitionUiState {
        data object Idle : RecognitionUiState()
        data class Listening(val progress: Float, val amplitude: Float) : RecognitionUiState()
        data object Identifying : RecognitionUiState()
        data class MatchFound(val track: ExternalTrack) : RecognitionUiState()
        data object NoMatch : RecognitionUiState()
        data class Error(val message: String) : RecognitionUiState()
    }

    private val _recognitionState = MutableStateFlow<RecognitionUiState>(RecognitionUiState.Idle)
    val recognitionState: StateFlow<RecognitionUiState> = _recognitionState.asStateFlow()
    private var recognitionJob: kotlinx.coroutines.Job? = null

    fun startAudioRecognition() {
        previewPlayer.stopPreview()
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            _recognitionState.value = RecognitionUiState.Listening(0f, 0f)
            val result = com.nasmusicdrop.app.data.recognition.ShazamRecognizer.recordAndRecognize(
                onListeningProgress = { prog, amp ->
                    _recognitionState.value = RecognitionUiState.Listening(prog, amp)
                },
                onAnalyzing = {
                    _recognitionState.value = RecognitionUiState.Identifying
                }
            )
            _recognitionState.value = when (result) {
                is com.nasmusicdrop.app.data.recognition.ShazamRecognizer.RecognitionResult.Success ->
                    RecognitionUiState.MatchFound(result.track)
                is com.nasmusicdrop.app.data.recognition.ShazamRecognizer.RecognitionResult.NoMatch ->
                    RecognitionUiState.NoMatch
                is com.nasmusicdrop.app.data.recognition.ShazamRecognizer.RecognitionResult.Error ->
                    RecognitionUiState.Error(result.message)
            }
        }
    }

    fun cancelAudioRecognition() {
        recognitionJob?.cancel()
        recognitionJob = null
        _recognitionState.value = RecognitionUiState.Idle
    }

    fun dismissAudioRecognition() {
        _recognitionState.value = RecognitionUiState.Idle
    }

    fun enqueueRecognizedTrack(track: ExternalTrack) {
        _recognitionState.value = RecognitionUiState.Idle
        viewModelScope.launch {
            _isResolvingExternal.value = true
            _externalBatchProgress.value = getApplication<Application>().getString(R.string.vm_locating_yt_single, track.artist, track.title)
            resolveAndEnqueueSingleExternalTrack(track)
            _isResolvingExternal.value = false
            _externalBatchProgress.value = null
        }
    }

    override fun onCleared() {
        recognitionJob?.cancel()
        previewPlayer.stopPreview()
        super.onCleared()
    }
}
