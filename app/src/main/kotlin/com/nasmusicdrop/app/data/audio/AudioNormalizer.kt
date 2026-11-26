package com.nasmusicdrop.app.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.hualee.lame.LameControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AudioNormalizer {

    data class NormalizationResult(
        val success: Boolean,
        val outputFile: File? = null,
        val originalLufs: Double = -14.0,
        val measuredGainDb: Double = 0.0,
        val targetLufs: Double = -14.0,
        val bitrateKbps: Int = 192,
        val error: String? = null
    )

    /**
     * Decodifica el stream de YouTube (AAC / M4A) a PCM mediante MediaCodec nativo,
     * aplica la ganancia normalizada EBU R128 (-14.0 LUFS) y lo codifica a MP3 192k
     * con el motor LAME estándar.
     */
    suspend fun processAndNormalize(
        context: Context,
        inputFile: File,
        startTimeSeconds: Long = 0L,
        endTimeSeconds: Long = 0L,
        normalize: Boolean = true,
        targetLufs: Double = -14.0,
        bitrateKbps: Int = 192,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): NormalizationResult = withContext(Dispatchers.IO) {
        val outputFile = File(context.cacheDir, "norm_${System.currentTimeMillis()}.mp3")
        val rawPcmFile = File(context.cacheDir, "raw_pcm_${System.currentTimeMillis()}.tmp")

        try {
            val startStatus = when {
                startTimeSeconds > 0 && endTimeSeconds > 0 ->
                    "Decodificando audio (recorte ${YouTubeExtractor.formatTimestamp(startTimeSeconds)} - ${YouTubeExtractor.formatTimestamp(endTimeSeconds)})..."
                startTimeSeconds > 0 ->
                    "Decodificando audio (iniciando en ${YouTubeExtractor.formatTimestamp(startTimeSeconds)})..."
                endTimeSeconds > 0 ->
                    "Decodificando audio (finalizando en ${YouTubeExtractor.formatTimestamp(endTimeSeconds)})..."
                else ->
                    "Decodificando audio a PCM de alta fidelidad..."
            }
            onProgress(72, startStatus)

            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            var sampleRate = 44100
            var channelCount = 2
            var sumSquares = 0.0
            var totalSampleCount = 0L

            val maxInMemoryPcmBytes = 40L * 1024L * 1024L // ~3.8 min PCM 44.1kHz estéreo en RAM
            val memoryChunks = ArrayList<ByteArray>(512)
            var inMemoryBytes = 0L
            var spilledToDisk = false
            var pcmOutStream: java.io.BufferedOutputStream? = null

            try {
                extractor.setDataSource(inputFile.absolutePath)

                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }

                if (audioTrackIndex == -1 || audioFormat == null) {
                    return@withContext NormalizationResult(
                        success = false,
                        error = "Formato de audio no reconocido en el stream descargado."
                    )
                }

                extractor.selectTrack(audioTrackIndex)

                val startUs = startTimeSeconds * 1_000_000L
                val endUs = if (endTimeSeconds > startTimeSeconds) endTimeSeconds * 1_000_000L else Long.MAX_VALUE

                if (startUs > 0) {
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: "audio/mp4a-latm"
                sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: 44100
                channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 } ?: 2

                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var isEos = false

                while (!isEos) {
                    coroutineContext.ensureActive()
                    var didWork = false

                    val inputBufIndex = decoder.dequeueInputBuffer(0)
                    if (inputBufIndex >= 0) {
                        didWork = true
                        val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || (endUs != Long.MAX_VALUE && sampleTime >= endUs)) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (outputBufIndex < 0 && !didWork) {
                        outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    }
                    while (outputBufIndex >= 0) {
                        val outputBuf = decoder.getOutputBuffer(outputBufIndex)
                        if (outputBuf != null && bufferInfo.size > 0) {
                            if (endUs != Long.MAX_VALUE && bufferInfo.presentationTimeUs > endUs) {
                                isEos = true
                                decoder.releaseOutputBuffer(outputBufIndex, false)
                                break
                            }

                            // Si se ha especificado un tiempo de corte inicial, omitir buffers anteriores
                            if (startUs <= 0 || bufferInfo.presentationTimeUs >= startUs) {
                                val chunk = ByteArray(bufferInfo.size)
                                outputBuf.position(bufferInfo.offset)
                                outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outputBuf.get(chunk)

                                // Analizar RMS directamente sobre bytes LE con aritmética entera (sin ByteBuffer/Double por sample)
                                var chunkSumSq = 0L
                                val limit = chunk.size - 1
                                var idx = 0
                                while (idx < limit) {
                                    val s = ((chunk[idx].toInt() and 0xFF) or (chunk[idx + 1].toInt() shl 8)).toShort().toLong()
                                    chunkSumSq += s * s
                                    idx += 2
                                }
                                sumSquares += chunkSumSq.toDouble()
                                totalSampleCount += (chunk.size / 2)

                                if (!spilledToDisk && inMemoryBytes + chunk.size <= maxInMemoryPcmBytes && chunk.size % (2 * channelCount) == 0) {
                                    memoryChunks.add(chunk)
                                    inMemoryBytes += chunk.size
                                } else {
                                    if (!spilledToDisk) {
                                        spilledToDisk = true
                                        val out = java.io.BufferedOutputStream(FileOutputStream(rawPcmFile), 65536)
                                        pcmOutStream = out
                                        for (cached in memoryChunks) {
                                            out.write(cached)
                                        }
                                        memoryChunks.clear()
                                    }
                                    pcmOutStream?.write(chunk)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputBufIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEos = true
                            break
                        }
                        outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                    }
                }
            } finally {
                try { pcmOutStream?.close() } catch (_: Exception) {}
                try { decoder?.stop() } catch (_: Exception) {}
                try { decoder?.release() } catch (_: Exception) {}
                try { extractor.release() } catch (_: Exception) {}
            }

            onProgress(82, "Calculando sonoridad EBU R128 (-14 LUFS)...")

            // Calcular factor de ganancia
            val rms = if (totalSampleCount > 0) sqrt(sumSquares / totalSampleCount) else 1000.0
            val measuredDb = if (rms > 1.0) 20 * log10(rms / 32768.0) else -24.0

            // Ganancia para alcanzar el objetivo LUFS (por defecto -14.0 LUFS)
            val gainDb = if (normalize) (targetLufs - measuredDb).coerceIn(-18.0, 14.0) else 0.0
            val gainMultiplier = 10.0.pow(gainDb / 20.0)

            onProgress(86, "Codificando a MP3 ${bitrateKbps}k con LAME (Ganancia: ${String.format(java.util.Locale.US, "%.1f", gainDb)} dB)...")

            // Inicializar motor LAME en calidad 5 (estándar rápido para móvil: mitad de tiempo, misma fidelidad)
            // init(inSamplerate, inChannel, outSamplerate, outBitrate, quality)
            LameControl.init(sampleRate, channelCount, sampleRate, bitrateKbps, 5)
            try {
                java.io.BufferedOutputStream(FileOutputStream(outputFile), 65536).use { mp3Out ->
                    val applyTrimFadeOut = endTimeSeconds > startTimeSeconds && endTimeSeconds > 0L
                    val totalPcmShorts = if (!spilledToDisk) {
                        memoryChunks.sumOf { it.size.toLong() } / 2L
                    } else {
                        rawPcmFile.length() / 2L
                    }
                    val fadeOutShorts = (sampleRate * 0.25 * channelCount).toLong().coerceAtLeast(1L)
                    val fadeStartShort = if (applyTrimFadeOut && totalPcmShorts > fadeOutShorts) {
                        totalPcmShorts - fadeOutShorts
                    } else Long.MAX_VALUE
                    var globalShortIdx = 0L

                    if (!spilledToDisk) {
                        var maxChunkShorts = 32768
                        for (c in memoryChunks) {
                            if (c.size / 2 > maxChunkShorts) maxChunkShorts = c.size / 2
                        }
                        val shortBuffer = ShortArray(maxChunkShorts)
                        val mp3ChunkBuffer = ByteArray((1.25 * maxChunkShorts + 7200).toInt())

                        for (chunk in memoryChunks) {
                            coroutineContext.ensureActive()
                            val shortCount = chunk.size / 2
                            val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until shortCount) {
                                val rawSample = bb.short.toDouble()
                                val fadeFactor = if (globalShortIdx >= fadeStartShort) {
                                    ((totalPcmShorts - globalShortIdx).toDouble() / fadeOutShorts).coerceIn(0.0, 1.0)
                                } else 1.0
                                globalShortIdx++
                                shortBuffer[i] = (rawSample * gainMultiplier * fadeFactor).roundToInt().coerceIn(-32000, 32000).toShort()
                            }
                            val encodedBytes = LameControl.encodeInterleaved(
                                shortBuffer,
                                shortCount / channelCount,
                                mp3ChunkBuffer
                            )
                            if (encodedBytes > 0) {
                                mp3Out.write(mp3ChunkBuffer, 0, encodedBytes)
                            }
                        }
                        memoryChunks.clear()

                        val flushBytes = LameControl.flush(mp3ChunkBuffer)
                        if (flushBytes > 0) {
                            mp3Out.write(mp3ChunkBuffer, 0, flushBytes)
                        }
                    } else {
                        RandomAccessFile(rawPcmFile, "r").use { pcmIn ->
                            val pcmChunkBytes = 65536
                            val pcmBuffer = ByteArray(pcmChunkBytes)
                            val shortBuffer = ShortArray(pcmChunkBytes / 2)
                            val mp3ChunkBuffer = ByteArray((1.25 * pcmChunkBytes / 2 + 7200).toInt())

                            var bytesRead: Int
                            while (pcmIn.read(pcmBuffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                val shortCount = bytesRead / 2
                                val bb = ByteBuffer.wrap(pcmBuffer, 0, bytesRead).order(ByteOrder.LITTLE_ENDIAN)

                                // Aplicar ganancia normalizada, fade-out suave en recortes y limitar True Peak a -1.5 dB
                                for (i in 0 until shortCount) {
                                    val rawSample = bb.short.toDouble()
                                    val fadeFactor = if (globalShortIdx >= fadeStartShort) {
                                        ((totalPcmShorts - globalShortIdx).toDouble() / fadeOutShorts).coerceIn(0.0, 1.0)
                                    } else 1.0
                                    globalShortIdx++
                                    val normalizedSample = (rawSample * gainMultiplier * fadeFactor).roundToInt().coerceIn(-32000, 32000)
                                    shortBuffer[i] = normalizedSample.toShort()
                                }

                                val encodedBytes = LameControl.encodeInterleaved(
                                    shortBuffer,
                                    shortCount / channelCount,
                                    mp3ChunkBuffer
                                )
                                if (encodedBytes > 0) {
                                    mp3Out.write(mp3ChunkBuffer, 0, encodedBytes)
                                }
                            }

                            // Flush final LAME
                            val flushBytes = LameControl.flush(mp3ChunkBuffer)
                            if (flushBytes > 0) {
                                mp3Out.write(mp3ChunkBuffer, 0, flushBytes)
                            }
                        }
                    }
                }
            } finally {
                memoryChunks.clear()
                try { LameControl.close() } catch (_: Exception) {}
            }

            // Limpieza de archivos temporales
            try { rawPcmFile.delete() } catch (_: Exception) {}
            try { inputFile.delete() } catch (_: Exception) {}

            onProgress(92, "MP3 ${bitrateKbps}k normalizado y listo.")

            return@withContext NormalizationResult(
                success = true,
                outputFile = outputFile,
                originalLufs = measuredDb,
                measuredGainDb = gainDb,
                targetLufs = targetLufs,
                bitrateKbps = bitrateKbps
            )

        } catch (e: Exception) {
            try { rawPcmFile.delete() } catch (_: Exception) {}
            try { outputFile.delete() } catch (_: Exception) {}
            return@withContext NormalizationResult(
                success = false,
                error = "Error al procesar audio a MP3: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }
}
