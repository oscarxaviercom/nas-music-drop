package com.nasmusicdrop.app.data.external

import android.util.Log
import com.nasmusicdrop.app.data.metadata.ItunesService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object AmazonMusicPlatformService : ExternalPlatformService {

    private const val TAG = "AmazonMusicService"
    override val platformName: String = "Amazon Music"

    private val SHARE_ES_REGEX = Regex(
        """(?:Echa un vistazo a|Escucha)\s+(.+)\s+de\s+(.+?)\s+en Amazon Music""",
        RegexOption.IGNORE_CASE
    )
    private val SHARE_EN_REGEX = Regex(
        """(?:Check out|Listen to)\s+(.+)\s+by\s+(.+?)\s+on Amazon Music""",
        RegexOption.IGNORE_CASE
    )
    private val FALLBACK_ES_REGEX = Regex(
        """(.+)\s+de\s+(.+?)\s+en Amazon Music""",
        RegexOption.IGNORE_CASE
    )
    private val FALLBACK_EN_REGEX = Regex(
        """(.+)\s+by\s+(.+?)\s+on Amazon Music""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean {
        return url.contains("music.amazon.") ||
            url.contains("amazon.es/music") ||
            url.contains("amazon.com/music") ||
            (url.contains("amzn.") && url.contains("Amazon Music", ignoreCase = true))
    }

    private fun cleanAmazonField(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("""\[(Explicit|Clean)\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\((Explicit|Clean|Amazon Original)\)""", RegexOption.IGNORE_CASE), "")
            .trim()
            .trim('"', '“', '”', '\'')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractUrlFromText(text: String): String? {
        val matcher = Pattern.compile("""https?://[^\s]+""").matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (canHandle(candidate) || candidate.contains("amzn.")) return candidate
        }
        return null
    }

    override suspend fun resolveUrl(rawUrl: String): ExternalResult = withContext(Dispatchers.IO) {
        val extractedUrl = extractUrlFromText(rawUrl) ?: rawUrl.trim()

        // 1. Si el texto compartido desde la app de Amazon Music incluye Título y Artista
        val esMatch = SHARE_ES_REGEX.find(rawUrl)
        val enMatch = SHARE_EN_REGEX.find(rawUrl)
        val match = esMatch ?: enMatch ?: FALLBACK_ES_REGEX.find(rawUrl) ?: FALLBACK_EN_REGEX.find(rawUrl)

        if (match != null) {
            val rawTitle = cleanAmazonField(match.groupValues[1])
            val rawArtist = cleanAmazonField(match.groupValues[2])
            if (rawTitle.isNotBlank() && rawArtist.isNotBlank()) {
                val itunesMatch = ItunesService.searchCandidates(rawTitle, rawArtist).firstOrNull()
                return@withContext ExternalResult.Track(
                    ExternalTrack(
                        title = itunesMatch?.title ?: rawTitle,
                        artist = itunesMatch?.artist ?: rawArtist,
                        album = itunesMatch?.album ?: "",
                        durationSeconds = 0L,
                        coverUrl = itunesMatch?.artworkUrl600 ?: "",
                        platform = platformName,
                        originalUrl = extractedUrl
                    )
                )
            }
        }

        // 2. Intentar leer metadatos públicos de la página web (si Amazon los expone)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(extractedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "facebookexternalhit/1.1")
                setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val ogTitle = (Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""").find(html)
                ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:title["']""").find(html))
                ?.groupValues?.getOrNull(1)?.trim() ?: ""

            if (ogTitle.isNotBlank() && !ogTitle.equals("Amazon Music", ignoreCase = true)) {
                val parsed = SHARE_ES_REGEX.find(ogTitle)
                    ?: SHARE_EN_REGEX.find(ogTitle)
                    ?: FALLBACK_ES_REGEX.find(ogTitle)
                    ?: FALLBACK_EN_REGEX.find(ogTitle)
                if (parsed != null) {
                    val t = cleanAmazonField(parsed.groupValues[1])
                    val a = cleanAmazonField(parsed.groupValues[2])
                    val itunesMatch = ItunesService.searchCandidates(t, a).firstOrNull()
                    return@withContext ExternalResult.Track(
                        ExternalTrack(
                            title = itunesMatch?.title ?: t,
                            artist = itunesMatch?.artist ?: a,
                            album = itunesMatch?.album ?: "",
                            durationSeconds = 0L,
                            coverUrl = itunesMatch?.artworkUrl600 ?: "",
                            platform = platformName,
                            originalUrl = extractedUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error consultando web de Amazon Music: ${e.message}")
        } finally {
            conn?.disconnect()
        }

        if (extractedUrl.contains("/playlists/", ignoreCase = true) ||
            extractedUrl.contains("/user-playlists/", ignoreCase = true)
        ) {
            return@withContext ExternalResult.Error(
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_amazon_playlist)
            )
        }

        if (extractedUrl.contains("/albums/", ignoreCase = true) && match == null) {
            return@withContext ExternalResult.Error(
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_amazon_album)
            )
        }

        return@withContext ExternalResult.Error(
            com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.ext_amazon_web)
        )
    }
}
