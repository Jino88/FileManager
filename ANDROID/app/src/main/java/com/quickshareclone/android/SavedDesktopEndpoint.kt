package com.quickshareclone.android

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedDesktopEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val serverUrl: String,
    val lastUsedAt: Long = System.currentTimeMillis(),
)
