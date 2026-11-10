package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class LocalUbuntuNpmRecipeInstaller(
    context: Context,
    private val projectRoot: File,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val root = appContext.filesDir.canonicalPath
    private val vueDownloader = UserInitiatedArtifactTransfer(appContext)

    suspend fun install(request: WorkstationInstallRecipe, nodeDependency: ManagedPackageRecord? = null,
        progress: (String) -> Unit = {}, activity: (String) -> Unit = {},
        museRuntime: ManagedPackageRecord? = null,
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        request.validate()
        require(LocalUbuntuNpmPolicy.supports(request)) { "This npm recipe has no reviewed local Ubuntu adapter" }
        require((request.familyId == LocalUbuntuMusePolicy.FAMILY) == (museRuntime != null)) {
            "Muse requires its separately managed native runtime"
        }
        val agentEnvironment = if (museRuntime != null) {
            val executable = LocalUbuntuMusePolicy.requireRuntime(museRuntime, root)
            require(authority.owns(museRuntime) && authority.verify(museRuntime)) { "Muse runtime failed integrity or health verification" }
            LocalUbuntuMusePolicy.environment(executable)
        } else if (request.familyId == "plugin.kimi-code") "export KIMI_CODE_NO_AUTO_UPDATE=1\n" else ""
        val extraDependencies = museRuntime?.let { listOf("${it.familyId}@${it.version}") }.orEmpty()
        if (request.familyId == LocalUbuntuGraphqlPolicy.FAMILY) return@withContext LocalUbuntuGraphqlInstaller(appContext,authority).install(request,nodeDependency,progress)
        if (request.familyId == LocalUbuntuTomlPolicy.FAMILY) return@withContext LocalUbuntuTomlInstaller(appContext,authority).install(request,nodeDependency,progress)
        if (request.familyId == LocalUbuntuVuePolicy.FAMILY) return@withContext installVue(request,nodeDependency,progress)
        if (request.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) return@withContext installSvelte(request, nodeDependency, progress, activity)
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Local AI plugins require ARM64 Android" }
        PackageBackendContract.requireSame("Local Ubuntu npm recipe", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        progress("Checking Ubuntu for ${request.command}…\n")
        ubuntu.ensureInstalled()
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        nodeDependency?.let {
            require(it.familyId == "runtime.node" && authority.owns(it) && authority.verify(it)) { "Node dependency is not healthy or locally owned" }
        }
        val pinnedNodeBin = nodeDependency?.let {
            requireNotNull(LocalLinuxPackageEnvironment.guestPath(
                "${it.installRoot}/payload/tree/node-v${it.version}-linux-arm64/bin", "$root/packages/",
            )) { "Node dependency is outside local package storage" }
        }
        val commandPrefix = pinnedNodeBin?.let { listOf("/usr/bin/env", "PATH=$it:/opt/droide/bin:/usr/local/bin:/usr/bin:/bin") }.orEmpty()
        suspend fun run(argv: List<String>, timeout: Long = 30_000, limit: Int = 128_000): ExecResult =
            withInstallerOutputHeartbeat(progress, activity) { onOutput ->
                OwnedInstallerCommand.capture(launch, projectRoot, commandPrefix + argv,
                    maxOutputBytes = limit, timeoutMs = timeout, onOutput = onOutput)
            }
        progress("Checking Node.js and npm…\n")
        val node = run(LocalUbuntuNpmRuntimePolicy.command(request.minimumNodeMajor, null))
        check(node.exitCode == 0 && !node.timedOut) { "Install Node.js ${request.minimumNodeMajor}+ and npm in the Linux terminal, then retry Install" }
        val runtime = Json.parseToJsonElement(node.output.trim()).jsonObject
        require(runtime["major"]?.jsonPrimitive?.intOrNull?.let { it >= request.minimumNodeMajor } == true &&
            runtime["platform"]?.jsonPrimitive?.content == "linux" && runtime["arch"]?.jsonPrimitive?.content == "arm64") {
            "This plugin needs Node.js ${request.minimumNodeMajor}+ for Linux ARM64 in local Ubuntu"
        }
        val npm = run(listOf("npm", "--version"))
        require(npm.exitCode == 0 && !npm.timedOut && npm.output.trim().substringBefore('.').toIntOrNull()?.let { it >= 10 } == true) {
            "Install npm 10+ in the Linux terminal, then retry Install"
        }
        val nodeLocation = run(listOf("/bin/sh", "-c", "command -v node"))
        val nodeCommand = nodeLocation.output.trim()
        require(nodeLocation.exitCode == 0 && !nodeLocation.timedOut && nodeCommand.startsWith('/') &&
            nodeCommand.length <= 1_000 && nodeCommand.none { it.code < 32 || it.code == 127 || it == ':' || it == '\\' } &&
            nodeCommand.drop(1).split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "The selected Node.js executable cannot be projected into the plugin runtime"
        }
        val nodeVersion = runtime["version"]?.jsonPrimitive?.content.orEmpty()
        if (request.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) {
            require(!node.outputIncomplete && !npm.outputIncomplete && !nodeLocation.outputIncomplete) { "Prisma runtime observation was incomplete" }
            LocalUbuntuPrismaLaunchPolicy.requireRuntime(nodeCommand, nodeVersion, nodeDependency?.let { "${it.familyId}@${it.version}" })
            nodeDependency?.let {
                require(LocalUbuntuNpmRuntimePolicy.admitsDependency(request, it) &&
                    LocalUbuntuNpmRuntimePolicy.matchesManagedNode(it, nodeCommand, root) && it.version == nodeVersion) { "Prisma selected Node does not match its owned dependency" }
            }
            for (constraint in LocalUbuntuPrismaPolicy.engineConstraints) {
                val compatibility = run(LocalUbuntuNpmRuntimePolicy.command(request.minimumNodeMajor, constraint))
                require(compatibility.exitCode == 0 && !compatibility.timedOut && !compatibility.outputIncomplete &&
                    LocalUbuntuNpmRuntimePolicy.compatible(compatibility.output)) { "Selected Node does not meet the reviewed Prisma closure engines" }
            }
        }
        val nodeBin = nodeCommand.substringBeforeLast('/').ifEmpty { "/" }
        val url = "https://registry.npmjs.org".toHttpUrl().newBuilder().addPathSegment(request.packageName).addPathSegment(request.packageVersion).build()
        progress("Resolving official npm integrity…\n")
        val response = SafeHttp.get(url.toString(), accept = "application/json", maxBytes = 1_000_000)
        check(response.code == 200 && response.finalUrl.toHttpUrl().host == "registry.npmjs.org") { "Official npm metadata is unavailable (HTTP ${response.code})" }
        val metadata = Json.parseToJsonElement(response.body).jsonObject
        require(metadata["name"]?.jsonPrimitive?.content == request.packageName && metadata["version"]?.jsonPrimitive?.content == request.packageVersion) { "npm package identity mismatch" }
        if (request.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) LocalUbuntuPrismaPolicy.validateMetadata(metadata)
        val engine = LocalUbuntuNpmRuntimePolicy.engine(metadata)
        if (engine != null) {
            val compatibility = run(LocalUbuntuNpmRuntimePolicy.command(request.minimumNodeMajor, engine))
            check(compatibility.exitCode == 0 && !compatibility.timedOut && LocalUbuntuNpmRuntimePolicy.compatible(compatibility.output)) {
                "This package requires Node.js $engine. Select a compatible Linux ARM64 Node.js with npm, then retry Install."
            }
        }
        val dist = metadata["dist"]?.jsonObject ?: error("npm artifact metadata is missing")
        val integrity = dist["integrity"]?.jsonPrimitive?.content ?: error("npm integrity is missing")
        LocalUbuntuNpmPolicy.requireIntegrity(integrity)
        LocalUbuntuNpmPolicy.requireRegistryTarball(dist["tarball"]?.jsonPrimitive?.content ?: error("npm artifact URL is missing"))
        request.registryIntegrity?.let { require(it == integrity) { "Reviewed npm integrity changed" } }
        val recipe = request.copy(registryIntegrity = integrity)
        val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        val reusable = LocalPackageInstallJournal.transactionMutex.withLock {
            val candidate = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            LocalPackageInstallJournal.recover(paths,
                candidate?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            val reviewFile = candidate?.let { File(it.installRoot, "RECIPE_LOCK.json") }
            val review = reviewFile?.takeIf {
                it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() in 1L..65_536L
            }?.let { runCatching { Json.parseToJsonElement(it.readText()) as? JsonObject }.getOrNull() }
            candidate?.takeIf {
                review != null && LocalInstalledPackageReuse.npmIdentityMatches(
                    it, recipe, review, nodeDependency, nodeCommand, requireNotNull(runtime["major"]?.jsonPrimitive?.intOrNull), nodeVersion,
                    extraDependencies,
                ) && authority.verify(it)
            }
        }
        if (reusable != null) {
            if (!reusable.active) authority.activate(reusable)
            progress("Using verified installed ${recipe.command} ${recipe.version}; no npm install or download needed.\n")
            return@withContext reusable.copy(active = true)
        }
        require(File(root).usableSpace >= 350_000_000L) { "Free at least 350 MB before installing an AI plugin" }
        LocalPackageInstallJournal.transactionMutex.withLock {
            val existing = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            LocalPackageInstallJournal.recover(paths,
                existing?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            val npmRoot = File(paths.stage, "payload/npm")
            check(npmRoot.mkdirs()) { "Cannot stage npm package" }
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val activationId = LocalPackageInstallJournal.prepare(paths.stage)
                File(npmRoot, "package.json").writeText(buildJsonObject {
                    put("name", "droide-managed-agent"); put("version", "1.0.0"); put("private", true)
                    put("dependencies", buildJsonObject { put(recipe.packageName, recipe.packageVersion); recipe.additionalDependencies.forEach { (name, ver) -> put(name, ver) } })
                }.toString())
                val guestNpm = if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) requireNotNull(LocalLinuxPackageEnvironment.guestPath(npmRoot.absolutePath, "$root/packages/"))
                    else "/opt/droide/packages/" + npmRoot.absolutePath.removePrefix("$root/packages/")
                val options = listOf("--prefix", guestNpm, "--registry", "https://registry.npmjs.org", "--cache", "$guestNpm/.cache",
                    "--no-audit", "--no-fund", "--strict-ssl=true", "--engine-strict", "--fetch-retries=1", "--fetch-timeout=60000")
                suspend fun npmCommand(args: List<String>, environment: List<String> = emptyList()) {
                    progress("npm ${args.firstOrNull() ?: "install"}…\n")
                    val environmentPrefix = if (environment.isEmpty()) emptyList() else listOf("/usr/bin/env") + environment
                    val result = run(environmentPrefix + listOf("npm") + args + options,
                        timeout = InstallerExecutionPolicy.NPM_STEP_DEADLINE_MS)
                    check(result.exitCode == 0 && !result.timedOut) { "npm installation failed: ${result.output.takeLast(4_000)}" }
                }
                val lock = File(npmRoot, "package-lock.json")
                val previousLock = existing?.let { File(it.installRoot, "payload/npm/package-lock.json") }
                val preservedLock = previousLock?.let {
                    LocalUbuntuNpmPolicy.reusableLock(it, existing?.artifactSha256, recipe)
                }
                if (preservedLock != null) lock.writeText(preservedLock.readText())
                else npmCommand(listOf("install", "--package-lock-only", "--ignore-scripts", "--omit=dev", "--include=optional"))
                require(lock.isFile && lock.length() <= 8_000_000L) { "npm did not produce a bounded dependency lock" }
                LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe)
                npmCommand(listOf("ci", "--ignore-scripts", "--omit=dev", "--include=optional"))
                if (recipe.allowLifecycleScripts) {

                    val preload = File(paths.stage, ".selector-copy-only.cjs")
                    preload.writeText(LocalUbuntuNpmPolicy.reviewedSelectorPreload(recipe))
                    val guestPreload = "/opt/droide/packages/" + preload.absolutePath.removePrefix("$root/packages/")
                    npmCommand(listOf("rebuild", recipe.packageName, "--ignore-scripts=false", "--foreground-scripts", "--offline",
                        "--node-options=--require=$guestPreload"),
                        listOf("NODE_OPTIONS=--require=$guestPreload", "npm_config_offline=true"))
                    check(preload.delete()) { "Cannot remove temporary binary-selector compatibility hook" }
                }
                check(PathSecurity.deleteTreeNoFollow(File(npmRoot, ".cache"))) { "Cannot remove npm transfer cache" }
                LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe)
                val commands = LocalUbuntuNpmPolicy.commands(recipe)
                val bin = File(paths.stage, "payload/bin").apply { check(mkdirs()) }
                val hostBin = File(paths.stage, "payload/host-bin").apply { check(mkdirs()) }
                val finalGuest = if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath, "$root/packages/"))
                    else "/opt/droide/packages/" + paths.final.absolutePath.removePrefix("$root/packages/")
                fun writeReview() {
                File(paths.stage, "RECIPE_LOCK.json").writeText(buildJsonObject {
                    put("schema", 1); put("familyId", recipe.familyId); put("version", recipe.version)
                    put("packageName", recipe.packageName); put("packageVersion", recipe.packageVersion); put("registryIntegrity", integrity)
                    put("nodeMajor", runtime["major"]!!); put("npmVersion", npm.output.trim()); put("provenanceUrl", recipe.provenanceUrl)
                    put("nodeCommand", nodeCommand)
                    if (museRuntime != null) put("museRuntime", extraDependencies.single())
                    if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) put("launchProfile", LocalUbuntuPrismaLaunchPolicy.launchProfile(
                        nodeCommand, nodeVersion, nodeDependency?.let { "${it.familyId}@${it.version}" }))
                    put("additionalDependencies", buildJsonObject { recipe.additionalDependencies.forEach { (name, ver) -> put(name, ver) } })
                    put("allowLifecycleScripts", recipe.allowLifecycleScripts); recipe.lifecycleScriptReview?.let { put("lifecycleScriptReview", it) }
                }.toString())
                }
                val lspProbe = if (recipe.familyId != LocalUbuntuPrismaPolicy.FAMILY_ID && commands.keys.any { LocalUbuntuNpmPolicy.isLanguageServerCommand(recipe, it) })
                    File(paths.stage, "payload/${LocalUbuntuNpmLspHealth.FILE_NAME}").apply {
                        writeText(LocalUbuntuNpmLspHealth.script)
                    } else null
                val stageGuest = if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath, "$root/packages/"))
                    else "/opt/droide/packages/" + paths.stage.absolutePath.removePrefix("$root/packages/")
                if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) {
                    LocalUbuntuPrismaPolicy.validateInstalledLayout(paths.stage)
                    File(paths.stage, LocalUbuntuPrismaLaunchPolicy.RELATIVE_PATH).writeText(LocalUbuntuPrismaLaunchPolicy.script)
                    File(paths.stage, "payload/${LocalUbuntuPrismaLspHealth.FILE_NAME}").writeText(LocalUbuntuPrismaLspHealth.script)
                    File(bin, recipe.command).apply { writeText(LocalUbuntuPrismaLaunchPolicy.guestWrapper(nodeCommand, finalGuest)); check(setExecutable(true, true)) }
                    File(hostBin, recipe.command).apply { writeText(LocalUbuntuPrismaLaunchPolicy.hostWrapper(ubuntu.launcherPath(), finalGuest)); check(setExecutable(true, true)) }
                    writeReview()
                    val dependencyKey = nodeDependency?.let { "${it.familyId}@${it.version}" }
                    File(paths.stage, LocalUbuntuPrismaLaunchPolicy.AUTHORITY_FILE).writeText(LocalUbuntuPrismaLaunchPolicy.authority(
                        stageGuest, finalGuest, activationId, nodeCommand, nodeVersion, dependencyKey, LocalUbuntuNpmPolicy.sha256(lock)).toString())
                    require(!java.nio.file.Files.exists(File(paths.stage, LocalUbuntuNpmPolicy.SEAL).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Prisma stage must not have a final seal" }
                    require(LocalUbuntuPrismaLaunchPolicy.verifyIntegration(paths.stage, stageGuest, finalGuest, activationId, dependencyKey, ubuntu.launcherPath())) { "Prisma stage integration is inconsistent" }
                    progress("Checking Prisma zero-document import and transport…\n")
                    val health = run(listOf("/bin/sh", "-c", LocalUbuntuPrismaLaunchPolicy.healthHandoff(nodeCommand, stageGuest)),
                        timeout = LocalUbuntuPrismaLspHealth.TIMEOUT_MS)
                    require(health.exitCode == 0 && !health.timedOut && !health.outputIncomplete) { "Prisma zero-document health failed" }
                    LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe)
                    require(LocalUbuntuPrismaLaunchPolicy.verifyIntegration(paths.stage, stageGuest, finalGuest, activationId, dependencyKey, ubuntu.launcherPath())) { "Prisma integration changed during health" }
                } else for ((name, args) in commands) {
                    val entry = File(npmRoot, "node_modules/.bin/${args.first()}")
                    require(entry.isFile && entry.canonicalFile.toPath().startsWith(npmRoot.canonicalFile.toPath())) { "The original $name entry point is missing" }
                    val tail = args.drop(1).joinToString(" ", transform = ::q)
                    val languageServer = LocalUbuntuNpmPolicy.isLanguageServerCommand(recipe, name)
                    val lspArgs = if (languageServer) LocalUbuntuNpmPolicy.languageServerArgs(recipe).joinToString(" ", transform = ::q) else ""
                    val healthCommand = if (languageServer)
                        "export PATH=${q(nodeBin)}:\"${'$'}PATH\"; exec ${q(nodeCommand)} ${q("$stageGuest/payload/${requireNotNull(lspProbe).name}")} ${q("$guestNpm/node_modules/.bin/${args.first()}")} $tail $lspArgs" else
                        "export PATH=${q(nodeBin)}:\"${'$'}PATH\"; exec ${q("$guestNpm/node_modules/.bin/${args.first()}")} $tail --version"
                    progress("Checking $name…\n")
                    val health = run(listOf("/bin/sh", "-c", agentEnvironment + healthCommand),
                        timeout = if (languageServer) LocalUbuntuNpmLspHealth.TIMEOUT_MS else 30_000)
                    require(health.exitCode == 0 && !health.timedOut) { "$name failed its Linux ARM64 health check: ${health.output.takeLast(2_000)}" }
                    val healthMode = if (languageServer)
                        "if [ \"${'$'}{1:-}\" = --droide-health ]; then export PATH=${q(nodeBin)}:\"${'$'}PATH\"; exec ${q(nodeCommand)} ${q("$finalGuest/payload/${LocalUbuntuNpmLspHealth.FILE_NAME}")} ${q("$finalGuest/payload/npm/node_modules/.bin/${args.first()}")} $tail $lspArgs; fi\n" else ""
                    File(bin, name).apply {
                        writeText("#!/bin/sh\nset -eu\nexport NO_UPDATE_NOTIFIER=1\n${agentEnvironment}${healthMode}export PATH=${q(nodeBin)}:\"${'$'}PATH\"\nexec ${q("$finalGuest/payload/npm/node_modules/.bin/${args.first()}")} $tail \"${'$'}@\"\n")
                        check(setExecutable(true, true))
                    }
                    File(hostBin, name).apply {
                        writeText("#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(ubuntu.launcherPath())} /bin/sh ${q("$finalGuest/payload/bin/$name")} \"${'$'}@\"\n")
                        check(setExecutable(true, true))
                    }
                }
                if (recipe.familyId != LocalUbuntuPrismaPolicy.FAMILY_ID) writeReview()
                progress("Sealing dependency tree and activating commands…\n")
                val seal = File(paths.stage, LocalUbuntuNpmPolicy.SEAL)
                seal.writeText(LocalUbuntuNpmPolicy.tree(paths.stage).toString())
                val sealHash = LocalUbuntuNpmPolicy.sha256(seal)
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId, version = recipe.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = paths.final.absolutePath, installedAtEpochMs = System.currentTimeMillis(),
                    pathEntries = listOf("${paths.final.absolutePath}/payload/host-bin"),
                    commands = commands.keys.associateWith { "${paths.final.absolutePath}/payload/host-bin/$it" },
                    dependencies = nodeDependency?.let { listOf("${it.familyId}@${it.version}") }.orEmpty() + extraDependencies,
                    healthChecks = commands.keys.map { ManagedPackageHealthCheck("host-bin/$it", LocalUbuntuNpmPolicy.healthArgs(recipe, it)) },
                    artifactSha256 = LocalUbuntuNpmPolicy.sha256(File(paths.final, "payload/npm/package-lock.json")), abi = "arm64-v8a",
                    metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId, LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name, LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealHash),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { installed -> runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, installed) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("Cannot clean failed npm staging tree"))
                }
                throw failure
            }
        }
    }

    /** Vue branches before generic Node/npm observation. Only authenticated archive data is staged. */
    private suspend fun installVue(recipe: WorkstationInstallRecipe, helperDependency: ManagedPackageRecord?,
        progress: (String) -> Unit): ManagedPackageRecord {
        require(LocalUbuntuVuePolicy.supports(recipe))
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" })
        PackageBackendContract.requireSame("Vue exact bundle",PackageBackendId.LOCAL_APP,authority.backendId,ubuntu.backendId)
        val helper = requireNotNull(helperDependency) { LocalUbuntuVuePolicy.PREREQUISITE_MESSAGE }
        require(helper.familyId == LocalUbuntuVueHelperPolicy.FAMILY && helper.version == LocalUbuntuVueHelperPolicy.VERSION &&
            authority.owns(helper) && authority.verifyVuePackageWithoutHealth(helper)) { LocalUbuntuVuePolicy.PREREQUISITE_MESSAGE }
        val paths = LocalPackageInstallJournal.paths(root,recipe.familyId,recipe.version,LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
        return LocalPackageInstallJournal.transactionMutex.withLock {
            val existing = authority.recoveryReceipt(recipe.familyId,recipe.version,LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            LocalPackageInstallJournal.recover(paths,existing?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            val reusable = LocalInstalledPackageReuse.artifact(existing,recipe.familyId,recipe.version,LocalUbuntuVuePolicy.DATA_SHA256,
                authority::owns,authority::verify,identity={it.dependencies==listOf(LocalUbuntuVueHelperPolicy.KEY)})
            if(reusable!=null) {
                if(!reusable.active) authority.activate(reusable)
                progress("Using verified installed exact Vue bundle; no original archive download needed.\n")
                return@withLock reusable.copy(active=true)
            }
            DeviceWorkstationStorageGuard.requireHeadroom(LocalUbuntuVuePolicy.MAX_TREE_BYTES+LocalUbuntuVuePolicy.MAX_ARCHIVES_BYTES+67_108_864L,
                "stage exact Vue archive bundle")
            val npm = File(paths.stage,LocalUbuntuVuePolicy.NPM_ROOT)
            require(npm.mkdirs())
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val activationId = LocalPackageInstallJournal.prepare(paths.stage)
                File(npm,"package.json").writeText(LocalUbuntuVuePolicy.manifest.toString())
                val budget = LocalUbuntuVueArchive.Budget()
                val archives = linkedMapOf<String,JsonElement>()
                val context = currentCoroutineContext()
                for(identity in LocalUbuntuVuePolicy.artifacts.keys.sorted()) {
                    progress("Authenticating original $identity archive…\n")
                    vueDownloader.withArtifact(LocalUbuntuVueArchive.spec(identity),onProgress={progress(it.describe()+"\n")}) { file ->
                        archives[identity] = LocalUbuntuVueArchive.extract(identity,file,npm,budget) { context.ensureActive() }
                    }
                }
                val proof = buildJsonObject { put("schema",1);put("policy",LocalUbuntuVuePolicy.DATA_SHA256);put("archives",JsonObject(archives)) }
                File(paths.stage,LocalUbuntuVuePolicy.PROOF_FILE).writeText(proof.toString())
                LocalUbuntuVuePolicy.validateInstalledLayout(paths.stage,proof) { context.ensureActive() }
                LocalUbuntuVueLaunchAuthority.helperPaths.forEach { relative ->
                    val text=appContext.assets.open("lsp/vue/"+relative.substringAfterLast('/')).use { it.readBytes().toString(Charsets.UTF_8) }
                    require(LocalUbuntuVuePolicy.sha256(text.toByteArray()) == LocalUbuntuVueLaunchAuthority.expectedHelperHashes[relative])
                    File(paths.stage,relative).writeText(text)
                }
                File(paths.stage,"RECIPE_LOCK.json").writeText(LocalUbuntuVuePolicy.review().toString())
                val finalGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath,"$root/packages/"))
                val generated = LocalUbuntuVueLaunchAuthority.stagedAuthority(paths.stage,finalGuest,activationId,helper,root)
                generated.forEach { (relative,text) -> LocalUbuntuVuePolicy.safeRelative(relative);File(paths.stage,relative).writeText(text) }
                progress("Checking genuine native Vue/TypeScript paired analysis and owned cleanup…\n")
                val command=(listOf("/system/bin/sh",ubuntu.launcherPath(),"/usr/bin/env","-i","PATH=/usr/bin:/bin","LANG=C.UTF-8","LC_ALL=C.UTF-8") +
                    LocalUbuntuVueLaunchAuthority.stageHealthCommand(paths.stage,helper)).joinToString(" ",transform=::q)
                val healthHold=LocalPackageInstallJournal.beginVueHealth(paths)
                val health=LocalExecutionSubstrate.shellBounded(command,128_000,timeoutMs=60_000)
                LocalPackageInstallJournal.finishVueHealth(healthHold,LocalUbuntuVueLspHealth.cleanupVerified(health))
                require(LocalUbuntuVueLspHealth.succeeded(health)) { "Genuine staged Vue/TypeScript paired health or owned cleanup failed" }
                require(authority.verifyVuePackageWithoutHealth(helper)) { "Private Vue Node changed during health" }
                LocalUbuntuVuePolicy.validateInstalledLayout(paths.stage,proof) { context.ensureActive() }
                generated[LocalUbuntuVueLaunchAuthority.HEALTH_SEED]?.let { seed ->
                    File(paths.stage,LocalUbuntuVueLaunchAuthority.HEALTH_SEED).writeText(
                        LocalUbuntuVueLaunchAuthority.finalHealthSeed(seed,paths.stage.absolutePath,finalGuest))
                }
                val bin=File(paths.stage,"payload/bin").apply { require(mkdirs()) }
                val host=File(paths.stage,"payload/host-bin").apply { require(mkdirs()) }
                File(bin,recipe.command).apply { writeText(LocalUbuntuVueLaunchAuthority.guestWrapper(recipe.familyId,finalGuest));require(setExecutable(true,true)) }
                File(host,recipe.command).apply { writeText(LocalUbuntuVendorPolicy.hostWrapper(ubuntu.launcherPath(),"$finalGuest/payload/bin/${recipe.command}"));require(setExecutable(true,true)) }
                // Normalize only this authored Vue data tree after verified native cleanup.
                java.nio.file.Files.walk(paths.stage.toPath()).use { entries -> entries.forEach { path ->
                    require(!java.nio.file.Files.isSymbolicLink(path))
                    if(java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        val relative=paths.stage.toPath().relativize(path).toString()
                        android.system.Os.chmod(path.toString(),if(relative.startsWith("payload/bin/")||relative.startsWith("payload/host-bin/"))493 else 420)
                    }
                } }
                val seal=LocalPackageIntegrity.seal(paths.stage,LocalUbuntuVuePolicy.MAX_FILES+32,LocalUbuntuVuePolicy.MAX_TREE_BYTES+8_388_608L,progress)
                android.system.Os.chmod(File(paths.stage,"SHA256SUMS").absolutePath,420)
                activation=LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                val record=ManagedPackageRecord(recipe.familyId,recipe.version,ExecutionScope.LOCAL_LINUX_ARM64.name,paths.final.absolutePath,System.currentTimeMillis(),
                    pathEntries=listOf("${paths.final.absolutePath}/payload/host-bin"),commands=mapOf(recipe.command to "${paths.final.absolutePath}/payload/host-bin/${recipe.command}"),
                    dependencies=listOf(LocalUbuntuVueHelperPolicy.KEY),healthChecks=listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}",listOf("--health"))),
                    artifactSha256=LocalUbuntuVuePolicy.DATA_SHA256,abi="arm64-v8a",metadata=mapOf(
                        PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal))
                authority.adopt(record)
                activation=null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch(failure: Throwable) {
                withContext(NonCancellable) {
                    if(runCatching { LocalPackageInstallJournal.requireNoVueHealthHold(paths) }.isSuccess) {
                        activation?.let { installed -> runCatching { LocalPackageInstallJournal.rollback(paths.final,paths.previous,installed) }.exceptionOrNull()?.let(failure::addSuppressed) }
                        if(!PathSecurity.deleteTreeNoFollow(paths.stage))failure.addSuppressed(IllegalStateException("Could not clean failed Vue stage"))
                    } else failure.addSuppressed(IllegalStateException("Vue owner cleanup is unknown; stage/final/previous trees and health journal preserved"))
                }
                throw failure
            }
        }
    }

    suspend fun probeSvelteRuntime(node: ManagedPackageRecord): JsonObject = withContext(Dispatchers.IO) {
        require(authority.verifySvelteNode(node)) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
        withSveltePrivateContext { privateHost, privateGuest ->
            observeSvelteRuntime(node, privateHost, privateGuest)
        }
    }

    private suspend fun <T> withSveltePrivateContext(block: suspend (File, String) -> T): T {
        val parent = File(root, "packages")
        LocalUbuntuSveltePolicy.requireRealDirectory(parent)
        val directory = File(parent, ".svelte-private-${java.util.UUID.randomUUID()}")
        check(directory.mkdir()); android.system.Os.chmod(directory.absolutePath, 448)
        val identity = java.nio.file.Files.readAttributes(directory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey()
        require(identity != null)
        try {
            for (name in listOf("workspace", "home", "tmp", "config", "cache", "data")) {
                val dir = File(directory, name); check(dir.mkdir()); android.system.Os.chmod(dir.absolutePath, 448)
            }
            File(directory, "user.npmrc").writeText(""); File(directory, "global.npmrc").writeText("")
            val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(directory.absolutePath, "$root/packages/"))
            return block(directory, guest)
        } finally {
            withContext(NonCancellable) {
                LocalUbuntuSveltePolicy.requireRealDirectory(directory)
                check(java.nio.file.Files.readAttributes(directory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey() == identity) { "Svelte private context changed; preserved for repair" }
                check(PathSecurity.deleteTreeNoFollow(directory)) { "Svelte private cleanup incomplete" }
            }
        }
    }

    private suspend fun svelteRun(node: ManagedPackageRecord, privateHost: File, privateGuest: String,
        argv: List<String>, timeout: Long = 30_000, output: Int = 128_000,
        progress: (String) -> Unit = {}, activity: (String) -> Unit = {}): ExecResult {
        val nodeGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(
            "${node.installRoot}/payload/tree/node-v${node.version}-linux-arm64/bin/node", "$root/packages/"))
        val env = listOf("HOME=$privateGuest/home", "TMPDIR=$privateGuest/tmp", "XDG_CONFIG_HOME=$privateGuest/config",
            "XDG_CACHE_HOME=$privateGuest/cache", "XDG_DATA_HOME=$privateGuest/data", "PATH=${nodeGuest.substringBeforeLast('/')}:/usr/bin:/bin",
            "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "TERM=dumb", "npm_config_userconfig=$privateGuest/user.npmrc",
            "npm_config_globalconfig=$privateGuest/global.npmrc", "npm_config_cache=$privateGuest/cache", "npm_config_registry=https://registry.npmjs.org",
            "npm_config_ignore_scripts=true", "npm_config_audit=false", "npm_config_fund=false")
        val command = "set -eu; cd ${q("$privateGuest/workspace")}; lease=\"\${DROIDE_PROCESS_LEASE:-}\"; " +
            "case \"\$lease\" in *[!0-9a-f]*) exit 70;; esac; [ \"\${#lease}\" -eq 20 ] || exit 70; " +
            "exec /usr/bin/env -i \"DROIDE_PROCESS_LEASE=\$lease\" " + (env + argv).joinToString(" ", transform = ::q)
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(File(privateHost, "workspace"))
        return withInstallerOutputHeartbeat(progress, activity) { onOutput ->
            OwnedInstallerCommand.capture(launch, File(privateHost, "workspace"), listOf("/bin/sh", "-c", command),
                maxOutputBytes = output, timeoutMs = timeout, onOutput = onOutput)
        }
    }

    private suspend fun observeSvelteRuntime(node: ManagedPackageRecord, privateHost: File, privateGuest: String): JsonObject {
        val nodeGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(
            "${node.installRoot}/payload/tree/node-v${node.version}-linux-arm64/bin/node", "$root/packages/"))
        val npmRoot = nodeGuest.substringBeforeLast("/bin/node") + "/lib/node_modules/npm"
        val script = "'use strict';const fs=require('node:fs');const npmRoot=process.argv[1];" +
            "const npm=JSON.parse(fs.readFileSync(npmRoot+'/package.json','utf8'));const semver=require(npmRoot+'/node_modules/semver');" +
            "const constraints=JSON.parse(process.argv[2]);if(!constraints.every(x=>semver.satisfies(process.versions.node,x)))throw Error('Node engine mismatch');" +
            "console.log(JSON.stringify({version:process.versions.node,command:process.execPath,platform:process.platform,arch:process.arch,npmVersion:npm.version}));"
        val result = svelteRun(node, privateHost, privateGuest, listOf(nodeGuest, "-e", script, npmRoot,
            JsonArray(LocalUbuntuSveltePolicy.engineConstraints.map(::JsonPrimitive)).toString()))
        require(result.exitCode == 0 && !result.timedOut && !result.outputIncomplete) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE + " Runtime observation was unsuccessful or incomplete." }
        val runtime = LocalUbuntuSveltePolicy.strictObject(result.output.trim(), 4096)
        require(runtime.keys == setOf("version", "command", "platform", "arch", "npmVersion") && runtime["version"] == JsonPrimitive(node.version) &&
            runtime["command"] == JsonPrimitive(nodeGuest) && runtime["platform"] == JsonPrimitive("linux") && runtime["arch"] == JsonPrimitive("arm64")) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE + " Observed runtime identity changed." }
        LocalUbuntuSvelteLaunchPolicy.requireRuntime(nodeGuest, node.version, "runtime.node@${node.version}")
        val npmVersion = runtime.getValue("npmVersion").jsonPrimitive.content
        require(npmVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")) && (npmVersion.substringBefore('.').toIntOrNull() ?: 0) >= 10) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
        require(authority.verifySvelteNode(node)) { "Svelte Node identity changed during observation" }
        return runtime
    }

    private suspend fun installSvelte(recipe: WorkstationInstallRecipe, nodeDependency: ManagedPackageRecord?,
        progress: (String) -> Unit, activity: (String) -> Unit): ManagedPackageRecord = withTimeout(900_000) {
        require(LocalUbuntuSveltePolicy.supports(recipe))
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        PackageBackendContract.requireSame("Svelte Ubuntu recipe", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        val node = requireNotNull(nodeDependency) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
        // The Node receipt is byte-verified without invoking ambient runtime health.
        require(authority.verifySvelteNode(node)) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
        ubuntu.ensureInstalled()
        withSveltePrivateContext { privateHost, privateGuest ->
            val runtime = observeSvelteRuntime(node, privateHost, privateGuest)
            val nodeCommand = runtime.getValue("command").jsonPrimitive.content
            val npmCommand = nodeCommand.substringBeforeLast("/bin/node") + "/lib/node_modules/npm/bin/npm-cli.js"
            val dependency = "runtime.node@${node.version}"
            val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            val reusable = LocalPackageInstallJournal.transactionMutex.withLock {
                val existing = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
                LocalPackageInstallJournal.recover(paths, existing?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
                existing?.takeIf { candidate ->
                    val review = runCatching { LocalUbuntuSveltePolicy.strictObject(LocalUbuntuSveltePolicy.readRegular(File(candidate.installRoot,"RECIPE_LOCK.json"),64000)) }.getOrNull()
                    review != null && LocalInstalledPackageReuse.npmIdentityMatches(candidate, recipe, review, node, nodeCommand,
                        node.version.substringBefore('.').toInt(), node.version) && authority.verify(candidate)
                }
            }
            if (reusable != null) {

                if (!reusable.active) authority.activate(reusable)
                progress("Using verified installed Svelte ${recipe.version}; no npm install or download needed.\n")
                return@withSveltePrivateContext reusable.copy(active=true)
            }
            LocalPackageInstallJournal.transactionMutex.withLock {
                val existing = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
                LocalPackageInstallJournal.recover(paths, existing?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
                require(File(root).usableSpace >= 350_000_000L)
                val npmRoot = File(paths.stage, "payload/npm"); check(npmRoot.mkdirs())
                var activation: LocalPackageInstallJournal.Activation? = null
                try {
                    val activationId = LocalPackageInstallJournal.prepare(paths.stage)
                    val stageGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath,"$root/packages/"))
                    val finalGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath,"$root/packages/"))
                    val guestNpm = "$stageGuest/payload/npm"
                    File(npmRoot,"package.json").writeText(LocalUbuntuSveltePolicy.manifest(recipe).toString())
                    suspend fun npm(args: List<String>) {
                        val options = listOf("--prefix",guestNpm,"--registry","https://registry.npmjs.org","--cache","$privateGuest/cache",
                            "--userconfig","$privateGuest/user.npmrc","--globalconfig","$privateGuest/global.npmrc","--no-audit","--no-fund",
                            "--strict-ssl=true","--engine-strict","--fetch-retries=1","--fetch-timeout=60000","--ignore-scripts","--omit=dev")
                        val result = svelteRun(node,privateHost,privateGuest,listOf(nodeCommand,npmCommand)+args+options,300_000,128_000,progress,activity)
                        require(result.exitCode==0&&!result.timedOut&&!result.outputIncomplete) { "Exact Svelte npm resolution/install failed; no alternate resolver is permitted" }
                    }
                    progress("Resolving exact Svelte identities and physical dependency edges…\n")
                    npm(listOf("install","--package-lock-only"))
                    val lock=File(npmRoot,"package-lock.json")
                    LocalUbuntuSveltePolicy.validateLock(LocalUbuntuSveltePolicy.readRegular(lock,8_000_000),recipe)
                    npm(listOf("ci"))
                    val proof=LocalUbuntuSveltePolicy.validateInstalledLayout(paths.stage)
                    File(paths.stage,LocalUbuntuSveltePolicy.PROOF_FILE).writeText(proof.toString())
                    File(paths.stage,LocalUbuntuSvelteLaunchPolicy.RELATIVE_PATH).writeText(LocalUbuntuSvelteLaunchPolicy.script)
                    File(paths.stage,LocalUbuntuSvelteLaunchPolicy.HEALTH_PATH).writeText(LocalUbuntuSvelteLspHealth.script)
                    File(paths.stage,"payload/bin").apply { check(mkdirs()) }
                    File(paths.stage,"payload/host-bin").apply { check(mkdirs()) }
                    File(paths.stage,"payload/bin/svelteserver").apply { writeText(LocalUbuntuSvelteLaunchPolicy.guestWrapper(nodeCommand,finalGuest));check(setExecutable(true,true)) }
                    File(paths.stage,"payload/host-bin/svelteserver").apply { writeText(LocalUbuntuSvelteLaunchPolicy.hostWrapper(ubuntu.launcherPath(),finalGuest));check(setExecutable(true,true)) }
                    File(paths.stage,"RECIPE_LOCK.json").writeText(buildJsonObject {
                        put("schema",1);put("familyId",recipe.familyId);put("version",recipe.version);put("packageName",recipe.packageName);put("packageVersion",recipe.packageVersion)
                        put("registryIntegrity",recipe.registryIntegrity!!);put("nodeMajor",node.version.substringBefore('.').toInt());put("npmVersion",runtime.getValue("npmVersion"))
                        put("provenanceUrl",recipe.provenanceUrl);put("nodeCommand",nodeCommand);put("allowLifecycleScripts",false)
                        put("additionalDependencies",JsonObject(recipe.additionalDependencies.mapValues { JsonPrimitive(it.value) }))
                        put("launchProfile",LocalUbuntuSvelteLaunchPolicy.launchProfile(nodeCommand,node.version,dependency))
                    }.toString())
                    File(paths.stage,LocalUbuntuSvelteLaunchPolicy.AUTHORITY_FILE).writeText(LocalUbuntuSvelteLaunchPolicy.authority(stageGuest,finalGuest,activationId,
                        nodeCommand,node.version,dependency,LocalUbuntuNpmPolicy.sha256(lock),LocalUbuntuNpmPolicy.sha256(File(paths.stage,LocalUbuntuSveltePolicy.PROOF_FILE))).toString())
                    require(LocalUbuntuSvelteLaunchPolicy.verifyIntegration(paths.stage,stageGuest,finalGuest,activationId,dependency,ubuntu.launcherPath()))
                    progress("Checking private zero-document Svelte public-bin transport…\n")
                    val health=svelteRun(node,privateHost,privateGuest,listOf("/bin/sh","-c",LocalUbuntuSvelteLaunchPolicy.healthHandoff(nodeCommand,stageGuest)),LocalUbuntuSvelteLspHealth.TIMEOUT_MS)
                    require(LocalUbuntuSvelteLspHealth.succeeded(health)) { "Svelte lifecycle or owned cleanup failed" }
                    require(authority.verifySvelteNode(node) && LocalUbuntuSvelteLaunchPolicy.verifyIntegration(paths.stage,stageGuest,finalGuest,activationId,dependency,ubuntu.launcherPath()))
                    val seal=File(paths.stage,LocalUbuntuNpmPolicy.SEAL);seal.writeText(LocalUbuntuNpmPolicy.tree(paths.stage).toString())
                    val sealHash=LocalUbuntuNpmPolicy.sha256(seal)
                    activation=LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                    val record=ManagedPackageRecord(familyId=recipe.familyId,version=recipe.version,scope=ExecutionScope.LOCAL_LINUX_ARM64.name,
                        installRoot=paths.final.absolutePath,installedAtEpochMs=System.currentTimeMillis(),pathEntries=listOf("${paths.final.absolutePath}/payload/host-bin"),
                        commands=mapOf("svelteserver" to "${paths.final.absolutePath}/payload/host-bin/svelteserver"),dependencies=listOf(dependency),
                        healthChecks=listOf(ManagedPackageHealthCheck("host-bin/svelteserver",listOf("--droide-health"))),artifactSha256=LocalUbuntuNpmPolicy.sha256(File(paths.final,"payload/npm/package-lock.json")),abi="arm64-v8a",
                        metadata=mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
                            LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_NPM,LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                            LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealHash))
                    authority.adopt(record);activation=null
                    withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                    record
                } catch(failure:Throwable) {
                    withContext(NonCancellable) {
                        activation?.let { installed -> runCatching { LocalPackageInstallJournal.rollback(paths.final,paths.previous,installed) }.exceptionOrNull()?.let(failure::addSuppressed) }
                        if(!PathSecurity.deleteTreeNoFollow(paths.stage))failure.addSuppressed(IllegalStateException("Cannot clean failed Svelte stage"))
                    };throw failure
                }
            }
        }
    }

    private fun q(value: String) = LocalExecutionSubstrate.shellQuote(value)
}
