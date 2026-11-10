package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlinx.coroutines.ensureActive

// Installation/repair is possible only for entries that the normal Extensions subsystem already
// marks AVAILABLE/INSTALLED.

class AgentToolchainActions private constructor(
    private val extensions: DevelopmentExtensionsManager,
    private val environmentRefresh: ToolchainEnvironmentRefresh?,
) {
    constructor(extensions: DevelopmentExtensionsManager) : this(extensions, null)

    internal companion object {
        fun forWorkspace(extensions: DevelopmentExtensionsManager, terminals: TerminalManager) =
            AgentToolchainActions(extensions, ToolchainEnvironmentRefresh(terminals))
    }

    internal suspend fun execute(args: JsonObject, mode: AgentMode, permissions: PermissionEngine,
        approvals: ApprovalManager, scope: String, provenance: AgentRequestProvenance,
        requireCurrent: () -> Unit = {}, progress: (String) -> Unit = {}): String {
        requireCurrent()
        val observer = kotlinx.coroutines.currentCoroutineContext()[PackageProgressObserver]
        fun report(message: String) {
            progress(message)
            observer?.publish(PackageProgressSnapshot("toolchain_review", message))
        }
        if (!permissions.toolAllowed("toolchain")) return "DENIED (capability): toolchain"
        require(args.keys.all { it in setOf("operation", "family", "version", "query") }) { "Unknown toolchain argument" }
        fun value(key: String) = args[key]?.let {
            require(it is JsonPrimitive && it.isString && it.content.length <= 256) { "Invalid $key" }
            it.content
        }.orEmpty()
        val op = value("operation").ifBlank { "list" }
        val family = value("family")
        // An omitted selection keeps the currently installed choice, as before; it must not become a
        // literal search for an installed version named latest.
        val version = value("version").ifBlank { if (op == "use_workspace") "" else "latest" }
        if (op in setOf("install", "repair", "use_workspace", "refresh_environment") && mode != AgentMode.BUILD)
            return "DENIED ($mode mode): toolchain changes require BUILD mode."
        suspend fun approve(resource: String, title: String, preview: String): Boolean {
            report("Waiting for installation approval in Droide")
            val allowed = PermissionApprovalGate.approved(permissions, approvals, "toolchain_install", resource, scope,
                "toolchain_install", title, preview, provenance)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            requireCurrent()
            return allowed && permissions.toolAllowed("toolchain")
        }
        return when (op) {
            "refresh_environment" -> {
                require(args.keys == setOf("operation")) { "Environment refresh takes only operation" }
                val refresh = environmentRefresh ?: return "UNAVAILABLE: workspace terminal authority is not available"
                report("Preparing a terminal with the current toolchain environment")
                refresh.open {
                    requireCurrent()
                    check(permissions.toolAllowed("toolchain")) { "Toolchain capability is no longer available" }
                }
            }
            "list" -> list(value("query").takeIf { it.isNotBlank() })
            "status" -> status(family, version)
            "plan_install" -> prepareInstall(family, version).let {
                it.preview + if (it.needsLicenseAcceptance)
                    "\nLICENSE_CONSENT_REQUIRED: call install to show the exact license and installation approval in Droide; continuation resumes after approval." else ""
            }
            "install" -> {
                val prepared = prepareInstall(family, version)
                val license = when (val plan = prepared.plan) {
                    is ExtensionInstallPlan.Android -> plan.license
                    is ExtensionInstallPlan.AndroidLocalComponent -> plan.license
                    is ExtensionInstallPlan.AndroidLocalWorkstation -> plan.license
                    else -> null
                }
                report(if (prepared.needsLicenseAcceptance) "Waiting for Android SDK license approval in Droide" else "Waiting for installation approval in Droide")
                ToolchainInstallConsent.install(permissions, approvals, prepared.permissionResource,
                    "install ${prepared.item.family.name} ${prepared.item.version.version}", prepared.preview,
                    license, !prepared.needsLicenseAcceptance, scope, provenance, requireCurrent,
                    acceptLicense = {
                        when (val plan = prepared.plan) {
                            is ExtensionInstallPlan.Android -> extensions.installer.acceptLicense(plan)
                            is ExtensionInstallPlan.AndroidLocalComponent -> extensions.installer.acceptLicense(plan)
                            is ExtensionInstallPlan.AndroidLocalWorkstation -> extensions.installer.acceptLicense(plan)
                            else -> error("No license in this installation plan")
                        }
                    }, install = {
                        report("Installing approved toolchain")
                        install(prepared.copy(needsLicenseAcceptance = false))
                    })
            }
            "repair" -> {
                val (item, preview) = repairPreview(family, version)
                if (!approve("repair:${item.family.id}@${item.version.version}", "Repair ${item.family.name}", preview))
                    "DENIED: toolchain repair" else repair(item)
            }
            "use_workspace" -> {
                if (!approve("workspace-select:$family@$version", "Select toolchain for workspace", "$family@$version"))
                    "DENIED: workspace toolchain selection" else activateWorkspace(family, version)
            }
            else -> "ERROR: unsupported toolchain operation: $op"
        }
    }
    data class PreparedInstall(
        val item: ExtensionVersionState,
        val plan: ExtensionInstallPlan,
        val preview: String,
        val permissionResource: String,
        val needsLicenseAcceptance: Boolean,
    )

    suspend fun list(query: String? = null): String =
        AgentToolchainCatalog.query(extensions.unifiedAuthority, query)

    suspend fun status(familyId: String, version: String? = null): String {
        val info = extensions.unifiedAuthority.info(familyId, version ?: "latest")
        if (info == null) return "PACKAGE_NOT_FOUND family=$familyId version=$version"
        return buildString {
            append("PACKAGE_STATUS family=").append(info.familyId).append('\n')
            append("version=").append(info.version).append('\n')
            append("state=").append(info.state).append('\n')
            append("dependencies=").append(info.dependencies.joinToString(",")).append('\n')
        }
    }

    suspend fun prepareInstall(familyId: String, version: String): PreparedInstall {
        val item = requireNotNull(extensions.requestedItem(familyId.trim(), version)) {
            "Unknown extension/toolchain family: ${familyId.trim()}"
        }
        require(item.state == ExtensionState.AVAILABLE) {
            "${item.family.name} ${item.version.version} is ${item.state.name.lowercase()}, not installable: ${item.detail}"
        }
        val plan = extensions.installer.prepare(item)
        val needsLicense = when (plan) {
            is ExtensionInstallPlan.Android -> !plan.licenseAccepted
            is ExtensionInstallPlan.AndroidLocalComponent -> !plan.licenseAccepted
            is ExtensionInstallPlan.AndroidLocalWorkstation -> !plan.licenseAccepted
            else -> false
        }
        val preview = buildString {
            appendLine(if (plan is ExtensionInstallPlan.UserManaged) "Update user-managed toolchain/runtime" else "Install managed toolchain/runtime")
            appendLine("${plan.familyName} ${plan.resolvedVersion}")
            appendLine("Provider: ${item.version.installKind.name.lowercase()}")
            appendLine("Scope: ${item.version.scope.name.lowercase()}")
            appendLine("Components:")
            plan.components.take(32).forEach { appendLine("• ${it.take(300)}") }
            when (plan) {
                is ExtensionInstallPlan.Android -> {
                    appendLine("Android SDK license SHA-256: ${plan.license.sha256}")
                    appendLine("License accepted: ${plan.licenseAccepted}")
                }
                is ExtensionInstallPlan.AndroidLocalComponent -> {
                    appendLine("Artifact: ${plan.entry.fileName}")
                    appendLine("Artifact SHA-256: ${plan.entry.sha256}")
                    appendLine("Upstream SHA-1: ${plan.entry.upstreamSha1}")
                    appendLine("Ubuntu target: ${LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${plan.entry.guestTarget}")
                    if (plan.entry.nativeTools.isNotEmpty()) {
                        appendLine("ARM64 native overlays:")
                        plan.entry.nativeTools.sortedBy { it.name }.forEach { tool ->
                            appendLine("  ${tool.name}: sha256:${tool.sha256}")
                        }
                    }
                    appendLine("Android SDK license SHA-256: ${plan.license.sha256}")
                    appendLine("License accepted: ${plan.licenseAccepted}")
                }
                is ExtensionInstallPlan.AndroidLocalWorkstation -> {
                    appendLine("Gradle SHA-256: ${plan.gradle.assetSha256}")
                    plan.entries.forEach { appendLine("${it.familyId} ${it.version}: sha256:${it.sha256}") }
                    appendLine("Android SDK license SHA-256: ${plan.license.sha256}")
                    appendLine("License accepted: ${plan.licenseAccepted}")
                }
                else -> Unit
            }
            appendLine(if (plan is ExtensionInstallPlan.UserManaged)
                "The upstream package manager owns this installation. The same Ubuntu workstation and project are reused; completed package changes are retained if canceled."
                else "Droide will install only artifacts/recipes already admitted by its certified/reviewed provider pipeline.")
        }.take(8_000)
        val licenseHash = when (plan) {
            is ExtensionInstallPlan.Android -> plan.license.sha256
            is ExtensionInstallPlan.AndroidLocalComponent -> plan.license.sha256
            is ExtensionInstallPlan.AndroidLocalWorkstation -> plan.license.sha256
            else -> "none"
        }
        val resource = "install:${plan.familyId}@${plan.resolvedVersion}:sha256:${sha256(preview + licenseHash)}"
        return PreparedInstall(item, plan, preview, resource, needsLicense)
    }

    suspend fun install(prepared: PreparedInstall): String {
        if (prepared.needsLicenseAcceptance) {
            return "UNAVAILABLE: Android SDK license approval is required; use the reviewed toolchain install workflow."
        }

        val result = extensions.unifiedAuthority.installPrepared(prepared.plan)
        if (!result.success) return "TOOLCHAIN_INSTALL_RESULT\nsuccess=false\nmessage=${result.message.take(6_000)}"

        val userManaged = prepared.plan is ExtensionInstallPlan.UserManaged
        val refreshed = if (userManaged) null else extensions.unifiedAuthority.info(prepared.plan.familyId, prepared.plan.resolvedVersion)
        val verifiedState = AgentToolchainInstallResultPolicy.state(userManaged, refreshed?.state)

        return buildString {
            append("TOOLCHAIN_INSTALL_RESULT\n")
            append("success=").append(result.success).append('\n')
            append("message=").append(result.message.take(6_000)).append('\n')
            append("verified_state=").append(verifiedState).append('\n')
            if (userManaged) append("version_identity=owned_by_upstream_package_manager\n")
            append(ToolchainEnvironmentRefresh.NEXT_STEP)
        }
    }

    suspend fun repairPreview(familyId: String, version: String): Pair<ExtensionVersionState, String> {
        val item = find(familyId, version.takeUnless { it == "latest" })
        require(item.state == ExtensionState.INSTALLED) { "Only Droide-managed installed packages can be repaired" }
        require(item.version.installKind in setOf(
            ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN,
            ExtensionInstallKind.ANDROID_LOCAL_COMPONENT,
            ExtensionInstallKind.MANAGED_PACKAGE,
            ExtensionInstallKind.GUEST_PACKAGE,
            ExtensionInstallKind.REVIEWED_RECIPE,
        )) { "This entry has no managed repair provider" }
        val preview = "Repair managed package\n${item.family.name} ${item.version.version}\n${item.detail}\nDroide will reinstall/health-check through the existing managed provider and will not replace user-managed EXTERNAL tools."
        return item to preview
    }

    suspend fun repair(item: ExtensionVersionState): String {

        val result = extensions.unifiedAuthority.repairVersion(item.family.id, item.version.version)
        if (!result.success) return "TOOLCHAIN_REPAIR_RESULT\nsuccess=false\nmessage=${result.message.take(6_000)}"

        val refreshed = extensions.unifiedAuthority.info(item.family.id, item.version.version)
        require(refreshed?.state == "active" || refreshed?.state == "inactive") {
            "Managed repair returned but unified authority did not prove INSTALLED: state=${refreshed?.state}"
        }

        return "TOOLCHAIN_REPAIR_RESULT\nsuccess=${result.success}\nmessage=${result.message.take(6_000)}\nverified_state=${refreshed.state}\n" +
            ToolchainEnvironmentRefresh.NEXT_STEP
    }

    suspend fun activateWorkspace(familyId: String, version: String, workspaceId: String? = null): String {
        val item = find(familyId, version)
        require(item.state == ExtensionState.INSTALLED) { "Install the managed package before selecting it for this workspace" }

        val result = extensions.unifiedAuthority.activate(item.family.id, item.version.version, workspaceId ?: extensions.workspaceId)

        return "TOOLCHAIN_WORKSPACE_SELECTION family=${item.family.id} version=${item.version.version} status=${if (result.success) "selected" else "failed"} message=${result.message}" +
            if (result.success) "\n" + ToolchainEnvironmentRefresh.NEXT_STEP else ""
    }

    private suspend fun find(familyId: String, version: String?): ExtensionVersionState {
        val cleanFamily = familyId.trim()
        require(cleanFamily.isNotBlank()) { "family is required" }
        val snapshot = extensions.refresh()
        val candidates = snapshot.items.filter { it.family.id == cleanFamily }
        require(candidates.isNotEmpty()) { "Unknown extension/toolchain family: $cleanFamily" }
        if (!version.isNullOrBlank()) {
            return candidates.firstOrNull { it.version.version == version.trim() }
                ?: error("Unknown version ${version.trim()} for $cleanFamily")
        }
        return candidates.firstOrNull { it.state == ExtensionState.INSTALLED }
            ?: candidates.firstOrNull { it.version.recommended }
            ?: candidates.first()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal object AgentToolchainInstallResultPolicy {
    fun state(userManaged: Boolean, managedState: String?): String {
        if (userManaged) return "external"
        require(managedState == "active" || managedState == "inactive") {
            "Managed install returned but unified authority did not prove INSTALLED: state=$managedState"
        }
        return requireNotNull(managedState)
    }
}
