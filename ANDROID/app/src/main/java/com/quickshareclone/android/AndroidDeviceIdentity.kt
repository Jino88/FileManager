package com.quickshareclone.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.Collections

object AndroidDeviceIdentity {
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: Build.MODEL.lowercase()

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifBlank { "Android Device" }

    fun receiveUrl(port: Int): String? {
        val ipAddress = Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { address ->
                !address.isLoopbackAddress && address.hostAddress?.contains(':') == false
            }
            ?.hostAddress
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return ipAddress?.let { "http://$it:$port" }
    }
}
