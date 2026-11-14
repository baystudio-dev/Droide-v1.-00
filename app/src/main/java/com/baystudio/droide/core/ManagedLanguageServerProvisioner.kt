package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope

// This does not alter Extensions UI metadata and never turns an unpromoted artifact into an installable package.


class ManagedLanguageServerProvisioner(
    context: Context,
    private val scope: CoroutineScope,
    private val workDir: File,
    private val bridge: DeviceBridgeManager,
    private val development: AndroidDevelopmentManager,
    private val registry: ManagedPackageRegistry,
) {
    data class ProvisionPlan(
        val server: ManagedLanguageServerPlanEntry,
        val catalogRevision: String,
        val target: ManagedPackageCatalogEntry,
        val entries: List<ManagedPackageCatalogEntry>,
    )

    data class Status(
        val serverId: String,
        val plannedVersion: String,
        val catalogAvailable: Boolean,
        val installed: Boolean,
        val verified: Boolean,
        val commandReady: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val localProcessHost = LocalLinuxProcessHost(workDir, scope)

    fun prepare(
        serverId: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): ProvisionPlan {
        ManagedLanguageServerPlan.validate()
        val server = ManagedLanguageServerPlan.forServer(serverId)
            ?: error("No managed language-server plan exists for $serverId")
        val document = ManagedPackageCatalog.load(appContext)
        val target = ManagedPackageCatalog.select(document, server.packageFamilyId, server.pinnedVersion, supportedAbis)
            ?: error("No trusted managed package is pinned for ${server.packageFamilyId} ${server.pinnedVersion}")
        val entries = ManagedPackageDependencyResolver.resolve(document, target, supportedAbis)
        check(entries.lastOrNull()?.id == target.id) { "Managed language-server dependency plan is malformed" }
        return ProvisionPlan(server, document.revision, target, entries)
    }

    // Opening/editing a file never downloads tools.


    suspend fun ensureAvailable(
        serverId: String,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): Boolean {
        val server = ManagedLanguageServerPlan.forServer(serverId) ?: return false
        if(server.packageFamilyId==LocalUbuntuElpPolicy.FAMILY) return false // Typed owner admission only; never generic health or PATH fallback.
        // Discovery is not package installation or historical Device Workstation certification.
        // Ubuntu Extensions owns explicit, reviewed installation; the live client negotiates LSP.
        return verifyLocalRuntimeCommands(server)
    }

    suspend fun install(plan: ProvisionPlan): ManagedPackageRecord {
        check(plan.entries.isNotEmpty() && plan.entries.last().id == plan.target.id) { "Invalid language-server install plan" }
        error(
            "Managed language-server installation still targets the legacy Device Workstation path. " +
                "Install ${plan.server.command} in local Ubuntu from the Linux terminal until a local transaction is implemented."
        )
    }

    suspend fun status(serverId: String): Status {
        val server = ManagedLanguageServerPlan.forServer(serverId)
            ?: return Status(serverId, "", false, false, false, false, "No managed language-server plan exists")
        if(server.packageFamilyId==LocalUbuntuElpPolicy.FAMILY) {
            val authority=LocalManagedPackageAuthority(appContext,registry)
            val record=authority.ownedReceipt(LocalUbuntuElpPolicy.FAMILY,LocalUbuntuElpPolicy.VERSION)
            val valid=record?.let{authority.verifyElpPackageWithoutHealth(it)}==true
            return Status(serverId,server.pinnedVersion,true,record!=null,valid,false,
                if(valid) "ELP original stored-byte proof passed. Current selected-tool compatibility and explicit project approval are required before analysis; project/embedded-service/eqWAlizer acceptance remains unverified."
                else "ELP unavailable. Use the exact original installer and explicit selected Erlang health approval in Extensions.")
        }
        val localReviewedRecipe = UbuntuNpmCatalog.find(server.packageFamilyId)?.takeIf { it.reviewedVersion == server.pinnedVersion }
        val localVendorRecipe = LocalUbuntuVendorCatalog.find(server.packageFamilyId)?.takeIf {
            it.familyId in setOf(LocalUbuntuPowerShellEditorServicesPolicy.FAMILY, LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuClojurePolicy.FAMILY, LocalUbuntuMakePolicy.FAMILY, LocalUbuntuLemMinxPolicy.FAMILY, LocalUbuntuZlsPolicy.FAMILY, LocalUbuntuBufPolicy.FAMILY, LocalUbuntuMarksmanPolicy.FAMILY, LocalUbuntuTexlabPolicy.FAMILY, LocalUbuntuTerraformPolicy.FAMILY) && it.pinnedVersion == server.pinnedVersion
        }
        val catalogAvailable = localReviewedRecipe != null || localVendorRecipe != null || runCatching {
            val document = ManagedPackageCatalog.load(appContext)
            ManagedPackageCatalog.select(document, server.packageFamilyId, server.pinnedVersion, Build.SUPPORTED_ABIS.toList()) != null
        }.getOrDefault(false)
        val perlMissing = if (serverId == "perlnavigator") {
            (listOf(server.command) + server.requiredCommands).distinct().filter { command ->
                runSuspendCatching { localProcessHost.resolveExecutable(command) }.getOrNull() == null
            }
        } else null
        val commandReady = perlMissing?.isEmpty() ?: verifyLocalRuntimeCommands(server)
        return Status(
            serverId = serverId,
            plannedVersion = server.pinnedVersion,
            catalogAvailable = catalogAvailable,
            installed = commandReady,
            verified = false,
            commandReady = commandReady,
            message = if (perlMissing != null) {
                val readiness = "Perl runtime execution and optional PPI/Perl::Critic, Perl::Tidy and perlimports modules are unverified; analysis requires explicit project enablement."
                if (commandReady) "PerlNavigator, Node and Perl commands detected. $readiness"
                else "Missing Ubuntu commands: ${perlMissing.joinToString()}. " +
                    "Install Perl separately from the language-server npm recipe. " +
                    "The reviewed PerlNavigator ${server.pinnedVersion} recipe is in Extensions → Language Servers. $readiness"
            } else if (serverId == LspSvelteProfile.ID) {
                if (commandReady) "Svelte language-server, Node and npm commands detected; command names do not prove the exact managed package, runtime or launcher. Typed managed identity and explicit project compiler/config/plugin consent are required for the guarded companion/observer integration. Native and device semantics remain unverified."
                else "Svelte language-server/Node/npm commands are missing in Ubuntu; the exact reviewed ${server.pinnedVersion} recipe is in Extensions → Language Servers. Managed installation requires owned Node 22+ and npm 10+. Original payload, native semantics and target-device acceptance remain unverified."
            } else if (serverId == "powershell-editor-services") {
                if (commandReady) "PSES and Node commands detected; discovery does not prove the exact PSES 4.7.0 / PowerShell 7.6.7 receipts, private pipe or deny-safe host. Managed byte proof and informed project/profile/module consent are required before startup."
                else "PSES/Node is missing in Ubuntu. Extensions → Language Servers offers PSES 4.7.0 plus its exact owned PowerShell 7.6.7 dependency as two artifacts for one server. Interactive defaults are preserved. Original payload/license/device acceptance remains separate."
            } else if (serverId == LspSqruffProfile.ID) {
                if (commandReady) "sqruff command detected; its name does not establish the reviewed 0.41.0 build or managed identity. Native project configuration requires consent; special coordinate/formatting repairs require exact managed proof. Diagnostics may remain pending."
                else "sqruff is missing in Ubuntu; the exact reviewed ${server.pinnedVersion} native recipe is in Extensions → Language Servers. Node is private installer-health only. Native lint/tokens/full formatting require guarded analysis; no database UI/execution is added. Original payload/device acceptance remains separate."
            } else if (serverId == LspPrismaProfile.ID) {
                if (commandReady) "Prisma language-server and Node commands detected; executable/package/runtime/prelude provenance is unverified. Legacy-schema analysis requires explicit project-config consent. A command name does not establish the managed pin, debug redirection or health guarantees; Next/ORM 8 is unavailable."
                else "Prisma language-server/Node commands are missing in Ubuntu; the reviewed ${server.pinnedVersion} legacy recipe is in Extensions → Language Servers. Managed installation requires selected Node 22+ and npm 10. Original payload, semantic and device acceptance remain unverified; Next/ORM 8 needs a separate adapter."
            } else if (serverId == "clojure-lsp") {
                if (commandReady) "Clojure command detected; active executable provenance and managed private-state guarantees are unverified. Full analysis requires informed trust and project-selected tools. Initial guest-temp log residue may remain."
                else "Clojure LSP is missing in Ubuntu; the exact reviewed ${server.pinnedVersion} native recipe is in Extensions → Language Servers. Node is needed for its managed wrapper/health; project tools are separate. Native platform and analysis readiness remain unverified."
            } else if (serverId == "make-ls") {
                if (commandReady) "Make LS command detected; executable provenance and parser readiness are unverified. Explicit project-read consent is required; hover, document symbols and formatting are unavailable for this pin."
                else "Make LS is missing in Ubuntu; the exact reviewed ${server.pinnedVersion} recipe is in Extensions → Language Servers. No Make/Go/compiler runtime is needed for parsing; target acceptance is unverified."
            } else if (serverId == "lemminx") {
                if (commandReady) "LemMinX, Node and Java commands detected; fixed Java 21, original JAR and private managed wrapper are unverified. XML analysis requires explicit consent for local and remote references."
                else "LemMinX/Node/Java commands are missing in Ubuntu; install the reviewed ${server.pinnedVersion} recipe from Extensions → Language Servers. Fixed Java/JAR and schema/network readiness remain unverified."
            } else if (commandReady) {
                "Language-server executable detected in Ubuntu; protocol capabilities are checked when it starts"
            } else {
                if (localReviewedRecipe != null || localVendorRecipe != null) "Language server is not available in local Ubuntu; install the reviewed ${server.pinnedVersion} recipe from Extensions → Language Servers"
                else "Language server is not available in local Ubuntu; install it from the Linux terminal"
            },
        )
    }

    private suspend fun verifyLocalRuntimeCommands(server: ManagedLanguageServerPlanEntry): Boolean {
        if(server.packageFamilyId==LocalUbuntuElpPolicy.FAMILY)return false
        val commands = linkedSetOf(server.command).apply { addAll(server.requiredCommands) }
        for (command in commands) {
            ProcessSecurityPolicy.requireExecutableName(command)
            val resolved = runSuspendCatching { localProcessHost.resolveExecutable(command) }.getOrNull()
            if (resolved == null) return false
        }
        return true
    }

}
