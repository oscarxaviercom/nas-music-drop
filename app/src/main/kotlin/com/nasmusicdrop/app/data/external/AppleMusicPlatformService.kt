package com.nasmusicdrop.app.data.external

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object AppleMusicPlatformService : ExternalPlatformService {

    private const val TAG = "AppleMusicService"
    override val platformName: String = "Apple Music"

    private val TRACK_QUERY_REGEX = Pattern.compile("""[?&]i=(\d+)""")
    private val SONG_PATH_REGEX = Pattern.compile("""https?://(?:music|itunes)\.apple\.com/([a-z]{2})/song/[^/]+/(\d+)""")
    private val ALBUM_PATH_REGEX = Pattern.compile("""https?://(?:music|itunes)\.apple\.com/([a-z]{2})/album/[^/]+/(\d+)""")
    private val PLAYLIST_PATH_REGEX = Pattern.compile("""https?://music\.apple\.com/([a-z]{2})/playlist/[^/]+/(pl\.[a-zA-Z0-9._-]+)""")

    override fun canHandle(url: String): Boolean {
        return url.contains("music.apple.com") || url.contains("itunes.apple.com")
    }

    private fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("""https?://[^\s]+""").matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (canHandle(candidate)) return candidate
        }
        return null
    }

    override suspend fun resolveUrl(rawUrl: String): ExternalResult = withContext(Dispatchers.IO) {
        val url = extractUrlFromText(rawUrl) ?: rawUrl.trim()

        try {
            // 1. Pista individual mediante ?i=TRACK_ID en enlace de álbum
            val iMatcher = TRACK_QUERY_REGEX.matcher(url)
            val regionMatch = Regex("""apple\.com/([a-z]{2})/""").find(url)
            val region = regionMatch?.groupValues?.getOrNull(1) ?: "es"

            if (iMatcher.find()) {
                val trackId = iMatcher.group(1)
                if (!trackId.isNullOrBlank()) {
                    return@withContext lookupSingleTrack(trackId, region, url)
                }
            }

            // 2. Pista individual mediante /song/.../ID
            val songMatcher = SONG_PATH_REGEX.matcher(url)
            if (songMatcher.find()) {
                val r = songMatcher.group(1) ?: region
                val id = songMatcher.group(2) ?: ""
                if (id.isNotBlank()) {
                    return@withContext lookupSingleTrack(id, r, url)
                }
            }

            // 3. Álbum completo mediante /album/.../ID
            val albumMatcher = ALBUM_PATH_REGEX.matcher(url)
            if (albumMatcher.find()) {
                val r = albumMatcher.group(1) ?: region
                val id = albumMatcher.group(2) ?: ""
                if (id.isNotBlank()) {
                    return@withContext lookupAlbum(id, r, url)
                }
            }

            // 4. Playlist mediante /playlist/.../pl.xxx
            val plMatcher = PLAYLIST_PATH_REGEX.matcher(url)
            if (plMatcher.find()) {
                val plId = plMatcher.group(2) ?: ""
                if (plId.isNotBlank()) {
                    return@withContext parsePlaylistFromHtml(url)
                }
            }

            return@withContext ExternalResult.Error("No se pudo reconocer el formato del enlace de Apple Music.")
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo Apple Music: ${e.message}", e)
            return@withContext ExternalResult.Error("Error al contactar con Apple Music: ${e.localizedMessage}")
        }
    }

    private fun lookupSingleTrack(trackId: String, region: String, originalUrl: String): ExternalResult {
        val json = fetchJson("https://itunes.apple.com/lookup?id=$trackId&entity=song&country=$region")
        val results = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            if (obj.optString("wrapperType") == "track") {
                val title = obj.optString("trackName", "Desconocido")
                val artist = obj.optString("artistName", "Desconocido")
                val album = obj.optString("collectionName", "")
                val durationSec = obj.optLong("trackTimeMillis", 0L) / 1000L
                val rawArt = obj.optString("artworkUrl100", "")
                val cover = rawArt.replace("100x100bb", "600x600bb")
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
        }
        return ExternalResult.Error("No se encontraron los datos de esta canción en Apple Music.")
    }

    private fun lookupAlbum(albumId: String, region: String, originalUrl: String): ExternalResult {
        val json = fetchJson("https://itunes.apple.com/lookup?id=$albumId&entity=song&country=$region")
        val results = json.optJSONArray("results") ?: JSONArray()

        var albumName = "Álbum"
        var albumArtist = "Varios Artistas"
        var albumCover = ""
        val tracks = mutableListOf<ExternalTrack>()

        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            when (obj.optString("wrapperType")) {
                "collection" -> {
                    albumName = obj.optString("collectionName", albumName)
                    albumArtist = obj.optString("artistName", albumArtist)
                    val rawArt = obj.optString("artworkUrl100", "")
                    albumCover = rawArt.replace("100x100bb", "600x600bb")
                }
                "track" -> {
                    val title = obj.optString("trackName", "Pista ${tracks.size + 1}")
                    val artist = obj.optString("artistName", albumArtist).ifBlank { albumArtist }
                    val durationSec = obj.optLong("trackTimeMillis", 0L) / 1000L
                    val rawArt = obj.optString("artworkUrl100", "")
                    val cover = rawArt.replace("100x100bb", "600x600bb").ifBlank { albumCover }
                    val trackNum = obj.optInt("trackNumber", tracks.size + 1)
                    tracks.add(
                        ExternalTrack(
                            title = title,
                            artist = artist,
                            album = albumName,
                            durationSeconds = durationSec,
                            coverUrl = cover,
                            trackNumber = trackNum,
                            platform = platformName,
                            originalUrl = originalUrl
                        )
                    )
                }
            }
        }

        if (tracks.isEmpty()) {
            return ExternalResult.Error("No se pudieron extraer las pistas de este álbum en Apple Music.")
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = albumName,
                author = albumArtist,
                coverUrl = albumCover,
                type = "album",
                platform = platformName,
                tracks = tracks
            )
        )
    }

    private fun parsePlaylistFromHtml(url: String): ExternalResult {
        val html = fetchHtml(url)
        val ogTitle = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?.replace(Regex("""\s+en Apple Music$|\s+on Apple Music$""", RegexOption.IGNORE_CASE), "")
            ?: "Playlist de Apple Music"
        val ogImage = Regex("""<meta[^>]+property="og:image"[^>]+content="([^"]+)"""").find(html)?.groupValues?.getOrNull(1) ?: ""

        val tracks = mutableListOf<ExternalTrack>()
        val serverDataMatch = Regex("""<script[^>]+id="serialized-server-data"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (serverDataMatch != null) {
            val rawJson = serverDataMatch.groupValues[1].trim()
            try {
                // serialized-server-data puede ser un JSONObject o JSONArray
                val root: Any = if (rawJson.startsWith("[")) JSONArray(rawJson) else JSONObject(rawJson)
                collectPlaylistTracksRecursive(root, ogTitle, ogImage, url, tracks)
            } catch (e: Exception) {
                Log.w(TAG, "Error parseando serialized-server-data: ${e.message}")
            }
        }

        val uniqueTracks = tracks.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}" }
        if (uniqueTracks.isEmpty()) {
            return ExternalResult.Error("No se pudieron extraer las canciones de esta playlist de Apple Music (puede ser privada o requerir sesión).")
        }

        return ExternalResult.Collection(
            ExternalCollection(
                title = ogTitle,
                author = "Apple Music",
                coverUrl = ogImage,
                type = "playlist",
                platform = platformName,
                tracks = uniqueTracks.mapIndexed { idx, t -> t.copy(trackNumber = idx + 1) }
            )
        )
    }

    private fun collectPlaylistTracksRecursive(
        node: Any?,
        playlistName: String,
        fallbackCover: String,
        originalUrl: String,
        out: MutableList<ExternalTrack>
    ) {
        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectPlaylistTracksRecursive(node.opt(i), playlistName, fallbackCover, originalUrl, out)
                }
            }
            is JSONObject -> {
                // Buscar nodos de pista en la estructura de AmpWeb de Apple Music
                val title = node.optString("title", "")
                val artistName = node.optString("artistName", "")
                val durationMs = node.optLong("duration", node.optLong("durationInMillis", 0L))
                val isTrackItem = title.isNotBlank() && artistName.isNotBlank() &&
                    (node.has("duration") || node.has("durationInMillis") || node.optString("contentKind") == "song" || node.optString("kind") == "song")

                if (isTrackItem) {
                    val durSec = if (durationMs > 1000L) durationMs / 1000L else durationMs
                    out.add(
                        ExternalTrack(
                            title = title,
                            artist = artistName,
                            album = node.optString("albumName", playlistName).ifBlank { playlistName },
                            durationSeconds = durSec,
                            coverUrl = fallbackCover,
                            platform = platformName,
                            originalUrl = originalUrl
                        )
                    )
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        collectPlaylistTracksRecursive(node.opt(key), playlistName, fallbackCover, originalUrl, out)
                    }
                }
            }
        }
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

    private fun fetchHtml(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 9000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
