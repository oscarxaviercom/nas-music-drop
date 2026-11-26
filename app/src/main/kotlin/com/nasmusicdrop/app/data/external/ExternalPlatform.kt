package com.nasmusicdrop.app.data.external

import java.util.UUID

data class ExternalTrack(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Long = 0L,
    val coverUrl: String = "",
    val trackNumber: Int = 1,
    val platform: String = "Spotify",
    val originalUrl: String = "",
    var isAlreadyInLibrary: Boolean = false,
    var resolvedVideoId: String? = null,
    var resolveError: String? = null
)

data class ExternalCollection(
    val title: String,
    val author: String,
    val coverUrl: String,
    val type: String, // "album" o "playlist"
    val platform: String = "Spotify",
    val tracks: List<ExternalTrack>
)

sealed class ExternalResult {
    data class Track(val track: ExternalTrack) : ExternalResult()
    data class Collection(val collection: ExternalCollection) : ExternalResult()
    data class Error(val message: String) : ExternalResult()
}

interface ExternalPlatformService {
    val platformName: String
    fun canHandle(url: String): Boolean
    suspend fun resolveUrl(rawUrl: String): ExternalResult
}
