package com.baystudio.droide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baystudio.droide.core.AndroidDevelopmentManager
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun AndroidDevelopmentSettingsSection(
    manager: AndroidDevelopmentManager,
    bridge: DeviceBridgeManager,
) {
    val context = LocalContext.current
    val scope = rememberWorkspaceCoroutineScope()
    val bridgeState by bridge.state.collectAsState()
    val devState by manager.status.collectAsState()
    val operation by manager.operation.collectAsState()
    var pairingCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmPairingReset by remember { mutableStateOf(false) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    var connectSequence by remember { mutableStateOf(0L) }
    val controlsBusy = busy || operation.running || bridgeState.attemptId != null
    DisposableEffect(bridge) {
        onDispose { connectJob?.cancel() }
    }

    fun hasNearbyPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    fun discover() {
        if (hasNearbyPermission()) {
            runUiCatching(onError = { message = "Device discovery failed: ${it.message}" }) { bridge.startDiscovery() }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) discover() else message = "Nearby devices permission is required for Wireless Debugging discovery."
    }
    LaunchedEffect(manager) { runSuspendCatching { manager.refresh() } }

    HorizontalDivider()
    Text("Ubuntu Android build", style = MaterialTheme.typography.labelLarge)
    Text(
        "Builds run in Ubuntu. Install and select compatible JDK, SDK and Build Tools from Extensions.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(devState.message, style = MaterialTheme.typography.bodySmall)
    devState.toolchainVersion?.let { Text("Toolchain $it", style = MaterialTheme.typography.bodySmall) }
    if (operation.message.isNotBlank()) {
        Text(operation.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (operation.running) operation.progress?.let { progress ->
            Text("${(progress * 100f).toInt()}% · ${operation.completedItems}/${operation.totalItems}", style = MaterialTheme.typography.labelSmall)
        }
    }
    message?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.primary) }
    OutlinedButton(enabled = !controlsBusy, onClick = {
        busy = true
        scope.launch {
            try {
                runSuspendCatching { manager.refresh() }
                    .onSuccess { message = it.message }
                    .onFailure { message = "Ubuntu build environment check failed: ${it.message}" }
            } finally { busy = false }
        }
    }) { Text("Check Ubuntu toolchain") }

    HorizontalDivider()
    Text("Android device connection", style = MaterialTheme.typography.labelLarge)
    Text(
        "Wireless Debugging connects a device for APK deployment, running apps and debugging.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!bridgeState.supported) {
        Text("Wireless Debugging requires Android 11+. Ubuntu builds remain available with a compatible local toolchain.",
            style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !controlsBusy && Build.VERSION.SDK_INT >= 30, onClick = {
            if (bridgeState.discovering) {
                runUiCatching(onError = { message = "Cannot stop device discovery: ${it.message}" }) {
                    bridge.stopDiscovery()
                }
            } else if (hasNearbyPermission()) discover()
            else runUiCatching(onError = { message = "Cannot request Nearby devices permission: ${it.message}" }) {
                permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }) { Text(if (bridgeState.discovering) "Stop discovery" else "Discover") }
        OutlinedButton(onClick = {
            runUiCatching(onError = { message = "Cannot open developer settings: ${it.message}" }) {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Developer options") }
    }
    bridgeState.connected?.let { endpoint ->
        Text("Connected: ${endpoint.name} · ${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
    }
    if (bridgeState.pairEndpoints.isNotEmpty()) {
        Text("Pair device", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("6-digit pairing code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        bridgeState.pairEndpoints.forEach { endpoint ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = pairingCode.length == 6 && !controlsBusy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            runSuspendCatching { bridge.pair(endpoint, pairingCode) }
                                .onSuccess { message = "Paired. Waiting for the Wireless Debugging connect endpoint…"; pairingCode = "" }
                                .onFailure { message = "Pairing failed: ${it.message}" }
                        } finally { busy = false }
                    }
                }) { Text("Pair") }
            }
        }
    }
    val connectRows = (listOfNotNull(bridgeState.connecting) + bridgeState.connectEndpoints).distinct()
    if (connectRows.isNotEmpty()) {
        Text("Available device bridge", style = MaterialTheme.typography.labelMedium)
        connectRows.forEach { endpoint ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
                }
                val active = bridgeState.connecting == endpoint && bridgeState.attemptId != null
                DeviceBridgeConnectButton(
                    connecting = active,
                    enabled = !controlsBusy,
                    onCancel = {
                        if (bridgeState.attemptId?.let(bridge::cancelConnect) == true) {
                            message = "Connection cancelled."
                        }
                    },
                    onConnect = connect@ {
                        if (connectJob?.isActive == true) return@connect
                        val generation = ++connectSequence
                        connectJob = scope.launch {
                            try {
                                val result = bridge.connect(endpoint)
                                if (connectSequence == generation) message = result
                            } catch (_: TimeoutCancellationException) {
                                if (connectSequence == generation) message = "Device connection timed out."
                            } catch (_: CancellationException) {
                                if (connectSequence == generation) message = "Connection cancelled."
                            } catch (error: Exception) {
                                if (connectSequence == generation) message = "Connection failed: ${error.message}"
                            } finally {
                                if (connectSequence == generation) connectJob = null
                            }
                        }
                    },
                )
            }
        }
    }
    bridgeState.lastError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bridgeState.connected != null) {
            OutlinedButton(enabled = !controlsBusy, onClick = {
                busy = true
                scope.launch {
                    try {
                        runSuspendCatching { bridge.disconnect() }
                            .onSuccess { message = "Device disconnected." }
                            .onFailure { message = "Disconnect failed: ${it.message}" }
                    } finally { busy = false }
                }
            }) { Text("Disconnect") }
        }
        OutlinedButton(enabled = !controlsBusy, onClick = { confirmPairingReset = true }) {
            Text("Reset pairing")
        }
    }
    if (confirmPairingReset) {
        AlertDialog(
            onDismissRequest = { confirmPairingReset = false },
            title = { Text("Reset device pairing?") },
            text = { Text("This disconnects the current device and removes Droide's pairing identity. You will need to pair your devices again.") },
            confirmButton = {
                TextButton(enabled = !controlsBusy, onClick = {
                    confirmPairingReset = false
                    busy = true
                    scope.launch {
                        try {
                            runSuspendCatching { bridge.resetPairingIdentity() }
                                .onSuccess { message = "Pairing reset." }
                                .onFailure { message = "Pairing reset failed: ${it.message}" }
                        } finally { busy = false }
                    }
                }) { Text("Reset pairing") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPairingReset = false }) { Text("Cancel") }
            },
        )
    }

}

/** The same control remains enabled as Cancel while its connection attempt is active. */
@Composable
internal fun DeviceBridgeConnectButton(
    connecting: Boolean,
    enabled: Boolean,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Button(enabled = connecting || enabled, onClick = if (connecting) onCancel else onConnect) {
        Text(if (connecting) "Cancel" else "Connect")
    }
}
