package com.quickshareclone.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns

object IntentParser {
    fun parseSharedFiles(intent: Intent, contentResolver: ContentResolver): List<SharedFileDescriptor> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }

        return uris.distinct().mapNotNull { uri ->
            val fileName = queryColumn(contentResolver, uri, OpenableColumns.DISPLAY_NAME) ?: "shared_file"
            val size = queryColumn(contentResolver, uri, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L
            SharedFileDescriptor.create(uri, fileName, size)
        }
    }

    private fun queryColumn(contentResolver: ContentResolver, uri: Uri, columnName: String): String? {
        contentResolver.query(uri, arrayOf(columnName), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(columnName)
            if (index == -1) return null
            return cursor.getString(index)
        }

        return null
    }
}
