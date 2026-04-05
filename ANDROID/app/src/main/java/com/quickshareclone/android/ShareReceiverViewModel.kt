package com.quickshareclone.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ShareReceiverViewModel(application: Application) : AndroidViewModel(application) {
    private val tag = "QuickShareClone"
    private val discoveryRepository = DiscoveryRepository(application)
    private val savedEndpointRepository = SavedEndpointRepository(application)
    private val deviceRegistrationRepository = DeviceRegistrationRepository(application)

    private val _uiState = MutableStateFlow(
        rebuildShareTargets(
            ShareUiState(
                savedEndpoints = savedEndpointRepository.load()
            )
        )
    )
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    private var discoveryJob: Job? = null
    private var uploadStatusJob: Job? = null
    private var inboundPromptJob: Job? = null

    fun setPendingFiles(files: List<SharedFileDescriptor>) {
        _uiState.update { current ->
            rebuildShareTargets(
                current.copy(
                    pendingFiles = files,
                    headline = if (files.isEmpty()) {
                        "Nearby PCs"
                    } else {
                        "Ready to send ${files.size} item(s)"
                    },
                    shareSendPromptVisible = files.isNotEmpty()
                )
            )
        }
    }

    fun updateManualServerUrl(value: String) {
        _uiState.update {
            it.copy(
                manualServerUrl = value,
                manualServerLabel = inferLabelFromUrl(value, it.manualServerLabel),
                errorMessage = null
            )
        }
    }

    fun updateManualServerLabel(value: String) {
        _uiState.update {
            it.copy(
                manualServerLabel = value,
                errorMessage = null
            )
        }
    }

    fun toggleSettings() {
        _uiState.update {
            it.copy(showSettings = !it.showSettings)
        }
    }

    fun saveManualServer() {
        val state = _uiState.value
        val normalized = normalizeServerUrl(state.manualServerUrl)
        if (normalized.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter a valid PC address first.") }
            return
        }

        val label = state.manualServerLabel.ifBlank {
            normalized.removePrefix("http://").removePrefix("https://")
        }

        val saved = savedEndpointRepository.save(
            SavedDesktopEndpoint(
                label = label,
                serverUrl = normalized
            )
        )
        Log.d(tag, "Saved manual endpoint: $label -> $normalized")
        _uiState.update {
            rebuildShareTargets(
                it.copy(
                    savedEndpoints = saved,
                    manualServerUrl = normalized,
                    manualServerLabel = label,
                    manualServerConnected = false,
                    connectionMessage = "Saved PC bookmark added",
                    showSettings = false,
                    hasEstablishedConnection = false
                )
            )
        }
        registerWithPc(normalized)
    }

    fun selectSavedEndpoint(id: String) {
        val endpoint = _uiState.value.savedEndpoints.firstOrNull { it.id == id } ?: return
        Log.d(tag, "Selected saved endpoint: ${endpoint.label}")
        stopDiscovery("Connected to ${endpoint.label}")
        _uiState.update {
            rebuildShareTargets(
                it.copy(
                    manualServerUrl = endpoint.serverUrl,
                    manualServerLabel = endpoint.label,
                    manualServerConnected = true,
                    selectedDeviceId = null,
                    connectionMessage = "Connected to ${endpoint.label}",
                    errorMessage = null,
                    hasEstablishedConnection = true
                )
            )
        }
        registerWithPc(endpoint.serverUrl)
    }

    fun deleteSavedEndpoint(id: String) {
        val saved = savedEndpointRepository.delete(id)
        _uiState.update {
            rebuildShareTargets(it.copy(savedEndpoints = saved))
        }
    }

    fun startDiscovery() {
        if (discoveryJob != null) {
            return
        }

        Log.d(tag, "Starting nearby device discovery")
        registerSavedEndpoints(savedEndpointRepository.load())
        discoveryJob = viewModelScope.launch {
            discoveryRepository.observeNearbyDevices().collect { devices ->
                Log.d(tag, "Nearby device count: ${devices.size}")
                devices.forEach { registerWithPc(it.primaryServerUrl) }
                _uiState.update { current ->
                    rebuildShareTargets(
                        current.copy(
                            nearbyDevices = devices,
                            selectedDeviceId = current.selectedDeviceId ?: devices.firstOrNull()?.deviceId,
                            connectionMessage = if (current.hasEstablishedConnection) {
                                current.connectionMessage
                            } else if (devices.isEmpty()) {
                                "Searching the local network for your PC..."
                            } else {
                                "Nearby PC discovered automatically"
                            }
                        )
                    )
                }
            }
        }

        if (uploadStatusJob == null) {
            uploadStatusJob = viewModelScope.launch {
                UploadStatusBus.state.collectLatest { status ->
                    _uiState.update {
                        val updatedHistory = when {
                            status.currentFileName.isNullOrBlank() -> it.transferHistory
                            else -> upsertHistory(
                                it.transferHistory,
                                TransferHistoryItem(
                                    id = status.currentFileName,
                                    title = status.currentFileName,
                                    subtitle = status.message.ifBlank { "Transfer" },
                                    status = when {
                                        status.errorMessage != null -> "Failed"
                                        status.isUploading -> "${status.progressPercent}%"
                                        status.progressPercent >= 100 -> "Completed"
                                        else -> "Waiting"
                                    }
                                )
                            )
                        }
                        it.copy(
                            isUploading = status.isUploading,
                            progressPercent = status.progressPercent,
                            currentFileName = status.currentFileName,
                            connectionMessage = if (status.message.isNotBlank()) status.message else it.connectionMessage,
                            errorMessage = status.errorMessage,
                            transferHistory = updatedHistory
                        )
                    }
                }
            }
        }

        if (inboundPromptJob == null) {
            inboundPromptJob = viewModelScope.launch {
                InboundTransferCoordinator.prompt.collectLatest { prompt ->
                    _uiState.update {
                        it.copy(inboundTransferPrompt = prompt)
                    }
                }
            }
        }
    }

    fun selectDevice(deviceId: String) {
        Log.d(tag, "Selected device: $deviceId")
        val selectedDevice = _uiState.value.nearbyDevices.firstOrNull { it.deviceId == deviceId }
        stopDiscovery(
            selectedDevice?.let { "Connected to ${it.deviceName}" } ?: "Connected to nearby PC"
        )
        _uiState.update {
            rebuildShareTargets(
                it.copy(
                    selectedDeviceId = deviceId,
                    manualServerConnected = false,
                    connectionMessage = selectedDevice?.let { "Connected to ${it.deviceName}" }
                        ?: "Connected to nearby PC",
                    hasEstablishedConnection = true
                )
            )
        }
    }

    fun uploadToSelectedDevice() {
        val state = _uiState.value
        val selected = state.nearbyDevices.firstOrNull { it.deviceId == state.selectedDeviceId }
        val serverUrl = when {
            state.manualServerConnected -> normalizeServerUrl(state.manualServerUrl)
            selected != null -> selected.primaryServerUrl.ifBlank { UploadConfig.fallbackServerBaseUrl }
            else -> ""
        }
        val endpointName = selected?.deviceName ?: state.manualServerLabel.ifBlank { serverUrl }
        startUpload(serverUrl, endpointName, state.manualServerConnected)
    }

    fun selectShareSendTarget(targetId: String) {
        _uiState.update {
            it.copy(shareSendSelectedTargetId = targetId)
        }
    }

    fun dismissShareSendPrompt() {
        _uiState.update {
            it.copy(shareSendPromptVisible = false)
        }
    }

    fun confirmShareSend() {
        val state = _uiState.value
        val target = state.shareSendTargets.firstOrNull { it.id == state.shareSendSelectedTargetId }
        if (target == null) {
            _uiState.update {
                it.copy(errorMessage = "Choose a PC first.")
            }
            return
        }

        startUpload(target.serverUrl, target.label, target.isSavedEndpoint)
    }

    private fun startUpload(serverUrl: String, endpointName: String, manualConnection: Boolean) {
        val state = _uiState.value
        if (state.pendingFiles.isEmpty()) {
            Log.d(tag, "Upload requested without pending files")
            return
        }

        if (serverUrl.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Choose a nearby PC or enter a manual PC address first.")
            }
            return
        }

        Log.d(tag, "Starting upload to $endpointName")
        val saved = savedEndpointRepository.save(
            SavedDesktopEndpoint(
                label = endpointName,
                serverUrl = serverUrl
            )
        )

        UploadForegroundService.start(getApplication(), serverUrl, state.pendingFiles, endpointName)
        registerWithPc(serverUrl)
        _uiState.update {
            rebuildShareTargets(
                it.copy(
                    isUploading = true,
                    progressPercent = 0,
                    connectionMessage = "Background transfer started",
                    currentFileName = state.pendingFiles.firstOrNull()?.fileName,
                    savedEndpoints = saved,
                    manualServerConnected = manualConnection,
                    manualServerUrl = if (manualConnection) serverUrl else it.manualServerUrl,
                    manualServerLabel = if (manualConnection) endpointName else it.manualServerLabel,
                    errorMessage = null,
                    hasEstablishedConnection = true,
                    shareSendPromptVisible = false
                )
            )
        }
    }

    private fun registerSavedEndpoints(endpoints: List<SavedDesktopEndpoint>) {
        endpoints.forEach { registerWithPc(it.serverUrl) }
    }

    private fun stopDiscovery(connectionMessage: String) {
        discoveryJob?.cancel()
        discoveryJob = null
        _uiState.update {
            rebuildShareTargets(it.copy(connectionMessage = connectionMessage))
        }
    }

    private fun registerWithPc(serverUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            deviceRegistrationRepository.registerWithPc(serverUrl)
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

    private fun inferLabelFromUrl(url: String, currentLabel: String): String {
        if (currentLabel.isNotBlank()) {
            return currentLabel
        }

        val normalized = normalizeServerUrl(url)
        return normalized.removePrefix("http://").removePrefix("https://")
    }

    fun approveInboundTransfer(requestId: String) {
        InboundTransferCoordinator.approve(requestId)
    }

    fun declineInboundTransfer(requestId: String) {
        InboundTransferCoordinator.decline(requestId)
    }

    private fun upsertHistory(
        history: List<TransferHistoryItem>,
        item: TransferHistoryItem
    ): List<TransferHistoryItem> {
        val filtered = history.filterNot { it.id == item.id }
        return listOf(item) + filtered
    }

    private fun rebuildShareTargets(state: ShareUiState): ShareUiState {
        val targets = buildList<ShareSendTarget> {
            state.savedEndpoints.forEach { endpoint ->
                add(
                    ShareSendTarget(
                        id = "saved:${endpoint.id}",
                        label = endpoint.label,
                        serverUrl = endpoint.serverUrl,
                        subtitle = endpoint.serverUrl,
                        isSavedEndpoint = true
                    )
                )
            }

            state.nearbyDevices.forEach { device ->
                if (this.none { existing -> existing.serverUrl == device.primaryServerUrl }) {
                    add(
                        ShareSendTarget(
                            id = "nearby:${device.deviceId}",
                            label = device.deviceName,
                            serverUrl = device.primaryServerUrl,
                            subtitle = device.primaryServerUrl,
                            isSavedEndpoint = false
                        )
                    )
                }
            }
        }

        val selectedTargetId = when {
            state.shareSendSelectedTargetId != null && targets.any { it.id == state.shareSendSelectedTargetId } ->
                state.shareSendSelectedTargetId
            state.manualServerConnected ->
                targets.firstOrNull { it.serverUrl == state.manualServerUrl }?.id
            state.selectedDeviceId != null ->
                targets.firstOrNull { it.id == "nearby:${state.selectedDeviceId}" }?.id
            else -> targets.firstOrNull()?.id
        }

        return state.copy(
            shareSendTargets = targets,
            shareSendSelectedTargetId = selectedTargetId
        )
    }
}

data class ShareUiState(
    val headline: String = "Nearby PCs",
    val connectionMessage: String = "Searching the local network for your PC...",
    val pendingFiles: List<SharedFileDescriptor> = emptyList(),
    val nearbyDevices: List<NearbyDesktopDevice> = emptyList(),
    val savedEndpoints: List<SavedDesktopEndpoint> = emptyList(),
    val selectedDeviceId: String? = null,
    val manualServerUrl: String = UploadConfig.fallbackServerBaseUrl,
    val manualServerLabel: String = "",
    val showSettings: Boolean = false,
    val manualServerConnected: Boolean = false,
    val isUploading: Boolean = false,
    val progressPercent: Int = 0,
    val currentFileName: String? = null,
    val errorMessage: String? = null,
    val inboundTransferPrompt: InboundTransferPrompt? = null,
    val transferHistory: List<TransferHistoryItem> = emptyList(),
    val hasEstablishedConnection: Boolean = false,
    val shareSendPromptVisible: Boolean = false,
    val shareSendTargets: List<ShareSendTarget> = emptyList(),
    val shareSendSelectedTargetId: String? = null,
)

data class TransferHistoryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val status: String,
)

data class ShareSendTarget(
    val id: String,
    val label: String,
    val serverUrl: String,
    val subtitle: String,
    val isSavedEndpoint: Boolean,
)
