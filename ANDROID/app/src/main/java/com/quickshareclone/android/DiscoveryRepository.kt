package com.quickshareclone.android

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class DiscoveryRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val tag = "QuickShareDiscovery"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun observeNearbyDevices(): Flow<List<NearbyDesktopDevice>> = callbackFlow {
        Log.d(tag, "Starting UDP discovery listener on port ${UploadConfig.discoveryPort}")
        val multicastLock = wifiManager.createMulticastLock("quick-share-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        val devices = linkedMapOf<String, DeviceRecord>()
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(UploadConfig.discoveryPort))
        }

        fun publish() {
            val now = System.currentTimeMillis()
            val active = devices.values
                .filter { now - it.seenAt <= UploadConfig.discoveryExpiryMs }
                .sortedByDescending { it.seenAt }
                .map { it.device }
            trySend(active)
        }

        val listenerJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val payload = packet.data.decodeToString(startIndex = 0, endIndex = packet.length)
                    val device = json.decodeFromString<NearbyDesktopDevice>(payload)
                    devices[device.deviceId] = DeviceRecord(device, System.currentTimeMillis())
                    Log.d(tag, "Discovered ${device.deviceName} at ${device.primaryServerUrl}")
                } catch (_: Exception) {
                }

                publish()
            }
        }

        awaitClose {
            Log.d(tag, "Stopping UDP discovery listener")
            listenerJob.cancel()
            socket.close()
            if (multicastLock.isHeld) {
                multicastLock.release()
            }
        }
    }

    private data class DeviceRecord(
        val device: NearbyDesktopDevice,
        val seenAt: Long
    )
}
