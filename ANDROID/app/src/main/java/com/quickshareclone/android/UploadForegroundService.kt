package com.quickshareclone.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class UploadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { UploadRepository(applicationContext) }
    private val json = Json
    private val tag = "QuickShareUpload"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL).orEmpty()
        val filesJson = intent?.getStringExtra(EXTRA_FILES).orEmpty()
        val endpointLabel = intent?.getStringExtra(EXTRA_ENDPOINT_LABEL).orEmpty()

        if (serverUrl.isBlank() || filesJson.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Preparing transfer...", 0, true))
        UploadStatusBus.update(
            UploadServiceStatus(
                isUploading = true,
                progressPercent = 0,
                currentFileName = null,
                message = "Preparing transfer...",
                errorMessage = null
            )
        )

        serviceScope.launch {
            runCatching {
                val files = json.decodeFromString(ListSerializer(SharedFileDescriptor.serializer()), filesJson)
                Log.d(tag, "Foreground upload service started for ${files.size} file(s)")

                repository.uploadFiles(serverUrl, files) { progress ->
                    val content = "Sending to $endpointLabel - ${progress.fileName}"
                    val notification = buildNotification(content, progress.progressPercent, true)
                    UploadStatusBus.update(
                        UploadServiceStatus(
                            isUploading = true,
                            progressPercent = progress.progressPercent,
                            currentFileName = progress.fileName,
                            message = "Uploading ${progress.currentFileIndex}/${progress.totalFiles}",
                            errorMessage = null
                        )
                    )
                    getNotificationManager().notify(NOTIFICATION_ID, notification)
                }

                UploadStatusBus.update(
                    UploadServiceStatus(
                        isUploading = false,
                        progressPercent = 100,
                        currentFileName = files.lastOrNull()?.fileName,
                        message = "Transfer completed",
                        errorMessage = null
                    )
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                getNotificationManager().notify(
                    COMPLETED_NOTIFICATION_ID,
                    buildNotification("Transfer completed", 100, false)
                )
            }.onFailure { error ->
                Log.e(tag, "Foreground upload failed", error)
                UploadStatusBus.update(
                    UploadServiceStatus(
                        isUploading = false,
                        progressPercent = 0,
                        currentFileName = null,
                        message = "Transfer failed",
                        errorMessage = error.message
                    )
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                getNotificationManager().notify(
                    COMPLETED_NOTIFICATION_ID,
                    buildNotification("Transfer failed: ${error.message}", 0, false)
                )
            }

            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(contentText: String, progressPercent: Int, ongoing: Boolean): Notification {
        val openIntent = Intent(this, ShareReceiverActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (ongoing) android.R.drawable.stat_sys_upload
                else android.R.drawable.stat_sys_upload_done
            )
            .setContentTitle("Quick Share Clone")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                if (ongoing) 100 else 0,
                if (ongoing) progressPercent.coerceIn(0, 100) else 0,
                false
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick Share Transfers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows background file transfer progress"
        }

        getNotificationManager().createNotificationChannel(channel)
    }

    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "quick_share_transfers"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETED_NOTIFICATION_ID = 1003
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_FILES = "files"
        private const val EXTRA_ENDPOINT_LABEL = "endpoint_label"

        fun start(context: Context, serverUrl: String, files: List<SharedFileDescriptor>, endpointLabel: String) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_FILES, Json.encodeToString(ListSerializer(SharedFileDescriptor.serializer()), files))
                putExtra(EXTRA_ENDPOINT_LABEL, endpointLabel)
            }

            ContextCompat.startForegroundService(context, intent)
        }
    }
}
