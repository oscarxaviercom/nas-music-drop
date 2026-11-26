package com.nasmusicdrop.app.data.queue

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nasmusicdrop.app.MainActivity
import com.nasmusicdrop.app.R
import com.nasmusicdrop.app.data.model.QueueStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nas_music_drop_downloads_v2"
        const val NOTIFICATION_ID = 1001
        const val SUMMARY_NOTIFICATION_ID = 1002

        fun startService(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        val initialNotification = buildNotification(
            title = "NAS Music Drop",
            content = "Iniciando transferencias...",
            progress = 0,
            isIndeterminate = true
        )
        startForeground(NOTIFICATION_ID, initialNotification)

        val queueManager = DownloadQueueManager.getInstance(applicationContext)
        var hasProcessedAny = false
        serviceScope.launch {
            queueManager.queueList.collectLatest { list ->
                val activeItems = list.filter {
                    it.status == QueueStatus.QUEUED ||
                            it.status == QueueStatus.ANALYZING ||
                            it.status == QueueStatus.DOWNLOADING ||
                            it.status == QueueStatus.NORMALIZING ||
                            it.status == QueueStatus.UPLOADING
                }

                if (activeItems.isEmpty()) {
                    if (hasProcessedAny && list.isNotEmpty()) {
                        val completedCount = list.count { it.status == QueueStatus.COMPLETED }
                        val failedCount = list.count { it.status == QueueStatus.FAILED }
                        val waitingWifiCount = list.count { it.status == QueueStatus.WAITING_WIFI }

                        val summaryParts = mutableListOf<String>()
                        if (completedCount > 0) summaryParts.add("$completedCount guardadas")
                        if (failedCount > 0) summaryParts.add("$failedCount con error")
                        if (waitingWifiCount > 0) summaryParts.add("$waitingWifiCount esperando Wi‑Fi")

                        if (summaryParts.isNotEmpty()) {
                            val title = if (waitingWifiCount > 0 && completedCount == 0 && failedCount == 0) {
                                "📡 Esperando conexión Wi‑Fi"
                            } else {
                                "✅ Music Drop completado"
                            }
                            showSummaryNotification(title, summaryParts.joinToString(" · "))
                        }
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    hasProcessedAny = true
                    val currentItem = activeItems.firstOrNull { it.status != QueueStatus.QUEUED } ?: activeItems.first()
                    val total = list.size
                    val completed = list.count { it.status == QueueStatus.COMPLETED }
                    val notification = buildNotification(
                        title = "${currentItem.artist} - ${currentItem.title}",
                        content = "${currentItem.progressStatusText} · ($completed/$total listas)",
                        progress = currentItem.progressPercent,
                        isIndeterminate = currentItem.status == QueueStatus.ANALYZING || currentItem.status == QueueStatus.QUEUED
                    )
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        val queueManager = DownloadQueueManager.getInstance(applicationContext)
        val hasWaitingWifi = queueManager.queueList.value.any { it.status == QueueStatus.WAITING_WIFI }
        if (!hasWaitingWifi) {
            queueManager.shutdown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transferencias al NAS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progreso de descarga y subida al NAS"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String,
        content: String,
        progress: Int,
        isIndeterminate: Boolean
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val appIconBmp = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .apply {
                if (appIconBmp != null) {
                    setLargeIcon(appIconBmp)
                }
            }
            .setColor(0xFF38BDF8.toInt())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (isIndeterminate) {
            builder.setProgress(100, 0, true)
        } else {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun showSummaryNotification(title: String, content: String) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val appIconBmp = try {
                BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            } catch (_: Exception) {
                null
            }
            val summaryNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_notification)
                .apply {
                    if (appIconBmp != null) setLargeIcon(appIconBmp)
                }
                .setColor(0xFF38BDF8.toInt())
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
        } catch (_: Exception) {}
    }
}
