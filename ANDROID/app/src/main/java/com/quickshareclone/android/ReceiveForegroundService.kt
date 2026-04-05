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
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class ReceiveForegroundService : Service() {
    private val tag = "QuickShareReceive"
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        updateForegroundNotification("Ready to receive from PC", 0, true)
        executor.execute { runServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serverSocket?.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer() {
        try {
            val socket = ServerSocket(UploadConfig.receivePort).apply {
                reuseAddress = true
                soTimeout = 1000
            }
            serverSocket = socket
            Log.d(tag, "Android receive server listening on ${AndroidDeviceIdentity.receiveUrl(UploadConfig.receivePort)}")

            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                } catch (_: SocketTimeoutException) {
                } catch (error: Exception) {
                    if (!socket.isClosed) {
                        Log.w(tag, "Receive server accept failed", error)
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(tag, "Failed to start receive server", error)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val headerText = readHeaderBlock(input)
                if (headerText.isBlank()) {
                    writeResponse(output, 400, """{"message":"Missing request"}""")
                    return
                }

                val request = parseRequest(headerText)
                when {
                    request.method == "GET" && request.path == "/api/device/ping" -> {
                        writeResponse(output, 200, """{"status":"ok"}""")
                    }

                    request.method == "POST" && request.path == "/api/device/receive" -> {
                        handleReceiveRequest(request.headers, input, output)
                    }

                    request.method == "POST" && request.path == "/api/device/offer" -> {
                        handleOfferRequest(request.headers, input, output)
                    }

                    request.method == "GET" && request.path.startsWith("/api/device/offer/status") -> {
                        handleOfferStatusRequest(request.path, output)
                    }

                    else -> {
                        writeResponse(output, 404, """{"message":"Not found"}""")
                    }
                }
            } catch (error: Exception) {
                Log.e(tag, "Failed to handle inbound PC transfer", error)
                val message = error.message ?: "Server error"
                runCatching {
                    writeResponse(client.getOutputStream(), 500, """{"message":"$message"}""")
                }
            }
        }
    }

    private fun handleReceiveRequest(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val fileName = decodeFileName(headers)
        val contentType = headers["content-type"]
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength <= 0) {
            writeResponse(output, 400, """{"message":"Missing content-length"}""")
            return
        }

        val offerId = headers["x-quickshare-offer-id"]
        val isPreApproved = !offerId.isNullOrBlank() && InboundTransferCoordinator.consumeApprovedOffer(offerId)
        if (!isPreApproved) {
            requestUserApproval(fileName, contentLength)
        }
        UploadStatusBus.update(
            UploadServiceStatus(
                isUploading = true,
                progressPercent = 0,
                currentFileName = fileName,
                message = "Receiving from PC",
                errorMessage = null
            )
        )
        val savedPath = QuickShareFileStore.saveIncomingFile(
            applicationContext,
            fileName,
            contentType,
            LimitedInputStream(input, contentLength)
        ) { copiedBytes ->
            val progress = ((copiedBytes.toDouble() / contentLength.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            UploadStatusBus.update(
                UploadServiceStatus(
                    isUploading = true,
                    progressPercent = progress,
                    currentFileName = fileName,
                    message = "Receiving from PC",
                    errorMessage = null
                )
            )
            updateForegroundNotification("Receiving $fileName", progress, true)
        }

        Log.d(tag, "Received $fileName from PC and saved to $savedPath")
        UploadStatusBus.update(
            UploadServiceStatus(
                isUploading = false,
                progressPercent = 100,
                currentFileName = fileName,
                message = "Received from PC",
                errorMessage = null
            )
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        getNotificationManager().notify(
            NOTIFICATION_ID,
            buildNotification("Received $fileName", 100, false)
        )
        writeResponse(output, 200, """{"savedPath":"$savedPath"}""")
    }

    private fun handleOfferRequest(headers: Map<String, String>, input: InputStream, output: OutputStream) {
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) {
            writeResponse(output, 400, """{"message":"Missing offer body"}""")
            return
        }

        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bodyBytes, offset, contentLength - offset)
            if (read <= 0) {
                break
            }
            offset += read
        }

        val payload = JSONObject(String(bodyBytes, 0, offset, Charsets.UTF_8))
        val offerId = payload.optString("offerId")
        val fileCount = payload.optInt("fileCount", 0)
        val totalBytes = payload.optLong("totalBytes", 0L)
        val files = payload.optJSONArray("files") ?: JSONArray()
        val fileNames = buildList {
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                add(item.optString("fileName"))
            }
        }

        if (offerId.isBlank() || fileCount <= 0) {
            writeResponse(output, 400, """{"message":"Invalid offer payload"}""")
            return
        }

        requestBatchApproval(offerId, fileCount, totalBytes, fileNames)
        writeResponse(output, 202, """{"offerId":"$offerId","status":"pending"}""")
    }

    private fun handleOfferStatusRequest(path: String, output: OutputStream) {
        val offerId = path.substringAfter("offerId=", "")
        if (offerId.isBlank()) {
            writeResponse(output, 400, """{"message":"Missing offerId"}""")
            return
        }

        val status = InboundTransferCoordinator.getOfferStatus(offerId)
        val statusCode = when (status) {
            "approved", "pending" -> 200
            "declined" -> 409
            else -> 404
        }
        writeResponse(output, statusCode, """{"offerId":"$offerId","status":"$status"}""")
    }

    private fun decodeFileName(headers: Map<String, String>): String {
        val encoded = headers["x-quickshare-file-name-base64"]
        if (!encoded.isNullOrBlank()) {
            return runCatching {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("quickshare_${System.currentTimeMillis()}")
        }

        return headers["x-quickshare-file-name"] ?: "quickshare_${System.currentTimeMillis()}"
    }

    private fun requestUserApproval(fileName: String, contentLength: Long) {
        val approval = InboundTransferCoordinator.requestApproval(fileName, contentLength)
        val approved = awaitApproval(approval, fileName)
        if (!approved) {
            throw IllegalStateException("Transfer declined on Android.")
        }
    }

    private fun requestBatchApproval(offerId: String, fileCount: Int, totalSizeBytes: Long, fileNames: List<String>) {
        val approval = InboundTransferCoordinator.requestBatchApproval(offerId, fileCount, totalSizeBytes, fileNames)
        val label = if (fileCount > 1) "$fileCount files" else fileNames.firstOrNull().orEmpty()
        executor.execute {
            awaitApproval(approval, label)
        }
    }

    private fun awaitApproval(approval: kotlinx.coroutines.CompletableDeferred<Boolean>, label: String): Boolean {
        UploadStatusBus.update(
            UploadServiceStatus(
                isUploading = true,
                progressPercent = 0,
                currentFileName = label,
                message = "Waiting for receive approval",
                errorMessage = null
            )
        )
        val openIntent = Intent(this, ShareReceiverActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(openIntent)

        val approved = runBlocking {
            withTimeoutOrNull(60_000L) {
                approval.await()
            }
        } ?: false

        if (!approved) {
            UploadStatusBus.update(
                UploadServiceStatus(
                    isUploading = false,
                    progressPercent = 0,
                    currentFileName = label,
                    message = "Receive cancelled",
                    errorMessage = "Transfer declined"
                )
            )
            return false
        }

        return true
    }

    private fun parseRequest(headerText: String): ParsedRequest {
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) {
                    null
                } else {
                    line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
                }
            }
            .toMap()

        return ParsedRequest(
            method = parts.getOrNull(0).orEmpty(),
            path = parts.getOrNull(1).orEmpty(),
            headers = headers
        )
    }

    private fun readHeaderBlock(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val next = input.read()
            if (next == -1) {
                break
            }

            buffer.write(next)
            matched = when {
                matched == 0 && next == '\r'.code -> 1
                matched == 1 && next == '\n'.code -> 2
                matched == 2 && next == '\r'.code -> 3
                matched == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }

            if (matched == 4) {
                break
            }
        }

        return buffer.toByteArray().decodeToString().removeSuffix("\r\n\r\n")
    }

    private fun writeResponse(output: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val bodyBytes = body.toByteArray()
        val response = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray()

        output.write(response)
        output.write(bodyBytes)
        output.flush()
    }

    private fun buildNotification(contentText: String, progressPercent: Int = 0, ongoing: Boolean = true): Notification {
        val intent = Intent(this, ShareReceiverActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Quick Share Clone")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(if (ongoing) 100 else 0, if (ongoing) progressPercent else 0, false)
            .build()
    }

    private fun updateForegroundNotification(contentText: String, progressPercent: Int, ongoing: Boolean) {
        val notification = buildNotification(contentText, progressPercent, ongoing)
        startForeground(NOTIFICATION_ID, notification)
        getNotificationManager().notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick Share Receiver",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Keeps Android ready to receive files from PC"
        }

        getNotificationManager().createNotificationChannel(channel)
    }

    private fun getNotificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "quick_share_receiver"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, ReceiveForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private data class ParsedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>
)

private class LimitedInputStream(
    private val upstream: InputStream,
    private var remainingBytes: Long
) : InputStream() {
    override fun read(): Int {
        if (remainingBytes <= 0) {
            return -1
        }

        val value = upstream.read()
        if (value != -1) {
            remainingBytes--
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remainingBytes <= 0) {
            return -1
        }

        val cappedLength = minOf(length.toLong(), remainingBytes).toInt()
        val bytesRead = upstream.read(buffer, offset, cappedLength)
        if (bytesRead > 0) {
            remainingBytes -= bytesRead.toLong()
        }
        return bytesRead
    }
}
