package com.quickshareclone.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UploadStatusBus {
    private val _state = MutableStateFlow(UploadServiceStatus())
    val state: StateFlow<UploadServiceStatus> = _state.asStateFlow()

    fun update(status: UploadServiceStatus) {
        _state.value = status
    }
}

data class UploadServiceStatus(
    val isUploading: Boolean = false,
    val progressPercent: Int = 0,
    val currentFileName: String? = null,
    val message: String = "",
    val errorMessage: String? = null
)
