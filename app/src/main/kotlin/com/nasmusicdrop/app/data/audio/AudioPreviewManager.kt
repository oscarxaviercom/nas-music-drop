package com.nasmusicdrop.app.data.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AudioPreviewManager {

    private const val TAG = "AudioPreviewManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var requestCounter = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    var onTrackCompleted: (() -> Unit)? = null

    private val _currentTrackId = MutableStateFlow<String?>(null)
    val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    private val _currentTrackTitle = MutableStateFlow<String>("")
    val currentTrackTitle: StateFlow<String> = _currentTrackTitle.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun playPreview(videoId: String, title: String) {
        if (_currentTrackId.value == videoId) {
            // Si ya es el track actual, alternar play/pause
            if (_isPlaying.value) {
                pausePreview()
            } else {
                resumePreview()
            }
            return
        }

        stopPreview()

        val reqId = ++requestCounter
        _currentTrackId.value = videoId
        _currentTrackTitle.value = title
        _isLoading.value = true
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L

        scope.launch(Dispatchers.IO) {
            val streamUrl = YouTubeExtractor.getAudioStreamUrl(videoId)
            launch(Dispatchers.Main) {
                if (reqId != requestCounter) {
                    return@launch
                }
                if (streamUrl == null) {
                    _isLoading.value = false
                    _currentTrackId.value = null
                    Log.e(TAG, "No se pudo obtener la URL de streaming para preview de: $videoId")
                    return@launch
                }
                prepareAndStart(reqId, streamUrl)
            }
        }
    }

    fun setLoadingState(trackId: String, title: String): Long {
        stopPreview()
        val reqId = ++requestCounter
        _currentTrackId.value = trackId
        _currentTrackTitle.value = title
        _isLoading.value = true
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
        return reqId
    }

    fun playFile(trackId: String, title: String, filePath: String, existingReqId: Long? = null) {
        if (existingReqId == null && _currentTrackId.value == trackId) {
            if (_isPlaying.value) pausePreview() else resumePreview()
            return
        }
        val reqId = existingReqId ?: setLoadingState(trackId, title)
        if (reqId != requestCounter) return
        scope.launch(Dispatchers.Main) {
            prepareAndStart(reqId, filePath)
        }
    }

    private fun prepareAndStart(reqId: Long, dataSource: String) {
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(dataSource)
                setOnPreparedListener { player ->
                    if (reqId != requestCounter) {
                        try { player.release() } catch (_: Exception) {}
                        return@setOnPreparedListener
                    }
                    _isLoading.value = false
                    _isPlaying.value = true
                    _durationMs.value = player.duration.toLong().coerceAtLeast(0L)
                    player.start()
                    startProgressTracker()
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = 0L
                    stopProgressTracker()
                    onTrackCompleted?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Error en MediaPlayer: what=$what, extra=$extra")
                    stopPreview()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al inicializar MediaPlayer: ${e.message}")
            stopPreview()
        }
    }

    fun pausePreview() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    _isPlaying.value = false
                    stopProgressTracker()
                }
            }
        } catch (_: Exception) {}
    }

    fun resumePreview() {
        try {
            mediaPlayer?.let {
                it.start()
                _isPlaying.value = true
                startProgressTracker()
            }
        } catch (_: Exception) {}
    }

    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    it.seekTo(positionMs.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
                } else {
                    it.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                }
                _currentPositionMs.value = positionMs.coerceAtLeast(0L)
            }
        } catch (_: Exception) {}
    }

    fun stopPreview() {
        stopProgressTracker()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _currentTrackId.value = null
        _isPlaying.value = false
        _isLoading.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive && _isPlaying.value) {
                try {
                    mediaPlayer?.let {
                        if (it.isPlaying) {
                            _currentPositionMs.value = it.currentPosition.toLong()
                        }
                    }
                } catch (_: Exception) {}
                delay(300)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }
}
