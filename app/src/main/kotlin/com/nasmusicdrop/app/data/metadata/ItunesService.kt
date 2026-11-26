package com.nasmusicdrop.app.data.metadata

import com.nasmusicdrop.app.data.model.SongCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ItunesService {

    suspend fun searchCandidates(
        titleOrQuery: String,
        fallbackArtist: String = ""
    ): List<SongCandidate> = withContext(Dispatchers.IO) {
        val cleanT = cleanSearchQuery(titleOrQuery)
        val cleanA = cleanSearchQuery(fallbackArtist)

        val results = mutableListOf<SongCandidate>()

        // 1. Si tenemos artista y título, buscar la combinación exacta "Artista Título" en iTunes
        if (cleanA.isNotBlank() && cleanT.isNotBlank()) {
            val combined = if (cleanT.contains(cleanA, ignoreCase = true)) cleanT else "$cleanA $cleanT"
            results.addAll(queryItunesMultiStore(combined, limit = 25, expectedArtist = cleanA))
        }

        // 2. Si no hubo resultados o no había artista, buscar sin "(feat. ...)" y con artista principal
        if (results.isEmpty() && cleanT.isNotBlank()) {
            val baseT = cleanT
                .replace(Regex("""\((?:feat|ft|with|con)\b[^\)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            val primaryA = cleanA
                .split(Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bwith\b|\bx\b|\by\b)\s*""", RegexOption.IGNORE_CASE))
                .firstOrNull { it.isNotBlank() }?.trim() ?: cleanA

            if (primaryA.isNotBlank() && (baseT != cleanT || primaryA != cleanA)) {
                results.addAll(queryItunesMultiStore("$primaryA $baseT", limit = 25, expectedArtist = primaryA))
            }
            if (results.isEmpty()) {
                results.addAll(queryItunesMultiStore(baseT.ifBlank { cleanT }, limit = 25, expectedArtist = primaryA))
            }
        }

        // 3. Si aún no hay resultados y tenemos artista, probar solo con el artista
        if (results.isEmpty() && cleanA.isNotBlank()) {
            results.addAll(queryItunesMultiStore(cleanA, limit = 20, expectedArtist = cleanA))
        }

        // Deduplicar por álbum y título
        val unique = results.distinctBy { "${it.title.lowercase()}_${it.album.lowercase()}" }

        // Si tenemos artista especificado, ordenar para priorizar las canciones que coincidan con dicho artista
        if (cleanA.isNotBlank()) {
            return@withContext unique.sortedByDescending {
                val matchesArtist = it.artist.contains(cleanA, ignoreCase = true) || cleanA.contains(it.artist, ignoreCase = true)
                val matchesTitle = cleanT.isNotBlank() && (it.title.contains(cleanT, ignoreCase = true) || cleanT.contains(it.title, ignoreCase = true))
                when {
                    matchesArtist && matchesTitle -> 3
                    matchesArtist -> 2
                    matchesTitle -> 1
                    else -> 0
                }
            }
        }

        return@withContext unique
    }

    suspend fun directSearch(term: String): List<SongCandidate> = withContext(Dispatchers.IO) {
        val clean = cleanSearchQuery(term)
        if (clean.isBlank()) return@withContext emptyList()
        val deviceCountry = java.util.Locale.getDefault().country
            .takeIf { it.length == 2 }?.uppercase() ?: "ES"
        val combined = mutableListOf<SongCandidate>()
        combined.addAll(queryItunesByCountry(clean, limit = 25, countryCode = deviceCountry))
        if (deviceCountry != "ES") {
            combined.addAll(queryItunesByCountry(clean, limit = 25, countryCode = "ES"))
        }
        if (combined.isEmpty() && deviceCountry != "US") {
            combined.addAll(queryItunesByCountry(clean, limit = 25, countryCode = "US"))
        }
        return@withContext combined.distinctBy { "${it.title.lowercase()}_${it.artist.lowercase()}_${it.album.lowercase()}" }
    }

    private suspend fun queryItunesMultiStore(
        searchTerm: String,
        limit: Int = 25,
        expectedArtist: String = ""
    ): List<SongCandidate> = coroutineScope {
        val deviceCountry = java.util.Locale.getDefault().country
            .takeIf { it.length == 2 }?.uppercase() ?: "ES"

        val primary = queryItunesByCountry(searchTerm, limit, deviceCountry)
        val hasArtistMatch = expectedArtist.isBlank() || primary.any {
            it.artist.contains(expectedArtist, ignoreCase = true) ||
                expectedArtist.contains(it.artist, ignoreCase = true)
        }
        if (primary.isNotEmpty() && hasArtistMatch) {
            return@coroutineScope primary
        }

        // Consultar las tiendas de respaldo en paralelo para evitar roundtrips secuenciales
        val fallbackStores = listOf("ES", "DE", "US").filter { it != deviceCountry }
        val extraResults = fallbackStores.map { store ->
            async(Dispatchers.IO) { queryItunesByCountry(searchTerm, limit, store) }
        }.awaitAll().flatten()

        primary + extraResults
    }

    private fun queryItunesByCountry(searchTerm: String, limit: Int, countryCode: String): List<SongCandidate> {
        val candidates = mutableListOf<SongCandidate>()
        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(searchTerm, "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit&country=$countryCode")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                setRequestProperty("User-Agent", "NASMusicDrop/1.1 (Android)")
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val items = root.optJSONArray("results") ?: return emptyList()

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val rawArt = item.optString("artworkUrl100", "")
                    // Transformar a exactamente 600x600bb.jpg (estándar sRGB solicitado)
                    val art600 = if (rawArt.contains("100x100bb")) {
                        rawArt.replace("100x100bb", "600x600bb")
                    } else rawArt

                    val trackName = item.optString("trackName", "")
                    val artistName = item.optString("artistName", "")
                    val albumName = item.optString("collectionName", "")
                    val releaseDate = item.optString("releaseDate", "")
                    val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else ""
                    val trackNumber = item.optInt("trackNumber", 1)
                    val genre = item.optString("primaryGenreName", "")
                    val previewUrl = item.optString("previewUrl", "").takeIf { it.isNotBlank() }

                    if (trackName.isNotBlank() && artistName.isNotBlank()) {
                        candidates.add(
                            SongCandidate(
                                title = trackName,
                                artist = artistName,
                                album = if (albumName.isNotBlank()) albumName else trackName,
                                year = year,
                                trackNumber = trackNumber,
                                genre = genre,
                                artworkUrl600 = art600,
                                previewUrl = previewUrl
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return candidates
    }

    fun cleanSearchQuery(raw: String): String {
        return raw
            .replace(Regex("""\b(official\s+video|official\s+music\s+video|video\s+oficial|videoclip|audio\s+oficial|letra|lyrics)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(remastered|remaster|hd|hq|4k|1080p|full\s+hd|version\s+extendida)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[[^\]]*\]"""), "")
            .replace(Regex("""\((?!feat|ft)[^\)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
