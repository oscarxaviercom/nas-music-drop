package com.nasmusicdrop.app.data.external

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object SpotifyPlatformService : ExternalPlatformService {

    private const val TAG = "SpotifyService"
    override val platformName: String = "Spotify"

    private val URL_REGEX = Pattern.compile(
        """https?://(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist)/([a-zA-Z0-9]+)|spotify\.link/([a-zA-Z0-9]+))"""
    )

    override fun canHandle(url: String): Boolean {
        return url.contains("spotify.com") || url.contains("spotify.link")
    }

    private fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("""https?://[^\s]+""").matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (candidate.contains("spotify.com") || candidate.contains("spotify.link")) {
                return candidate
            }
        }
        return null
    }

    private fun followRedirects(startUrl: String): String {
        var currentUrl = startUrl
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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
        val extractedUrl = extractUrlFromText(rawUrl) ?: rawUrl.trim()
        val finalUrl = followRedirects(extractedUrl)

        val matcher = URL_REGEX.matcher(finalUrl)
        if (!matcher.find()) {
            return@withContext ExternalResult.Error("No se pudo reconocer el formato del enlace de Spotify.")
        }

        val type = matcher.group(1) // track, album, playlist
        val id = matcher.group(2)

        if (type.isNullOrBlank() || id.isNullOrBlank()) {
            return@withContext ExternalResult.Error("Identificador de Spotify no encontrado en el enlace.")
        }

        try {
            val embedUrl = "https://open.spotify.com/embed/$type/$id"
            val html = fetchHtml(embedUrl)

            val nextDataJson = extractNextDataJson(html)
            if (nextDataJson != null) {
                val pageProps = nextDataJson.optJSONObject("props")?.optJSONObject("pageProps")
                val status = pageProps?.optInt("status", 200) ?: 200
                if (status == 404) {
                    val msg = if (type == "playlist" && id.startsWith("37i9dQ")) {
                        com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_spotify_personal)
                    } else if (type == "playlist") {
                        com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_spotify_private)
                    } else {
                        com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_spotify_404)
                    }
                    return@withContext ExternalResult.Error(msg)
                }

                val entity = pageProps
                    ?.optJSONObject("state")
                    ?.optJSONObject("data")
                    ?.optJSONObject("entity")

                if (entity != null) {
                    when (type) {
                        "track" -> return@withContext parseTrack(entity, finalUrl)
                        "album" -> return@withContext parseAlbum(entity, finalUrl)
                        "playlist" -> return@withContext parsePlaylist(entity, finalUrl)
                    }
                }
            }

            // Fallback con oEmbed si NextData falla para pistas sueltas
            if (type == "track") {
                val oembed = fetchOEmbed(finalUrl)
                if (oembed != null) {
                    return@withContext ExternalResult.Track(oembed)
                }
            }

            return@withContext ExternalResult.Error("No se pudieron extraer los metadatos de Spotify para este enlace.")

        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo Spotify: ${e.message}", e)
            return@withContext ExternalResult.Error("Error al contactar con Spotify: ${e.localizedMessage}")
        }
    }

    private fun parseTrack(entity: JSONObject, originalUrl: String): ExternalResult {
        val title = entity.optString("name", "Desconocido")
        val artistsArr = entity.optJSONArray("artists")
        val artistsList = mutableListOf<String>()
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val a = artistsArr.getJSONObject(i).optString("name", "")
                if (a.isNotBlank()) artistsList.add(a)
            }
        }
        val artist = artistsList.joinToString(", ").ifBlank { "Desconocido" }
        val album = entity.optJSONObject("album")?.optString("name", "") ?: ""
        val durationMs = entity.optLong("duration", 0L)
        val durationSec = durationMs / 1000L

        val coverUrl = extractBestImageUrl(entity)

        return ExternalResult.Track(
            ExternalTrack(
                title = title,
                artist = artist,
                album = album,
                durationSeconds = durationSec,
                coverUrl = coverUrl,
                originalUrl = originalUrl,
                platform = "Spotify"
            )
        )
    }

    private fun parseAlbum(entity: JSONObject, originalUrl: String): ExternalResult {
        val albumName = entity.optString("name", "Álbum sin título")
        val artistsArr = entity.optJSONArray("artists")
        val artistsList = mutableListOf<String>()
        if (artistsArr != null) {
            for (i in 0 until artistsArr.length()) {
                val a = artistsArr.getJSONObject(i).optString("name", "")
                if (a.isNotBlank()) artistsList.add(a)
            }
        }
        val albumArtist = artistsList.joinToString(", ").ifBlank { "Varios Artistas" }
        val coverUrl = extractBestImageUrl(entity)

        val trackListJson = entity.optJSONArray("trackList") ?: JSONArray()
        val tracks = mutableListOf<ExternalTrack>()

        for (i in 0 until trackListJson.length()) {
            val tObj = trackListJson.getJSONObject(i)
            val trackTitle = tObj.optString("title", "Pista ${i + 1}")
            val trackArtist = tObj.optString("subtitle", albumArtist).ifBlank { albumArtist }
            val trackDurationMs = tObj.optLong("duration", 0L)

            tracks.add(
                ExternalTrack(
                    title = trackTitle,
                    artist = trackArtist,
                    album = albumName,
                    durationSeconds = trackDurationMs / 1000L,
                    coverUrl = coverUrl,
                    trackNumber = i + 1,
                    originalUrl = originalUrl,
                    platform = "Spotify"
                )
            )
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = albumName,
                author = albumArtist,
                coverUrl = coverUrl,
                type = "album",
                platform = "Spotify",
                tracks = tracks
            )
        )
    }

    private fun parsePlaylist(entity: JSONObject, originalUrl: String): ExternalResult {
        val playlistName = entity.optString("name", "Playlist")
        val coverUrl = extractBestImageUrl(entity)

        val trackListJson = entity.optJSONArray("trackList") ?: JSONArray()
        val tracks = mutableListOf<ExternalTrack>()

        for (i in 0 until trackListJson.length()) {
            val tObj = trackListJson.getJSONObject(i)
            val trackTitle = tObj.optString("title", "Canción ${i + 1}")
            val trackArtist = tObj.optString("subtitle", "Desconocido")
            val trackDurationMs = tObj.optLong("duration", 0L)

            tracks.add(
                ExternalTrack(
                    title = trackTitle,
                    artist = trackArtist,
                    album = playlistName,
                    durationSeconds = trackDurationMs / 1000L,
                    coverUrl = coverUrl,
                    trackNumber = i + 1,
                    originalUrl = originalUrl,
                    platform = "Spotify"
                )
            )
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = playlistName,
                author = "Spotify",
                coverUrl = coverUrl,
                type = "playlist",
                platform = "Spotify",
                tracks = tracks
            )
        )
    }

    private fun extractBestImageUrl(entity: JSONObject): String {
        try {
            val visualIdentity = entity.optJSONObject("visualIdentity")
            val images = visualIdentity?.optJSONArray("image")
            if (images != null && images.length() > 0) {
                var bestUrl = ""
                var bestWidth = 0
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val w = img.optInt("maxWidth", 0)
                    val url = img.optString("url", "")
                    if (w >= bestWidth && url.isNotBlank()) {
                        bestWidth = w
                        bestUrl = url
                    }
                }
                if (bestUrl.isNotBlank()) return bestUrl
            }

            // Otra ubicación común en embeds
            val coverArt = entity.optJSONObject("coverArt")
            val sources = coverArt?.optJSONArray("sources")
            if (sources != null && sources.length() > 0) {
                return sources.getJSONObject(0).optString("url", "")
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun extractNextDataJson(html: String): JSONObject? {
        val pattern = Pattern.compile("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        if (matcher.find()) {
            val jsonStr = matcher.group(1)?.trim() ?: return null
            return try {
                JSONObject(jsonStr)
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun fetchHtml(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode} al consultar Spotify")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchOEmbed(spotifyUrl: String): ExternalTrack? {
        try {
            val oEmbedUrl = "https://open.spotify.com/oembed?url=${java.net.URLEncoder.encode(spotifyUrl, "UTF-8")}"
            val conn = (URL(oEmbedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "MusicDrop/1.0 (Android)")
            }
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val rawTitle = json.optString("title", "")
                val thumb = json.optString("thumbnail_url", "")
                // El título en oEmbed suele ser "Título - Canción de Artista" o "Título"
                val cleanTitle = rawTitle.replace(Regex(""" - Canción de .*$"""), "").trim()
                return ExternalTrack(
                    title = cleanTitle.ifBlank { rawTitle },
                    artist = "Spotify",
                    coverUrl = thumb,
                    originalUrl = spotifyUrl,
                    platform = "Spotify"
                )
            }
        } catch (_: Exception) {}
        return null
    }
}
