package com.nasmusicdrop.app.data.metadata

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object LrclibService {

    private const val TAG = "LrclibService"

    data class LyricsResult(
        val lyrics: String?,
        val syncedLyrics: String? = null,
        val isSynced: Boolean = false
    )

    data class LyricsCandidate(
        val trackName: String,
        val artistName: String,
        val albumName: String = "",
        val durationSeconds: Int = 0,
        val lyrics: String,
        val syncedLyrics: String? = null,
        val isSynced: Boolean = false
    )

    suspend fun fetchLyrics(trackName: String, artistName: String): LyricsResult = withContext(Dispatchers.IO) {
        val cleanTrack = cleanTitle(trackName)
        val cleanArtist = cleanArtistName(artistName)

        // 1. Intentar /api/get exacto
        val directRes = queryGet(cleanTrack, cleanArtist)
        if (directRes != null) return@withContext directRes

        // 2. Intentar /api/search?track_name=...&artist_name=...
        val searchParamRes = querySearch("track_name=${URLEncoder.encode(cleanTrack, "UTF-8")}&artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}")
        if (searchParamRes != null) return@withContext searchParamRes

        // 3. Intentar /api/search?q=... (búsqueda difusa completa)
        val fuzzyQuery = URLEncoder.encode("$cleanArtist $cleanTrack", "UTF-8")
        val qRes = querySearch("q=$fuzzyQuery")
        if (qRes != null) return@withContext qRes

        // 4. Reintentar sin "(feat. ...)" en título y solo con el artista principal (muy común en Amazon Music / Spotify)
        val baseTrack = stripFeaturing(cleanTrack)
        val primaryArtist = extractPrimaryArtist(cleanArtist)
        if (baseTrack != cleanTrack || primaryArtist != cleanArtist) {
            queryGet(baseTrack, primaryArtist)?.let { return@withContext it }
            querySearch("track_name=${URLEncoder.encode(baseTrack, "UTF-8")}&artist_name=${URLEncoder.encode(primaryArtist, "UTF-8")}")?.let { return@withContext it }
            querySearch("q=${URLEncoder.encode("$primaryArtist $baseTrack", "UTF-8")}")?.let { return@withContext it }
        }

        // 5. Fallback con título sin limpiar por si tuviera pistas adicionales
        if (trackName != cleanTrack) {
            val fallbackQuery = URLEncoder.encode("$primaryArtist $trackName", "UTF-8")
            val fallbackRes = querySearch("q=$fallbackQuery")
            if (fallbackRes != null) return@withContext fallbackRes
        }

        Log.d(TAG, "No se encontraron letras en LRCLIB para: $cleanArtist - $cleanTrack")
        return@withContext LyricsResult(null, null, false)
    }

    private fun stripFeaturing(title: String): String {
        return title
            .replace(Regex("""\((?:feat|ft|with|con)\b[^\)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[(?:feat|ft|with|con)\b[^\]]*\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractPrimaryArtist(artist: String): String {
        return artist
            .split(Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b|\by\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: artist
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\b(official\s+video|official\s+music\s+video|video\s+oficial|videoclip|audio\s+oficial|letra|lyrics)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(remastered|remaster|hd|hq|4k|1080p)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .replace(Regex("""\((?!feat|ft)[^\)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun queryGet(track: String, artist: String): LyricsResult? {
        var conn: HttpURLConnection? = null
        try {
            val qTrack = URLEncoder.encode(track, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")
            val url = URL("https://lrclib.net/api/get?artist_name=$qArtist&track_name=$qTrack")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "NASMusicDrop/1.0 (Android)")
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return parseLyricsJson(JSONObject(body))
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryGet falló: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun querySearch(queryString: String): LyricsResult? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://lrclib.net/api/search?$queryString")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "NASMusicDrop/1.0 (Android)")
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    // Tomar el primer resultado con letras disponibles
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val res = parseLyricsJson(obj)
                        if (res != null) return res
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "querySearch falló: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return null
    }

    private fun parseLyricsJson(obj: JSONObject): LyricsResult? {
        val synced = obj.optString("syncedLyrics", "").trim()
        val plain = obj.optString("plainLyrics", "").trim()

        if (synced.isNotBlank() && plain.isNotBlank()) {
            return LyricsResult(lyrics = plain, syncedLyrics = synced, isSynced = true)
        }
        if (synced.isNotBlank()) {
            // Generar versión limpia sin timestamps para USLT
            val cleanPlain = synced.lines()
                .map { it.replace(Regex("""^\[\d{2}:\d{2}\.\d{2,3}\]\s*"""), "").trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            return LyricsResult(lyrics = cleanPlain.ifBlank { synced }, syncedLyrics = synced, isSynced = true)
        }
        if (plain.isNotBlank()) {
            return LyricsResult(lyrics = plain, syncedLyrics = null, isSynced = false)
        }
        return null
    }

    suspend fun searchLyricsCandidates(query: String): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val list = mutableListOf<LyricsCandidate>()
        if (query.isBlank()) return@withContext list
        var conn: HttpURLConnection? = null
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = URL("https://lrclib.net/api/search?q=$encodedQuery")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "MusicDrop/1.0 (Android)")
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val parsed = parseLyricsJson(obj)
                    if (parsed != null && !parsed.lyrics.isNullOrBlank()) {
                        list.add(
                            LyricsCandidate(
                                trackName = obj.optString("trackName", ""),
                                artistName = obj.optString("artistName", ""),
                                albumName = obj.optString("albumName", ""),
                                durationSeconds = obj.optInt("duration", 0),
                                lyrics = parsed.lyrics,
                                syncedLyrics = parsed.syncedLyrics,
                                isSynced = parsed.isSynced
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "searchLyricsCandidates error: ${e.message}")
        } finally {
            conn?.disconnect()
        }
        return@withContext list
    }
}
