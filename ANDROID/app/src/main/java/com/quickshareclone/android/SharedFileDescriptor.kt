package com.quickshareclone.android

import android.net.Uri
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SharedFileDescriptor(
    val fileId: String,
    val fileName: String,
    val uri: String,
    val sizeBytes: Long,
) {
    companion object {
        fun create(uri: Uri, fileName: String, sizeBytes: Long): SharedFileDescriptor {
            return SharedFileDescriptor(
                fileId = UUID.randomUUID().toString(),
                fileName = fileName,
                uri = uri.toString(),
                sizeBytes = sizeBytes
            )
        }
    }
}
