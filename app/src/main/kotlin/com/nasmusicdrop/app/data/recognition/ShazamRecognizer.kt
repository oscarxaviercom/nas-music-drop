package com.nasmusicdrop.app.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.nasmusicdrop.app.data.external.ExternalTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object ShazamRecognizer {

    private const val TAG = "ShazamRecognizer"
    private const val SAMPLE_RATE = 16000
    private const val RECORD_SECONDS = 5.5
    private const val WINDOW_SIZE = 2048
    private const val STEP_SIZE = 128
    private const val BINS_COUNT = 1025
    private const val RING_SIZE = 256
    private const val DATA_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"

    sealed class RecognitionResult {
        data class Success(val track: ExternalTrack) : RecognitionResult()
        data object NoMatch : RecognitionResult()
        data class Error(val message: String) : RecognitionResult()
    }

    private data class FrequencyPeak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedPeakFrequencyBin: Int
    )

    private val hannWindow = FloatArray(WINDOW_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * (i + 1) / (WINDOW_SIZE + 1)))).toFloat()
    }

    private val userAgents = listOf(
        "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 7 Build/TQ3A.230805.001)",
        "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A.231005.007)",
        "Dalvik/2.1.0 (Linux; U; Android 12; Redmi Note 11 Build/SKQ1.211103.001)"
    )

    @SuppressLint("MissingPermission")
    suspend fun recordAndRecognize(
        onListeningProgress: (progress: Float, amplitude: Float) -> Unit,
        onAnalyzing: () -> Unit
    ): RecognitionResult = withContext(Dispatchers.IO) {
        val totalSamples = (SAMPLE_RATE * RECORD_SECONDS).toInt()
        val minBufBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufBytes == AudioRecord.ERROR || minBufBytes == AudioRecord.ERROR_BAD_VALUE) {
            return@withContext RecognitionResult.Error("El micrófono del dispositivo no soporta grabación a 16 kHz.")
        }

        val bufferBytes = max(minBufBytes * 2, SAMPLE_RATE) // ~0.5s buffer
        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: Exception) {
            return@withContext RecognitionResult.Error("No se pudo acceder al micrófono: ${e.localizedMessage ?: "permiso denegado"}")
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@withContext RecognitionResult.Error("No se pudo inicializar el micrófono.")
        }

        val samples = ShortArray(totalSamples)
        var offset = 0
        val chunkBuf = ShortArray(1024)

        try {
            audioRecord.startRecording()
            while (offset < totalSamples) {
                currentCoroutineContext().ensureActive()
                val toRead = min(chunkBuf.size, totalSamples - offset)
                val read = audioRecord.read(chunkBuf, 0, toRead)
                if (read > 0) {
                    System.arraycopy(chunkBuf, 0, samples, offset, read)
                    offset += read

                    var peakAmp = 0
                    for (i in 0 until read) {
                        val v = abs(chunkBuf[i].toInt())
                        if (v > peakAmp) peakAmp = v
                    }
                    val normAmp = (peakAmp / 12000f).coerceIn(0f, 1f)
                    val prog = (offset.toFloat() / totalSamples.toFloat()).coerceIn(0f, 1f)
                    onListeningProgress(prog, normAmp)
                } else if (read < 0) {
                    break
                }
            }
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            try {
                audioRecord.release()
            } catch (_: Exception) {
            }
        }

        currentCoroutineContext().ensureActive()
        onAnalyzing()

        val recorded = if (offset == totalSamples) samples else samples.copyOf(offset)
        if (recorded.size < SAMPLE_RATE * 2) {
            return@withContext RecognitionResult.Error("Grabación demasiado corta para reconocer la canción.")
        }

        return@withContext try {
            val signatureUri = buildSignatureUri(recorded)
            queryShazam(signatureUri, recorded.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error en reconocimiento: ${e.message}", e)
            RecognitionResult.Error("Error al consultar el servicio de reconocimiento: ${e.localizedMessage ?: "sin conexión"}")
        }
    }

    private fun buildSignatureUri(pcmSamples: ShortArray): String {
        val ringSamples = ShortArray(WINDOW_SIZE)
        var ringIdx = 0
        val fftInput = DoubleArray(WINDOW_SIZE)
        val fftImag = DoubleArray(WINDOW_SIZE)

        val fftOutputs = Array(RING_SIZE) { FloatArray(BINS_COUNT) }
        var fftOutIdx = 0

        val spreadOutputs = Array(RING_SIZE) { FloatArray(BINS_COUNT) }
        var spreadOutIdx = 0
        var numSpreadDone = 0

        val bandPeaks = Array(4) { mutableListOf<FrequencyPeak>() }

        var pos = 0
        while (pos + STEP_SIZE <= pcmSamples.size) {
            // 1. Copy 128 samples into ring buffer
            for (i in 0 until STEP_SIZE) {
                ringSamples[ringIdx + i] = pcmSamples[pos + i]
            }
            ringIdx = (ringIdx + STEP_SIZE) and (WINDOW_SIZE - 1)

            // 2. Reorder & apply Hann window
            for (i in 0 until WINDOW_SIZE) {
                val sample = ringSamples[(i + ringIdx) and (WINDOW_SIZE - 1)].toDouble()
                fftInput[i] = sample * hannWindow[i]
                fftImag[i] = 0.0
            }

            // 3. In-place 2048-point FFT
            fftRadix2(fftInput, fftImag)

            val realFft = fftOutputs[fftOutIdx]
            val scale = (1 shl 17).toDouble()
            for (b in 0 until BINS_COUNT) {
                val power = (fftInput[b] * fftInput[b] + fftImag[b] * fftImag[b]) / scale
                realFft[b] = max(1e-10, power).toFloat()
            }
            fftOutIdx = (fftOutIdx + 1) and 0xFF

            // 4. Peak spreading
            val prevFft = fftOutputs[(fftOutIdx - 1) and 0xFF]
            val spread = spreadOutputs[spreadOutIdx]
            System.arraycopy(prevFft, 0, spread, 0, BINS_COUNT)

            for (p in 0..1022) {
                spread[p] = max(spread[p], max(spread[p + 1], spread[p + 2]))
            }
            val spreadCopy = spread.copyOf()
            for (p in 0..1024) {
                for (formerOffset in intArrayOf(1, 3, 6)) {
                    val former = spreadOutputs[(spreadOutIdx - formerOffset) and 0xFF]
                    if (spreadCopy[p] > former[p]) {
                        former[p] = spreadCopy[p]
                    }
                }
            }
            spreadOutIdx = (spreadOutIdx + 1) and 0xFF
            numSpreadDone++

            // 5. Peak recognition
            if (numSpreadDone >= 46) {
                val fftMinus46 = fftOutputs[(fftOutIdx - 46) and 0xFF]
                val fftMinus49 = spreadOutputs[(spreadOutIdx - 49) and 0xFF]
                val neighborOffsets = intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)
                val timeOffsets = intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249)

                for (binPos in 10..1014) {
                    val centerVal = fftMinus46[binPos]
                    if (centerVal >= (1.0f / 64.0f) && centerVal >= fftMinus49[binPos - 1]) {
                        var maxNeighbor = 0.0f
                        for (off in neighborOffsets) {
                            val v = fftMinus49[binPos + off]
                            if (v > maxNeighbor) maxNeighbor = v
                        }

                        if (centerVal > maxNeighbor) {
                            var maxTimeNeighbor = maxNeighbor
                            for (tOff in timeOffsets) {
                                val otherFft = spreadOutputs[(spreadOutIdx + tOff) and 0xFF]
                                val v = otherFft[binPos - 1]
                                if (v > maxTimeNeighbor) maxTimeNeighbor = v
                            }

                            if (centerVal > maxTimeNeighbor) {
                                val passNum = numSpreadDone - 46
                                val mag = max(1.0 / 64.0, ln(centerVal.toDouble())) * 1477.3 + 6144.0
                                val magBefore = max(1.0 / 64.0, ln(fftMinus46[binPos - 1].toDouble())) * 1477.3 + 6144.0
                                val magAfter = max(1.0 / 64.0, ln(fftMinus46[binPos + 1].toDouble())) * 1477.3 + 6144.0

                                val var1 = mag * 2.0 - magBefore - magAfter
                                if (var1 <= 0.0) continue
                                val var2 = (magAfter - magBefore) * 32.0 / var1
                                val correctedBin = (binPos * 64 + var2.toInt()) and 0xFFFF

                                val freqHz = (correctedBin * (SAMPLE_RATE / 2.0 / 1024.0 / 64.0)).toInt()
                                val band = when (freqHz) {
                                    in 250..519 -> 0
                                    in 520..1449 -> 1
                                    in 1450..3499 -> 2
                                    in 3500..5500 -> 3
                                    else -> continue
                                }
                                bandPeaks[band].add(
                                    FrequencyPeak(
                                        fftPassNumber = passNum,
                                        peakMagnitude = mag.toInt().coerceIn(0, 65535),
                                        correctedPeakFrequencyBin = correctedBin
                                    )
                                )
                            }
                        }
                    }
                }
            }
            pos += STEP_SIZE
        }

        val binary = encodeSignatureToBinary(pcmSamples.size, bandPeaks)
        return DATA_URI_PREFIX + Base64.encodeToString(binary, Base64.NO_WRAP)
    }

    private fun encodeSignatureToBinary(
        numberSamples: Int,
        bandPeaks: Array<MutableList<FrequencyPeak>>
    ): ByteArray {
        val bodyOut = ByteArrayOutputStream()

        // First chunk: 0x40000000 + size_minus_header (placeholder 0)
        bodyOut.write(intLe(0x40000000))
        bodyOut.write(intLe(0))

        for (band in 0 until 4) {
            val peaks = bandPeaks[band]
            if (peaks.isEmpty()) continue

            val peaksOut = ByteArrayOutputStream()
            var lastPass = 0
            for (peak in peaks) {
                if (peak.fftPassNumber - lastPass >= 255) {
                    peaksOut.write(0xFF)
                    peaksOut.write(intLe(peak.fftPassNumber))
                    lastPass = peak.fftPassNumber
                }
                peaksOut.write((peak.fftPassNumber - lastPass) and 0xFF)
                peaksOut.write(shortLe(peak.peakMagnitude))
                peaksOut.write(shortLe(peak.correctedPeakFrequencyBin))
                lastPass = peak.fftPassNumber
            }

            val peaksBytes = peaksOut.toByteArray()
            bodyOut.write(intLe(0x60030040 + band))
            bodyOut.write(intLe(peaksBytes.size))
            bodyOut.write(peaksBytes)
            val pad = (4 - (peaksBytes.size % 4)) % 4
            repeat(pad) { bodyOut.write(0) }
        }

        val bodyBytes = bodyOut.toByteArray()
        val sizeMinusHeader = bodyBytes.size
        // Patch size_minus_header inside first chunk (offset 4 of bodyBytes)
        System.arraycopy(intLe(sizeMinusHeader), 0, bodyBytes, 4, 4)

        // Build 48-byte header
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0xcafe2580.toInt()) // magic1
        header.putInt(0) // crc32 placeholder
        header.putInt(sizeMinusHeader)
        header.putInt(0x94119c00.toInt()) // magic2
        header.putInt(0)
        header.putInt(0)
        header.putInt(0)
        header.putInt(3 shl 27) // 16000 Hz
        header.putInt(0)
        header.putInt(0)
        header.putInt(numberSamples + (SAMPLE_RATE * 0.24).toInt())
        header.putInt((15 shl 19) + 0x40000)

        val full = ByteArray(48 + bodyBytes.size)
        System.arraycopy(header.array(), 0, full, 0, 48)
        System.arraycopy(bodyBytes, 0, full, 48, bodyBytes.size)

        val crc = CRC32()
        crc.update(full, 8, full.size - 8)
        System.arraycopy(intLe(crc.value.toInt()), 0, full, 4, 4)
        return full
    }

    private fun queryShazam(signatureUri: String, numberSamples: Int): RecognitionResult {
        val timestampMs = System.currentTimeMillis()
        val sampleMs = (numberSamples.toDouble() / SAMPLE_RATE.toDouble() * 1000.0).toInt()

        val payload = JSONObject().apply {
            put("geolocation", JSONObject().apply {
                put("altitude", 300)
                put("latitude", 45)
                put("longitude", 2)
            })
            put("signature", JSONObject().apply {
                put("samplems", sampleMs)
                put("timestamp", timestampMs)
                put("uri", signatureUri)
            })
            put("timestamp", timestampMs)
            put("timezone", "Europe/Madrid")
        }.toString()

        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString()
        val endpoint = "https://amp.shazam.com/discovery/v5/es/ES/android/-/tag/$uuid1/$uuid2" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Language", "es_ES")
            setRequestProperty("User-Agent", userAgents.random())
        }

        val responseText = try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == 429) {
                return RecognitionResult.Error("Demasiadas peticiones seguidas. Espera unos segundos e inténtalo de nuevo.")
            }
            if (code !in 200..299) {
                return RecognitionResult.Error("El servidor de reconocimiento respondió con código $code.")
            }

            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(responseText)
        val trackObj = json.optJSONObject("track") ?: return RecognitionResult.NoMatch

        val title = trackObj.optString("title", "").trim()
        val artist = trackObj.optString("subtitle", "").trim()
        if (title.isBlank() || artist.isBlank()) {
            return RecognitionResult.NoMatch
        }

        val imagesObj = trackObj.optJSONObject("images")
        val coverUrl = imagesObj?.optString("coverarthq", "")?.takeIf { it.isNotBlank() }
            ?: imagesObj?.optString("coverart", "")?.takeIf { it.isNotBlank() }
            ?: ""

        var album = ""
        val sections = trackObj.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val sec = sections.optJSONObject(i) ?: continue
                val meta = sec.optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val m = meta.optJSONObject(j) ?: continue
                    val metaTitle = m.optString("title", "")
                    if (metaTitle.equals("Álbum", ignoreCase = true) || metaTitle.equals("Album", ignoreCase = true)) {
                        album = m.optString("text", "").trim()
                        break
                    }
                }
                if (album.isNotBlank()) break
            }
        }

        val shareUrl = trackObj.optString("url", "")
        return RecognitionResult.Success(
            ExternalTrack(
                title = title,
                artist = artist,
                album = album,
                durationSeconds = 0L,
                coverUrl = coverUrl,
                platform = "Reconocimiento de audio",
                originalUrl = shareUrl
            )
        )
    }

    /**
     * Cooley-Tukey iterative radix-2 FFT in-place (n must be power of 2 = 2048)
     */
    private fun fftRadix2(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until half) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + half] * wR - imag[i + k + half] * wI
                    val vI = real[i + k + half] * wI + imag[i + k + half] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + half] = uR - vR
                    imag[i + k + half] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun shortLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte()
    )
}
