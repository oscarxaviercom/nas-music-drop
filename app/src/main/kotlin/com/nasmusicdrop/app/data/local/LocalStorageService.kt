package com.nasmusicdrop.app.data.local

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object LocalStorageService {

    data class LocalSaveResult(
        val success: Boolean,
        val localDisplayPath: String = "",
        val fileSizeBytes: Long = 0L,
        val errorMessage: String? = null
    )

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Desconocido" }
    }

    private fun getRelativeDirectory(
        artistName: String,
        albumName: String,
        organizeByAlbum: Boolean,
        folderStructure: String = if (organizeByAlbum) "ARTIST_ALBUM" else "ARTIST"
    ): String {
        val safeArtist = sanitizeFilename(artistName)
        val safeAlbum = sanitizeFilename(albumName)
        return when (folderStructure) {
            "NONE" -> "Music"
            "ARTIST_ALBUM" -> if (albumName.isNotBlank() && safeAlbum != "Desconocido") {
                "Music/$safeArtist/$safeAlbum"
            } else {
                "Music/$safeArtist"
            }
            else -> if (organizeByAlbum && albumName.isNotBlank() && safeAlbum != "Desconocido") {
                "Music/$safeArtist/$safeAlbum"
            } else {
                "Music/$safeArtist"
            }
        }
    }

    suspend fun checkTrackExistsLocally(
        context: Context,
        artistName: String,
        trackTitle: String,
        albumName: String = "",
        organizeByAlbum: Boolean = false,
        folderStructure: String = if (organizeByAlbum) "ARTIST_ALBUM" else "ARTIST"
    ): String? = withContext(Dispatchers.IO) {
        val artistVariants = com.nasmusicdrop.app.data.repository.DuplicateMatcher.extractArtistVariants(artistName)
        val safeTitle = sanitizeFilename(trackTitle)
        val relativeDir = getRelativeDirectory(artistName, albumName, organizeByAlbum, folderStructure)

        try {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

            val candidateArtistDirs = linkedSetOf<File>()
            for (artist in artistVariants) {
                val safeArtist = sanitizeFilename(artist)
                val targetFilename = "$safeArtist - $safeTitle.mp3"
                val subPath = getRelativeDirectory(artist, albumName, organizeByAlbum, folderStructure)
                    .removePrefix("Music/").removePrefix("Music").trimStart('/')
                val directFile = if (subPath.isEmpty()) File(musicDir, targetFilename) else File(musicDir, "$subPath/$targetFilename")
                if (directFile.exists() && directFile.length() > 0) {
                    return@withContext if (subPath.isEmpty()) "Music/$targetFilename" else "Music/$subPath/$targetFilename"
                }
                candidateArtistDirs.add(File(musicDir, safeArtist))
            }

            // Buscar también archivos sueltos en la carpeta raíz Music/ (ej. modo "Sin carpetas")
            musicDir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.length() > 0 &&
                    com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(entry.name, trackTitle) &&
                    com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesArtistFolderMatch(entry.name, artistName)
                ) {
                    return@withContext "Music/${entry.name}"
                }
            }

            // Buscar también carpetas de artista en Music con coincidencia flexible (ej. "fun ft. Janelle Monáe")
            musicDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesArtistFolderMatch(dir.name, artistName)) {
                    candidateArtistDirs.add(dir)
                }
            }

            // Escanear carpetas de artista candidatas y sus subcarpetas (álbumes)
            for (artistDir in candidateArtistDirs) {
                if (artistDir.exists() && artistDir.isDirectory) {
                    artistDir.listFiles()?.forEach { child ->
                        if (child.isFile && child.length() > 0 &&
                            com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(child.name, trackTitle)
                        ) {
                            return@withContext "Music/${artistDir.name}/${child.name}"
                        } else if (child.isDirectory) {
                            child.listFiles()?.forEach { subFile ->
                                if (subFile.isFile && subFile.length() > 0 &&
                                    com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(subFile.name, trackTitle)
                                ) {
                                    return@withContext "Music/${artistDir.name}/${child.name}/${subFile.name}"
                                }
                            }
                        }
                    }
                }
            }

            // 2. Comprobar mediante MediaStore
            val primarySafeArtist = sanitizeFilename(artistVariants.firstOrNull() ?: artistName)
            val targetFilename = "$primarySafeArtist - $safeTitle.mp3"
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(targetFilename)

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@withContext "$relativeDir/$targetFilename"
                }
            }
        } catch (_: Exception) {}

        return@withContext null
    }

    suspend fun saveTrackToDevice(
        context: Context,
        localFile: File,
        artistName: String,
        trackTitle: String,
        albumName: String,
        organizeByAlbum: Boolean,
        coverBytes: ByteArray? = null,
        saveFolderCoverJpg: Boolean = false,
        fileNameFormat: String = "ARTIST_TITLE",
        folderStructure: String = if (organizeByAlbum) "ARTIST_ALBUM" else "ARTIST",
        onProgress: (percent: Int, statusText: String) -> Unit = { _, _ -> }
    ): LocalSaveResult = withContext(Dispatchers.IO) {
        val safeArtist = sanitizeFilename(artistName)
        val safeTitle = sanitizeFilename(trackTitle)
        val targetFilename = if (fileNameFormat == "TITLE_ONLY") "$safeTitle.mp3" else "$safeArtist - $safeTitle.mp3"
        val relativeDir = getRelativeDirectory(artistName, albumName, organizeByAlbum, folderStructure)
        val displayPath = "$relativeDir/$targetFilename"

        try {
            onProgress(95, "Guardando en $displayPath...")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Eliminar entrada previa con el mismo nombre y ruta para evitar duplicados tipo "canción (1).mp3"
                deleteExistingMediaStoreEntry(
                    context = context,
                    collectionUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    displayName = targetFilename,
                    relativePath = "$relativeDir/"
                )

                // Android 10+ vía MediaStore con RELATIVE_PATH
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, targetFilename)
                    put(MediaStore.Audio.Media.TITLE, trackTitle)
                    put(MediaStore.Audio.Media.ARTIST, artistName)
                    if (albumName.isNotBlank()) {
                        put(MediaStore.Audio.Media.ALBUM, albumName)
                    }
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "$relativeDir/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri: Uri? = context.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                if (uri == null) {
                    return@withContext LocalSaveResult(
                        success = false,
                        errorMessage = "No se pudo crear la entrada en el almacenamiento del dispositivo."
                    )
                }

                val outStream = context.contentResolver.openOutputStream(uri, "w")
                if (outStream == null) {
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                    return@withContext LocalSaveResult(
                        success = false,
                        errorMessage = "No se pudo abrir el flujo de escritura en el almacenamiento del dispositivo."
                    )
                }

                outStream.use { out ->
                    FileInputStream(localFile).use { input ->
                        val buffer = ByteArray(32768)
                        var bytes: Int
                        var written = 0L
                        val total = localFile.length()
                        while (input.read(buffer).also { bytes = it } != -1) {
                            out.write(buffer, 0, bytes)
                            written += bytes
                            val p = if (total > 0L) 95 + ((written.toDouble() / total) * 4).toInt().coerceIn(0, 4) else 99
                            onProgress(p, "Guardando en $displayPath...")
                        }
                        out.flush()
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)

                // Guardar cover.jpg opcional (solo si no existe ya y no está en modo Sin carpetas)
                if (folderStructure != "NONE" && saveFolderCoverJpg && coverBytes != null && coverBytes.isNotEmpty() &&
                    !mediaStoreEntryExists(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "cover.jpg", "$relativeDir/")
                ) {
                    try {
                        val imgValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "cover.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "$relativeDir/")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val imgUri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            imgValues
                        )
                        if (imgUri != null) {
                            context.contentResolver.openOutputStream(imgUri, "w")?.use { imgOut ->
                                imgOut.write(coverBytes)
                                imgOut.flush()
                            }
                            imgValues.clear()
                            imgValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                            context.contentResolver.update(imgUri, imgValues, null, null)
                        }
                    } catch (_: Exception) {}
                }

            } else {
                // Fallback directo a carpeta Music en Android 8-9 (API 26-28)
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val subPath = relativeDir.removePrefix("Music/").removePrefix("Music")
                val targetDir = File(musicDir, subPath)
                if (!targetDir.exists()) targetDir.mkdirs()

                val destFile = File(targetDir, targetFilename)
                localFile.copyTo(destFile, overwrite = true)

                if (folderStructure != "NONE" && saveFolderCoverJpg && coverBytes != null && coverBytes.isNotEmpty()) {
                    try {
                        val coverFile = File(targetDir, "cover.jpg")
                        if (!coverFile.exists()) {
                            coverFile.writeBytes(coverBytes)
                        }
                    } catch (_: Exception) {}
                }

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )
            }

            onProgress(100, "¡Guardado con éxito en $displayPath!")
            return@withContext LocalSaveResult(
                success = true,
                localDisplayPath = displayPath,
                fileSizeBytes = localFile.length()
            )

        } catch (e: Exception) {
            return@withContext LocalSaveResult(
                success = false,
                errorMessage = "Error al guardar en el dispositivo: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun deleteExistingMediaStoreEntry(
        context: Context,
        collectionUri: Uri,
        displayName: String,
        relativePath: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(displayName, relativePath)
            context.contentResolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val itemUri = android.content.ContentUris.withAppendedId(collectionUri, id)
                    context.contentResolver.delete(itemUri, null, null)
                }
            }
        } catch (_: Exception) {}
    }

    private fun mediaStoreEntryExists(
        context: Context,
        collectionUri: Uri,
        displayName: String,
        relativePath: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val args = arrayOf(displayName, relativePath)
            context.contentResolver.query(
                collectionUri,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                args,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
