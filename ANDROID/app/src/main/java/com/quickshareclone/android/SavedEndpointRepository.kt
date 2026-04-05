package com.quickshareclone.android

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SavedEndpointRepository(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val preferences = context.getSharedPreferences("quick_share_clone_saved_endpoints", Context.MODE_PRIVATE)

    fun load(): List<SavedDesktopEndpoint> {
        val raw = preferences.getString(KEY_ENDPOINTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(SavedDesktopEndpoint.serializer()), raw)
                .sortedByDescending { it.lastUsedAt }
        }.getOrElse {
            emptyList()
        }
    }

    fun save(endpoint: SavedDesktopEndpoint): List<SavedDesktopEndpoint> {
        val updated = load()
            .filterNot { it.id == endpoint.id || it.serverUrl.equals(endpoint.serverUrl, ignoreCase = true) }
            .plus(endpoint.copy(lastUsedAt = System.currentTimeMillis()))
            .sortedByDescending { it.lastUsedAt }

        persist(updated)
        return updated
    }

    fun delete(id: String): List<SavedDesktopEndpoint> {
        val updated = load().filterNot { it.id == id }
        persist(updated)
        return updated
    }

    private fun persist(endpoints: List<SavedDesktopEndpoint>) {
        preferences.edit()
            .putString(KEY_ENDPOINTS, json.encodeToString(ListSerializer(SavedDesktopEndpoint.serializer()), endpoints))
            .apply()
    }

    private companion object {
        const val KEY_ENDPOINTS = "saved_endpoints"
    }
}
