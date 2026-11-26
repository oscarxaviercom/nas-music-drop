package com.nasmusicdrop.app.data.nas

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.nasmusicdrop.app.data.model.ConnectionTestResult
import com.nasmusicdrop.app.data.model.NasConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet

object SmbNasService {

    @Volatile private var cachedWorkingIp: String? = null
    @Volatile private var cachedWorkingIpAt: Long = 0L
    private const val IP_CACHE_TTL_MS = 5 * 60 * 1000L

    private fun markIpWorking(ip: String) {
        cachedWorkingIp = ip
        cachedWorkingIpAt = System.currentTimeMillis()
    }

    private fun invalidateWorkingIp(ip: String) {
        if (cachedWorkingIp == ip) {
            cachedWorkingIp = null
            cachedWorkingIpAt = 0L
        }
    }

    data class UploadResult(
        val success: Boolean,
        val remotePath: String = "",
        val fileSizeBytes: Long = 0L,
        val error: String? = null
    )

    /**
     * Prueba la conexión en vivo con el NAS e informa de latencia y permisos
     */
    suspend fun testConnection(config: NasConfig): ConnectionTestResult = withContext(Dispatchers.IO) {
        val log = StringBuilder()
        var activeIp = ""
        var isTailscale = false
        var latencyMs = 0L

        log.appendLine(com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_test_start))

        // 1. Probar conectividad socket (puerto 445 SMB)
        val ipsToTry = mutableListOf<String>()
        if (config.autoSelectBestIp) {
            // Intentar primero local si responde rápido, si no Tailscale
            ipsToTry.add(config.localIp)
            ipsToTry.add(config.tailscaleIp)
        } else {
            ipsToTry.add(config.tailscaleIp)
        }

        var reachableIp: String? = null
        for (ip in ipsToTry) {
            if (ip.isBlank()) continue
            val (host, port) = resolveHostAndPort(ip, config)
            log.append("Probando IP $host:$port... ")
            val start = System.currentTimeMillis()
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 1800)
                    latencyMs = System.currentTimeMillis() - start
                    reachableIp = ip
                    isTailscale = ip == config.tailscaleIp
                    log.appendLine("✓ Conectado (${latencyMs} ms)")
                }
                break
            } catch (e: Exception) {
                log.appendLine("✗ No responde")
            }
        }

        if (reachableIp == null) {
            return@withContext ConnectionTestResult(
                success = false,
                logOutput = log.toString() + "\nERROR: No se pudo alcanzar el puerto SMB en ninguna de las IPs configuradas."
            )
        }

        activeIp = reachableIp
        val (activeHost, activePort) = resolveHostAndPort(activeIp, config)

        // 2. Probar autenticación SMB y acceso al share
        val client = SMBClient()
        try {
            log.append("Autenticando usuario '${config.username}'... ")
            val connection = client.connect(activeHost, activePort)
            val authContext = AuthenticationContext(
                config.username,
                config.password.toCharArray(),
                null
            )
            val session = connection.authenticate(authContext)
            log.appendLine("✓ Autenticado")

            log.append("Conectando al recurso compartido '${config.shareName}'... ")
            val share = session.connectShare(config.shareName) as? DiskShare
            if (share == null) {
                return@withContext ConnectionTestResult(
                    success = false,
                    activeIp = activeIp,
                    latencyMs = latencyMs,
                    isTailscale = isTailscale,
                    logOutput = log.toString() + "\nERROR: No se pudo abrir el share '${config.shareName}' como disco."
                )
            }
            log.appendLine("✓ Share montado")

            // 3. Comprobar carpeta base
            log.append("Verificando carpeta base '${config.baseAudioPath}'... ")
            val audioExists = share.folderExists(config.baseAudioPath)
            if (!audioExists) {
                log.appendLine("no existía, intentando crear...")
                share.mkdir(config.baseAudioPath)
            }
            log.appendLine("✓ Carpeta lista")

            // 4. Probar permiso de escritura con archivo temporal de test
            val testFile = "${config.baseAudioPath}/.test_${System.currentTimeMillis()}.tmp"
            log.append("Comprobando permisos de escritura... ")
            val smbFile = share.openFile(
                testFile,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ, AccessMask.DELETE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            try {
                smbFile.getOutputStream().use { it.write("NASMusicDrop Test OK".toByteArray()) }
            } finally {
                try { smbFile.close() } catch (_: Exception) {}
            }
            // Eliminar archivo de test
            share.rm(testFile)
            log.appendLine("✓ Escritura y borrado verificados")

            // 5. Consultar espacio libre aproximado en el NAS
            try {
                val freeBytes = share.shareInformation.freeSpace
                if (freeBytes > 0L) {
                    val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
                    val freeText = String.format(java.util.Locale.US, "%.1f GB", freeGb)
                    log.appendLine("✓ Espacio libre en el NAS: $freeText")
                    if (freeGb < 2.0) {
                        log.appendLine("⚠️ Aviso: queda poco espacio libre en el recurso compartido.")
                    }
                }
            } catch (_: Exception) {}

            return@withContext ConnectionTestResult(
                success = true,
                activeIp = activeIp,
                latencyMs = latencyMs,
                isTailscale = isTailscale,
                shareAccessible = true,
                audioFolderExists = true,
                canWrite = true,
                logOutput = log.toString() + "\n¡TODO CORRECTO! Conexión y permisos verificados al 100%."
            )

        } catch (e: Exception) {
            val friendly = formatFriendlySmbError(e, listOf(activeIp))
            return@withContext ConnectionTestResult(
                success = false,
                activeIp = activeIp,
                latencyMs = latencyMs,
                isTailscale = isTailscale,
                logOutput = log.toString() + "\nERROR:\n$friendly"
            )
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /**
     * Comprueba si la canción ya existe físicamente en el NAS antes de descargarla
     */
    suspend fun checkTrackExistsOnNas(
        config: NasConfig,
        artistName: String,
        songTitle: String,
        albumName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val ipsToTry = getIpsToTry(config)
        for (ip in ipsToTry) {
            val (host, port) = resolveHostAndPort(ip, config)
            val client = SMBClient()
            try {
                val connection = client.connect(host, port)
                val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
                val session = connection.authenticate(auth)
                val share = session.connectShare(config.shareName) as DiskShare

                val folderCache = HashMap<String, List<Pair<String, Boolean>>>()
                val match = findTrackInShare(share, config, artistName, songTitle, albumName, folderCache)

                markIpWorking(ip)
                client.close()
                return@withContext match
            } catch (_: Exception) {
                invalidateWorkingIp(ip)
                try { client.close() } catch (_: Exception) {}
            }
        }
        return@withContext null
    }

    /**
     * Comprueba en una única sesión SMB qué índices de una lista de canciones ya existen en el NAS.
     * Mucho más rápido que abrir una conexión por cada pista en listas de reproducción.
     */
    suspend fun checkTracksExistOnNas(
        config: NasConfig,
        tracks: List<Pair<String, String>> // (artist, title)
    ): Set<Int> = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext emptySet()
        val ipsToTry = getIpsToTry(config)
        for (ip in ipsToTry) {
            val (host, port) = resolveHostAndPort(ip, config)
            val client = SMBClient()
            try {
                val connection = client.connect(host, port)
                val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
                val session = connection.authenticate(auth)
                val share = session.connectShare(config.shareName) as DiskShare

                val folderCache = HashMap<String, List<Pair<String, Boolean>>>()
                val existingIndices = HashSet<Int>()

                for ((index, pair) in tracks.withIndex()) {
                    val match = findTrackInShare(share, config, pair.first, pair.second, null, folderCache)
                    if (match != null) {
                        existingIndices.add(index)
                    }
                }

                client.close()
                return@withContext existingIndices
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
        return@withContext emptySet()
    }

    private fun listShareFolderCached(
        share: DiskShare,
        dirPath: String,
        cache: MutableMap<String, List<Pair<String, Boolean>>>
    ): List<Pair<String, Boolean>> {
        cache[dirPath]?.let { return it }
        val entries = if (share.folderExists(dirPath)) {
            try {
                share.list(dirPath).mapNotNull { info ->
                    val name = info.fileName
                    if (name == "." || name == "..") null
                    else {
                        val isDir = (info.fileAttributes and 0x10L) != 0L
                        name to isDir
                    }
                }
            } catch (_: Exception) { emptyList() }
        } else {
            emptyList()
        }
        cache[dirPath] = entries
        return entries
    }

    private fun findTrackInShare(
        share: DiskShare,
        config: NasConfig,
        artistName: String,
        songTitle: String,
        albumName: String?,
        cache: MutableMap<String, List<Pair<String, Boolean>>>
    ): String? {
        val artistVariants = com.nasmusicdrop.app.data.repository.DuplicateMatcher.extractArtistVariants(artistName)
        val candidateArtistDirs = linkedSetOf<String>()

        // 1. Rutas directas por variantes de artista
        for (artist in artistVariants) {
            val safeArtist = sanitizeFileName(artist)
            candidateArtistDirs.add("${config.baseAudioPath}\\$safeArtist")
        }

        // 2. Buscar también en la lista de carpetas del NAS (cacheada en la sesión) por coincidencia flexible
        // Ej: "fun, Janelle Monáe" encontrará la carpeta "fun ft. Janelle Monáe" o "fun."
        val rootEntries = listShareFolderCached(share, config.baseAudioPath, cache)
        // Comprobar primero archivos sueltos en la carpeta base (ej. modo "Sin carpetas")
        for ((entryName, isDir) in rootEntries) {
            if (!isDir &&
                com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(entryName, songTitle) &&
                com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesArtistFolderMatch(entryName, artistName)
            ) {
                return "${config.shareName}/${config.baseAudioPath}\\$entryName"
            }
            if (isDir && com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesArtistFolderMatch(entryName, artistName)) {
                candidateArtistDirs.add("${config.baseAudioPath}\\$entryName")
            }
        }

        for (artistBaseDir in candidateArtistDirs) {
            val dirsToScan = mutableListOf<String>()
            if ((config.folderStructure == "ARTIST_ALBUM" || config.organizeByAlbum) && !albumName.isNullOrBlank()) {
                val safeAlbum = sanitizeFileName(albumName)
                dirsToScan.add("$artistBaseDir\\$safeAlbum")
            }
            dirsToScan.add(artistBaseDir)

            for (dir in dirsToScan) {
                val entries = listShareFolderCached(share, dir, cache)
                if (entries.isEmpty()) continue

                // Buscar en archivos directos de esta carpeta
                for ((name, isDir) in entries) {
                    if (!isDir && com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(name, songTitle)) {
                        return "${config.shareName}/$dir\\$name"
                    }
                }

                // Si estamos en la carpeta raíz del artista, buscar también en subcarpetas de álbumes
                if (dir == artistBaseDir) {
                    for ((subName, isDir) in entries) {
                        if (isDir) {
                            val subDir = "$artistBaseDir\\$subName"
                            val subEntries = listShareFolderCached(share, subDir, cache)
                            for ((fileName, subIsDir) in subEntries) {
                                if (!subIsDir && com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesFilenameMatchTitle(fileName, songTitle)) {
                                    return "${config.shareName}/$subDir\\$fileName"
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Sube el archivo MP3 al NAS con soporte opcional de cover.jpg, organización por álbum y conmutación automática de IP
     */
    suspend fun uploadTrack(
        config: NasConfig,
        localFile: File,
        artistName: String,
        songTitle: String,
        albumName: String? = null,
        artworkBytes: ByteArray? = null,
        onProgress: (percent: Int, statusText: String) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        val ipsToTry = getIpsToTry(config)
        var lastException: Throwable? = null

        for ((index, ip) in ipsToTry.withIndex()) {
            val ipLabel = if (ip == config.tailscaleIp) "Remota" else "Local"
            val (host, port) = resolveHostAndPort(ip, config)
            var attempt = 0
            val maxAttemptsForIp = 3

            while (attempt < maxAttemptsForIp) {
                attempt++
                val client = SMBClient()
                try {
                    if (attempt == 1) {
                        onProgress(5, com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_connecting_via, ipLabel, host, port))
                    } else {
                        onProgress(8, com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_retrying_conn, attempt, maxAttemptsForIp))
                    }

                    val connection = client.connect(host, port)
                    val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
                    val session = connection.authenticate(auth)
                    val share = session.connectShare(config.shareName) as DiskShare

                    onProgress(15, "Verificando estructura de carpetas en el NAS...")

                    val safeArtist = sanitizeFileName(artistName)
                    val safeTitle = sanitizeFileName(songTitle)
                    val safeAlbum = if (!albumName.isNullOrBlank()) sanitizeFileName(albumName) else safeTitle

                    // Reutilizar carpeta de artista existente si coincide sin importar mayúsculas/tildes
                    val resolvedArtistFolder = if (config.folderStructure != "NONE") {
                        findExistingArtistFolder(share, config.baseAudioPath, artistName) ?: safeArtist
                    } else safeArtist

                    val targetDir = when (config.folderStructure) {
                        "NONE" -> config.baseAudioPath
                        "ARTIST_ALBUM" -> if (!albumName.isNullOrBlank()) {
                            "${config.baseAudioPath}\\$resolvedArtistFolder\\$safeAlbum"
                        } else {
                            "${config.baseAudioPath}\\$resolvedArtistFolder"
                        }
                        else -> if (config.organizeByAlbum && !albumName.isNullOrBlank()) {
                            "${config.baseAudioPath}\\$resolvedArtistFolder\\$safeAlbum"
                        } else {
                            "${config.baseAudioPath}\\$resolvedArtistFolder"
                        }
                    }
                    val mp3FileName = if (config.fileNameFormat == "TITLE_ONLY") "$safeTitle.mp3" else "$safeArtist - $safeTitle.mp3"
                    val targetRemotePath = "$targetDir\\$mp3FileName"

                    // Asegurar que las carpetas existen
                    ensureDirectoryExists(share, config.baseAudioPath)
                    if (targetDir != config.baseAudioPath) {
                        ensureDirectoryExists(share, targetDir)
                    }

                    // Subir cover.jpg si está activado en Ajustes (omitido en modo Sin carpetas para no sobrescribir una única carátula común)
                    if (config.folderStructure != "NONE" && config.saveFolderCoverJpg && artworkBytes != null && artworkBytes.isNotEmpty()) {
                        val coverRemotePath = "$targetDir\\cover.jpg"
                        try {
                            uploadBytesIfNotExists(share, coverRemotePath, artworkBytes)
                        } catch (_: Exception) {}
                    }

                    onProgress(30, com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_uploading_via, ipLabel))

                    // Subir el archivo MP3
                    val totalBytes = localFile.length()
                    var uploadedBytes = 0L
                    val startTime = System.currentTimeMillis()

                    val smbFile = share.openFile(
                        targetRemotePath,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        null
                    )

                    try {
                        FileInputStream(localFile).use { input ->
                            smbFile.getOutputStream().use { output ->
                                val buffer = ByteArray(65536) // 64KB buffer
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                    output.write(buffer, 0, read)
                                    uploadedBytes += read

                                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                    val speedMb = if (elapsedSec > 0.2) (uploadedBytes / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                    val pct = if (totalBytes > 0L) 30 + ((uploadedBytes.toDouble() / totalBytes) * 68).toInt() else 98

                                    onProgress(
                                        pct.coerceIn(30, 98),
                                        "Subiendo: ${uploadedBytes / (1024 * 1024)}/${totalBytes / (1024 * 1024)} MB (${String.format(java.util.Locale.US, "%.1f", speedMb)} MB/s)"
                                    )
                                }
                            }
                        }
                    } finally {
                        try { smbFile.close() } catch (_: Exception) {}
                    }

                    markIpWorking(ip)
                    client.close()

                    onProgress(100, com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_saved_ok))

                    return@withContext UploadResult(
                        success = true,
                        remotePath = targetRemotePath,
                        fileSizeBytes = totalBytes
                    )

                } catch (e: kotlinx.coroutines.CancellationException) {
                    try { client.close() } catch (_: Exception) {}
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    invalidateWorkingIp(ip)
                    try { client.close() } catch (_: Exception) {}
                    if (attempt < maxAttemptsForIp && isTransientNetworkError(e)) {
                        kotlinx.coroutines.delay(1500L * attempt)
                    } else {
                        break
                    }
                }
            }

            if (index < ipsToTry.size - 1) {
                onProgress(10, com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_failover, ip))
            }
        }

        return@withContext UploadResult(
            success = false,
            error = formatFriendlySmbError(lastException, ipsToTry)
        )
    }

    private fun isTransientNetworkError(e: Throwable): Boolean {
        val chain = buildList {
            var curr: Throwable? = e
            while (curr != null) {
                curr.message?.let { add(it) }
                add(curr.javaClass.simpleName)
                curr = curr.cause
            }
        }.joinToString(" ").lowercase()
        return chain.contains("connection reset") ||
            chain.contains("broken pipe") ||
            chain.contains("socketexception") ||
            chain.contains("timed out") ||
            chain.contains("timeout")
    }

    private fun findExistingArtistFolder(share: DiskShare, baseAudioPath: String, artistName: String): String? {
        return try {
            if (!share.folderExists(baseAudioPath)) return null
            for (info in share.list(baseAudioPath)) {
                val name = info.fileName
                if (name == "." || name == "..") continue
                val isDir = (info.fileAttributes and 0x10L) != 0L
                if (isDir && com.nasmusicdrop.app.data.repository.DuplicateMatcher.doesArtistFolderMatch(name, artistName)) {
                    return name
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Traduce excepciones técnicas de red / SMB (SMBClient, SocketException, TransportException)
     * a mensajes claros, comprensibles y accionables para el usuario.
     */
    fun formatFriendlySmbError(e: Throwable?, ipsAttempted: List<String> = emptyList()): String {
        if (ipsAttempted.isEmpty()) {
            return com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_no_ip)
        }

        val chain = buildList {
            var curr: Throwable? = e
            while (curr != null) {
                curr.message?.let { add(it) }
                add(curr.javaClass.name)
                add(curr.javaClass.simpleName)
                curr = curr.cause
            }
        }.joinToString(" ").lowercase()

        val triedText = if (ipsAttempted.isNotEmpty()) " (IPs: ${ipsAttempted.joinToString(", ")})" else ""

        return when {
            chain.contains("connection reset") || chain.contains("broken pipe") || chain.contains("socket closed") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_interrupted)
            chain.contains("connection refused") || chain.contains("econnrefused") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_refused, triedText)
            chain.contains("timed out") || chain.contains("timeout") || chain.contains("etimedout") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_unreachable)
            chain.contains("no route to host") || chain.contains("host is down") || chain.contains("unreachable") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_timeout)
            chain.contains("logon_failure") || chain.contains("logon failure") || chain.contains("status_logon_failure") || chain.contains("authentication failed") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_auth)
            chain.contains("bad_network_name") || chain.contains("status_bad_network_name") || chain.contains("object_name_not_found") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_share)
            chain.contains("access_denied") || chain.contains("status_access_denied") ->
                "Access denied on NAS.\n\n"
            chain.contains("unknownhost") ->
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_dns, triedText)
            else -> {
                val cleanMsg = e?.message?.lines()?.firstOrNull()?.take(80)
                    ?: com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_comm)
                com.nasmusicdrop.app.data.AppStrings.get(com.nasmusicdrop.app.R.string.smb_err_detail, cleanMsg)
            }
        }
    }

    private suspend fun getIpsToTry(config: NasConfig): List<String> = coroutineScope {
        val candidates = mutableListOf<String>()
        val configured = listOf(config.localIp, config.tailscaleIp).filter { it.isNotBlank() }.distinct()
        if (configured.isEmpty()) return@coroutineScope candidates

        // Si tenemos una IP reciente que funcionó, priorizarla sin esperar 1.2s de timeout en la otra
        val cached = cachedWorkingIp
        if (cached != null && cached in configured && (System.currentTimeMillis() - cachedWorkingIpAt) < IP_CACHE_TTL_MS) {
            candidates.add(cached)
            configured.filter { it != cached }.forEach { candidates.add(it) }
            return@coroutineScope candidates
        }

        if (config.autoSelectBestIp && configured.size > 1) {
            // Probar ambas IPs en paralelo para no sumar timeouts
            val checks = configured.map { ip ->
                ip to async(Dispatchers.IO) { isIpReachable(ip, config) }
            }
            for ((ip, deferred) in checks) {
                if (deferred.await()) {
                    candidates.add(ip)
                }
            }
        }
        configured.forEach { ip ->
            if (!candidates.contains(ip)) candidates.add(ip)
        }
        candidates
    }

    private fun resolveHostAndPort(rawIp: String, config: NasConfig): Pair<String, Int> {
        val trimmed = rawIp.trim()
        val isRemote = trimmed == config.tailscaleIp.trim() && trimmed.isNotEmpty()
        val defaultPort = if (isRemote) config.smbPort.coerceIn(1, 65535) else 445
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx > 0 && trimmed.indexOf(':') == colonIdx) {
            val hostPart = trimmed.substring(0, colonIdx).trim()
            val portPart = trimmed.substring(colonIdx + 1).trim().toIntOrNull()
            if (hostPart.isNotEmpty() && portPart != null && portPart in 1..65535) {
                return hostPart to portPart
            }
        }
        return trimmed to defaultPort
    }

    private suspend fun isIpReachable(ip: String, config: NasConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val (host, port) = resolveHostAndPort(ip, config)
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 1200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveBestIp(config: NasConfig): String? {
        val candidates = mutableListOf<String>()
        if (config.autoSelectBestIp) {
            candidates.add(config.localIp)
            candidates.add(config.tailscaleIp)
        } else {
            candidates.add(config.tailscaleIp)
        }

        for (ip in candidates) {
            if (ip.isBlank()) continue
            try {
                val (host, port) = resolveHostAndPort(ip, config)
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 1500)
                    return ip
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun ensureDirectoryExists(share: DiskShare, path: String) {
        val normalized = path.replace('/', '\\')
        val segments = normalized.split('\\').filter { it.isNotBlank() }
        var current = ""
        for (seg in segments) {
            current = if (current.isEmpty()) seg else "$current\\$seg"
            if (!share.folderExists(current)) {
                try {
                    share.mkdir(current)
                } catch (_: Exception) {}
            }
        }
    }

    private fun uploadBytesIfNotExists(share: DiskShare, remotePath: String, data: ByteArray) {
        val f = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null
        )
        try {
            f.getOutputStream().use { it.write(data) }
        } finally {
            try { f.close() } catch (_: Exception) {}
        }
    }

    suspend fun downloadTrackToCache(
        config: NasConfig,
        remotePath: String,
        cacheDir: File
    ): File? = withContext(Dispatchers.IO) {
        val sharePrefix = "${config.shareName}/"
        val smbRelativePath = remotePath.removePrefix(sharePrefix).replace("/", "\\").trimStart('\\')
        val outFile = File(cacheDir, "history_play_${smbRelativePath.hashCode()}.mp3")
        if (outFile.exists() && outFile.length() > 10000) {
            return@withContext outFile
        }

        val ipsToTry = getIpsToTry(config)
        for (ip in ipsToTry) {
            val (host, port) = resolveHostAndPort(ip, config)
            val client = SMBClient()
            try {
                val connection = client.connect(host, port)
                val auth = AuthenticationContext(config.username, config.password.toCharArray(), null)
                val session = connection.authenticate(auth)
                val share = session.connectShare(config.shareName) as DiskShare

                if (!share.fileExists(smbRelativePath)) {
                    client.close()
                    return@withContext null
                }

                val smbFile = share.openFile(
                    smbRelativePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                try {
                    smbFile.inputStream.use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output, 65536)
                        }
                    }
                } finally {
                    try { smbFile.close() } catch (_: Exception) {}
                }
                client.close()
                if (outFile.exists() && outFile.length() > 0) {
                    return@withContext outFile
                }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
        return@withContext null
    }

    fun sanitizeFileName(name: String): String {
        return name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
