package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class LocalReviewedWholeTreeLock(
    val schema: Int = 1,
    val familyId: String,
    val version: String,
    val installer: String = "local-ubuntu-reviewed-tree-v1",
    val provider: String,
    val releaseTag: String,
    val command: String,
    val artifactFormat: String,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSize: Long,
    val treeEntryPoint: String,
    val commands: Map<String, String>,
    val treeFiles: Int,
    val treeSymlinks: Int,
    val treeBytes: Long,
    val treeSha256: String,
    val treeLinksSha256: String,
    val runtimeDependencies: List<String>,
    val provenanceUrl: String,
    val guestProfile: String,
    val libcCompatibility: String,
    val integration: String,
    val compatibilityEvidenceUrl: String,
    val installedAtEpochMs: Long,
    val assetSha512: String? = null,
)

// Commit durable state only after verification succeeds.
class LocalReviewedWholeTreeArtifactInstaller(
    context: Context,
    private val localAuthority: LocalManagedPackageAuthority,
    private val ubuntuEnvironment: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val downloader = UserInitiatedArtifactTransfer(appContext)
    private val json = Json { encodeDefaults = true }
    private val root = appContext.filesDir.canonicalPath

    fun supports(recipe: GitHubReleaseInstallRecipe): Boolean = LocalUbuntuReviewedWholeTreePolicy.supports(recipe)
    fun supports(recipe: VendorArtifactInstallRecipe): Boolean = LocalUbuntuReviewedWholeTreePolicy.supports(recipe)

    suspend fun install(recipe: GitHubReleaseInstallRecipe, progress: (String) -> Unit = {}): ManagedPackageRecord {
        require(supports(recipe)) { "GitHub artifact is outside the certified local Ubuntu whole-tree cohort" }
        return install(
            Spec(
                familyId = recipe.familyId,
                version = recipe.version,
                provider = "GITHUB_RELEASE",
                releaseTag = recipe.releaseTag,
                command = recipe.command,
                artifactFormat = recipe.artifactFormat,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetMaxBytes = recipe.assetSize,
                expectedAssetBytes = recipe.assetSize,
                treeEntryPoint = requireNotNull(recipe.treeEntryPoint),
                treeExecutablePaths = recipe.treeExecutablePaths,
                treeAuxCommands = emptyMap(),
                treeMaxFiles = requireNotNull(recipe.treeMaxFiles),
                treeMaxUnpackedBytes = requireNotNull(recipe.treeMaxUnpackedBytes),
                treeLinkPolicy = DeclarativeWholeTreeLinkPolicy.REJECT,
                treeModePolicy = DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST,
                provenanceUrl = recipe.provenanceUrl,
                healthArgs = recipe.healthArgs,
                runtimeCompatibility = recipe.runtimeCompatibility,
            ), progress = progress,
        )
    }

    suspend fun install(recipe: VendorArtifactInstallRecipe, progress: (String) -> Unit = {}): ManagedPackageRecord {
        require(supports(recipe)) { "Vendor artifact is outside the certified local Ubuntu whole-tree cohort" }
        return install(
            Spec(
                familyId = recipe.familyId,
                version = recipe.version,
                provider = "VENDOR_OFFICIAL",
                releaseTag = recipe.releaseTag,
                command = recipe.command,
                artifactFormat = recipe.artifactFormat,
                assetName = recipe.assetName,
                assetUrl = recipe.assetUrl,
                assetSha256 = recipe.assetSha256,
                assetMaxBytes = recipe.assetMaxBytes,
                expectedAssetBytes = null,
                treeEntryPoint = requireNotNull(recipe.treeEntryPoint),
                treeExecutablePaths = recipe.treeExecutablePaths,
                treeAuxCommands = recipe.treeAuxCommands,
                treeMaxFiles = requireNotNull(recipe.treeMaxFiles),
                treeMaxUnpackedBytes = requireNotNull(recipe.treeMaxUnpackedBytes),
                treeLinkPolicy = recipe.treeLinkPolicy,
                treeModePolicy = recipe.treeModePolicy,
                provenanceUrl = recipe.provenanceUrl,
                healthArgs = recipe.healthArgs,
                runtimeCompatibility = recipe.runtimeCompatibility,
            ), progress = progress,
        )
    }

    suspend fun install(recipe: LocalUbuntuVendorRecipe, progress: (String) -> Unit = {},
        nodeDependency: ManagedPackageRecord? = null, powerShellDependency: ManagedPackageRecord? = null,
        nimDependencies: List<ManagedPackageRecord> = emptyList(), elpHealthPermit:ElpStageHealthPermit?=null): ManagedPackageRecord {
        recipe.validate()
        if(recipe.familyId==LocalUbuntuElpPolicy.FAMILY) {
            require(nodeDependency==null&&powerShellDependency==null&&nimDependencies.isEmpty())
            return LocalUbuntuElpInstaller(appContext,localAuthority).install(recipe,elpHealthPermit,progress)
        }
        require(elpHealthPermit==null)
        if (recipe.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY) {
            require(nodeDependency==null&&powerShellDependency==null&&nimDependencies.isEmpty())
            return LocalUbuntuGraphqlInstaller(appContext,localAuthority).installNode(recipe,progress)
        }
        if (recipe.familyId == LocalUbuntuTomlNodePolicy.FAMILY) {
            require(nodeDependency==null&&powerShellDependency==null&&nimDependencies.isEmpty())
            return LocalUbuntuTomlInstaller(appContext,localAuthority).installNode(recipe,progress)
        }
        val def = recipe.definition
        if (recipe.familyId == LocalUbuntuNimPolicy.FAMILY) {
            require(nimDependencies.map { it.familyId }.toSet() == setOf(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuNimHelperPolicy.FAMILY) && nimDependencies.size == 2)
            nimDependencies.forEach { dependency ->
                LocalUbuntuNimLaunchAuthority.requireDependency(dependency,dependency.familyId,root)
                check(localAuthority.owns(dependency) && localAuthority.verifyNimPackageWithoutHealth(dependency)) { "Nim exact private prerequisite failed byte admission" }
            }
        } else require(nimDependencies.isEmpty())
        if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) {
            val runtime = requireNotNull(powerShellDependency) { "Explicit PSES installation requires its exact owned PowerShell runtime" }
            LocalUbuntuPowerShellEditorServicesPolicy.requireRuntime(runtime, root)
            check(localAuthority.owns(runtime) && localAuthority.verify(runtime)) { "PowerShell runtime prerequisite failed verification" }
        } else require(powerShellDependency == null) { "This vendor recipe has no reviewed PowerShell prerequisite" }
        if (LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe)) {
            val node = requireNotNull(nodeDependency) { "Explicit js-debug installation requires its owned Node.js prerequisite" }
            LocalUbuntuJsDebugPolicy.requireNode(node, root)
            check(localAuthority.owns(node) && localAuthority.verify(node)) { "js-debug Node.js prerequisite failed verification" }
        } else require(nodeDependency == null) { "This vendor recipe has no reviewed Node.js prerequisite" }
        return install(Spec(recipe.familyId, recipe.version, "LOCAL_UBUNTU_VENDOR", recipe.releaseTag, recipe.command,
            if (def.format == LocalUbuntuVendorFormat.ZIP) DeclarativeGitHubArtifactFormat.ZIP else DeclarativeGitHubArtifactFormat.TAR_GZ,
            recipe.assetName, recipe.assetUrl, recipe.assetSha256, def.maxAssetBytes, recipe.assetSize,
            recipe.entryPoint, LocalUbuntuVendorPolicy.executablePaths(recipe).toList(), def.auxiliaryCommands,
            def.maxFiles, def.maxUnpackedBytes,
            if (recipe.familyId in setOf(LocalUbuntuNimPolicy.FAMILY,LocalUbuntuNimToolchainPolicy.FAMILY)) DeclarativeWholeTreeLinkPolicy.REJECT else DeclarativeWholeTreeLinkPolicy.SAFE_RELATIVE,
            if (recipe.familyId in setOf(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY)) DeclarativeWholeTreeModePolicy.PINNED_ARCHIVE else DeclarativeWholeTreeModePolicy.EXPLICIT_ALLOWLIST, recipe.provenanceUrl, LocalUbuntuVendorPolicy.healthArgs(recipe),
            DeclarativeRuntimeCompatibility(DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64,
                if (def.admission == LocalUbuntuVendorAdmission.INTERPRETED) DeclarativeLibcCompatibility.INTERPRETED else DeclarativeLibcCompatibility.GLIBC,
                requiredGuestPackages = recipe.requiredPackages, evidenceUrl = recipe.provenanceUrl), recipe,
            listOfNotNull(nodeDependency, powerShellDependency) + nimDependencies), progress)
    }

    private suspend fun install(spec: Spec, progress: (String) -> Unit): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Local Ubuntu reviewed whole-tree artifacts currently require ARM64"
        }
        PackageBackendContract.requireSame(
            "Local reviewed Ubuntu whole-tree artifact",
            PackageBackendId.LOCAL_APP,
            localAuthority.backendId,
            ubuntuEnvironment.backendId,
        )

        progress("Checking Ubuntu runtime…\n")
        val powerShell = spec.vendor?.let(LocalUbuntuVendorPolicy::isPowerShell) == true
        val nim = LocalUbuntuNimPolicy.isFamily(spec.familyId)
        val vueHelper = spec.familyId == LocalUbuntuVueHelperPolicy.FAMILY
        // The PowerShell family opts out of broad pending-package repair under the existing guest owner.
        if (!powerShell && !nim && !vueHelper) ubuntuEnvironment.ensureInstalled()
        val layer = ubuntuEnvironment.ensurePackages(spec.runtimeCompatibility.requiredGuestPackages, progress, allowSharedRepair = !powerShell && !nim && !vueHelper)
        val dependencyRecords = listOfNotNull(layer) + spec.ownedRuntimeDependencies
        dependencyRecords.forEach { dependency ->

            if (dependency === layer) localAuthority.adopt(dependency)
            check(if (LocalUbuntuNimPolicy.isFamily(dependency.familyId)) localAuthority.verifyNimPackageWithoutHealth(dependency) else localAuthority.verify(dependency)) {
                "Ubuntu runtime dependency ${dependency.familyId}@${dependency.version} failed verification"
            }
        }
        PackageRuntimeCompatibilityPolicy.verifyGuestCommands(ubuntuEnvironment, spec.runtimeCompatibility)
        val reusable = LocalPackageInstallJournal.transactionMutex.withLock {
            val candidate = localAuthority.recoveryReceipt(spec.familyId, spec.version, spec.kind)
            val paths = LocalPackageInstallJournal.paths(root, spec.familyId, spec.version, spec.kind)
            LocalPackageInstallJournal.recover(paths,
                candidate?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            LocalInstalledPackageReuse.artifact(
                candidate,
                spec.familyId, spec.version, spec.assetSha256.ifEmpty { candidate?.artifactSha256.orEmpty() }, localAuthority::owns, localAuthority::verify,
                identity = { matchesReviewedInstall(it, spec, dependencyRecords.map { item -> "${item.familyId}@${item.version}" }.distinct().sorted()) },
            )
        }
        if (reusable != null) {
            if (!reusable.active) localAuthority.activate(reusable)
            progress("Using verified installed ${spec.familyId} ${spec.version}; no download or extraction needed.\n")
            return@withContext reusable.copy(active = true)
        }
        val artifactId = "local-ubuntu-tree-${safe(spec.familyId).take(40)}-${safe(spec.version).take(24)}"
        val artifactSpec: TrustedArtifactDescriptor = spec.vendor?.artifactDescriptor(artifactId)
            ?: TrustedArtifactSpec(artifactId, spec.assetUrl, spec.assetSha256, spec.assetName,
                spec.assetMaxBytes, spec.expectedAssetBytes)
        progress("Downloading ${spec.assetName}…\n")
        downloader.withArtifact(artifactSpec, onProgress = { status ->
            progress(status.describe() + "\n")
        }) { artifact ->
            // Download admission used the publisher's digest. Persist a canonical SHA-256 as well, so
            // existing receipt, journal and cache identities remain compatible.
            val spec = if (spec.vendor?.assetSha512 != null) {
                val resolved = spec.vendor.withVerifiedSha256(LocalUbuntuNpmPolicy.sha256(artifact))
                spec.copy(assetSha256 = resolved.assetSha256, vendor = resolved)
            } else spec
            val actualAssetSize = artifact.length()
            require(actualAssetSize in 1L..spec.assetMaxBytes) { "Whole-tree artifact size is outside its reviewed bound" }
            spec.expectedAssetBytes?.let { require(actualAssetSize == it) { "Whole-tree artifact size changed" } }

            val transactionNeed = Math.addExact(
                Math.addExact(spec.treeMaxUnpackedBytes, actualAssetSize),
                256L * 1024L * 1024L,
            )
            DeviceWorkstationStorageGuard.requireHeadroom(
                additionalBytes = transactionNeed,
                purpose = "install local Ubuntu whole-tree ${spec.familyId} ${spec.version}",
            )

            progress("Verifying and extracting publisher archive…\n")
            (spec.vendor?.let { LocalUbuntuVendorArchive.project(it, artifact, ubuntuEnvironment.launcherPath()) }
                ?: SafeWholeTreeArchive.project(
                archive = artifact,
                format = spec.artifactFormat,
                executablePaths = spec.treeExecutablePaths.toSet(),
                maxFiles = spec.treeMaxFiles,
                maxUnpackedBytes = spec.treeMaxUnpackedBytes,
                linkPolicy = spec.treeLinkPolicy,
                modePolicy = spec.treeModePolicy,
                checkActive = coroutineContext.let { context -> { context.ensureActive() } },
            )).use { projection ->
                val commands = linkedMapOf(spec.command to spec.treeEntryPoint).apply { putAll(spec.treeAuxCommands) }
                commands.forEach { (name, relative) ->
                    require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid whole-tree command" }
                    val payload = resolvedProjectedFile(projection, relative)
                    if (LocalUbuntuNodeDistribution.isAuxScript(spec.familyId, spec.version, name, relative, payload.file)) return@forEach
                    spec.vendor?.let { LocalUbuntuVendorPolicy.admit(it, payload.file); return@forEach }
                    val elf = LinuxArm64ElfAdmission.requireUbuntuGlibc(payload.file)
                    require(elf.interpreter == LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) {
                        "Whole-tree command '$name' does not use the certified Ubuntu/glibc interpreter"
                    }
                }

                val safeFamily = safe(spec.familyId)
                val safeVersion = safe(spec.version)
                val transactionPaths = LocalPackageInstallJournal.paths(
                    root, spec.familyId, spec.version, spec.kind,
                )
                val stage = transactionPaths.stage.absolutePath
                val final = transactionPaths.final.absolutePath
                val previous = transactionPaths.previous.absolutePath
                val stageTree = "$stage/payload/tree"
                val stageBin = "$stage/payload/bin"
                val stageHostBin = "$stage/payload/host-bin"
                val finalTree = "$final/payload/tree"
                val finalBin = "$final/payload/bin"
                val finalHostBin = "$final/payload/host-bin"
                listOf(stage, final, previous, stageTree, stageBin, stageHostBin, finalTree, finalBin, finalHostBin)
                    .forEach(LocalExecutionSubstrate::requireSafeLocalPath)

                val stageDir = File(stage)
                val finalDir = File(final)
                val previousDir = File(previous)
                LocalPackageInstallJournal.transactionMutex.withLock {
                    val existingReceipt = localAuthority.recoveryReceipt(spec.familyId, spec.version, spec.kind)
                    LocalPackageInstallJournal.recover(transactionPaths,
                        existingReceipt?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
                    check(File(stageTree).mkdirs() && File(stageBin).mkdirs() && File(stageHostBin).mkdirs()) {
                        "Could not prepare local whole-tree transaction"
                    }

                    var activation: LocalPackageInstallJournal.Activation? = null
                    try {
                        val activationId = LocalPackageInstallJournal.prepare(stageDir)
                        // The projection and staged tree live on the same app-owned filesystem.

                        val largestProjectedFile = projection.files.maxOfOrNull { it.size } ?: 0L
                        DeviceWorkstationStorageGuard.requireHeadroom(
                            additionalBytes = Math.addExact(largestProjectedFile, 64L * 1024L * 1024L),
                            purpose = "stage the largest member of local Ubuntu whole-tree ${spec.familyId} ${spec.version}",
                        )
                        projection.files.forEach { file ->
                            val destination = File(stageTree, file.relativePath)
                            LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
                            file.file.inputStream().use { LocalExecutionSubstrate.pushStream(it, destination.absolutePath, file.mode) }
                            projection.releaseLocalPayload(file)
                        }
                        projection.symlinks.forEach { link ->
                            val destination = File(stageTree, link.relativePath)
                            LocalExecutionSubstrate.requireSafeLocalPath(destination.absolutePath)
                            check(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true) {
                                "Could not prepare local whole-tree symlink directory"
                            }
                            check(!destination.exists() && !PathSecurity.isSymbolicLink(destination)) { "Whole-tree symlink path already exists" }
                            Os.symlink(link.target, destination.absolutePath)
                        }

                        val treeManifest = projection.files.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = "\n") {
                            "${it.sha256}  ${it.relativePath}"
                        }
                        pushText("$stage/TREE_SHA256SUMS", treeManifest, 420)
                        val linksManifest = projection.symlinks.sortedBy { it.relativePath }.joinToString(separator = "\n", postfix = if (projection.symlinks.isEmpty()) "" else "\n") {
                            "${it.relativePath}\t${it.target}\t${it.resolvedPath}"
                        }
                        pushText("$stage/TREE_LINKS.tsv", linksManifest, 420)
                        val linksSha = sha256Bytes(linksManifest.toByteArray(Charsets.UTF_8))

                        check(LocalPackageIntegrity.verify(File(stageTree), File(stage, "TREE_SHA256SUMS"),
                            exactInventory = true, progress = progress, manifestRoot = File(stage))) {
                            "Local whole-tree transfer integrity failed"
                        }
                        verifyStagedLinks(stageTree, projection.symlinks)

                        progress("Checking ${spec.command} in Ubuntu…\n")
                        var nimGenerated: Map<String,String> = emptyMap()
                        val vendorHealth = spec.vendor?.let { vendor ->
                            if (vendor.familyId == LocalUbuntuVueHelperPolicy.FAMILY) {
                                LocalUbuntuVueLaunchAuthority.helperPaths.forEach { path ->
                                    val text = appContext.assets.open("lsp/vue/"+path.substringAfterLast('/')).use { it.readBytes().toString(Charsets.UTF_8) }
                                    require(LocalUbuntuVuePolicy.sha256(text.toByteArray()) == LocalUbuntuVueLaunchAuthority.expectedHelperHashes[path])
                                    pushText("$stage/$path",text,420)
                                }
                                val finalGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final,"$root/packages/"))
                                LocalUbuntuVueLaunchAuthority.stagedHelperAuthority(stageDir,finalGuest,activationId,root)
                                    .forEach { (path,text) -> pushText("$stage/$path",text,420) }
                                return@let LocalUbuntuVueLaunchAuthority.helperHealthCommand(stageDir)
                            }
                            if (LocalUbuntuNimPolicy.isFamily(vendor.familyId)) {
                                val helpers = LocalUbuntuNimLaunchAuthority.helperPaths.associateWith { path ->
                                    appContext.assets.open("lsp/nim/" + path.substringAfterLast('/')).use { it.readBytes().toString(Charsets.UTF_8) }
                                }
                                helpers.forEach { (path,text) -> pushText("$stage/$path",text,420) }
                                val context = coroutineContext
                                nimGenerated = LocalUbuntuNimLaunchAuthority.prepare(vendor,stageDir,activationId,
                                    requireNotNull(LocalLinuxPackageEnvironment.guestPath(final,"$root/packages/")), spec.ownedRuntimeDependencies,root,helpers) { context.ensureActive() }
                                nimGenerated.forEach { (path,text) -> pushText("$stage/$path",text,420) }
                                return@let when(vendor.familyId) {
                                    LocalUbuntuNimPolicy.FAMILY -> LocalUbuntuNimLspHealth.installCommand(stage,"$stageTree/${spec.treeEntryPoint}",LocalUbuntuNimHelperPolicy.EXECUTABLE)
                                    LocalUbuntuNimHelperPolicy.FAMILY -> {
                                        val a = LocalUbuntuPowerShellRuntimePolicy.strictObject(nimGenerated.getValue(LocalUbuntuNimLaunchAuthority.FILE_NAME),65536)
                                        listOf("$stageTree/${spec.treeEntryPoint}","$stage/payload/nim-helper-identity-health.cjs","--health",stage,
                                            LocalUbuntuPowerShellRuntimePolicy.string(a,"configSha256"),LocalUbuntuPowerShellRuntimePolicy.string(a,"closureSha256"),LocalUbuntuNimHelperPolicy.SOURCE_BINDING_SHA256)
                                    }
                                    else -> emptyList() // Static-only compiler/backend/Nimble prerequisite, never invoked for installer health.
                                }
                            }
                            if (vendor.definition.lsp || LocalUbuntuVendorPolicy.isPowerShell(vendor)) {
                                pushText("$stage/${LocalUbuntuVendorPolicy.lspHealthFileName(vendor)}", LocalUbuntuVendorPolicy.lspHealthScript(vendor), 420)
                                if (LocalUbuntuVendorPolicy.isPowerShell(vendor)) {
                                    pushText("$stage/${LocalUbuntuPowerShellLaunchAuthority.NOTICE_FILE}", LocalUbuntuPowerShellLaunchAuthority.noticeText, 420)
                                    val runtime = spec.ownedRuntimeDependencies.singleOrNull()
                                    val finalGuestRoot = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final, "$root/packages/"))
                                    pushText("$stage/${LocalUbuntuPowerShellLaunchAuthority.FILE_NAME}",
                                        LocalUbuntuPowerShellLaunchAuthority.create(vendor, activationId, stage, finalGuestRoot, treeManifest, runtime), 420)
                                    val runtimeEntry = if (vendor.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) File(stageTree, "pwsh")
                                        else File(requireNotNull(runtime).installRoot, "payload/tree/pwsh")
                                    val powerShellContext = coroutineContext
                                    val inventory = LocalUbuntuPowerShellRuntimePolicy.requireTree(runtimeEntry) { powerShellContext.ensureActive() }
                                    LocalUbuntuPowerShellRuntimePolicy.requireExecutionClosure(inventory,
                                        File(root, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")) { powerShellContext.ensureActive() }
                                }
                                if (vendor.familyId == LocalUbuntuClojurePolicy.FAMILY) {
                                    val finalGuestRoot = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final, "$root/packages/"))
                                    pushText("$stage/${LocalUbuntuClojureLaunchAuthority.FILE_NAME}",
                                        LocalUbuntuClojureLaunchAuthority.create(vendor, projection, activationId, stage, finalGuestRoot), 420)
                                }
                                LocalUbuntuVendorPolicy.lspProbe(vendor, stage, "$stageTree/${spec.treeEntryPoint}")
                            } else LocalUbuntuVendorPolicy.healthCommand(vendor, "$stageTree/${spec.treeEntryPoint}")
                        }
                        val vueHealthHold=if(vueHelper)LocalPackageInstallJournal.beginVueHealth(transactionPaths) else null
                        val health = if (nim && vendorHealth?.isEmpty() == true) null else health(vendorHealth ?: listOf("$stageTree/${spec.treeEntryPoint}") + spec.healthArgs, cleanNimEnvironment = nim || vueHelper)
                        if(vueHealthHold!=null && health!=null)LocalPackageInstallJournal.finishVueHealth(vueHealthHold,LocalUbuntuVueLspHealth.helperCleanupVerified(health))
                        if (vueHelper && health != null) check(LocalUbuntuVueLspHealth.helperSucceeded(health)) { "Vue private Node identity health or owned cleanup failed" }
                        if (nim && health != null) check(!health.timedOut && !health.outputIncomplete && health.exitCode == 0 &&
                            (spec.familyId != LocalUbuntuNimPolicy.FAMILY || LocalUbuntuNimLspHealth.succeeded(health))) { "Nim isolated health failed: ${health.output.takeLast(8000)}" }
                        if (health != null) check(health.exitCode == 0 && (!powerShell || (!health.timedOut && !health.outputIncomplete))) { "Local whole-tree health check failed: ${health.output.takeLast(8_000)}" }
                        if (spec.familyId == "runtime.node") {
                            for (name in listOf("npm", "npx")) {
                                val auxiliary = health(listOf("$stageTree/${spec.treeEntryPoint}", "$stageTree/${commands.getValue(name)}", "--version"))
                                check(auxiliary.exitCode == 0 && !auxiliary.timedOut) { "Original $name failed its Ubuntu health check" }
                            }
                        }
                        if (spec.familyId == LocalUbuntuNimPolicy.FAMILY) {
                            val finalGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final,"$root/packages/"))
                            pushText("$stage/payload/nim-health-identity.json",LocalUbuntuNimLaunchAuthority.finalHealthSeed(
                                nimGenerated.getValue("payload/nim-health-identity.json"),stage,finalGuest),420)
                        }
                        if(vueHelper) {
                            check(LocalPackageIntegrity.verify(File(stageTree),File(stage,"TREE_SHA256SUMS"),exactInventory=true,manifestRoot=stageDir)) {
                                "Original private Vue Node tree changed during staged health"
                            }
                            verifyStagedLinks(stageTree,projection.symlinks)
                        }
                        val stagedEnvironment = LocalUbuntuJdkPolicy.environment(spec.familyId, stageTree, commands)
                        stagedEnvironment["JAVA_HOME"]?.let { home ->
                            val compilerHealth = ubuntuEnvironment.execute(
                                listOf("/bin/sh", "-c", LocalUbuntuJdkPolicy.healthScript(home, compileSample = true)),
                                maxOutputBytes = 128_000,
                            )
                            check(compilerHealth.exitCode == 0) { "Managed JDK compile/run admission failed: ${compilerHealth.output.takeLast(8_000)}" }
                        }

                        val guestTree = requireNotNull(LocalLinuxPackageEnvironment.guestPath(finalTree, "$root/packages/"))
                        val guestRoot = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final, "$root/packages/"))
                        commands.forEach { (command, relative) ->
                            spec.vendor?.let { vendor ->
                                pushText("$stageBin/$command", LocalUbuntuVendorPolicy.guestWrapper(vendor, "$guestTree/$relative", guestRoot), 493)
                                pushText("$stageHostBin/$command", LocalUbuntuVendorPolicy.hostWrapper(ubuntuEnvironment.launcherPath(), "$guestRoot/payload/bin/$command"), 493)
                                return@forEach
                            }
                            if (spec.familyId == "runtime.node") {
                                pushText("$stageBin/$command", LocalUbuntuNodeDistribution.guestWrapper(spec.version, command, guestTree), 493)
                                pushText("$stageHostBin/$command", LocalUbuntuNodeDistribution.hostWrapper(ubuntuEnvironment.launcherPath(), "$guestRoot/payload/bin/$command"), 493)
                                return@forEach
                            }
                            val guestWrapper = "#!/bin/sh\nset -eu\nexec ${q("$guestTree/$relative")} \"${'$'}@\"\n"
                            pushText("$stageBin/$command", guestWrapper, 493)
                            val hostWrapper = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntuEnvironment.launcherPath())} ${q("$guestTree/$relative")} \"${'$'}@\"\n"
                            pushText("$stageHostBin/$command", hostWrapper, 493)
                        }

                        val commandsManifest = commands.toSortedMap().entries.joinToString(separator = "\n", postfix = "\n") { (name, relative) ->
                            "$name\t$relative"
                        }
                        pushText("$stage/TREE_COMMANDS.tsv", commandsManifest, 420)
                        val commandsSha = sha256Bytes(commandsManifest.toByteArray(Charsets.UTF_8))

                        val dependencyKeys = dependencyRecords.map { "${it.familyId}@${it.version}" }.distinct().sorted()
                        val lock = LocalReviewedWholeTreeLock(
                            familyId = spec.familyId,
                            version = spec.version,
                            provider = spec.provider,
                            releaseTag = spec.releaseTag,
                            command = spec.command,
                            artifactFormat = spec.vendor?.definition?.format?.name ?: spec.artifactFormat.name,
                            assetName = spec.assetName,
                            assetUrl = spec.assetUrl,
                            assetSha256 = spec.assetSha256,
                            assetSha512 = spec.vendor?.assetSha512,
                            assetSize = actualAssetSize,
                            treeEntryPoint = spec.treeEntryPoint,
                            commands = commands.toSortedMap(),
                            treeFiles = projection.files.size,
                            treeSymlinks = projection.symlinks.size,
                            treeBytes = projection.totalBytes,
                            treeSha256 = projection.treeSha256,
                            treeLinksSha256 = linksSha,
                            runtimeDependencies = dependencyKeys,
                            provenanceUrl = spec.provenanceUrl,
                            guestProfile = spec.runtimeCompatibility.guestProfile.name,
                            libcCompatibility = spec.runtimeCompatibility.libc.name,
                            integration = spec.runtimeCompatibility.integration.name,
                            compatibilityEvidenceUrl = spec.runtimeCompatibility.evidenceUrl,
                            installedAtEpochMs = System.currentTimeMillis(),
                        )
                        pushText("$stage/RECIPE_LOCK.json", json.encodeToString(lock), 420)

                        val maxStageFiles = Math.addExact(spec.treeMaxFiles, commands.size * 2 + if (nim || vueHelper) 20 else 8)
                        val maxStageKiB = Math.addExact((spec.treeMaxUnpackedBytes + 1023L) / 1024L, 8192L)
                        val bounds = LocalExecutionSubstrate.shellBounded(
                            "set -eu; C=${'$'}(find ${q(stage)} -type f | wc -l | tr -d ' '); " +
                                "B=${'$'}(du -sk ${q(stage)} | awk '{print ${'$'}1}'); " +
                                "test \"${'$'}C\" -le ${q(maxStageFiles.toString())}; test \"${'$'}B\" -le ${q(maxStageKiB.toString())}",
                            maxOutputBytes = 16_384,
                        )
                        check(bounds.exitCode == 0) { "Local whole-tree package exceeded reviewed final bounds" }

                        val sealSha = ReviewedWholeTreeSeal.create(
                            File(stage), spec.familyId, maxStageFiles,
                            Math.addExact(spec.treeMaxUnpackedBytes, 8L * 1024 * 1024), progress,
                        )

                        progress("Activating verified ${spec.command}…\n")
                        activation = LocalPackageInstallJournal.activate(stageDir, finalDir, previousDir)

                        val record = ManagedPackageRecord(
                            familyId = spec.familyId,
                            version = spec.version,
                            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                            installRoot = final,
                            installedAtEpochMs = System.currentTimeMillis(),
                            active = true,
                            pathEntries = listOf(finalHostBin),
                            commands = commands.keys.associateWith { "$finalHostBin/$it" },
                            environment = LocalUbuntuJdkPolicy.environment(spec.familyId, finalTree, commands),
                            dependencies = dependencyKeys,
                            healthChecks = if (spec.familyId == "runtime.node") listOf("node", "npm", "npx").map {
                                ManagedPackageHealthCheck("host-bin/$it", listOf("--version"))
                            } else listOf(ManagedPackageHealthCheck("host-bin/${spec.command}", spec.healthArgs)),
                            artifactSha256 = spec.assetSha256.lowercase(),
                            abi = "arm64-v8a",
                            metadata = mapOf(
                                LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                                LocalManagedPackageMetadata.KIND_KEY to spec.kind,
                                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
                                LocalManagedPackageMetadata.COMMAND_KEY to spec.command,
                                LocalManagedPackageMetadata.TREE_SHA256_KEY to projection.treeSha256,
                                LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY to linksSha,
                                LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY to commandsSha,
                                LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealSha,
                            ) + (if (spec.vendor != null) mapOf(LocalUbuntuVendorPolicy.POLICY_KEY to LocalUbuntuVendorPolicy.POLICY_VERSION) else emptyMap()),
                        )
                        localAuthority.adopt(record)
                        activation = null // The durable receipt is now the commit marker.
                        withContext(NonCancellable) { LocalPackageInstallJournal.commit(previousDir) }
                        if (spec.familyId == LocalUbuntuJdkPolicy.FAMILY || actualAssetSize >= LARGE_ARCHIVE_CACHE_BYTES) {
                            when (artifactSpec) {
                                is TrustedArtifactSpec -> downloader.discard(artifactSpec)
                                is TrustedSha512ArtifactSpec -> downloader.discard(artifactSpec)
                            }
                        }
                        record
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            if(vueHelper && runCatching { LocalPackageInstallJournal.requireNoVueHealthHold(transactionPaths) }.isFailure) {
                                failure.addSuppressed(IllegalStateException("Vue helper cleanup is unknown; all package trees and journal preserved"))
                                return@withContext
                            }
                            activation?.let { committed ->
                                runCatching { LocalPackageInstallJournal.rollback(finalDir, previousDir, committed) }
                                    .exceptionOrNull()?.let(failure::addSuppressed)
                            }
                            if (!PathSecurity.deleteTreeNoFollow(stageDir)) {
                                failure.addSuppressed(IllegalStateException("Could not clean failed whole-tree staging tree"))
                            }
                        }
                        throw failure
                    }
                }
            }
        }
    }

    private fun resolvedProjectedFile(projection: SafeTreeProjection, path: String): SafeTreeFile {
        val links = projection.symlinks.associateBy { it.relativePath }
        var current = path
        val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current)) { "Whole-tree command link graph contains a cycle" }
            val link = links[current] ?: break
            current = link.resolvedPath
        }
        return projection.files.singleOrNull { it.relativePath == current }
            ?: error("Whole-tree command target '$path' does not resolve to a regular file")
    }

    private fun verifyStagedLinks(treeRoot: String, links: List<SafeTreeSymlink>) {
        val canonicalRoot = File(treeRoot).canonicalFile
        links.forEach { link ->
            val file = File(treeRoot, link.relativePath)
            LocalExecutionSubstrate.requireSafeLocalPath(file.absolutePath)
            check(PathSecurity.isSymbolicLink(file)) { "Whole-tree symlink was not preserved: ${link.relativePath}" }
            check(Os.readlink(file.absolutePath) == link.target) { "Whole-tree symlink target changed: ${link.relativePath}" }
            check(file.exists()) { "Whole-tree symlink target is missing after staging: ${link.relativePath}" }
            val canonicalTarget = file.canonicalFile
            check(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                "Whole-tree symlink escaped the staged tree: ${link.relativePath}"
            }
        }
    }

    private suspend fun health(argv: List<String>, cleanNimEnvironment: Boolean = false): ExecResult {
        val prefix = if (cleanNimEnvironment) listOf("/usr/bin/env", "-i", "PATH=/usr/bin:/bin", "HOME=/root", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TMPDIR=/tmp") else emptyList()
        val command = (listOf("/system/bin/sh", ubuntuEnvironment.launcherPath()) + prefix + argv).joinToString(" ", transform = ::q)
        return LocalExecutionSubstrate.shellBounded(command, 128_000, timeoutMs = 60_000)
    }

    private suspend fun pushText(path: String, text: String, mode: Int) {
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode) }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._+-]"), "_")
    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
    private fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun matchesReviewedInstall(record: ManagedPackageRecord, spec: Spec, dependencies: List<String>): Boolean = runCatching {
        val file = File(record.installRoot, "RECIPE_LOCK.json")
        if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() !in 1L..64_000L) return@runCatching false
        val review = json.decodeFromString<LocalReviewedWholeTreeLock>(file.readText())
        val commands = linkedMapOf(spec.command to spec.treeEntryPoint).apply { putAll(spec.treeAuxCommands) }
        val health = if (spec.familyId == "runtime.node") listOf("node", "npm", "npx").map {
            ManagedPackageHealthCheck("host-bin/$it", listOf("--version"))
        } else listOf(ManagedPackageHealthCheck("host-bin/${spec.command}", spec.healthArgs))
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == spec.kind && record.dependencies == dependencies &&
            record.healthChecks == health && record.commands == commands.keys.associateWith { "${record.installRoot}/payload/host-bin/$it" } &&
            review.schema == 1 && review.familyId == spec.familyId && review.version == spec.version &&
            review.provider == spec.provider && review.releaseTag == spec.releaseTag && review.command == spec.command &&
            review.artifactFormat == (spec.vendor?.definition?.format?.name ?: spec.artifactFormat.name) &&
            review.assetName == spec.assetName && review.assetUrl == spec.assetUrl && review.assetSha256.matches(Regex("[0-9a-f]{64}")) &&
            review.assetSha256 == record.artifactSha256 &&
            (spec.assetSha256.isEmpty() || review.assetSha256 == spec.assetSha256) &&
            (spec.vendor?.assetSha512 == null || review.assetSha512 == spec.vendor.assetSha512) &&
            review.assetSize in 1L..spec.assetMaxBytes && (spec.expectedAssetBytes == null || review.assetSize == spec.expectedAssetBytes) &&
            review.treeEntryPoint == spec.treeEntryPoint && review.commands == commands && review.runtimeDependencies == dependencies &&
            review.treeFiles in 1..spec.treeMaxFiles && review.treeBytes in 1L..spec.treeMaxUnpackedBytes &&
            (spec.treeLinkPolicy != DeclarativeWholeTreeLinkPolicy.REJECT || review.treeSymlinks == 0) &&
            review.guestProfile == spec.runtimeCompatibility.guestProfile.name &&
            review.libcCompatibility == spec.runtimeCompatibility.libc.name && review.integration == spec.runtimeCompatibility.integration.name &&
            review.compatibilityEvidenceUrl == spec.runtimeCompatibility.evidenceUrl
    }.getOrDefault(false)

    private data class Spec(
        val familyId: String,
        val version: String,
        val provider: String,
        val releaseTag: String,
        val command: String,
        val artifactFormat: DeclarativeGitHubArtifactFormat,
        val assetName: String,
        val assetUrl: String,
        val assetSha256: String,
        val assetMaxBytes: Long,
        val expectedAssetBytes: Long?,
        val treeEntryPoint: String,
        val treeExecutablePaths: List<String>,
        val treeAuxCommands: Map<String, String>,
        val treeMaxFiles: Int,
        val treeMaxUnpackedBytes: Long,
        val treeLinkPolicy: DeclarativeWholeTreeLinkPolicy,
        val treeModePolicy: DeclarativeWholeTreeModePolicy,
        val provenanceUrl: String,
        val healthArgs: List<String>,
        val runtimeCompatibility: DeclarativeRuntimeCompatibility,
        val vendor: LocalUbuntuVendorRecipe? = null,
        val ownedRuntimeDependencies: List<ManagedPackageRecord> = emptyList(),
    ) {
        val kind get() = if (vendor == null) LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE else LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE
    }

    companion object {
        private const val LARGE_ARCHIVE_CACHE_BYTES = 128L * 1024L * 1024L
    }
}

internal object ReviewedWholeTreeSeal {
    suspend fun create(
        stage: File,
        familyId: String,
        maxFiles: Int,
        maxBytes: Long,
        progress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val requiresExactModes = LocalUbuntuNimPolicy.isFamily(familyId) || familyId in setOf(
            LocalUbuntuVueHelperPolicy.FAMILY,
            LocalUbuntuPowerShellRuntimePolicy.FAMILY,
            LocalUbuntuPowerShellEditorServicesPolicy.FAMILY,
        )
        if (requiresExactModes) setMetadataMode(File(stage, "DROIDE_ACTIVATION_ID"))
        val seal = LocalPackageIntegrity.seal(stage, maxFiles, maxBytes, progress)
        coroutineContext.ensureActive()
        val manifest = File(stage, "SHA256SUMS")
        check(manifest.isFile && !PathSecurity.isSymbolicLink(manifest)) { "Local whole-tree seal is missing" }
        if (requiresExactModes) setMetadataMode(manifest)
        seal
    }

    private fun setMetadataMode(file: File) {
        val descriptor = Os.open(
            file.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK,
            0,
        )
        try {
            val before = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_nlink == 1L) {
                "Reviewed metadata must be a regular file with one link: ${file.name}"
            }
            // Apply permissions to the opened inode, never to a replacement at the same path.
            Os.fchmod(descriptor, 420)
            val after = Os.fstat(descriptor)
            val named = Os.lstat(file.absolutePath)
            check(after.st_dev == before.st_dev && after.st_ino == before.st_ino && after.st_nlink == 1L &&
                after.st_mode and 4095 == 420 && named.st_dev == after.st_dev && named.st_ino == after.st_ino &&
                named.st_mode == after.st_mode && named.st_nlink == 1L) {
                "Reviewed metadata changed while setting permissions: ${file.name}"
            }
        } finally {
            Os.close(descriptor)
        }
    }
}
