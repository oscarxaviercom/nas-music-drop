package com.nasmusicdrop.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nasmusicdrop.app.data.model.NasConfig
import com.nasmusicdrop.app.data.model.UploadHistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NasSettingsRepository private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: NasSettingsRepository? = null

        fun getInstance(context: Context): NasSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NasSettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("nas_music_drop_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<NasConfig> = _configFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow(loadHistory())
    val historyFlow: StateFlow<List<UploadHistoryItem>> = _historyFlow.asStateFlow()

    fun getConfig(): NasConfig = _configFlow.value

    fun saveConfig(config: NasConfig) {
        if (_configFlow.value == config) return
        prefs.edit().apply {
            putString("destination_mode", config.destinationMode)
            putString("tailscale_ip", config.tailscaleIp)
            putString("local_ip", config.localIp)
            putInt("smb_port", config.smbPort.coerceIn(1, 65535))
            putString("username", config.username)
            putString("password_enc", encryptPassword(config.password))
            remove("password")
            putString("share_name", config.shareName)
            putString("base_audio_path", config.baseAudioPath)
            putBoolean("normalize_volume", config.normalizeVolume)
            putFloat("target_lufs", config.targetLufs.toFloat())
            putInt("bitrate_kbps", config.bitrateKbps)
            putBoolean("auto_select_best_ip", config.autoSelectBestIp)
            putBoolean("check_duplicates", config.checkDuplicates)
            putBoolean("save_folder_cover_jpg", config.saveFolderCoverJpg)
            putBoolean("organize_by_album", config.folderStructure == "ARTIST_ALBUM")
            putString("folder_structure", config.folderStructure)
            putString("theme_mode", config.themeMode)
            putString("accent_color", config.accentColor)
            putBoolean("wifi_only", config.wifiOnly)
            putInt("duration_tolerance_sec", config.durationToleranceSeconds)
            putString("file_name_format", config.fileNameFormat)
            putBoolean("auto_clear_completed", config.autoClearCompleted)
            putBoolean("auto_check_updates", config.autoCheckUpdates)
            apply()
        }
        _configFlow.value = config
    }

    private fun getOrCreateKey(): SecretKey {
        val alias = "nas_music_drop_smb_key"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encryptPassword(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encrypted = Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            "enc:$iv:$encrypted"
        } catch (_: Exception) {
            ""
        }
    }

    private fun decryptPassword(stored: String): String {
        if (!stored.startsWith("enc:")) return stored
        return try {
            val parts = stored.split(":", limit = 3)
            if (parts.size != 3) return ""
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val data = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun loadConfig(): NasConfig {
        val savedMode = prefs.getString("destination_mode", null)
        val tailIp = prefs.getString("tailscale_ip", "") ?: ""
        val locIp = prefs.getString("local_ip", "") ?: ""
        val effectiveMode = savedMode ?: if (tailIp.isNotBlank() || locIp.isNotBlank()) "NAS" else "LOCAL"

        val encryptedPwd = prefs.getString("password_enc", null)
        val legacyPlainPwd = prefs.getString("password", null)
        val resolvedPwd = when {
            !encryptedPwd.isNullOrEmpty() -> decryptPassword(encryptedPwd)
            !legacyPlainPwd.isNullOrEmpty() -> {
                // Migrar contraseña antigua en texto plano a formato cifrado
                prefs.edit()
                    .putString("password_enc", encryptPassword(legacyPlainPwd))
                    .remove("password")
                    .apply()
                legacyPlainPwd
            }
            else -> ""
        }

        val legacyOrganizeByAlbum = prefs.getBoolean("organize_by_album", false)
        val resolvedFolderStructure = prefs.getString("folder_structure", null)
            ?: if (legacyOrganizeByAlbum) "ARTIST_ALBUM" else "ARTIST"

        return NasConfig(
            destinationMode = effectiveMode,
            tailscaleIp = tailIp,
            localIp = locIp,
            smbPort = prefs.getInt("smb_port", 445).coerceIn(1, 65535),
            username = prefs.getString("username", "") ?: "",
            password = resolvedPwd,
            shareName = prefs.getString("share_name", "Multimedia") ?: "Multimedia",
            baseAudioPath = prefs.getString("base_audio_path", "Audio") ?: "Audio",
            normalizeVolume = prefs.getBoolean("normalize_volume", true),
            targetLufs = prefs.getFloat("target_lufs", -14.0f).toDouble(),
            bitrateKbps = prefs.getInt("bitrate_kbps", 192),
            autoSelectBestIp = prefs.getBoolean("auto_select_best_ip", true),
            checkDuplicates = prefs.getBoolean("check_duplicates", true),
            saveFolderCoverJpg = prefs.getBoolean("save_folder_cover_jpg", false),
            organizeByAlbum = resolvedFolderStructure == "ARTIST_ALBUM",
            folderStructure = resolvedFolderStructure,
            themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            accentColor = prefs.getString("accent_color", "TEAL") ?: "TEAL",
            wifiOnly = prefs.getBoolean("wifi_only", false),
            durationToleranceSeconds = prefs.getInt("duration_tolerance_sec", 15),
            fileNameFormat = prefs.getString("file_name_format", "ARTIST_TITLE") ?: "ARTIST_TITLE",
            autoClearCompleted = prefs.getBoolean("auto_clear_completed", false),
            autoCheckUpdates = prefs.getBoolean("auto_check_updates", true)
        )
    }

    fun addHistoryItem(item: UploadHistoryItem) {
        val updated = mutableListOf<UploadHistoryItem>()
        synchronized(this) {
            val current = _historyFlow.value.toMutableList()
            current.add(0, item)
            // Guardar max 100 items
            if (current.size > 100) current.removeAt(current.size - 1)
            saveHistory(current)
            updated.addAll(current)
        }
        _historyFlow.value = updated
    }

    fun clearHistory() {
        synchronized(this) {
            prefs.edit().remove("upload_history").apply()
            _historyFlow.value = emptyList()
        }
    }

    fun deleteHistoryItem(id: String) {
        val updated = mutableListOf<UploadHistoryItem>()
        synchronized(this) {
            val current = _historyFlow.value.filter { it.id != id }
            saveHistory(current)
            updated.addAll(current)
        }
        _historyFlow.value = updated
    }

    private fun saveHistory(list: List<UploadHistoryItem>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artist", item.artist)
                put("album", item.album)
                put("year", item.year)
                put("genre", item.genre)
                put("trackNumber", item.trackNumber)
                put("artworkUrl", item.artworkUrl)
                put("remotePath", item.remotePath)
                put("fileSizeBytes", item.fileSizeBytes)
                put("timestamp", item.timestamp)
                put("status", item.status)
                put("errorMessage", item.errorMessage ?: "")
                put("bitrateKbps", item.bitrateKbps)
                put("originalLufs", item.originalLufs)
                put("targetLufs", item.targetLufs)
                put("appliedGainDb", item.appliedGainDb)
                put("hasLyrics", item.hasLyrics)
                put("coverResolution", item.coverResolution)
                put("lyrics", item.lyrics ?: "")
            }
            arr.put(obj)
        }
        prefs.edit().putString("upload_history", arr.toString()).apply()
    }

    private fun loadHistory(): List<UploadHistoryItem> {
        val jsonStr = prefs.getString("upload_history", null) ?: return emptyList()
        val list = mutableListOf<UploadHistoryItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    UploadHistoryItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        year = obj.optString("year", ""),
                        genre = obj.optString("genre", ""),
                        trackNumber = obj.optInt("trackNumber", 1),
                        artworkUrl = obj.optString("artworkUrl", ""),
                        remotePath = obj.optString("remotePath", ""),
                        fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        status = obj.optString("status", "SUCCESS"),
                        errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                        bitrateKbps = obj.optInt("bitrateKbps", 192),
                        originalLufs = obj.optDouble("originalLufs", -14.0),
                        targetLufs = obj.optDouble("targetLufs", -14.0),
                        appliedGainDb = obj.optDouble("appliedGainDb", 0.0),
                        hasLyrics = obj.optBoolean("hasLyrics", false),
                        coverResolution = obj.optString("coverResolution", "600x600 px (JPEG)"),
                        lyrics = obj.optString("lyrics").takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }
}
