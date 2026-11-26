package com.nasmusicdrop.app.data.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SimpleDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            this.requestMethod = httpMethod
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            headers.forEach { (k, v) ->
                v.forEach { setRequestProperty(k, it) }
            }
            if (dataToSend != null && dataToSend.isNotEmpty()) {
                doOutput = true
                outputStream.use { it.write(dataToSend) }
            }
        }

        try {
            val responseCode = conn.responseCode
            val responseMessage = conn.responseMessage ?: ""
            val responseHeaders = conn.headerFields.filterKeys { it != null }

            val inputStream: InputStream = if (responseCode in 200..399) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            val responseBody = inputStream.bufferedReader().use { it.readText() }

            return Response(responseCode, responseMessage, responseHeaders, responseBody, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }
}

object YouTubeExtractor {

    data class VideoInfo(
        val videoId: String,
        val rawTitle: String,
        val channelAuthor: String,
        val cleanTitle: String,
        val cleanArtist: String,
        val thumbnailUrl: String
    )

    data class AudioDownloadResult(
        val success: Boolean,
        val tempAudioFile: File? = null,
        val mimeType: String = "audio/mp4",
        val error: String? = null
    )

    private data class CachedAudioStream(
        val url: String,
        val mimeType: String,
        val bitrateKbps: Int,
        val cachedAtMs: Long = System.currentTimeMillis()
    )

    private val streamCache = ConcurrentHashMap<String, CachedAudioStream>()
    private const val STREAM_CACHE_TTL_MS = 10 * 60 * 1000L

    private fun getCachedStream(key: String): CachedAudioStream? {
        val entry = streamCache[key] ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs > STREAM_CACHE_TTL_MS) {
            streamCache.remove(key)
            return null
        }
        return entry
    }

    private fun cacheStream(key: String, url: String, mimeType: String, bitrateKbps: Int) {
        if (url.isNotBlank()) {
            streamCache[key] = CachedAudioStream(url, mimeType, bitrateKbps)
        }
    }

    @Volatile
    private var isNewPipeInit = false

    @Synchronized
    private fun ensureNewPipeInit() {
        if (!isNewPipeInit) {
            NewPipe.init(SimpleDownloader())
            isNewPipeInit = true
        }
    }

    fun cleanTrackTitle(rawTitle: String, artist: String = ""): String {
        var clean = rawTitle.trim()

        // Si el título empieza con "Artista - ", quitarlo
        if (artist.isNotBlank() && clean.startsWith("$artist - ", ignoreCase = true)) {
            clean = clean.substring("$artist - ".length).trim()
        } else if (artist.isNotBlank() && clean.startsWith("$artist — ", ignoreCase = true)) {
            clean = clean.substring("$artist — ".length).trim()
        }

        // 1. Quitar corchetes que contengan palabras típicas de YouTube: [Official Video], [HD], etc.
        clean = clean.replace(Regex("""\[\s*(official\s*(music\s*)?video|video\s*oficial|videoclip|audio\s*oficial|audio|lyrics?|letra|remaster(ed)?(\s*\d{4})?|hd|4k|1080p|hq|live|en\s*vivo|visualizer)\s*\]""", RegexOption.IGNORE_CASE), "")

        // 2. Quitar paréntesis que contengan palabras típicas de YouTube: (Official Video), (2021 Remaster), etc.
        clean = clean.replace(Regex("""\(\s*(official\s*(music\s*)?video|video\s*oficial|videoclip|audio\s*oficial|audio|lyrics?|letra|remaster(ed)?(\s*\d{4})?|\d{4}\s*remaster|hd|4k|1080p|hq|visualizer|official\s*audio)\s*\)""", RegexOption.IGNORE_CASE), "")

        // 3. Quitar expresiones sueltas al final o en límites de palabras
        clean = clean.replace(Regex("""\b(official\s+video|official\s+music\s+video|video\s+oficial|videoclip\s+oficial|videoclip|audio\s+oficial|official\s+audio)\b""", RegexOption.IGNORE_CASE), "")
        clean = clean.replace(Regex("""\b(remastered\s+\d{4}|remastered|remaster\s+\d{4}|full\s+hd|1080p|4k\s+video)\b""", RegexOption.IGNORE_CASE), "")

        // 4. Quitar corchetes o paréntesis que hayan quedado vacíos
        clean = clean.replace("()", "").replace("[]", "").replace("{}", "")

        // 5. Quitar comillas que envuelven el título ("Yellow" -> Yellow) y guiones residuales
        clean = clean.trim(' ', '"', '\'', '“', '”', '«', '»', '-')

        // 6. Normalizar espacios duplicados
        clean = clean.replace(Regex("""\s+"""), " ").trim()

        return if (clean.isBlank()) rawTitle else clean
    }

    fun cleanArtistName(rawArtist: String): String {
        return rawArtist
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
            .trim(' ', '"', '\'', '“', '”')
    }

    fun extractPlaylistId(rawText: String): String? {
        val pattern = Regex("""[?&]list=([a-zA-Z0-9_-]+)""")
        val match = pattern.find(rawText)
        if (match != null && match.groupValues.size > 1) {
            val listId = match.groupValues[1]
            if (listId != "WL" && listId != "LL" && listId.length > 2) {
                return listId
            }
        }
        return null
    }

    suspend fun fetchPlaylist(playlistId: String): com.nasmusicdrop.app.data.model.PlaylistData? = withContext(Dispatchers.IO) {
        try {
            ensureNewPipeInit()
            val playlistUrl = "https://www.youtube.com/playlist?list=$playlistId"
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
            val title = info.name ?: "Lista de YouTube"
            val uploader = info.uploaderName ?: ""
            val items = mutableListOf<com.nasmusicdrop.app.data.model.PlaylistItem>()

            for (stream in info.relatedItems) {
                val vid = extractVideoId(stream.url) ?: continue
                val thumb = stream.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$vid/hqdefault.jpg"
                val cleanedName = cleanTrackTitle(stream.name ?: "Canción", stream.uploaderName ?: uploader)
                items.add(
                    com.nasmusicdrop.app.data.model.PlaylistItem(
                        videoId = vid,
                        title = cleanedName,
                        uploader = cleanArtistName(stream.uploaderName ?: uploader),
                        thumbnailUrl = thumb,
                        durationSeconds = stream.duration
                    )
                )
            }

            return@withContext com.nasmusicdrop.app.data.model.PlaylistData(
                playlistId = playlistId,
                title = title,
                uploader = uploader,
                items = items
            )
        } catch (e: Exception) {
            return@withContext null
        }
    }

    fun extractVideoId(rawText: String): String? {
        val patterns = listOf(
            Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/|music\.youtube\.com/watch\?v=)([a-zA-Z0-9_-]{11})"""),
            Regex("""[?&]v=([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})""")
        )
        for (pattern in patterns) {
            val match = pattern.find(rawText)
            if (match != null && match.groupValues.size > 1) {
                return match.groupValues[1]
            }
        }
        val trimmed = rawText.trim()
        if (trimmed.length == 11 && trimmed.matches(Regex("""[a-zA-Z0-9_-]{11}"""))) {
            return trimmed
        }
        return null
    }

    suspend fun fetchVideoInfo(videoId: String): VideoInfo = withContext(Dispatchers.IO) {
        var title = ""
        var author = ""
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        // 1. Intento ultrarrápido con oEmbed oficial de YouTube (~150ms)
        try {
            val oembedUrl = "https://www.youtube.com/oembed?url=$videoUrl&format=json"
            val conn = (URL(oembedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            try {
                if (conn.responseCode == 200) {
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    title = json.optString("title", "")
                    author = json.optString("author_name", "").removeSuffix(" - Topic").trim()
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {}

        // 2. Si oEmbed no devolvió título, consultar con NewPipeExtractor
        if (title.isBlank()) {
            try {
                ensureNewPipeInit()
                val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
                title = streamInfo.name ?: "Canción de YouTube"
                author = streamInfo.uploaderName ?: author
                val bestStream = selectBestAudioStream(streamInfo.audioStreams)
                if (bestStream != null) {
                    cacheStream("yt_$videoId", bestStream.content, bestStream.format?.mimeType ?: "audio/mp4", bestStream.averageBitrate / 1000)
                }
            } catch (_: Exception) {
                if (title.isBlank()) title = "Canción de YouTube"
            }
        }

        var parsedArtist = cleanArtistName(author)
        var parsedTitle = title

        if (title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            parsedArtist = cleanArtistName(parts[0])
            parsedTitle = parts[1].trim()
        } else if (title.contains(" — ")) {
            val parts = title.split(" — ", limit = 2)
            parsedArtist = cleanArtistName(parts[0])
            parsedTitle = parts[1].trim()
        }

        parsedTitle = cleanTrackTitle(parsedTitle, parsedArtist)

        val thumb = resolveBestThumbnailUrl(videoId)

        return@withContext VideoInfo(
            videoId = videoId,
            rawTitle = title,
            channelAuthor = author,
            cleanTitle = parsedTitle,
            cleanArtist = parsedArtist,
            thumbnailUrl = thumb
        )
    }

    suspend fun downloadAudioStream(
        context: Context,
        videoId: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): AudioDownloadResult = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "yt_raw_${videoId}_${System.currentTimeMillis()}.tmp")

        try {
            val cached = getCachedStream("yt_$videoId")
            val streamUrl: String
            val mimeType: String
            val bitrateKbps: Int

            if (cached != null) {
                streamUrl = cached.url
                mimeType = cached.mimeType
                bitrateKbps = cached.bitrateKbps
            } else {
                ensureNewPipeInit()
                onProgress(15, "Resolviendo stream oficial con NewPipeExtractor...")

                val videoUrl = "https://www.youtube.com/watch?v=$videoId"
                val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)

                val audioStreams = streamInfo.audioStreams
                if (audioStreams.isNullOrEmpty()) {
                    return@withContext AudioDownloadResult(
                        success = false,
                        error = "No se encontraron streams de audio disponibles para este vídeo."
                    )
                }

                // Preferir AAC (m4a >= 120k) para decodificación hardware ultrarrápida en Android; si no, mayor bitrate
                val selectedStream = selectBestAudioStream(audioStreams) ?: audioStreams[0]
                streamUrl = selectedStream.content
                mimeType = selectedStream.format?.mimeType ?: "audio/mp4"
                bitrateKbps = selectedStream.averageBitrate / 1000
                cacheStream("yt_$videoId", streamUrl, mimeType, bitrateKbps)
            }

            onProgress(30, "Descargando audio de alta fidelidad (${bitrateKbps}k)...")
            return@withContext downloadStreamToFile(streamUrl, mimeType, destFile, onProgress)
        } catch (e: Exception) {
            try { destFile.delete() } catch (_: Exception) {}
            return@withContext AudioDownloadResult(
                success = false,
                error = "Fallo de extracción: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun resolveBestThumbnailUrl(videoId: String): String {
        val maxRes = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(maxRes).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 1500
                readTimeout = 1500
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                return maxRes
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        return "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
    }

    private suspend fun downloadStreamToFile(
        streamUrl: String,
        mimeType: String,
        destFile: File,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): AudioDownloadResult = withContext(Dispatchers.IO) {
        val chunkSize = 1_572_864L // 1.5 MB por bloque para evitar throttling de YouTube CDN
        var downloadedBytes = 0L
        var totalBytes = Regex("""[?&]clen=(\d+)""").find(streamUrl)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
        var attempt = 0
        val maxAttempts = 4
        var appendMode = false

        while (attempt < maxAttempts) {
            attempt++
            val currentOffset = if (destFile.exists()) destFile.length() else 0L
            downloadedBytes = currentOffset
            appendMode = currentOffset > 0L

            if (totalBytes > 0L && currentOffset >= totalBytes) {
                break
            }

            val requestRangeEnd = if (totalBytes > 0L) {
                minOf(currentOffset + chunkSize - 1L, totalBytes - 1L)
            } else {
                currentOffset + chunkSize - 1L
            }

            val streamConn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Range", "bytes=$currentOffset-$requestRangeEnd")
            }

            try {
                val respCode = streamConn.responseCode
                val isPartial = respCode == 206

                if (!isPartial) {
                    // El servidor devolvió 200 (sin soporte Range, ej. SoundCloud): leer flujo completo desde 0
                    downloadedBytes = 0L
                    appendMode = false
                    val contentLen = streamConn.contentLengthLong
                    if (contentLen > 0L) totalBytes = contentLen
                } else {
                    val contentRange = streamConn.getHeaderField("Content-Range")
                    val parsedTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    if (parsedTotal != null && parsedTotal > 0L) {
                        totalBytes = parsedTotal
                    } else if (totalBytes <= 0L && streamConn.contentLengthLong > 0L) {
                        totalBytes = currentOffset + streamConn.contentLengthLong
                    }
                }

                streamConn.inputStream.use { input ->
                    java.io.BufferedOutputStream(FileOutputStream(destFile, appendMode), 65536).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0L) {
                                val pct = 30 + ((downloadedBytes.toDouble() / totalBytes) * 40).toInt()
                                val mb = String.format(java.util.Locale.US, "%.1f", downloadedBytes / (1024.0 * 1024.0))
                                val totalMb = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                onProgress(pct.coerceIn(30, 70), "Descargando audio: $mb / $totalMb MB...")
                            }
                        }
                    }
                }

                // Si era un flujo completo 200 o ya alcanzamos el total de bytes, terminamos
                if (!isPartial || (totalBytes > 0L && downloadedBytes >= totalBytes)) {
                    break
                }

                // Si quedan 2 o más bloques, descargarlos con 2 conexiones Range en paralelo
                if (totalBytes > downloadedBytes + chunkSize) {
                    val remainingRanges = mutableListOf<Pair<Long, Long>>()
                    var cursor = downloadedBytes
                    while (cursor < totalBytes) {
                        val end = minOf(cursor + chunkSize - 1L, totalBytes - 1L)
                        remainingRanges.add(cursor to end)
                        cursor = end + 1L
                    }

                    val nextIdx = AtomicInteger(0)
                    val totalAtomic = AtomicLong(downloadedBytes)
                    val rafLock = Any()
                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.setLength(totalBytes)
                        coroutineScope {
                            val workers = List(2) {
                                async(Dispatchers.IO) {
                                    while (true) {
                                        ensureActive()
                                        val idx = nextIdx.getAndIncrement()
                                        if (idx >= remainingRanges.size) break
                                        val (rStart, rEnd) = remainingRanges[idx]
                                        val bytes = downloadRangeBytesWithRetry(streamUrl, rStart, rEnd) { delta ->
                                            val cur = totalAtomic.addAndGet(delta)
                                            val pct = 30 + ((cur.toDouble() / totalBytes) * 40).toInt()
                                            val mb = String.format(java.util.Locale.US, "%.1f", cur / (1024.0 * 1024.0))
                                            val totalMb = String.format(java.util.Locale.US, "%.1f", totalBytes / (1024.0 * 1024.0))
                                            onProgress(pct.coerceIn(30, 70), "Descargando audio: $mb / $totalMb MB...")
                                        }
                                        synchronized(rafLock) {
                                            raf.seek(rStart)
                                            raf.write(bytes)
                                        }
                                    }
                                }
                            }
                            workers.awaitAll()
                        }
                    }
                    break
                }

                // Si solo queda 1 bloque, continuar secuencialmente
                attempt = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= maxAttempts) {
                    try { destFile.delete() } catch (_: Exception) {}
                    return@withContext AudioDownloadResult(
                        success = false,
                        error = "Fallo en la descarga tras $maxAttempts intentos: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                }
                onProgress(30, "Reanudando descarga (intento ${attempt + 1}/$maxAttempts)...")
            } finally {
                streamConn.disconnect()
            }
        }

        if (destFile.exists() && destFile.length() > 30000) {
            AudioDownloadResult(
                success = true,
                tempAudioFile = destFile,
                mimeType = mimeType
            )
        } else {
            try { destFile.delete() } catch (_: Exception) {}
            AudioDownloadResult(
                success = false,
                error = "El archivo descargado está incompleto o dañado."
            )
        }
    }

    private suspend fun downloadRangeBytesWithRetry(
        streamUrl: String,
        rangeStart: Long,
        rangeEnd: Long,
        onBytesRead: (Long) -> Unit
    ): ByteArray {
        val maxChunkAttempts = 3
        var lastErr: Exception? = null
        for (attempt in 1..maxChunkAttempts) {
            val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                setRequestProperty("Range", "bytes=$rangeStart-$rangeEnd")
            }
            try {
                val baos = ByteArrayOutputStream((rangeEnd - rangeStart + 1).toInt().coerceAtLeast(65536))
                conn.inputStream.use { input ->
                    val buf = ByteArray(65536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        baos.write(buf, 0, read)
                        onBytesRead(read.toLong())
                    }
                }
                return baos.toByteArray()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                if (attempt < maxChunkAttempts) {
                    kotlinx.coroutines.delay(500L * attempt)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("Error descargando bloque $rangeStart-$rangeEnd")
    }

    private fun selectBestAudioStream(
        audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream>?
    ): org.schabi.newpipe.extractor.stream.AudioStream? {
        if (audioStreams.isNullOrEmpty()) return null
        val hardwareAac = audioStreams
            .filter {
                val mime = it.format?.mimeType?.lowercase() ?: ""
                (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) && it.averageBitrate >= 96_000
            }
            .maxByOrNull { it.averageBitrate }
        return hardwareAac ?: audioStreams.maxByOrNull { it.averageBitrate } ?: audioStreams[0]
    }

    suspend fun getAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        getCachedStream("yt_$videoId")?.let { return@withContext it.url }
        try {
            ensureNewPipeInit()
            val videoUrl = "https://www.youtube.com/watch?v=$videoId"
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            val audioStreams = streamInfo.audioStreams
            if (audioStreams.isNullOrEmpty()) return@withContext null
            val bestStream = selectBestAudioStream(audioStreams) ?: audioStreams[0]
            cacheStream("yt_$videoId", bestStream.content, bestStream.format?.mimeType ?: "audio/mp4", bestStream.averageBitrate / 1000)
            return@withContext bestStream.content
        } catch (_: Exception) {
            return@withContext null
        }
    }

    suspend fun searchYouTube(query: String): List<com.nasmusicdrop.app.data.model.SearchResultItem> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        try {
            ensureNewPipeInit()
            val service = ServiceList.YouTube
            val queryHandler = service.searchQHFactory.fromQuery(trimmed)
            val searchInfo = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(service, queryHandler)

            val results = mutableListOf<com.nasmusicdrop.app.data.model.SearchResultItem>()
            for (item in searchInfo.relatedItems) {
                if (item is org.schabi.newpipe.extractor.stream.StreamInfoItem) {
                    val vid = extractVideoId(item.url) ?: continue
                    val thumb = item.thumbnails?.firstOrNull()?.url ?: "https://img.youtube.com/vi/$vid/hqdefault.jpg"
                    val rawTitle = item.name ?: "Sin título"
                    val uploader = cleanArtistName(item.uploaderName ?: "")
                    val cleanT = cleanTrackTitle(rawTitle, uploader)

                    results.add(
                        com.nasmusicdrop.app.data.model.SearchResultItem(
                            videoId = vid,
                            title = rawTitle,
                            cleanTitle = cleanT,
                            uploader = uploader,
                            durationSeconds = item.duration,
                            thumbnailUrl = thumb
                        )
                    )
                }
            }
            return@withContext results
        } catch (_: Exception) {
            return@withContext emptyList()
        }
    }

    fun parseStartTimeSeconds(rawUrl: String): Long {
        val pattern = Regex("""[?&](?:t|start)=([0-9hms]+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(rawUrl) ?: return 0L
        val value = match.groupValues[1]

        if (value.all { it.isDigit() }) {
            return value.toLongOrNull() ?: 0L
        }

        var totalSec = 0L
        val h = Regex("""(\d+)h""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val m = Regex("""(\d+)m""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val s = Regex("""(\d+)s""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        totalSec = h * 3600 + m * 60 + s
        if (totalSec > 0) return totalSec

        return value.removeSuffix("s").removeSuffix("S").toLongOrNull() ?: 0L
    }

    fun parseEndTimeSeconds(rawUrl: String): Long {
        val pattern = Regex("""[?&](?:end|to)=([0-9hms]+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(rawUrl) ?: return 0L
        val value = match.groupValues[1]

        if (value.all { it.isDigit() }) {
            return value.toLongOrNull() ?: 0L
        }

        var totalSec = 0L
        val h = Regex("""(\d+)h""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val m = Regex("""(\d+)m""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val s = Regex("""(\d+)s""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        totalSec = h * 3600 + m * 60 + s
        if (totalSec > 0) return totalSec

        return value.removeSuffix("s").removeSuffix("S").toLongOrNull() ?: 0L
    }

    fun formatTimestamp(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d", m, s)
    }

    fun parseTimestampToSeconds(text: String): Long {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return 0L
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val min = parts[0].toLongOrNull() ?: 0L
                val sec = parts[1].toLongOrNull() ?: 0L
                return min * 60 + sec
            } else if (parts.size == 3) {
                val hrs = parts[0].toLongOrNull() ?: 0L
                val min = parts[1].toLongOrNull() ?: 0L
                val sec = parts[2].toLongOrNull() ?: 0L
                return hrs * 3600 + min * 60 + sec
            }
        }
        return trimmed.toLongOrNull() ?: 0L
    }

    // --- Soporte directo para SoundCloud (metadatos + descarga directa de audio) ---

    fun isSoundCloudUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("soundcloud.com") || lower.contains("snd.sc")
    }

    fun extractSoundCloudUrl(rawText: String): String? {
        val matcher = java.util.regex.Pattern.compile("""https?://[^\s]+""").matcher(rawText)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (isSoundCloudUrl(candidate)) return candidate
        }
        return if (isSoundCloudUrl(rawText)) rawText.trim() else null
    }

    suspend fun resolveSoundCloudUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        var current = extractSoundCloudUrl(rawUrl) ?: rawUrl.trim()
        if (current.contains("on.soundcloud.com") || current.contains("snd.sc")) {
            try {
                var redirects = 0
                while (redirects < 5) {
                    val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 7000
                        readTimeout = 7000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    if (conn.responseCode in 300..399) {
                        val loc = conn.getHeaderField("Location") ?: break
                        current = loc
                        redirects++
                    } else {
                        break
                    }
                }
            } catch (_: Exception) {}
        }
        return@withContext current.substringBefore("?")
    }

    suspend fun fetchSoundCloudInfo(scUrl: String): VideoInfo = withContext(Dispatchers.IO) {
        ensureNewPipeInit()
        val cleanUrl = resolveSoundCloudUrl(scUrl)
        val streamInfo = StreamInfo.getInfo(ServiceList.SoundCloud, cleanUrl)
        val rawTitle = streamInfo.name ?: "Pista de SoundCloud"
        val author = streamInfo.uploaderName ?: "SoundCloud"

        var parsedArtist = cleanArtistName(author)
        var parsedTitle = rawTitle
        if (rawTitle.contains(" - ")) {
            val parts = rawTitle.split(" - ", limit = 2)
            parsedArtist = cleanArtistName(parts[0])
            parsedTitle = parts[1].trim()
        }
        parsedTitle = cleanTrackTitle(parsedTitle, parsedArtist)
        val thumb = streamInfo.thumbnails.lastOrNull()?.url ?: ""

        val progressiveStreams = streamInfo.audioStreams
            ?.filter { it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
            ?: emptyList()
        val selectedStream = progressiveStreams
            .sortedByDescending {
                val mime = it.format?.mimeType?.lowercase() ?: ""
                val formatScore = if (mime.contains("mpeg") || mime.contains("mp4")) 1000000 else 0
                formatScore + it.averageBitrate
            }
            .firstOrNull()
            ?: streamInfo.audioStreams?.firstOrNull()
        if (selectedStream != null && !selectedStream.content.isNullOrBlank()) {
            val scMime = selectedStream.format?.mimeType ?: "audio/mpeg"
            val scBitrate = selectedStream.averageBitrate / 1000
            cacheStream("sc_$scUrl", selectedStream.content, scMime, scBitrate)
            cacheStream("sc_$cleanUrl", selectedStream.content, scMime, scBitrate)
        }

        return@withContext VideoInfo(
            videoId = "sc_${cleanUrl.hashCode()}",
            rawTitle = rawTitle,
            channelAuthor = author,
            cleanTitle = parsedTitle,
            cleanArtist = parsedArtist,
            thumbnailUrl = thumb
        )
    }

    suspend fun fetchSoundCloudPlaylist(scUrl: String): com.nasmusicdrop.app.data.model.PlaylistData? = withContext(Dispatchers.IO) {
        try {
            ensureNewPipeInit()
            val cleanUrl = resolveSoundCloudUrl(scUrl)
            val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(ServiceList.SoundCloud, cleanUrl)
            val items = info.relatedItems.mapNotNull { item ->
                val url = item.url ?: return@mapNotNull null
                val rawT = item.name ?: return@mapNotNull null
                val uploader = cleanArtistName(item.uploaderName ?: "SoundCloud")
                val thumb = item.thumbnails.lastOrNull()?.url ?: ""
                com.nasmusicdrop.app.data.model.PlaylistItem(
                    videoId = url, // Guardamos la URL de la pista de SoundCloud
                    title = rawT,
                    uploader = uploader,
                    thumbnailUrl = thumb
                )
            }
            if (items.isEmpty()) return@withContext null
            return@withContext com.nasmusicdrop.app.data.model.PlaylistData(
                playlistId = cleanUrl,
                title = info.name ?: "Playlist de SoundCloud",
                uploader = info.uploaderName ?: "SoundCloud",
                items = items
            )
        } catch (_: Exception) {
            return@withContext null
        }
    }

    suspend fun downloadSoundCloudAudio(
        context: Context,
        scUrl: String,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): AudioDownloadResult = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "sc_raw_${System.currentTimeMillis()}.tmp")
        try {
            val cached = getCachedStream("sc_$scUrl")
            if (cached != null) {
                onProgress(30, "Descargando audio directo de SoundCloud...")
                return@withContext downloadStreamToFile(cached.url, cached.mimeType, destFile, onProgress)
            }

            ensureNewPipeInit()
            onProgress(15, "Resolviendo audio directo desde SoundCloud...")
            val cleanUrl = resolveSoundCloudUrl(scUrl)
            val streamInfo = StreamInfo.getInfo(ServiceList.SoundCloud, cleanUrl)

            val progressiveStreams = streamInfo.audioStreams
                ?.filter { it.deliveryMethod == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP }
                ?: emptyList()

            val selectedStream = progressiveStreams
                .sortedByDescending {
                    val mime = it.format?.mimeType?.lowercase() ?: ""
                    val formatScore = if (mime.contains("mpeg") || mime.contains("mp4")) 1000000 else 0
                    formatScore + it.averageBitrate
                }
                .firstOrNull()
                ?: streamInfo.audioStreams?.firstOrNull()

            if (selectedStream == null || selectedStream.content.isNullOrBlank()) {
                return@withContext AudioDownloadResult(
                    success = false,
                    error = "Esta pista de SoundCloud no tiene stream directo descargable (solo HLS o restringida)."
                )
            }

            val mimeType = selectedStream.format?.mimeType ?: "audio/mpeg"
            onProgress(30, "Descargando audio directo de SoundCloud...")
            return@withContext downloadStreamToFile(selectedStream.content, mimeType, destFile, onProgress)
        } catch (e: Exception) {
            try { destFile.delete() } catch (_: Exception) {}
            return@withContext AudioDownloadResult(
                success = false,
                error = "Error descargando de SoundCloud: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }
}
