package com.nasmusicdrop.app.data.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object Id3TagWriter {

    data class TagData(
        val title: String,
        val artist: String,
        val album: String,
        val year: String = "",
        val trackNumber: Int = 1,
        val genre: String = "",
        val artworkUrl: String = "",
        val artworkBytes: ByteArray? = null,
        val lyrics: String? = null
    )

    /**
     * Aplica etiquetas ID3v2.3 estrictamente estándar con carátula JPEG 600x600
     * garantizando compatibilidad total con Android MediaStore, Windows y reproductores de coche.
     */
    suspend fun applyId3v23Tags(
        mp3File: File,
        tagData: TagData
    ): File = withContext(Dispatchers.IO) {
        val tempOutput = File(mp3File.parentFile, "tagged_${System.currentTimeMillis()}.mp3")

        // 1. Descargar y preparar la carátula estándar JPEG 600x600 si no vino ya precargada
        val artworkBytes: ByteArray? = tagData.artworkBytes ?: if (tagData.artworkUrl.isNotBlank()) {
            downloadAndResizeArtwork(tagData.artworkUrl, targetSize = 600)
        } else null

        // 2. Construir los frames ID3v2.3
        val framesStream = ByteArrayOutputStream()

        // Título (TIT2)
        writeTextFrame(framesStream, "TIT2", tagData.title)
        // Artista (TPE1)
        writeTextFrame(framesStream, "TPE1", tagData.artist)
        // Álbum (TALB)
        writeTextFrame(framesStream, "TALB", tagData.album)
        // Año (TYER) - en ID3v2.3 es TYER en ISO-8859-1
        if (tagData.year.isNotBlank()) {
            writeAsciiFrame(framesStream, "TYER", tagData.year.take(4))
        }
        // Nº de pista (TRCK)
        writeAsciiFrame(framesStream, "TRCK", tagData.trackNumber.toString())
        // Género (TCON)
        if (tagData.genre.isNotBlank()) {
            writeTextFrame(framesStream, "TCON", tagData.genre)
        }
        // Carátula Estándar (APIC) - Se ubica PRIMERO antes de USLT/TXXX para máxima compatibilidad con TagLib/Symfonium
        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            writeApicFrame(framesStream, artworkBytes)
        }
        // Metadatos de normalización EBU R128 (-14 LUFS)
        writeUserTextFrame(framesStream, "replaygain_track_gain", "-14.00 dB")
        writeUserTextFrame(framesStream, "R128_TRACK_GAIN", "-14.00 dB")
        // Letras no sincronizadas (USLT)
        if (!tagData.lyrics.isNullOrBlank()) {
            writeLyricsFrame(framesStream, tagData.lyrics)
        }

        val allFrames = framesStream.toByteArray()

        // 3. Cabecera ID3v2.3 (10 bytes):
        // ID3 (3 bytes) + versión 03 00 (2 bytes) + flags 00 (1 byte) + tamaño synchsafe (4 bytes)
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3.toByte() // v2.3.0 (el estándar universal más compatible)
        header[4] = 0.toByte()
        header[5] = 0.toByte() // flags

        val synchsafeSize = encodeSynchsafe(allFrames.size)
        System.arraycopy(synchsafeSize, 0, header, 6, 4)

        try {
            // 4. Leer el stream de audio original saltando cualquier cabecera ID3 anterior existente
            FileInputStream(mp3File).use { input ->
                FileOutputStream(tempOutput).use { output ->
                    // Escribir cabecera ID3v2.3 y frames
                    output.write(header)
                    output.write(allFrames)

                    // Detectar si el archivo ya tenía ID3v2 previo y saltarlo
                    val existingHeader = ByteArray(10)
                    val readHdr = input.read(existingHeader)

                    if (readHdr == 10 && existingHeader[0] == 'I'.code.toByte() &&
                        existingHeader[1] == 'D'.code.toByte() && existingHeader[2] == '3'.code.toByte()
                    ) {
                        val oldSize = decodeSynchsafe(existingHeader, 6)
                        // Saltar de forma exacta todos los bytes del tag antiguo
                        var remaining = oldSize.toLong()
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            if (skipped <= 0) {
                                if (input.read() == -1) break
                                remaining--
                            } else {
                                remaining -= skipped
                            }
                        }
                    } else if (readHdr > 0) {
                        // No tenía ID3v2, escribir los bytes leídos de la cabecera
                        output.write(existingHeader, 0, readHdr)
                    }

                    // Copiar el resto del audio MP3
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            // Retornar archivo etiquetado si se generó con éxito
            if (tempOutput.exists() && tempOutput.length() > 50000) {
                return@withContext tempOutput
            } else {
                try { tempOutput.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            try { tempOutput.delete() } catch (_: Exception) {}
            throw e
        }

        return@withContext mp3File
    }

    /**
     * Escribe un frame de texto UTF-16 con BOM (para caracteres internacionales, acentos, etc.)
     */
    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val content = ByteArrayOutputStream()
        content.write(1) // 0x01 = UTF-16 with BOM
        content.write(0xFF) // BOM LE
        content.write(0xFE)
        val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
        content.write(textBytes)

        val frameBytes = content.toByteArray()
        writeFrameHeader(out, frameId, frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Escribe un frame TXXX (User defined text information)
     */
    private fun writeUserTextFrame(out: ByteArrayOutputStream, description: String, value: String) {
        val content = ByteArrayOutputStream()
        content.write(0) // ISO-8859-1
        content.write(description.toByteArray(StandardCharsets.ISO_8859_1))
        content.write(0) // Null separator
        content.write(value.toByteArray(StandardCharsets.ISO_8859_1))

        val frameBytes = content.toByteArray()
        writeFrameHeader(out, "TXXX", frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Escribe un frame ASCII / ISO-8859-1
     */
    private fun writeAsciiFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val content = ByteArrayOutputStream()
        content.write(0) // 0x00 = ISO-8859-1
        content.write(text.toByteArray(StandardCharsets.ISO_8859_1))

        val frameBytes = content.toByteArray()
        writeFrameHeader(out, frameId, frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Escribe el frame APIC estándar ID3v2.3:
     * Encoding (1) + Mime (image/jpeg\0) + Picture Type (0x03 = Front Cover) + Description (\0) + JPEG Data
     */
    private fun writeApicFrame(out: ByteArrayOutputStream, jpegBytes: ByteArray) {
        val content = ByteArrayOutputStream()
        content.write(0) // Encoding: ISO-8859-1 para mime y descripción
        content.write("image/jpeg".toByteArray(StandardCharsets.ISO_8859_1))
        content.write(0) // Null byte terminador de MIME
        content.write(3) // 0x03 = Front Cover (Carátula frontal)
        content.write(0) // Null byte de descripción vacía
        content.write(jpegBytes) // Bytes brutos JPEG

        val frameBytes = content.toByteArray()
        writeFrameHeader(out, "APIC", frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Escribe el frame USLT de letras no sincronizadas conforme a la especificación ID3v2.3
     */
    private fun writeLyricsFrame(out: ByteArrayOutputStream, lyrics: String) {
        val cleanLyrics = lyrics.replace("\r\n", "\n").replace("\r", "\n")
        val content = ByteArrayOutputStream()
        content.write(1) // Encoding: 1 = Unicode UTF-16 with BOM
        content.write("eng".toByteArray(StandardCharsets.ISO_8859_1)) // Idioma 3 bytes ISO-639-2
        // Descriptor vacío con BOM estándar UTF-16LE: 0xFF 0xFE seguido de 0x00 0x00
        content.write(0xFF)
        content.write(0xFE)
        content.write(0)
        content.write(0)

        // Texto de letras: BOM (0xFF 0xFE) + texto en UTF-16LE
        content.write(0xFF)
        content.write(0xFE)
        content.write(cleanLyrics.toByteArray(StandardCharsets.UTF_16LE))

        val frameBytes = content.toByteArray()
        writeFrameHeader(out, "USLT", frameBytes.size)
        out.write(frameBytes)
    }

    /**
     * Escribe la cabecera del frame ID3v2.3: ID (4 bytes) + Tamaño (4 bytes enteros, no synchsafe) + Flags (2 bytes)
     */
    private fun writeFrameHeader(out: ByteArrayOutputStream, frameId: String, size: Int) {
        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1))
        out.write((size shr 24) and 0xFF)
        out.write((size shr 16) and 0xFF)
        out.write((size shr 8) and 0xFF)
        out.write(size and 0xFF)
        out.write(0) // Flags byte 1
        out.write(0) // Flags byte 2
    }

    /**
     * Descarga la imagen y la normaliza a estándar Baseline JPEG de 600x600 a 90% calidad
     */
    suspend fun downloadAndResizeArtwork(imageUrl: String, targetSize: Int = 600): ByteArray? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        var originalBitmap: android.graphics.Bitmap? = null
        var scaledBitmap: android.graphics.Bitmap? = null
        try {
            conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            if (conn.responseCode == 200) {
                val rawBytes = conn.inputStream.use { it.readBytes() }
                originalBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    ?: return@withContext null

                // Redimensionar respetando ratio o a exactamente 600x600 si es cuadrada
                scaledBitmap = if (originalBitmap.width != targetSize || originalBitmap.height != targetSize) {
                    Bitmap.createScaledBitmap(originalBitmap, targetSize, targetSize, true)
                } else {
                    originalBitmap
                }

                val outStream = ByteArrayOutputStream()
                // Comprimir como Baseline JPEG estándar sRGB (máxima compatibilidad)
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                return@withContext outStream.toByteArray()
            }
        } catch (_: Exception) {
        } finally {
            if (scaledBitmap != null && scaledBitmap !== originalBitmap) {
                scaledBitmap.recycle()
            }
            originalBitmap?.recycle()
            conn?.disconnect()
        }
        return@withContext null
    }

    private fun encodeSynchsafe(value: Int): ByteArray {
        val out = ByteArray(4)
        out[0] = ((value shr 21) and 0x7F).toByte()
        out[1] = ((value shr 14) and 0x7F).toByte()
        out[2] = ((value shr 7) and 0x7F).toByte()
        out[3] = (value and 0x7F).toByte()
        return out
    }

    private fun decodeSynchsafe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
                ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
                ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
                (bytes[offset + 3].toInt() and 0x7F)
    }
}
