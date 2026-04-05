package com.quickshareclone.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class ShareReceiverActivity : ComponentActivity() {
    private val tag = "QuickShareClone"
    private val viewModel: ShareReceiverViewModel by viewModels()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(tag, "Notification permission granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        ReceiveForegroundService.start(this)
        handleIntent(intent)
        viewModel.startDiscovery()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            MaterialTheme {
                ShareScreen(
                    uiState = uiState,
                    onDeviceClick = viewModel::selectDevice,
                    onSendClick = viewModel::uploadToSelectedDevice,
                    onShareSendTargetSelect = viewModel::selectShareSendTarget,
                    onConfirmShareSend = viewModel::confirmShareSend,
                    onDismissShareSend = viewModel::dismissShareSendPrompt,
                    onManualServerUrlChange = viewModel::updateManualServerUrl,
                    onManualServerLabelChange = viewModel::updateManualServerLabel,
                    onToggleSettings = viewModel::toggleSettings,
                    onSaveManualServer = viewModel::saveManualServer,
                    onSavedEndpointClick = viewModel::selectSavedEndpoint,
                    onDeleteSavedEndpoint = viewModel::deleteSavedEndpoint,
                    onApproveInboundTransfer = viewModel::approveInboundTransfer,
                    onDeclineInboundTransfer = viewModel::declineInboundTransfer
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val files = IntentParser.parseSharedFiles(intent, contentResolver)
        Log.d(tag, "Received intent ${intent.action} with ${files.size} file(s)")
        viewModel.setPendingFiles(files)
    }

    private fun ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun ShareScreen(
    uiState: ShareUiState,
    onDeviceClick: (String) -> Unit,
    onSendClick: () -> Unit,
    onShareSendTargetSelect: (String) -> Unit,
    onConfirmShareSend: () -> Unit,
    onDismissShareSend: () -> Unit,
    onManualServerUrlChange: (String) -> Unit,
    onManualServerLabelChange: (String) -> Unit,
    onToggleSettings: () -> Unit,
    onSaveManualServer: () -> Unit,
    onSavedEndpointClick: (String) -> Unit,
    onDeleteSavedEndpoint: (String) -> Unit,
    onApproveInboundTransfer: (String) -> Unit,
    onDeclineInboundTransfer: (String) -> Unit
) {
    Surface(color = Color(0xFFF4EFE7)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFFF6EFE4), Color(0xFFE7F4F0), Color(0xFFFCEEDF))
                    )
                )
                .padding(20.dp)
        ) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    HeroCard(uiState, onToggleSettings)
                }

                item {
                    ConnectionCard(uiState)
                }

                if (uiState.showSettings) {
                    item {
                        ManualConnectCard(
                            uiState = uiState,
                            onManualServerUrlChange = onManualServerUrlChange,
                            onManualServerLabelChange = onManualServerLabelChange,
                            onSaveManualServer = onSaveManualServer
                        )
                    }
                }

                if (uiState.savedEndpoints.isNotEmpty()) {
                    item {
                        SectionTitle("Saved PCs")
                    }

                    items(uiState.savedEndpoints, key = { it.id }) { endpoint ->
                        SavedEndpointCard(
                            endpoint = endpoint,
                            selected = uiState.manualServerConnected && uiState.manualServerUrl == endpoint.serverUrl,
                            canSend = uiState.pendingFiles.isNotEmpty() && !uiState.isUploading,
                            onSelect = { onSavedEndpointClick(endpoint.id) },
                            onSend = {
                                onSavedEndpointClick(endpoint.id)
                                onSendClick()
                            },
                            onDelete = { onDeleteSavedEndpoint(endpoint.id) }
                        )
                    }
                }

                item {
                    AnimatedVisibility(visible = uiState.isUploading || uiState.currentFileName != null) {
                        UploadProgressCard(uiState)
                    }
                }

                item {
                    SectionTitle("Nearby PCs")
                }

                if (uiState.nearbyDevices.isEmpty()) {
                    item {
                        EmptyDiscoveryCard()
                    }
                } else {
                    items(uiState.nearbyDevices, key = { it.deviceId }) { device ->
                        DeviceCard(
                            device = device,
                            selected = device.deviceId == uiState.selectedDeviceId,
                            canSend = uiState.pendingFiles.isNotEmpty() && !uiState.isUploading,
                            onSelect = { onDeviceClick(device.deviceId) },
                            onSend = {
                                onDeviceClick(device.deviceId)
                                onSendClick()
                            }
                        )
                    }
                }

                if (uiState.transferHistory.isNotEmpty()) {
                    item {
                        SectionTitle("Transfer History")
                    }

                    items(uiState.transferHistory, key = { it.id }) { item ->
                        TransferHistoryCard(item)
                    }
                }
            }

            uiState.inboundTransferPrompt?.let { prompt ->
                val message = if (prompt.fileCount > 1) {
                    val previewText = prompt.previewNames
                        .filter { it.isNotBlank() }
                        .joinToString(separator = "\n", prefix = "\n")
                        .ifBlank { "" }
                    "Receive ${prompt.fileCount} file(s) (${formatFileSize(prompt.totalSizeBytes)}) into QuickShare folder?$previewText"
                } else {
                    "Receive ${prompt.fileName} (${formatFileSize(prompt.fileSizeBytes)}) into QuickShare folder?"
                }
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Receive From PC") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = { onApproveInboundTransfer(prompt.requestId) }) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { onDeclineInboundTransfer(prompt.requestId) }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (uiState.shareSendPromptVisible && uiState.pendingFiles.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = onDismissShareSend,
                    title = { Text("Send To Connected PC") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                "${uiState.pendingFiles.size} file(s) ready to send. Choose the current PC and press OK."
                            )
                            if (uiState.shareSendTargets.isEmpty()) {
                                Text(
                                    "No connected PC is available yet. Open the PC app first or wait for discovery.",
                                    color = Color(0xFF6B7280)
                                )
                            } else {
                                uiState.shareSendTargets.forEach { target ->
                                    ShareSendTargetRow(
                                        target = target,
                                        selected = target.id == uiState.shareSendSelectedTargetId,
                                        onClick = { onShareSendTargetSelect(target.id) }
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = onConfirmShareSend,
                            enabled = uiState.shareSendSelectedTargetId != null && uiState.shareSendTargets.isNotEmpty()
                        ) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissShareSend) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F2937)
    )
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0) return "unknown size"
    val sizeMb = sizeBytes / 1024f / 1024f
    return if (sizeMb >= 1024f) {
        String.format("%.2f GB", sizeMb / 1024f)
    } else {
        String.format("%.1f MB", sizeMb)
    }
}

@Composable
private fun ManualConnectCard(
    uiState: ShareUiState,
    onManualServerUrlChange: (String) -> Unit,
    onManualServerLabelChange: (String) -> Unit,
    onSaveManualServer: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCCFFFFFF))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Save manual PC bookmarks here. Actual transfers should use the Send button on Saved PCs or Nearby PCs.",
                color = Color(0xFF6B7280)
            )
            OutlinedTextField(
                value = uiState.manualServerLabel,
                onValueChange = onManualServerLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC name") }
            )
            OutlinedTextField(
                value = uiState.manualServerUrl,
                onValueChange = onManualServerUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC server URL or IP:5070") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onSaveManualServer,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF59E0B),
                        contentColor = Color.White
                    )
                ) {
                    Text("Save Bookmark")
                }
            }
        }
    }
}

@Composable
private fun SavedEndpointCard(
    endpoint: SavedDesktopEndpoint,
    selected: Boolean,
    canSend: Boolean,
    onSelect: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onSelect() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFFFF7ED) else Color(0xEFFFFFFF)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = endpoint.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827)
                    )
                    Text(
                        text = endpoint.serverUrl,
                        color = Color(0xFF6B7280)
                    )
                }
                IconButton(onClick = onDelete) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete saved PC"
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selected) "Ready to use" else "Tap to use",
                    color = if (selected) Color(0xFF9A3412) else Color(0xFF6B7280)
                )
                if (canSend) {
                    Button(
                        onClick = onSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF111827),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(uiState: ShareUiState, onToggleSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xF7FFFFFF)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Quick Share Clone",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF0F766E),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = uiState.headline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF111827),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.connectionMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF4B5563)
                    )
                }
                IconButton(onClick = onToggleSettings) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open settings"
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(uiState: ShareUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCCFFFFFF))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (uiState.manualServerConnected || uiState.selectedDeviceId != null) "Connected" else "Ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(text = uiState.connectionMessage, color = Color(0xFF6B7280))
            if (uiState.pendingFiles.isNotEmpty()) {
                Text(
                    text = "${uiState.pendingFiles.size} file(s) selected",
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.SemiBold
                )
                uiState.pendingFiles.take(2).forEach { file ->
                    Text(text = file.fileName, color = Color(0xFF6B7280))
                }
                if (uiState.pendingFiles.size > 2) {
                    Text(text = "+ ${uiState.pendingFiles.size - 2} more", color = Color(0xFF6B7280))
                }
            }
        }
    }
}

@Composable
private fun UploadProgressCard(uiState: ShareUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102A43))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = uiState.currentFileName ?: "Transfer in progress",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            LinearProgressIndicator(
                progress = { uiState.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF34D399),
                trackColor = Color(0x3344D399)
            )
            Text(
                text = "${uiState.progressPercent}%${uiState.errorMessage?.let { " | $it" } ?: ""}",
                color = Color(0xFFD9E2EC)
            )
        }
    }
}

@Composable
private fun TransferHistoryCard(item: TransferHistoryItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEFFFFFFF))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = item.subtitle, color = Color(0xFF6B7280))
            }
            Text(
                text = item.status,
                color = Color(0xFF0F766E),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ShareSendTargetRow(
    target: ShareSendTarget,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFFFF7ED) else Color(0xFFF9FAFB)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.label,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
                Text(
                    text = target.subtitle,
                    color = Color(0xFF6B7280)
                )
            }
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color(0xFF0F766E) else Color(0xFFD1D5DB))
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: NearbyDesktopDevice,
    selected: Boolean,
    canSend: Boolean,
    onSelect: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable { onSelect() },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFECFDF5) else Color(0xEFFFFFFF)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 6.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = device.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
            }

            Text(
                text = device.primaryServerUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF6B7280)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selected) "Selected for transfer" else "Tap to select",
                    color = if (selected) Color(0xFF047857) else Color(0xFF6B7280)
                )
                if (canSend) {
                    Button(
                        onClick = onSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF111827),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDiscoveryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCCFFFFFF))
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Scanning for nearby PCs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Open the desktop app on the same Wi-Fi. It will broadcast itself automatically over UDP.",
                color = Color(0xFF6B7280)
            )
        }
    }
}
