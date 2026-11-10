package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class LocalUbuntuRubyInstaller(
    context: Context,
    private val projectRoot: File,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val root = context.applicationContext.filesDir.canonicalPath
    private val downloader = UserInitiatedArtifactTransfer(context.applicationContext)

    suspend fun install(recipe: LocalUbuntuRubyRecipe, progress: (String) -> Unit = {}): ManagedPackageRecord = withContext(Dispatchers.IO) {
        LocalUbuntuRubyPolicy.requireReviewed(recipe)
        require(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") { "Local Ruby language server requires ARM64 Android" }
        PackageBackendContract.requireSame("Local Ubuntu Ruby recipe", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        val layer = requireNotNull(ubuntu.ensurePackages(LocalUbuntuRubyPolicy.packages, progress))
        authority.adopt(layer)
        require(File(root).usableSpace >= 350_000_000L) { "Free at least 350 MB before installing Ruby LSP" }
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        suspend fun run(argv: List<String>, timeout: Long = 30_000L): ExecResult = OwnedInstallerCommand.capture(
            launch, projectRoot, argv, maxOutputBytes = 128_000,
            timeoutMs = timeout, onOutput = progress,
        )
        val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_RUBY)
        LocalPackageInstallJournal.transactionMutex.withLock {
            val previous = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_RUBY)
            LocalPackageInstallJournal.recover(paths, previous?.let {
                LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            })
            val ruby = File(paths.stage, "payload/ruby")
            val cache = File(ruby, "vendor/cache")
            check(cache.mkdirs()) { "Cannot stage local Ruby bundle" }
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val id = LocalPackageInstallJournal.prepare(paths.stage)
                File(ruby, "Gemfile").writeText(LocalUbuntuRubyPolicy.gemfile)
                File(ruby, "Gemfile.lock").writeText(LocalUbuntuRubyPolicy.lock)
                File(paths.stage, "RECIPE_LOCK.json").writeText(LocalUbuntuRubyPolicy.review(recipe).toString())
                File(paths.stage, "payload/${LocalUbuntuNpmLspHealth.FILE_NAME}").writeText(LocalUbuntuNpmLspHealth.script)
                val stageGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath, "$root/packages/"))
                val finalGuest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath, "$root/packages/"))
                val environment = LocalUbuntuRubyPolicy.environment(stageGuest)
                progress("Checking Ubuntu Ruby ABI and Bundler…\n")
                val runtime = run(environment + listOf("/usr/bin/ruby", "-rjson", "-rrbconfig", "-e",
                    "print JSON.generate({engine:RUBY_ENGINE,abi:RbConfig::CONFIG['ruby_version'],cpu:Gem::Platform.local.cpu})"))
                require(runtime.exitCode == 0 && !runtime.timedOut) { "Ubuntu Ruby cannot execute" }
                val details = Json.parseToJsonElement(runtime.output.trim()).jsonObject
                require(details["engine"] == JsonPrimitive("ruby") && details["abi"] == JsonPrimitive(LocalUbuntuRubyPolicy.RUBY_ABI) &&
                    details["cpu"] == JsonPrimitive("aarch64")) { "Ruby LSP needs the reviewed Ubuntu Ruby 3.2 ABI for AArch64" }
                val bundler = run(environment + listOf("/usr/bin/bundle", "--version"))
                require(bundler.exitCode == 0 && !bundler.timedOut && bundler.output.trim() == "Bundler version 2.4.20") {
                    "The signed Ubuntu Bundler 2.4.20 prerequisite is unavailable"
                }
                progress("Downloading six original RubyGems archives with publisher SHA-256…\n")
                for (gem in recipe.gems) {
                    currentCoroutineContext().ensureActive()
                    downloader.withArtifact(gem.artifact()) { file ->
                        file.copyTo(File(cache, gem.fileName))
                    }
                }
                check(LocalUbuntuRubyPolicy.verifyFiles(paths.stage, recipe)) { "Ruby bundle source pins changed before compilation" }
                progress("Building original Prism and RBS extensions offline…\n")
                val installed = run(environment + listOf("/usr/bin/bundle", "install", "--local", "--no-cache", "--jobs", "2", "--retry", "0"), 0L)
                check(installed.exitCode == 0 && !installed.timedOut) { "Offline Ruby bundle installation failed: ${installed.output.takeLast(3_000)}" }
                check(LocalUbuntuRubyPolicy.verifyFiles(paths.stage, recipe)) { "Frozen Ruby dependency lock or original gem archives changed" }
                LocalUbuntuRubyPolicy.admitNativeLibraries(paths.stage)
                val version = run(LocalUbuntuRubyPolicy.server(stageGuest) + "--version")
                check(version.exitCode == 0 && !version.timedOut && version.output.trim() == recipe.version) { "Original Ruby LSP version check failed" }
                progress("Checking Ruby LSP initialize/shutdown…\n")
                val health = run(LocalUbuntuRubyPolicy.health(stageGuest), LocalUbuntuNpmLspHealth.TIMEOUT_MS)
                check(health.exitCode == 0 && !health.timedOut) { "Ruby LSP failed its stdio health check: ${health.output.takeLast(3_000)}" }
                File(paths.stage, "payload/bin").apply { check(mkdirs()) }
                File(paths.stage, "payload/host-bin").apply { check(mkdirs()) }
                File(paths.stage, "payload/bin/${recipe.command}").apply {
                    writeText(LocalUbuntuRubyPolicy.guestWrapper(finalGuest)); check(setExecutable(true, true))
                }
                File(paths.stage, "payload/host-bin/${recipe.command}").apply {
                    writeText(LocalUbuntuRubyPolicy.hostWrapper(ubuntu.launcherPath(), finalGuest)); check(setExecutable(true, true))
                }
                val seal = File(paths.stage, LocalUbuntuNpmPolicy.SEAL)
                seal.writeText(LocalUbuntuNpmPolicy.tree(paths.stage).toString())
                val sealHash = LocalUbuntuNpmPolicy.sha256(seal)
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId, version = recipe.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = paths.final.absolutePath, installedAtEpochMs = System.currentTimeMillis(), abi = "arm64-v8a",
                    artifactSha256 = LocalUbuntuRubyPolicy.lockSha256,
                    commands = mapOf(recipe.command to "${paths.final}/payload/host-bin/${recipe.command}"),
                    pathEntries = listOf("${paths.final}/payload/host-bin"), dependencies = listOf("${layer.familyId}@${layer.version}"),
                    healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", listOf("--droide-health"))),
                    metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_RUBY,
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id, LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealHash,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, it) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("Cannot clean Ruby staging tree"))
                }
                throw failure
            }
        }
    }
}
