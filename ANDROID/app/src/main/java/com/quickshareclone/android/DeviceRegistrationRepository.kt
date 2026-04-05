package com.quickshareclone.android

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

class DeviceRegistrationRepository(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json
) {
    private val tag = "QuickShareRegister"
    private val lastRegistrationTimes = ConcurrentHashMap<String, Long>()

    fun registerWithPc(serverBaseUrl: String) {
        val normalizedServerUrl = normalizeServerUrl(serverBaseUrl)
        if (normalizedServerUrl.isBlank()) {
            return
        }

        val receiveUrl = AndroidDeviceIdentity.receiveUrl(UploadConfig.receivePort)
        if (receiveUrl.isNullOrBlank()) {
            Log.w(tag, "Skipping registration because no local receive URL could be resolved")
            return
        }

        val now = System.currentTimeMillis()
        val lastRegistration = lastRegistrationTimes[normalizedServerUrl] ?: 0L
        if (now - lastRegistration < 5_000L) {
            return
        }

        val payload = json.encodeToString(
            AndroidRegistrationPayload(
                deviceId = AndroidDeviceIdentity.deviceId(context),
                deviceName = AndroidDeviceIdentity.deviceName(),
                platform = "android",
                receiveUrl = receiveUrl
            )
        )

        val request = Request.Builder()
            .url("${normalizedServerUrl.trimEnd('/')}/api/android/register")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Registration failed with ${response.code}")
                }
            }
            lastRegistrationTimes[normalizedServerUrl] = now
            Log.d(tag, "Registered Android receiver with $normalizedServerUrl as $receiveUrl")
        }.onFailure { error ->
            Log.w(tag, "Android registration failed for $normalizedServerUrl", error)
        }
    }

    private fun normalizeServerUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return ""
        }

        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}

@kotlinx.serialization.Serializable
private data class AndroidRegistrationPayload(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val receiveUrl: String
)
