package com.quickshareclone.android

object UploadConfig {
    const val fallbackServerBaseUrl = "http://192.168.0.10:5070"
    const val chunkSizeBytes = 8 * 1024 * 1024
    const val maxChunkRetries = 3
    const val discoveryPort = 37845
    const val receivePort = 37846
    const val discoveryExpiryMs = 7000L
}
