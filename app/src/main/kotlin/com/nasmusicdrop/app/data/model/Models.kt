package com.nasmusicdrop.app.data.model

enum class QueueStatus {
    QUEUED,
    WAITING_WIFI,
    ANALYZING,
    DUPLICATE_FOUND,
    DOWNLOADING,
    NORMALIZING,
    UPLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}

data class QueueItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val videoId: String,
    val title: String = "Cargando información...",
    val artist: String = "YouTube",
    val album: String = "",
    val thumbnailUrl: String = "",
    val status: QueueStatus = QueueStatus.QUEUED,
    val progressPercent: Int = 0,
    val progressStatusText: String = "En cola de espera...",
    val errorMessage: String? = null,
    val candidate: SongCandidate? = null,
    val allCandidates: List<SongCandidate> = emptyList(),
    val remotePath: String? = null,
    val duplicatePath: String? = null,
    val lyrics: String? = null,
    val syncedLyrics: String? = null,
    val isSyncedLyrics: Boolean = false,
    val startTimeSeconds: Long = 0L,
    val endTimeSeconds: Long = 0L,
    val isUserEdited: Boolean = false,
    val isLyricsUserEdited: Boolean = false,
    val isExternalResolved: Boolean = false
)

data class SearchResultItem(
    val videoId: String,
    val title: String,
    val cleanTitle: String,
    val uploader: String,
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String
)

data class PlaylistItem(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val durationSeconds: Long = 0,
    val isAlreadyInLibrary: Boolean = false
)

data class PlaylistData(
    val playlistId: String,
    val title: String,
    val uploader: String,
    val items: List<PlaylistItem>
)

data class SongCandidate(
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val trackNumber: Int = 1,
    val genre: String = "",
    val artworkUrl600: String = "",
    val previewUrl: String? = null,
    val lyrics: String? = null,
    val isSyncedLyrics: Boolean = false
)

data class UploadHistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val genre: String = "",
    val trackNumber: Int = 1,
    val artworkUrl: String,
    val remotePath: String,
    val fileSizeBytes: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "SUCCESS",
    val errorMessage: String? = null,
    val bitrateKbps: Int = 192,
    val originalLufs: Double = -14.0,
    val targetLufs: Double = -14.0,
    val appliedGainDb: Double = 0.0,
    val hasLyrics: Boolean = false,
    val coverResolution: String = "600x600 px (JPEG)",
    val lyrics: String? = null
)

data class NasConfig(
    val destinationMode: String = "LOCAL", // "LOCAL" o "NAS"
    val tailscaleIp: String = "",
    val localIp: String = "",
    val smbPort: Int = 445,
    val username: String = "",
    val password: String = "",
    val shareName: String = "Multimedia",
    val baseAudioPath: String = "Audio",
    val normalizeVolume: Boolean = true,
    val targetLufs: Double = -14.0,
    val bitrateKbps: Int = 192,
    val autoSelectBestIp: Boolean = true,
    val checkDuplicates: Boolean = true,
    val saveFolderCoverJpg: Boolean = false,
    val organizeByAlbum: Boolean = false,
    val folderStructure: String = "ARTIST", // "NONE", "ARTIST", "ARTIST_ALBUM"
    val themeMode: String = "SYSTEM", // "SYSTEM", "LIGHT", "DARK"
    val accentColor: String = "TEAL", // "TEAL", "BLUE", "PURPLE", "ORANGE", "RED", "GREEN", "PINK"
    val wifiOnly: Boolean = false,
    val durationToleranceSeconds: Int = 15,
    val fileNameFormat: String = "ARTIST_TITLE", // "ARTIST_TITLE" o "TITLE_ONLY"
    val autoClearCompleted: Boolean = false,
    val autoCheckUpdates: Boolean = true
)

data class ConnectionTestResult(
    val success: Boolean,
    val activeIp: String = "",
    val latencyMs: Long = 0,
    val isTailscale: Boolean = false,
    val shareAccessible: Boolean = false,
    val audioFolderExists: Boolean = false,
    val canWrite: Boolean = false,
    val logOutput: String = ""
)
