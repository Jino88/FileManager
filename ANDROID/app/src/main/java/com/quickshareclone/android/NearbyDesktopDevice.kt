package com.quickshareclone.android

import kotlinx.serialization.Serializable

@Serializable
data class NearbyDesktopDevice(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val serverUrls: List<String>,
    val lastUpdatedAt: String,
)

val NearbyDesktopDevice.primaryServerUrl: String
    get() = serverUrls.firstOrNull().orEmpty()
