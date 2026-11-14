package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File
import com.baystudio.droide.core.AndroidDevelopmentManager
import com.baystudio.droide.core.DevelopmentExtensionsManager
import com.baystudio.droide.core.ExtensionUpdatePolicy
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionState
import com.baystudio.droide.core.ExtensionVersionState
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.resolvedVersion
import com.baystudio.droide.core.UbuntuAptCatalog
import com.baystudio.droide.core.LanguageRuntimePresentation
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ExtensionSelection(val familyId: String, val version: String)

/** Keep deep-link navigation pending until discovery replaces the initial detection placeholders. */
@Composable
internal fun ExtensionNavigationEffects(
    items: List<ExtensionVersionState>,
    initialFamilyId: String?,
    initialSnapshotPending: Boolean,
    selection: ExtensionSelection?,
    onSelect: (ExtensionSelection?) -> Unit,
    onInitialFamilyHandled: () -> Unit,
) {
    LaunchedEffect(items, initialFamilyId, initialSnapshotPending, selection) {
        if (initialFamilyId != null) {
            if (!initialSnapshotPending) {
                extensionDefaultChoice(items.filter { it.family.id == initialFamilyId })?.let { choice ->
                    onSelect(ExtensionSelection(choice.family.id, choice.version.version))
                    onInitialFamilyHandled()
                }
            }
        } else if (selection != null && items.none {
                it.family.id == selection.familyId && it.version.version == selection.version
            }) {
            val replacement = extensionDefaultChoice(items.filter { it.family.id == selection.familyId })
            onSelect(replacement?.let { ExtensionSelection(it.family.id, it.version.version) })
        }
    }
}

/** Lifecycle refreshes only; the toolbar's explicit refresh remains a fresh manager call. */
@Composable
internal fun ExtensionCatalogRefreshEffects(
    managerIdentity: Any,
    activeFile: String,
    bridgeConnection: Any?,
    installerState: com.baystudio.droide.core.ManagedExtensionInstaller.State,
    refresh: suspend (String) -> Unit,
    onFailure: (String) -> Unit,
    environmentRevision: Long = 0L,
) {
    // This is one view's handled-input marker, not a catalog cache. Explicit
    // refreshes and every real terminal installer transition remain fresh requests.
    var handledInputs by remember(managerIdentity) { mutableStateOf<Triple<String, Any?, Long>?>(null) }
    LaunchedEffect(managerIdentity, activeFile, bridgeConnection, installerState.phase, installerState.operationActive, environmentRevision) {
        if (installerState.running) return@LaunchedEffect
        val inputs = Triple(activeFile, bridgeConnection, environmentRevision)
        val inputsChanged = handledInputs != inputs
        val terminalPhase = installerState.phase in setOf(
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.READY,
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
            com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED,
        )
        if (!inputsChanged && !terminalPhase) return@LaunchedEffect
        val result = runSuspendCatching { refresh(activeFile) }
        // A non-cooperative callback can return/fail after cancellation. It cannot
        // publish an obsolete error or mark these inputs as handled.
        currentCoroutineContext().ensureActive()
        result.onFailure { if (inputsChanged) onFailure(it.message ?: "Extensions refresh failed") }
        // Cancellation escapes runSuspendCatching. Do not mark cancelled inputs as
        // handled: a phase change must not strand the latest file/bridge request.
        handledInputs = inputs
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsPane(
    manager: DevelopmentExtensionsManager,
    androidDevelopment: AndroidDevelopmentManager,
    deviceBridge: DeviceBridgeManager,
    activeFile: String,
    initialFamilyId: String? = null,
    environmentRevision: Long = 0L,
    onInitialFamilyHandled: () -> Unit = {},
) {
    manager.temporaryRecoveryReason?.let { reason ->
        Text(reason + " Normal startup returns after the app process restarts.", modifier = Modifier.padding(16.dp))
        return
    }

    val snapshot by manager.snapshot.collectAsState()
    val installerState by manager.installer.state.collectAsState()
    val marketplaceSnapshot by manager.marketplace.snapshot.collectAsState()
    val bridgeState by deviceBridge.state.collectAsState()
    val scope = rememberWorkspaceCoroutineScope()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ExtensionCategory?>(null) }
    var selectedIdentity by remember(manager) { mutableStateOf<ExtensionSelection?>(null) }
    var selectedMarketplace by remember { mutableStateOf<com.baystudio.droide.core.MarketplaceExtensionSummary?>(null) }
    var refreshMessage by remember { mutableStateOf<String?>(null) }

    ExtensionNavigationEffects(
        items = snapshot.items,
        initialFamilyId = initialFamilyId,
        initialSnapshotPending = snapshot.message == "Checking extensions…",
        selection = selectedIdentity,
        onSelect = { selectedIdentity = it; selectedMarketplace = null },
        onInitialFamilyHandled = onInitialFamilyHandled,
    )

    // Keep detail status and actions tied to the same snapshot as the catalog.
    val selection = selectedIdentity
    val selected = selection?.let { identity ->
        snapshot.items.firstOrNull { it.family.id == identity.familyId && it.version.version == identity.version }
    }
    ExtensionCatalogRefreshEffects(
        manager, activeFile, bridgeState.connected, installerState,
        refresh = { manager.refresh(it); Unit },
        environmentRevision = environmentRevision,
        onFailure = { refreshMessage = it },
    )

    LaunchedEffect(manager, query, category) {
        val remoteQuery = query.trim()
        if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
            delay(350)
            manager.marketplace.search(remoteQuery)
        }
    }

    val filtered = remember(snapshot.items, query, category) {
        extensionCatalogCards(snapshot.items, query, category)
    }
    val remoteQuery = query.trim()
    val remoteResults = marketplaceSnapshot.page?.extensions
        ?.takeIf { category == null && marketplaceSnapshot.query.equals(remoteQuery, ignoreCase = true) }
        .orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                modifier = Modifier.weight(1f),
                label = { Text("Search extensions") },
                singleLine = true,
            )
            IconButton(onClick = {
                scope.launch {
                    runSuspendCatching { manager.refreshUserEnvironment(activeFile) }
                        .onSuccess {
                            refreshMessage = null
                            if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
                                manager.marketplace.search(remoteQuery)
                            }
                        }
                        .onFailure { refreshMessage = it.message ?: "Extensions refresh failed" }
                }
            }) { Icon(Icons.Default.Refresh, "Refresh extensions") }
        }

        if (installerState.running && selected?.family?.id != installerState.familyId) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(installerState.message, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (installerState.log.isNotBlank()) Text(installerState.log.takeLast(800),
                    style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 5)
                TextButton(onClick = { manager.installer.cancelInstallation() }) { Text("Cancel installation") }
            }
        }
        val categories = listOf<ExtensionCategory?>(null) + ExtensionCategory.entries
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(category).coerceAtLeast(0),
            edgePadding = 8.dp,
        ) {
            categories.forEach { item ->
                Tab(
                    selected = category == item,
                    onClick = { category = item },
                    text = { Text(item?.label ?: "All", maxLines = 1) },
                )
            }
        }

        val marketplaceNotice = if (
            category == null &&
            remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS &&
            (marketplaceSnapshot.loading || marketplaceSnapshot.offlineCache)
        ) marketplaceSnapshot.message else null
        val notice = refreshMessage ?: marketplaceNotice
        if (!notice.isNullOrBlank()) {
            DismissibleNotice(notice, ownerKey = manager,
                color = if (marketplaceSnapshot.offlineCache) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        }

        when {
            selectedMarketplace != null -> MarketplaceExtensionDetail(
                summary = selectedMarketplace!!,
                manager = manager,
                onBack = { selectedMarketplace = null },
            )
            selected != null -> ExtensionDetail(
                item = selected,
                manager = manager,
                onSelectItem = { selectedIdentity = ExtensionSelection(it.family.id, it.version.version) },
                onBack = { selectedIdentity = null },
                onRefresh = {
                    scope.launch {
                        runSuspendCatching { manager.refreshUserEnvironment(activeFile) }
                    }
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (filtered.isNotEmpty()) {
                    items(filtered, key = { "local:${it.primary.family.id}" }) { card ->
                        ExtensionRow(card = card, onClick = {
                            selectedIdentity = ExtensionSelection(card.primary.family.id, card.primary.version.version)
                        })
                    }
                }
                if (category == null && remoteQuery.length >= com.baystudio.droide.core.ExtensionMarketplaceManager.MIN_REMOTE_QUERY_CHARS) {
                    item(key = "open-vsx-heading") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Open VSX · inspect compatibility before installing", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                            if (marketplaceSnapshot.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    items(remoteResults, key = { "ovsx:${it.id}@${it.version}" }) { item ->
                        MarketplaceExtensionRow(item = item, manager = manager, onClick = { selectedMarketplace = item })
                    }
                    if (!marketplaceSnapshot.loading && remoteResults.isEmpty()) {
                        item(key = "open-vsx-empty") {
                            Text(
                                marketplaceSnapshot.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExtensionRow(card: ExtensionCatalogCard, onClick: () -> Unit) {
    val item = card.primary
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExtensionBrandIcon(family = item.family, size = 36.dp)
                ExtensionStateBadge(
                    state = item.state,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.family.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (item.family.category == ExtensionCategory.LANGUAGES) "Editor syntax" else item.version.version,
                        modifier = Modifier.widthIn(max = 150.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
                val provenance = item.family.publisher.takeIf(String::isNotBlank)
                    ?.let { "$it · ${item.family.category.label}" }
                    ?: item.family.category.label
                Text(provenance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                extensionListStatus(item)?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (card.installed.any { it != item } || (item.state == ExtensionState.AVAILABLE && card.installed.isNotEmpty())) {
                    extensionInstalledSummary(card)?.let { status ->
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                if (extensionSelectableChoices(card.choices).size > 1) {
                    Text("Versions and environments available", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun extensionListStatus(item: ExtensionVersionState): String? = when (item.state) {
    ExtensionState.BUILT_IN -> if (item.family.category == ExtensionCategory.LANGUAGES) LanguageRuntimePresentation.listStatus(item.family.id.removePrefix("language.")) else null
    ExtensionState.INSTALLED -> "Installed"
    ExtensionState.EXTERNAL -> "Installed"
    ExtensionState.AVAILABLE -> "Not installed"
    ExtensionState.UNAVAILABLE -> "Unavailable"
}

private fun extensionDetailStatus(item: ExtensionVersionState): String {
    val detail = item.detail.trim()
    val concise = detail.takeIf { it.isNotBlank() && it.length <= 80 }
    return when (item.state) {
        ExtensionState.BUILT_IN -> if (item.family.category == ExtensionCategory.LANGUAGES) LanguageRuntimePresentation.syntaxStatus(item.family.id.removePrefix("language.")) else "Built into Droide"
        ExtensionState.INSTALLED -> concise ?: "Installed"
        ExtensionState.EXTERNAL -> concise ?: "Detected on workstation"
        ExtensionState.AVAILABLE -> concise ?: "Ready to install"
        ExtensionState.UNAVAILABLE -> concise ?: "Not available for this device or setup"
    }
}

@Composable
private fun MarketplaceExtensionRow(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    onClick: () -> Unit,
) {
    val installedVersion = manager.marketplace.installedVersion(item.id)
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                MarketplaceBrandIcon(item, manager, 36.dp)
                ExtensionStateBadge(
                    state = if (installedVersion != null) ExtensionState.INSTALLED else ExtensionState.AVAILABLE,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(item.version, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                }
                val publisher = buildString {
                    append(item.namespace)
                    append(" · Open VSX")
                    if (item.verifiedPublisher) append(" · verified")
                }
                Text(publisher, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(
                    if (installedVersion != null && installedVersion != item.version) "Update available from $installedVersion · ${item.description}" else item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MarketplaceBrandIcon(
    item: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    size: androidx.compose.ui.unit.Dp,
) {
    val iconPath by produceState<String?>(initialValue = item.iconCachePath, item.id, item.iconUrl) {
        if (value == null) value = manager.marketplace.icon(item)
    }
    val path = iconPath
    if (path != null && File(path).isFile) {
        AsyncImage(
            model = File(path),
            contentDescription = "${item.displayName} icon",
            modifier = Modifier.size(size),
        )
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
        ) {
            Icon(Icons.Default.Extension, null, Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MarketplaceExtensionDetail(
    summary: com.baystudio.droide.core.MarketplaceExtensionSummary,
    manager: DevelopmentExtensionsManager,
    onBack: () -> Unit,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val operation by manager.marketplace.operation.collectAsState()
    var inspection by remember(summary.id) { mutableStateOf<com.baystudio.droide.core.MarketplaceInspection?>(null) }
    var loading by remember(summary.id) { mutableStateOf(true) }
    var message by remember(summary.id) { mutableStateOf<String?>(null) }
    var confirmInstall by remember(summary.id) { mutableStateOf(false) }
    var confirmUninstall by remember(summary.id) { mutableStateOf(false) }

    LaunchedEffect(summary.id) {
        loading = true
        runSuspendCatching { manager.marketplace.inspect(summary.id) }
            .onSuccess { inspection = it }
            .onFailure { message = it.message ?: "Could not inspect marketplace extension" }
        loading = false
    }

    val current = inspection
    val installedVersion = remember(summary.id, operation.phase, operation.message) { manager.marketplace.installedVersion(summary.id) }
    val rollbackAvailable = remember(summary.id, operation.phase, operation.message) { manager.marketplace.rollbackAvailable(summary.id) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to Extensions") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarketplaceBrandIcon(summary, manager, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(current?.detail?.displayName ?: summary.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${current?.detail?.namespace ?: summary.namespace} · Open VSX",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking extension…", style = MaterialTheme.typography.bodySmall)
        }

        current?.let { inspected ->
            val detail = inspected.detail
            Text(detail.description, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            DetailLine("Version", detail.version)
            installedVersion?.let { DetailLine("Installed", it) }
            DetailLine("Publisher", detail.namespace)
            DetailLine("Publisher status", if (detail.verifiedPublisher) "Verified" else "Not verified")
            DetailLine("Trusted publishing", if (detail.trustedPublishing) "Yes" else "Not reported")
            detail.license?.let { DetailLine("License", it) }
            detail.targetPlatform?.let { DetailLine("Registry target", it) }
            inspected.engineConstraint?.let { DetailLine("VS Code engine", it) }
            DetailLine(
                "Compatibility",
                inspected.compatibility.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            )
            DetailLine("Install mode", if (inspected.installable) "Declarative data only" else "Unavailable in Droide")
            Text(
                "Install supported language definitions, grammars, snippets and themes from Open VSX. Extensions requiring the VS Code runtime are not supported here. Tools use separate installers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inspected.supportedContributionKinds.isNotEmpty()) {
                DetailLine("Contributes", inspected.supportedContributionKinds.sorted().joinToString(", "))
            }
            if (inspected.extensionDependencies.isNotEmpty()) {
                DetailLine("Dependencies", inspected.extensionDependencies.sorted().joinToString(", "))
            }
            if (detail.deprecated) {
                Text("Deprecated by the registry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!detail.verifiedPublisher) {
                Text(
                    "Publisher not verified by Open VSX. Only supported declarative resources can be installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            inspected.reasons.forEach { reason ->
                Text("• $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (operation.extensionId == summary.id && operation.message.isNotBlank()) {
                Text(operation.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (operation.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            message?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }

            val installLabel = if (installedVersion == null) "Install" else if (installedVersion != detail.version) "Update" else null
            if (installLabel != null && inspected.installable) {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        if (detail.verifiedPublisher) {
                            scope.launch {
                                message = null
                                runSuspendCatching { manager.marketplace.install(summary.id) }
                                    .onSuccess { record ->
                                        message = "${record.displayName} ${record.version} is active"
                                        inspection = runSuspendCatching { manager.marketplace.inspect(summary.id, forceRefresh = true) }.getOrNull() ?: inspection
                                    }
                                    .onFailure { message = it.message ?: "Marketplace install failed" }
                            }
                        } else confirmInstall = true
                    },
                ) { Text(installLabel) }
            }
            if (!inspected.installable) {
                Text(
                    if (inspected.executable) "Install disabled: this package needs VS Code executable activation, which this Droide marketplace cannot run." else "Install disabled: package is blocked or outside Droide's supported declarative subset. See compatibility reasons above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installedVersion != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rollbackAvailable) {
                        OutlinedButton(
                            enabled = !operation.running,
                            onClick = {
                                scope.launch {
                                    message = null
                                    runSuspendCatching { manager.marketplace.rollback(summary.id) }
                                        .onSuccess { message = "Rolled back to ${it.version}" }
                                        .onFailure { message = it.message ?: "Rollback failed" }
                                }
                            },
                        ) { Text("Rollback") }
                    }
                    TextButton(enabled = !operation.running, onClick = { confirmUninstall = true }) { Text("Uninstall") }
                }
            }
        }
    }

    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmInstall = false },
            title = { Text("Install unverified publisher extension?") },
            text = {
                Text("Publisher not verified. Executable code is blocked.")
            },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmInstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.install(summary.id) }
                                .onSuccess { message = "${it.displayName} ${it.version} is active" }
                                .onFailure { message = it.message ?: "Marketplace install failed" }
                        }
                    },
                ) { Text("Install data-only") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmUninstall = false },
            title = { Text("Uninstall ${summary.displayName}?") },
            text = { Text("Remove this extension? Rollback history is kept temporarily.") },
            confirmButton = {
                Button(
                    enabled = !operation.running,
                    onClick = {
                        confirmUninstall = false
                        scope.launch {
                            message = null
                            runSuspendCatching { manager.marketplace.uninstall(summary.id) }
                                .onSuccess { message = "Extension removed" }
                                .onFailure { message = it.message ?: "Uninstall failed" }
                        }
                    },
                ) { Text("Uninstall") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExtensionStateBadge(state: ExtensionState, modifier: Modifier = Modifier) {
    if (state == ExtensionState.AVAILABLE || state == ExtensionState.EXTERNAL) return
    val tint = when (state) {
        ExtensionState.BUILT_IN, ExtensionState.INSTALLED -> MaterialTheme.colorScheme.primary
        ExtensionState.EXTERNAL, ExtensionState.AVAILABLE -> MaterialTheme.colorScheme.secondary
        ExtensionState.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val image = when (state) {
        ExtensionState.BUILT_IN, ExtensionState.INSTALLED -> Icons.Default.CheckCircle
        ExtensionState.EXTERNAL, ExtensionState.AVAILABLE -> Icons.Default.Info
        ExtensionState.UNAVAILABLE -> Icons.Default.Warning
    }
    Surface(
        modifier = modifier.size(17.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Icon(image, state.name, modifier = Modifier.padding(1.dp), tint = tint)
    }
}

@Composable
private fun ExtensionDetail(
    item: ExtensionVersionState,
    manager: DevelopmentExtensionsManager,
    onSelectItem: (ExtensionVersionState) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val snapshot by manager.snapshot.collectAsState()
    val installState by manager.installer.state.collectAsState()
    val androidProviderState by manager.installer.androidProviderState.collectAsState()
    val packageProviderState by manager.installer.packageProviderState.collectAsState()
    var plan by remember(item.family.id, item.version.version) { mutableStateOf<com.baystudio.droide.core.ExtensionInstallPlan?>(null) }
    var installJob by remember(item.family.id, item.version.version) { mutableStateOf<Job?>(null) }
    var installMessage by remember(item.family.id, item.version.version) { mutableStateOf<String?>(null) }
    var confirmUninstall by remember(item.family.id, item.version.version) { mutableStateOf(false) }
    var elpHealthRequest by remember(item.family.id,item.version.version) { mutableStateOf<Pair<com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor,com.baystudio.droide.core.ElpRuntimeHealthApprovalRequest>?>(null) }
    var showVersionRequest by remember(item.family.id) { mutableStateOf(false) }
    var requestedVersion by remember(item.family.id) { mutableStateOf("latest") }
    val reviewedAndroid = extensionReviewedAndroidChoice(snapshot.items, item.family.id)
    val updatePolicy = remember(item.family) { ExtensionUpdatePolicy.forFamily(item.family) }
    val uriHandler = LocalUriHandler.current
    fun prepareVersion(value: String) {
        if (installJob != null || installState.running) return
        installMessage = null
        installJob = scope.launch {
            try {
                runSuspendCatching { manager.prepareRequestedVersion(item.family.id, value) }
                    .onSuccess { prepared -> plan = prepared }
                    .onFailure { installMessage = it.message ?: "Could not resolve this version" }
            } finally { installJob = null }
        }
    }
    if (showVersionRequest) {
        AlertDialog(onDismissRequest = { showVersionRequest = false },
            title = { Text("${item.family.name} version") },
            text = { Column(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (reviewedAndroid != null)
                    "Upstream latest or other versions require Android SDK command-line tools and accepted licenses in Terminal. " +
                        "Use the reviewed ${reviewedAndroid.version.version} option for the built-in installer."
                    else "Use latest or a version provided by the publisher.")
                OutlinedTextField(value = requestedVersion, onValueChange = { requestedVersion = it.take(100) },
                    label = { Text("Version") }, singleLine = true)
            } },
            confirmButton = { TextButton(onClick = { showVersionRequest = false; prepareVersion(requestedVersion) }, enabled = requestedVersion.isNotBlank()) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { showVersionRequest = false }) { Text("Cancel") } })
    }
    val versionChoices = remember(snapshot.items, item.family.id) {
        extensionSelectableChoices(snapshot.items.filter { it.family.id == item.family.id })
    }
    DisposableEffect(item.family.id, item.version.version) {
        onDispose { installJob?.cancel() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back to Extensions") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ExtensionBrandIcon(family = item.family, size = 48.dp)
                ExtensionStateBadge(item.state, Modifier.align(Alignment.BottomEnd))
            }
            Column(Modifier.weight(1f)) {
                Text(item.family.name, style = MaterialTheme.typography.headlineSmall)
                if (item.family.publisher.isNotBlank()) {
                    Text(item.family.publisher, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text(item.family.description, style = MaterialTheme.typography.bodyMedium)
        if (updatePolicy.hasVersionPicker) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reviewedAndroid != null) {
                    Button(onClick = { prepareVersion(reviewedAndroid.version.version) },
                        enabled = reviewedAndroid.state == ExtensionState.AVAILABLE && installJob == null && !installState.running) {
                        Text(if (reviewedAndroid.state == ExtensionState.INSTALLED) "Installed ${reviewedAndroid.version.version}"
                            else "Install reviewed ${reviewedAndroid.version.version}")
                    }
                } else {
                    Button(onClick = { prepareVersion("latest") }, enabled = installJob == null && !installState.running) { Text("Update / install latest") }
                }
                OutlinedButton(onClick = { showVersionRequest = true }, enabled = installJob == null && !installState.running) { Text("Choose version") }
            }
        }
        Text(updatePolicy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (relatedFamily in updatePolicy.relatedFamilies) {
            val target = extensionDefaultChoice(snapshot.items.filter { it.family.id == relatedFamily })
            if (target != null) TextButton(onClick = { onSelectItem(target) }, enabled = installJob == null && !installState.running) {
                Text("Open ${target.family.name}")
            }
        }
        updatePolicy.documentationUrl?.let { url ->
            TextButton(onClick = { runCatching { uriHandler.openUri(url) }
                .onFailure { installMessage = "Could not open update instructions: $url" } }) {
                Text("Update instructions")
            }
        }
        if (versionChoices.any { it.version.version != item.version.version || it.version.scope != item.version.scope }) {
            ExtensionVersionChoices(
                choices = versionChoices,
                selected = item,
                enabled = installJob == null && !installState.running,
                onSelect = onSelectItem,
            )
        }
        if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) {
            Text(
                "Managed Android components share one JDK/SDK/Build Tools pack. Removing any component removes the entire pack and its managed records; individual versions cannot be removed here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        HorizontalDivider()
        DetailLine("Category", item.family.category.label)
        DetailLine("Version", if (item.family.category == ExtensionCategory.LANGUAGES) "Editor syntax" else item.version.version)
        DetailLine("Channel", item.version.channel)
        DetailLine("Install mode", when (item.version.installKind) {
            com.baystudio.droide.core.ExtensionInstallKind.BUILT_IN -> if (item.family.category == ExtensionCategory.LANGUAGES) "Editor syntax only" else "Bundled Droide feature"
            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> "Managed Android pack · shared removal"
            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> "Reviewed Android SDK component · local Ubuntu"
            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE -> "Reviewed managed tool"
            com.baystudio.droide.core.ExtensionInstallKind.USER_MANAGED -> "User-managed upstream version"
            com.baystudio.droide.core.ExtensionInstallKind.CATALOG_ONLY ->
                if (item.state == ExtensionState.EXTERNAL) "Detected tool · managed outside Droide" else "Detection only · no managed recipe"
        })
        DetailLine("Execution", extensionExecutionLabel(item))
        DetailLine("Status", if (item.family.category == ExtensionCategory.LANGUAGES) "Syntax available" else item.state.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        if (item.family.category == ExtensionCategory.LANGUAGES) {
            val languageId = item.family.id.removePrefix("language.")
            when (LanguageRuntimePresentation.kind(languageId)) {
                LanguageRuntimePresentation.Kind.DATA_OR_DOCUMENT -> {
                    DetailLine("Runtime / compiler", "No standalone runtime / compiler required")
                    Text("Syntax highlighting is ready. Add a language server or formatter for more features.",
                        style = MaterialTheme.typography.bodySmall)
                }
                LanguageRuntimePresentation.Kind.LINUX_SHELL -> {
                    val shell = LanguageRuntimePresentation.shellStatus(snapshot.localLinuxExecutables)
                    DetailLine("Linux shell", shell.detail)
                    Text(shell.guidance, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRefresh, enabled = !installState.running) { Text("Verify shell status") }
                }
                LanguageRuntimePresentation.Kind.SEPARATE_TOOL -> {
                    val familyId = UbuntuAptCatalog.languageRuntime[languageId]
                    val choices = snapshot.items.filter { it.family.id == familyId }
                    val installed = choices.firstOrNull { it.state in setOf(ExtensionState.INSTALLED, ExtensionState.EXTERNAL) }
                    DetailLine("Runtime / compiler", installed?.let { "${it.family.name}: ${it.detail}" }
                        ?: if (familyId == null) "No managed runtime mapped" else "Not installed")
                    val target = installed ?: choices.firstOrNull { it.state == ExtensionState.AVAILABLE && it.version.recommended }
                        ?: choices.firstOrNull { it.state == ExtensionState.AVAILABLE }
                    if (target != null) Button(onClick = { onSelectItem(target) }, enabled = !installState.running) {
                        Text(if (installed != null) "Open runtime / compiler" else "Install runtime / compiler")
                    } else {
                        OutlinedButton(onClick = {}, enabled = false) { Text("Runtime installer unavailable") }
                        Text("Editor support is ready. Install a compatible compiler or runtime in the Linux terminal, if available.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (item.version.provides.isNotEmpty()) DetailLine("Provides", item.version.provides.sorted().joinToString(", "))
        if (item.version.requires.isNotEmpty()) DetailLine("Requires", item.version.requires.sorted().joinToString(", "))
        Text(
            extensionDetailStatus(item),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )

        if(item.family.id=="lsp.elp" && item.state==ExtensionState.INSTALLED) {
            OutlinedButton(enabled=installJob==null&&!installState.running,onClick={
                installJob=scope.launch { try {
                    runSuspendCatching { manager.installer.prepare(item) as com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor }
                        .onSuccess { prepared -> runSuspendCatching{manager.installer.requestElpHealthApproval(prepared)}
                            .onSuccess{elpHealthRequest=prepared to it}.onFailure{installMessage=it.message} }
                        .onFailure{installMessage=it.message}
                } finally {installJob=null} }
            }) {Text("Approve runtime health recheck")}
        }
        installMessage?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.primary) }
        if (installState.familyId == item.family.id && installState.message.isNotBlank()) {
            Text(installState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (installState.familyId == item.family.id && installState.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { manager.installer.cancelInstallation(); installJob?.cancel() }) { Text("Cancel") }
        }
        if (installState.familyId == item.family.id && installState.log.isNotBlank()) {
            Text(installState.log, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        if (item.state == ExtensionState.UNAVAILABLE) {
            OutlinedButton(onClick = {}, enabled = false) { Text("Install unavailable") }
            Text(item.detail, style = MaterialTheme.typography.bodySmall)
        }

        when (item.state) {
            ExtensionState.AVAILABLE -> {
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                val result = runSuspendCatching { manager.installer.prepare(item) }
                                result.onSuccess { prepared -> plan = prepared }
                                    .onFailure { installMessage = it.message ?: "Could not prepare installation" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text(if (installState.familyId == item.family.id && installState.phase in setOf(
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED)) "Retry" else "Install") }
            }
            ExtensionState.INSTALLED -> {
                val record = manager.registry.find(item.family.id, item.version.version)
                val workspaceSelected = manager.workspaceVersion(item.family.id) == item.version.version
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = installJob == null && !installState.running) { Text("Verify status") }
                    if (item.version.installKind in setOf(
                            com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                            com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                            com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                        ) && record != null && !record.active) {
                        Button(
                            enabled = installJob == null && !installState.running,
                            onClick = {
                                installJob = scope.launch {
                                    try {
                                        runSuspendCatching { manager.unifiedAuthority.activate(item.family.id, item.version.version) }
                                            .onSuccess { installMessage = it.message; if (it.success) onRefresh() }
                                            .onFailure { installMessage = it.message ?: "Activation failed" }
                                    } finally {
                                        installJob = null
                                    }
                                }
                            },
                        ) { Text("Set global default") }
                    }
                }
                if (item.version.installKind in setOf(
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                        com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                    ) && record != null) {
                    OutlinedButton(
                        enabled = installJob == null && !installState.running,
                        onClick = {
                            installJob = scope.launch {
                                try {
                                    runSuspendCatching {
                                        if (workspaceSelected) {
                                            manager.clearWorkspaceVersion(item.family.id)
                                            "Workspace override cleared."
                                        } else {
                                            val result = manager.unifiedAuthority.activate(item.family.id, item.version.version, manager.workspaceId)
                                            check(result.success) { result.message }
                                            result.message
                                        }
                                    }.onSuccess { installMessage = it; onRefresh() }
                                        .onFailure { installMessage = it.message ?: "Workspace toolchain selection failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        },
                    ) { Text(if (workspaceSelected) "Use global default" else "Use in workspace") }
                }
                if (item.version.installKind in setOf(
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
                        com.baystudio.droide.core.ExtensionInstallKind.MANAGED_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.GUEST_PACKAGE,
                        com.baystudio.droide.core.ExtensionInstallKind.REVIEWED_RECIPE,
                        com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN,
                    )
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = installJob == null && !installState.running,
                            onClick = {
                                installJob = scope.launch {
                                    try {
                                        runSuspendCatching { manager.unifiedAuthority.repairVersion(item.family.id, item.version.version) }
                                            .onSuccess { txResult -> 
                                                if (txResult.success) {
                                                    installMessage = txResult.message
                                                    onRefresh()
                                                } else {
                                                    installMessage = txResult.message
                                                }
                                            }
                                            .onFailure { installMessage = it.message ?: "Repair failed" }
                                    } finally {
                                        installJob = null
                                    }
                                }
                            },
                        ) { Text("Repair") }
                        TextButton(
                            enabled = installJob == null && !installState.running,
                            onClick = { confirmUninstall = true },
                        ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove Android pack" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove integration" else "Uninstall") }
                    }
                }
            }
            ExtensionState.EXTERNAL -> {
                Text("This command is detected in its environment. Droide does not own this installation or remove it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onRefresh, enabled = installJob == null && !installState.running) { Text("Verify detection") }
                versionChoices.firstOrNull { it.state == ExtensionState.AVAILABLE }?.let { managed ->
                    Button(onClick = { onSelectItem(managed) }, enabled = installJob == null && !installState.running) { Text("Install managed version") }
                }
            }
            else -> Unit
        }
    }

    elpHealthRequest?.let { (healthPlan,request) ->
        AlertDialog(onDismissRequest={request.decline();elpHealthRequest=null;installMessage="ELP health declined; no selected tool was run."},
            title={Text(request.title)},
            text={Column(Modifier.verticalScroll(rememberScrollState())){Text(request.detail)}},
            confirmButton={TextButton(onClick={
                manager.installWithElpHealthApproval(healthPlan,request)
                elpHealthRequest=null
            },enabled=installJob==null&&!installState.running){Text("Run this health check")}},
            dismissButton={TextButton(onClick={request.decline();elpHealthRequest=null;installMessage="ELP health attempt inactive; previous package unchanged."}){Text("Decline")}})
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) confirmUninstall = false },
            title = { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire Android toolchain pack?" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove command integration?" else "Uninstall ${item.family.name}?") },
            text = {
                Text(
                    if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN)
                        "This removes the managed Android JDK, SDK and Build Tools together, including records for other Android components. Projects using this pack will need it installed again. Continue?"
                    else if (item.version.version == UbuntuAptCatalog.VERSION)
                        "Command integration will be removed. Shared Ubuntu packages remain installed for the Linux terminal."
                    else
                        "Remove ${item.family.name} ${item.version.version}? Required dependencies block removal."
                )
            },
            confirmButton = {
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        installJob = scope.launch {
                            try {
                                runSuspendCatching { manager.unifiedAuthority.uninstall(item.family.id, item.version.version) }
                                    .onSuccess { txResult ->
                                        if (txResult.success) {
                                            installMessage = txResult.message
                                            confirmUninstall = false
                                            onRefresh()
                                        } else {
                                            installMessage = txResult.message
                                        }
                                    }
                                    .onFailure { installMessage = it.message ?: "Uninstall failed" }
                            } finally {
                                installJob = null
                            }
                        }
                    },
                ) { Text(if (item.version.installKind == com.baystudio.droide.core.ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Remove entire pack" else if (item.version.version == UbuntuAptCatalog.VERSION) "Remove integration" else "Uninstall") }
            },
            dismissButton = {
                TextButton(
                    enabled = installJob == null && !installState.running,
                    onClick = { confirmUninstall = false },
                ) { Text("No") }
            },
        )
    }

    plan?.let { currentPlan ->
        val androidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Android
        val localAndroidPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.AndroidLocalComponent
        val androidWorkstationPlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.AndroidLocalWorkstation
        val packagePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Package
        val recipePlan = currentPlan as? com.baystudio.droide.core.ExtensionInstallPlan.Recipe
        AlertDialog(
            onDismissRequest = { if (installJob == null && !installState.running) plan = null },
            title = { Text("Install ${currentPlan.familyName}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${currentPlan.resolvedVersion}", style = MaterialTheme.typography.bodyMedium)
                    Text("Will install:", style = MaterialTheme.typography.labelMedium)
                    currentPlan.components.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    manager.installer.externalDownloadNotice(currentPlan)?.let { notice ->
                        Text(notice, style = MaterialTheme.typography.bodyMedium)
                    }
                    androidPlan?.let { ap ->
                        Text("${ap.toolchainEntry.abi} · SHA-256 ${ap.toolchainEntry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.toolchainEntry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this revision.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    localAndroidPlan?.let { ap ->
                        Text("${ap.entry.kind.name.replace('_', ' ')} · SHA-256 ${ap.entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(ap.entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Ubuntu target: ${com.baystudio.droide.core.LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${ap.entry.guestTarget}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                        if (ap.licenseAccepted) {
                            Text("Android SDK license accepted for this component install.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    packagePlan?.entries?.lastOrNull()?.let { entry ->
                        Text("${entry.abi} · SHA-256 ${entry.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text(entry.provenance, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidWorkstationPlan?.let { ap ->
                        HorizontalDivider()
                        if (ap.licenseAccepted) Text("Android SDK license accepted for this workstation install.", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Android SDK License", style = MaterialTheme.typography.labelLarge)
                            Text(ap.license.text, style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256 ${ap.license.sha256}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    recipePlan?.let { rp ->
                        Text("Exact npm recipe: ${rp.recipe.packageName}@${rp.recipe.packageVersion}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        Text("Source-reviewed install. Integrity and health checks run before activation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rp.recipe.provenanceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (installState.familyId == item.family.id) {
                        if (installState.message.isNotBlank()) Text(installState.message, style = MaterialTheme.typography.bodySmall,
                            color = if (installState.phase == com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (installState.running) {
                            val fraction = when (currentPlan) {
                                is com.baystudio.droide.core.ExtensionInstallPlan.Android -> androidProviderState.fraction
                                is com.baystudio.droide.core.ExtensionInstallPlan.Package -> packageProviderState.fraction
                                else -> null
                            }
                            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (installState.log.isNotBlank()) Text(installState.log,
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    installMessage?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                val needsLicense = (androidPlan != null && !androidPlan.licenseAccepted) ||
                    (localAndroidPlan != null && !localAndroidPlan.licenseAccepted) ||
                    (androidWorkstationPlan != null && !androidWorkstationPlan.licenseAccepted)
                Button(
                    enabled = installJob == null && !installState.running,
                    onClick = {
                        if (needsLicense) {
                            installJob = scope.launch {
                                try {
                                    val result = runSuspendCatching {
                                        when {
                                            androidPlan != null -> manager.installer.acceptLicense(androidPlan)
                                            localAndroidPlan != null -> manager.installer.acceptLicense(localAndroidPlan)
                                            androidWorkstationPlan != null -> manager.installer.acceptLicense(androidWorkstationPlan)
                                            else -> error("No Android license plan is active")
                                        }
                                    }
                                    result.onSuccess {
                                        plan = when {
                                            androidPlan != null -> androidPlan.copy(licenseAccepted = true)
                                            localAndroidPlan != null -> localAndroidPlan.copy(licenseAccepted = true)
                                            androidWorkstationPlan != null -> androidWorkstationPlan.copy(licenseAccepted = true)
                                            else -> currentPlan
                                        }
                                    }.onFailure { installMessage = it.message ?: "License acceptance failed" }
                                } finally {
                                    installJob = null
                                }
                            }
                        } else {
                            // Only the confirmation is pane-local. The manager owns the job and
                            // progress presentation before this dialog leaves the Composition.
                            installJob = scope.launch {
                                try {
                                    runSuspendCatching {
                                        manager.installer.acceptExternalDownloadFromUser(currentPlan)
                                        if (currentPlan is com.baystudio.droide.core.ExtensionInstallPlan.LocalVendor && currentPlan.familyId == "lsp.elp") {
                                            elpHealthRequest = currentPlan to manager.installer.requestElpHealthApproval(currentPlan)
                                        } else manager.installInBackground(currentPlan)
                                        plan = null
                                    }.onFailure { installMessage = it.message ?: "Installation could not start" }
                                } finally { installJob = null }
                            }
                        }
                    },
                ) { Text(if (needsLicense) "Accept license" else if (installState.familyId == item.family.id && installState.phase in setOf(
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.FAILED,
                    com.baystudio.droide.core.ManagedExtensionInstaller.Phase.CANCELED)) "Retry" else "Install") }
            },
            dismissButton = {
                if (installState.running) {
                    TextButton(onClick = { manager.installer.cancelInstallation(); installJob?.cancel() }) { Text("Cancel") }
                } else {
                    TextButton(onClick = { plan = null }) { Text("Close") }
                }
            },
        )
    }

}

@Composable
internal fun ExtensionVersionChoices(
    choices: List<ExtensionVersionState>,
    selected: ExtensionVersionState,
    enabled: Boolean,
    onSelect: (ExtensionVersionState) -> Unit,
) {
    var expanded by remember(selected.family.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = enabled) { Text("Versions and environments") }
        if (expanded) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { choice ->
                    val current = choice.version.version == selected.version.version && choice.version.scope == selected.version.scope
                    OutlinedButton(
                        onClick = { expanded = false; onSelect(choice) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled && !current,
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(extensionChoiceLabel(choice), style = MaterialTheme.typography.labelMedium)
                            if (current) Text("Selected", style = MaterialTheme.typography.labelSmall)
                            else if (choice.version.recommended) Text("Recommended", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}
