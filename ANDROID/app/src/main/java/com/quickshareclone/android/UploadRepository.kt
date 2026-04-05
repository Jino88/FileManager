package com.quickshareclone.android

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

class UploadRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "QuickShareUpload"

    suspend fun uploadFiles(
        serverBaseUrl: String,
        files: List<SharedFileDescriptor>,
        onProgress: (UploadProgress) -> Unit
    ) {
        Log.d(tag, "Uploading ${files.size} file(s) to $serverBaseUrl")
        files.forEachIndexed { index, file ->
            uploadSingleFile(serverBaseUrl, file) { progress ->
                onProgress(UploadProgress(index + 1, files.size, file.fileName, progress))
            }
        }
    }

    private fun uploadSingleFile(serverBaseUrl: String, file: SharedFileDescriptor, onProgress: (Int) -> Unit) {
        val resolvedSizeBytes = resolveFileSize(file)
        val estimatedTotalChunks = computeTotalChunks(resolvedSizeBytes)
        requestUpload(serverBaseUrl, file, estimatedTotalChunks, resolvedSizeBytes)
        waitForDestinationSelection(serverBaseUrl, file.fileId)
        val status = getUploadStatus(serverBaseUrl, file.fileId)
        val uri = Uri.parse(file.uri)
        val received = status.receivedChunks.toHashSet()
        Log.d(tag, "Preparing ${file.fileName} with estimated $estimatedTotalChunks chunk(s), resolved size=$resolvedSizeBytes, resume has ${received.size} chunk(s)")

        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(UploadConfig.chunkSizeBytes)
            var chunkIndex = 0
            var bytesRead: Int

            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (chunkIndex !in received) {
                    val tempChunk = writeTempChunk(file.fileId, chunkIndex, buffer, bytesRead)
                    try {
                        val advertisedTotalChunks = maxOf(estimatedTotalChunks, chunkIndex + 1)
                        Log.d(tag, "Uploading chunk $chunkIndex/$advertisedTotalChunks for ${file.fileName}")
                        uploadChunkWithRetry(serverBaseUrl, file, chunkIndex, advertisedTotalChunks, tempChunk)
                    } finally {
                        tempChunk.delete()
                    }
                }

                chunkIndex++
                val progressTotal = maxOf(estimatedTotalChunks, chunkIndex)
                onProgress(((chunkIndex.toDouble() / progressTotal.toDouble()) * 100).toInt().coerceIn(0, 99))
            }

            onProgress(100)
            completeUpload(serverBaseUrl, file.fileId, file.fileName, chunkIndex)
            Log.d(tag, "Completed ${file.fileName} with $chunkIndex chunk(s)")
            return
        } ?: error("Unable to open shared file stream")
    }

    private fun requestUpload(serverBaseUrl: String, file: SharedFileDescriptor, totalChunks: Int, totalBytes: Long) {
        val payload = """
            {"fileId":"${file.fileId}","fileName":"${file.fileName.replace("\"", "\\\"")}","totalChunks":$totalChunks,"totalBytes":${totalBytes.coerceAtLeast(0)}}
        """.trimIndent()

        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/upload/request")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Upload request failed with ${response.code}")
        }
    }

    private fun waitForDestinationSelection(serverBaseUrl: String, fileId: String) {
        repeat(120) {
            val status = getUploadStatus(serverBaseUrl, fileId)
            if (status.destinationSelected) {
                Log.d(tag, "Destination selected on PC for $fileId")
                return
            }

            Thread.sleep(1000)
        }

        error("Timed out waiting for PC destination selection.")
    }

    private fun computeTotalChunks(sizeBytes: Long): Int =
        if (sizeBytes <= 0L) 1 else ceil(sizeBytes.toDouble() / UploadConfig.chunkSizeBytes.toDouble()).toInt()

    private fun resolveFileSize(file: SharedFileDescriptor): Long {
        if (file.sizeBytes > 0) {
            return file.sizeBytes
        }

        val uri = Uri.parse(file.uri)
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                val length = descriptor.length
                if (length > 0) {
                    return length
                }
            }
        }.onFailure { error ->
            Log.w(tag, "Failed to resolve asset length for ${file.fileName}", error)
        }

        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val length = descriptor.statSize
                if (length > 0) {
                    return length
                }
            }
        }.onFailure { error ->
            Log.w(tag, "Failed to resolve file descriptor length for ${file.fileName}", error)
        }

        Log.w(tag, "Falling back to single-chunk upload because size could not be resolved for ${file.fileName}")
        return file.sizeBytes
    }

    private fun writeTempChunk(fileId: String, chunkIndex: Int, buffer: ByteArray, bytesRead: Int): File {
        val tempDir = File(context.cacheDir, "upload_chunks").apply { mkdirs() }
        val file = File(tempDir, "${fileId}_$chunkIndex.part")
        FileOutputStream(file).use { output -> output.write(buffer, 0, bytesRead) }
        return file
    }

    private fun uploadChunkWithRetry(
        serverBaseUrl: String,
        file: SharedFileDescriptor,
        chunkIndex: Int,
        totalChunks: Int,
        tempChunk: File
    ) {
        var lastError: Exception? = null
        repeat(UploadConfig.maxChunkRetries) {
            try {
                uploadChunk(serverBaseUrl, file, chunkIndex, totalChunks, tempChunk)
                return
            } catch (ex: Exception) {
                lastError = ex
            }
        }
        throw lastError ?: IllegalStateException("Chunk upload failed")
    }

    private fun uploadChunk(serverBaseUrl: String, file: SharedFileDescriptor, chunkIndex: Int, totalChunks: Int, tempChunk: File) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("fileId", file.fileId)
            .addFormDataPart("fileName", file.fileName)
            .addFormDataPart("chunkIndex", chunkIndex.toString())
            .addFormDataPart("totalChunks", totalChunks.toString())
            .addFormDataPart("chunk", tempChunk.name, tempChunk.asRequestBody("application/octet-stream".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/upload/chunk")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Chunk upload failed with ${response.code}")
        }
    }

    private fun completeUpload(serverBaseUrl: String, fileId: String, fileName: String, totalChunks: Int) {
        val payload = """{"fileId":"$fileId","fileName":"${fileName.replace("\"", "\\\"")}","totalChunks":$totalChunks}"""
        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/upload/complete")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Complete upload failed with ${response.code}")
        }
    }

    private fun getUploadStatus(serverBaseUrl: String, fileId: String): UploadStatusResponse {
        val request = Request.Builder()
            .url("${serverBaseUrl.trimEnd('/')}/api/upload/status?fileId=$fileId")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return UploadStatusResponse(fileId, emptyList())
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }
}

@Serializable
data class UploadStatusResponse(
    @SerialName("fileId") val fileId: String,
    @SerialName("receivedChunks") val receivedChunks: List<Int>,
    @SerialName("destinationSelected") val destinationSelected: Boolean = false
)

data class UploadProgress(
    val currentFileIndex: Int,
    val totalFiles: Int,
    val fileName: String,
    val progressPercent: Int
)
