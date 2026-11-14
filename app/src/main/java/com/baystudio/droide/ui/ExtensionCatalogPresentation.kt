package com.baystudio.droide.ui

import com.baystudio.droide.core.ExecutionScope
import com.baystudio.droide.core.ExtensionCategory
import com.baystudio.droide.core.ExtensionInstallKind
import com.baystudio.droide.core.ExtensionState
import com.baystudio.droide.core.ExtensionVersionState

/** A catalog family is one product; versions and execution environments are choices in its detail. */
internal data class ExtensionCatalogCard(
    val primary: ExtensionVersionState,
    val choices: List<ExtensionVersionState>,
) {
    val installed: List<ExtensionVersionState>
        get() = choices.filter { it.state == ExtensionState.INSTALLED || it.state == ExtensionState.EXTERNAL }
}

internal fun extensionDefaultChoice(items: List<ExtensionVersionState>): ExtensionVersionState? =
    items.minByOrNull { item ->
        when (item.state) {
            ExtensionState.BUILT_IN -> 0
            ExtensionState.INSTALLED -> when {
                item.detail.contains("workspace default", ignoreCase = true) -> 5
                item.detail.contains("global default", ignoreCase = true) -> 11
                item.version.recommended -> 12
                else -> 13
            }
            ExtensionState.AVAILABLE -> if (item.version.recommended) 20 else 21
            ExtensionState.EXTERNAL -> 10
            ExtensionState.UNAVAILABLE -> 40
        }
    }

internal fun extensionCatalogCards(
    items: List<ExtensionVersionState>,
    query: String = "",
    category: ExtensionCategory? = null,
): List<ExtensionCatalogCard> = items.groupBy { it.family.id }.values.mapNotNull { versions ->
    val primary = extensionDefaultChoice(versions) ?: return@mapNotNull null
    if (category != null && primary.family.category != category) return@mapNotNull null
    val searchable = versions.flatMap { item ->
        listOf(item.family.id, item.family.name, item.family.publisher, item.family.description,
            item.version.version, item.version.channel, item.family.category.label, extensionExecutionLabel(item)) +
            item.family.keywords + item.version.provides + item.version.requires
    }
    if (query.isNotBlank() && searchable.none { it.contains(query.trim(), ignoreCase = true) }) return@mapNotNull null
    ExtensionCatalogCard(primary, versions.distinctBy { it.version.version to it.version.scope })
}

/** Missing external detection placeholders are not alternative versions that a user can install. */
internal fun extensionSelectableChoices(items: List<ExtensionVersionState>): List<ExtensionVersionState> =
    items.filter { it.state != ExtensionState.UNAVAILABLE }
        .distinctBy { it.version.version to it.version.scope }

internal fun extensionExecutionLabel(item: ExtensionVersionState): String = when {
    item.version.scope == ExecutionScope.EDITOR -> "Editor"
    item.version.scope == ExecutionScope.LOCAL -> "Android local"
    item.detail.contains("Device Workstation", ignoreCase = true) -> "Device Workstation"
    item.detail.contains("app-local", ignoreCase = true) || item.detail.contains("local Ubuntu", ignoreCase = true) -> "Local Ubuntu"
    item.version.version == "Workstation" || item.version.installKind == ExtensionInstallKind.MANAGED_PACKAGE ||
        item.version.installKind == ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> "Device Workstation"
    item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE -> "Alpine workstation"
    item.state == ExtensionState.EXTERNAL || item.version.channel.contains("ubuntu") ||
        item.state == ExtensionState.AVAILABLE -> "Local Ubuntu"
    item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE && item.version.channel != "installed" -> "Local Ubuntu"
    else -> "Linux workstation"
}

internal fun extensionChoiceLabel(item: ExtensionVersionState): String = when (item.state) {
    ExtensionState.BUILT_IN -> if (item.version.channel == "contributed") "Extension-provided · ${extensionExecutionLabel(item)}"
        else "Built in · ${extensionExecutionLabel(item)}"
    ExtensionState.INSTALLED -> "${item.version.version} · ${extensionExecutionLabel(item)} · installed" +
        if(item.family.id=="lsp.elp") " · OTP/transport health only" else ""
    ExtensionState.EXTERNAL -> "${extensionExecutionLabel(item)} · detected outside Droide"
    ExtensionState.AVAILABLE -> "${item.version.version} · ${extensionExecutionLabel(item)} · " +
        if(item.family.id=="lsp.elp") "explicit health approval required" else "ready to install"
    ExtensionState.UNAVAILABLE -> "${item.version.version} · ${extensionExecutionLabel(item)} · unavailable"
}

internal fun extensionInstalledSummary(card: ExtensionCatalogCard): String? = card.installed
    .takeIf { it.isNotEmpty() }
    ?.joinToString("; ") { item ->
        if (item.state == ExtensionState.EXTERNAL) "${extensionExecutionLabel(item)}: detected outside Droide"
        else "${extensionExecutionLabel(item)}: ${item.version.version} installed"
    }

/** Advertise the exact reviewed Android route, never relabel a pinned component as upstream latest. */
internal fun extensionReviewedAndroidChoice(
    items: List<ExtensionVersionState>, familyId: String,
): ExtensionVersionState? {
    if (familyId !in setOf("sdk.android", "build.android-tools")) return null
    return extensionDefaultChoice(items.filter {
        it.family.id == familyId && it.version.scope == ExecutionScope.LOCAL_LINUX_ARM64 &&
            it.version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT
    })
}
