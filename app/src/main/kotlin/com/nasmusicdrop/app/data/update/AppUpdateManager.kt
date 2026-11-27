package com.nasmusicdrop.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nasmusicdrop.app.R
import com.nasmusicdrop.app.data.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long
)

sealed class UpdateCheckResult {
    data class Available(val info: ReleaseUpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/oscarxaviercom/nas-music-drop/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MusicDrop-Android")
            }

            val code = conn.responseCode
            if (code == 404) {
                return@withContext UpdateCheckResult.UpToDate
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckResult.Error(
                    AppStrings.get(R.string.update_check_http_error, code)
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val cleanRemote = tagName.removePrefix("v").removePrefix("V").trim()
            if (cleanRemote.isBlank() || !isNewerVersion(cleanRemote, currentVersion)) {
                return@withContext UpdateCheckResult.UpToDate
            }

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) {
                return@withContext UpdateCheckResult.UpToDate
            }

            val notes = json.optString("body", "").trim()
            UpdateCheckResult.Available(
                ReleaseUpdateInfo(
                    versionName = cleanRemote,
                    tagName = tagName,
                    releaseNotes = notes,
                    apkDownloadUrl = apkUrl,
                    apkSizeBytes = apkSize
                )
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                AppStrings.get(R.string.update_check_failed, e.localizedMessage ?: "network error")
            )
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val targetFile = File(updatesDir, "MusicDrop-update.apk")
        if (targetFile.exists()) targetFile.delete()

        var conn: HttpURLConnection? = null
        try {
            var currentUrl = apkUrl
            var redirects = 0
            while (redirects < 6) {
                conn?.disconnect()
                val nextConn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "MusicDrop-Android")
                }
                conn = nextConn
                val status = nextConn.responseCode
                if (status in 300..399) {
                    val loc = nextConn.getHeaderField("Location") ?: break
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            val finalConn = conn ?: throw IllegalStateException("No connection")
            if (finalConn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${finalConn.responseCode}")
            }

            val totalBytes = finalConn.contentLengthLong
            var downloaded = 0L
            var lastPct = -1

            finalConn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }

            onProgress(100)
            Result.success(targetFile)
        } catch (e: Exception) {
            try { targetFile.delete() } catch (_: Exception) {}
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return false
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val rParts = remote.split('.').mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val lParts = local.split('.').mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
