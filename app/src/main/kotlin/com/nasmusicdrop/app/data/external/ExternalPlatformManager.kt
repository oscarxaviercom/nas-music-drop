package com.nasmusicdrop.app.data.external

import android.content.Context
import android.util.Log
import com.nasmusicdrop.app.data.audio.YouTubeExtractor
import com.nasmusicdrop.app.data.local.LocalStorageService
import com.nasmusicdrop.app.data.model.NasConfig
import com.nasmusicdrop.app.data.model.SearchResultItem
import com.nasmusicdrop.app.data.nas.SmbNasService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

sealed class YouTubeMatchResult {
    data class Success(
        val videoId: String,
        val matchedItem: SearchResultItem,
        val durationDiffSeconds: Long
    ) : YouTubeMatchResult()

    data class Failure(
        val reason: String
    ) : YouTubeMatchResult()
}

object ExternalPlatformManager {

    private const val TAG = "ExternalPlatformMgr"

    // Registro modular de plataformas externas
    private val services = listOf<ExternalPlatformService>(
        SpotifyPlatformService,
        AppleMusicPlatformService,
        DeezerPlatformService,
        AmazonMusicPlatformService
    )

    fun findService(url: String): ExternalPlatformService? {
        return services.firstOrNull { it.canHandle(url) }
    }

    suspend fun resolve(rawUrl: String): ExternalResult {
        val service = findService(rawUrl)
            ?: return ExternalResult.Error("Plataforma no soportada para este enlace.")
        return service.resolveUrl(rawUrl)
    }

    /**
     * Comprueba de forma anticipada si una lista de pistas ya existe en la biblioteca (NAS o móvil)
     * usando una única sesión SMB cuando el destino es NAS.
     */
    suspend fun checkTracksAlreadyInLibrary(
        config: NasConfig,
        context: Context,
        tracks: List<ExternalTrack>
    ): List<ExternalTrack> = withContext(Dispatchers.IO) {
        if (!config.checkDuplicates || tracks.isEmpty()) return@withContext tracks

        return@withContext try {
            if (config.destinationMode == "LOCAL") {
                tracks.map { track ->
                    val exists = LocalStorageService.checkTrackExistsLocally(
                        context = context,
                        artistName = track.artist,
                        trackTitle = track.title,
                        albumName = track.album,
                        organizeByAlbum = config.organizeByAlbum,
                        folderStructure = config.folderStructure
                    )
                    track.copy(isAlreadyInLibrary = exists != null)
                }
            } else {
                val existingIndices = SmbNasService.checkTracksExistOnNas(
                    config = config,
                    tracks = tracks.map { it.artist to it.title }
                )
                tracks.mapIndexed { index, track ->
                    track.copy(isAlreadyInLibrary = index in existingIndices)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error comprobando duplicados en lote: ${e.message}")
            tracks
        }
    }

    /**
     * Comprueba de forma anticipada si la pista ya existe en la biblioteca (NAS o móvil)
     * ANTES de hacer peticiones a YouTube o descargarla.
     */
    suspend fun checkTrackAlreadyInLibrary(
        config: NasConfig,
        context: Context,
        track: ExternalTrack
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.checkDuplicates) return@withContext false

        try {
            if (config.destinationMode == "LOCAL") {
                val exists = LocalStorageService.checkTrackExistsLocally(
                    context = context,
                    artistName = track.artist,
                    trackTitle = track.title,
                    albumName = track.album,
                    organizeByAlbum = config.organizeByAlbum
                )
                return@withContext exists != null
            } else {
                val exists = SmbNasService.checkTrackExistsOnNas(
                    config = config,
                    artistName = track.artist,
                    songTitle = track.title
                )
                return@withContext exists != null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error comprobando duplicado previo: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Busca la canción en YouTube priorizando audio limpio de estudio:
     * - Consulta: "Artista - Título audio"
     * - Descarta variaciones superiores al margen de duración (si la plataforma reporta duración)
     * - Prioriza canales "- Topic" (+50), "official audio" (+35) y "lyrics/letra" (+25)
     * - Penaliza videoclips oficiales (-50), directos (-60) y versiones alteradas (-70)
     */
    suspend fun searchBestYouTubeMatch(
        track: ExternalTrack,
        toleranceSeconds: Long = 15L
    ): YouTubeMatchResult = withContext(Dispatchers.IO) {
        val targetDuration = track.durationSeconds
        val query = "${track.artist} - ${track.title} audio".trim()

        var results = YouTubeExtractor.searchYouTube(query)
        if (results.isEmpty()) {
            val fallbackQuery = "${track.artist} ${track.title}".trim()
            results = YouTubeExtractor.searchYouTube(fallbackQuery)
        }

        if (results.isEmpty()) {
            return@withContext YouTubeMatchResult.Failure("No se encontraron resultados en YouTube para '${track.artist} - ${track.title}'")
        }

        var bestMatch: SearchResultItem? = null
        var bestScore = Int.MIN_VALUE
        var bestDiff = Long.MAX_VALUE

        val artistLower = track.artist.lowercase()
        val origTitleLower = track.title.lowercase()
        val origAlbumLower = track.album.lowercase()

        val isOriginalLive = origTitleLower.contains("live") || origTitleLower.contains("en vivo") ||
            origTitleLower.contains("directo") || origAlbumLower.contains("live")
        val isOriginalCoverOrInst = origTitleLower.contains("cover") || origTitleLower.contains("karaoke") ||
            origTitleLower.contains("instrumental")
        val isOriginalAltered = origTitleLower.contains("slowed") || origTitleLower.contains("reverb") ||
            origTitleLower.contains("sped up") || origTitleLower.contains("nightcore") || origTitleLower.contains("8d")
        val isOriginalRemix = origTitleLower.contains("remix") || origAlbumLower.contains("remix")

        for ((index, item) in results.take(10).withIndex()) {
            val itemDur = item.durationSeconds
            val diff: Long

            var score = if (targetDuration > 0L) {
                if (itemDur <= 0L) continue
                diff = abs(itemDur - targetDuration)
                if (diff > toleranceSeconds) continue
                100 - (diff.toInt() * 6)
            } else {
                diff = 0L
                50 + (10 - index).coerceAtLeast(0)
            }

            val uploader = item.uploader.lowercase()
            val titleLower = item.title.lowercase()

            // 1. Prioridad máxima a canales "- Topic" (audio maestro limpio de YouTube Music)
            if (uploader.endsWith("- topic")) {
                score += 50
            } else if (artistLower.isNotBlank() && uploader.contains(artistLower)) {
                score += 15
            }

            // 2. Bonus por audio oficial o vídeo con letra (sin diálogos ni efectos de videoclip)
            when {
                titleLower.contains("official audio") || titleLower.contains("audio oficial") -> score += 35
                titleLower.contains("audio") -> score += 25
            }
            if (titleLower.contains("lyric") || titleLower.contains("letra")) {
                score += 25
            }

            // 3. Penalización a videoclips oficiales (suelen incluir intros habladas y efectos de sonido)
            val isVideoClip = titleLower.contains("official video") ||
                titleLower.contains("official music video") ||
                titleLower.contains("music video") ||
                titleLower.contains("video oficial") ||
                titleLower.contains("videoclip") ||
                Regex("""\bm/?v\b""").containsMatchIn(titleLower)
            if (isVideoClip) {
                score -= 50
            }

            // 4. Penalización a directos, covers y versiones alteradas no solicitadas
            if (!isOriginalLive && (titleLower.contains("live") || titleLower.contains("en vivo") ||
                    titleLower.contains("directo") || titleLower.contains("concierto"))
            ) {
                score -= 60
            }
            if (!isOriginalCoverOrInst && (titleLower.contains("cover") || titleLower.contains("karaoke") ||
                    titleLower.contains("instrumental"))
            ) {
                score -= 60
            }
            if (!isOriginalAltered && (titleLower.contains("slowed") || titleLower.contains("reverb") ||
                    titleLower.contains("sped up") || titleLower.contains("nightcore") ||
                    Regex("""\b8d\b""").containsMatchIn(titleLower))
            ) {
                score -= 70
            }
            if (!isOriginalRemix && titleLower.contains("remix")) {
                score -= 45
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = item
                bestDiff = diff
            }
        }

        if (bestMatch != null) {
            return@withContext YouTubeMatchResult.Success(
                videoId = bestMatch.videoId,
                matchedItem = bestMatch,
                durationDiffSeconds = bestDiff
            )
        }

        return@withContext YouTubeMatchResult.Failure(
            com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_yt_duration_mismatch, targetDuration, toleranceSeconds)
        )
    }
}
