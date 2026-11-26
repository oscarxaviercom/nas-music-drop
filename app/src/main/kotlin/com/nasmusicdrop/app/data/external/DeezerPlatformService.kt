package com.nasmusicdrop.app.data.external

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object DeezerPlatformService : ExternalPlatformService {

    private const val TAG = "DeezerService"
    override val platformName: String = "Deezer"

    private val URL_REGEX = Pattern.compile(
        """https?://(?:www\.)?deezer\.com/(?:[a-z]{2}/)?(track|album|playlist)/(\d+)"""
    )

    override fun canHandle(url: String): Boolean {
        return url.contains("deezer.com") ||
            url.contains("dzr.page.link") ||
            url.contains("deezer.page.link") ||
            url.contains("link.deezer.com")
    }

    private fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("""https?://[^\s]+""").matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (canHandle(candidate)) return candidate
        }
        return null
    }

    private fun followRedirects(startUrl: String): String {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            try {
                val status = conn.responseCode
                if (status in 300..399) {
                    val newUrl = conn.getHeaderField("Location") ?: break
                    currentUrl = newUrl
                    redirects++
                } else {
                    break
                }
            } finally {
                conn.disconnect()
            }
        }
        return currentUrl
    }

    override suspend fun resolveUrl(rawUrl: String): ExternalResult = withContext(Dispatchers.IO) {
        val extracted = extractUrlFromText(rawUrl) ?: rawUrl.trim()
        val finalUrl = try {
            followRedirects(extracted)
        } catch (_: Exception) {
            extracted
        }

        val matcher = URL_REGEX.matcher(finalUrl)
        if (!matcher.find()) {
            return@withContext ExternalResult.Error("No se pudo reconocer el formato del enlace de Deezer.")
        }

        val type = matcher.group(1)
        val id = matcher.group(2)
        if (type.isNullOrBlank() || id.isNullOrBlank()) {
            return@withContext ExternalResult.Error("Identificador de Deezer no encontrado.")
        }

        try {
            val apiUrl = "https://api.deezer.com/$type/$id"
            val json = fetchJson(apiUrl)
            if (json.has("error")) {
                val errMsg = json.optJSONObject("error")?.optString("message") ?: "Contenido no disponible"
                return@withContext ExternalResult.Error("Deezer: $errMsg")
            }

            return@withContext when (type) {
                "track" -> parseTrack(json, finalUrl)
                "album" -> parseAlbum(json, finalUrl)
                "playlist" -> parsePlaylist(json, finalUrl)
                else -> ExternalResult.Error("Tipo de enlace de Deezer no soportado.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo Deezer: ${e.message}", e)
            return@withContext ExternalResult.Error("Error al contactar con Deezer: ${e.localizedMessage}")
        }
    }

    private fun parseTrack(json: JSONObject, originalUrl: String): ExternalResult {
        val title = json.optString("title", "Desconocido")
        val artist = json.optJSONObject("artist")?.optString("name", "Desconocido") ?: "Desconocido"
        val albumObj = json.optJSONObject("album")
        val album = albumObj?.optString("title", "") ?: ""
        val durationSec = json.optLong("duration", 0L)
        val cover = albumObj?.optString("cover_xl")
            ?.ifBlank { albumObj.optString("cover_big") }
            ?.ifBlank { albumObj.optString("cover_medium") }
            ?: ""

        return ExternalResult.Track(
            ExternalTrack(
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                coverUrl = cover,
                platform = platformName,
                originalUrl = originalUrl
            )
        )
    }

    private fun parseAlbum(json: JSONObject, originalUrl: String): ExternalResult {
        val albumName = json.optString("title", "Álbum")
        val albumArtist = json.optJSONObject("artist")?.optString("name", "Varios Artistas") ?: "Varios Artistas"
        val cover = json.optString("cover_xl")
            .ifBlank { json.optString("cover_big") }
            .ifBlank { json.optString("cover_medium") }

        val dataArr = json.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        val tracks = mutableListOf<ExternalTrack>()
        for (i in 0 until dataArr.length()) {
            val t = dataArr.getJSONObject(i)
            val tTitle = t.optString("title", "Pista ${i + 1}")
            val tArtist = t.optJSONObject("artist")?.optString("name", albumArtist)?.ifBlank { albumArtist } ?: albumArtist
            val tDur = t.optLong("duration", 0L)
            tracks.add(
                ExternalTrack(
                    title = tTitle,
                    artist = tArtist,
                    album = albumName,
                    durationSeconds = tDur,
                    coverUrl = cover,
                    trackNumber = i + 1,
                    platform = platformName,
                    originalUrl = originalUrl
                )
            )
        }

        if (tracks.isEmpty()) {
            return ExternalResult.Error("El álbum de Deezer no contiene pistas visibles.")
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = albumName,
                author = albumArtist,
                coverUrl = cover,
                type = "album",
                platform = platformName,
                tracks = tracks
            )
        )
    }

    private fun parsePlaylist(json: JSONObject, originalUrl: String): ExternalResult {
        val playlistName = json.optString("title", "Playlist")
        val creator = json.optJSONObject("creator")?.optString("name", "Deezer") ?: "Deezer"
        val cover = json.optString("picture_xl")
            .ifBlank { json.optString("picture_big") }
            .ifBlank { json.optString("picture_medium") }

        val dataArr = json.optJSONObject("tracks")?.optJSONArray("data") ?: JSONArray()
        val tracks = mutableListOf<ExternalTrack>()
        for (i in 0 until dataArr.length()) {
            val t = dataArr.getJSONObject(i)
            val tTitle = t.optString("title", "Canción ${i + 1}")
            val tArtist = t.optJSONObject("artist")?.optString("name", "Desconocido") ?: "Desconocido"
            val tAlbumObj = t.optJSONObject("album")
            val tAlbum = tAlbumObj?.optString("title", playlistName) ?: playlistName
            val tCover = tAlbumObj?.optString("cover_xl")
                ?.ifBlank { tAlbumObj.optString("cover_big") }
                ?.ifBlank { cover }
                ?: cover
            val tDur = t.optLong("duration", 0L)
            tracks.add(
                ExternalTrack(
                    title = tTitle,
                    artist = tArtist,
                    album = tAlbum,
                    durationSeconds = tDur,
                    coverUrl = tCover,
                    trackNumber = i + 1,
                    platform = platformName,
                    originalUrl = originalUrl
                )
            )
        }

        if (tracks.isEmpty()) {
            return ExternalResult.Error("La playlist de Deezer está vacía o es privada.")
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = playlistName,
                author = creator,
                coverUrl = cover,
                type = "playlist",
                platform = platformName,
                tracks = tracks
            )
        )
    }

    private fun fetchJson(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "MusicDrop/1.4 (Android)")
        }
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
