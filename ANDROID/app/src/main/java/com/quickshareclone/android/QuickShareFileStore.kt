package com.quickshareclone.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

object QuickShareFileStore {
    fun saveIncomingFile(
        context: Context,
        fileName: String,
        mimeType: String?,
        inputStream: InputStream,
        onProgress: ((Long) -> Unit)? = null
    ): String {
        val safeName = sanitizeFileName(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, safeName, mimeType, inputStream, onProgress)
        } else {
            saveToLegacyDownloads(context, safeName, inputStream, onProgress)
        }
    }

    private fun saveWithMediaStore(
        context: Context,
        fileName: String,
        mimeType: String?,
        inputStream: InputStream,
        onProgress: ((Long) -> Unit)?
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/QuickShare")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create MediaStore entry for $fileName")

        resolver.openOutputStream(uri)?.use { output ->
            copyStream(inputStream, output, onProgress)
        } ?: error("Unable to open output stream for $fileName")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/QuickShare/$fileName"
    }

    private fun saveToLegacyDownloads(
        context: Context,
        fileName: String,
        inputStream: InputStream,
        onProgress: ((Long) -> Unit)?
    ): String {
        val downloadsDirectory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val quickShareDirectory = File(downloadsDirectory, "QuickShare").apply { mkdirs() }
        val outputFile = File(quickShareDirectory, fileName)
        FileOutputStream(outputFile).use { output ->
            copyStream(inputStream, output, onProgress)
        }
        MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), null, null)
        return outputFile.absolutePath
    }

    private fun copyStream(input: InputStream, output: OutputStream, onProgress: ((Long) -> Unit)?) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalCopied = 0L
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) {
                break
            }

            output.write(buffer, 0, bytesRead)
            totalCopied += bytesRead
            onProgress?.invoke(totalCopied)
        }
        output.flush()
    }

    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "quickshare_${System.currentTimeMillis()}" }
    }
}
