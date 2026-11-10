package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal object LocalAndroidWorkstationPolicy {
    const val FAMILY = "meta.android-development"
    const val VERSION = "ubuntu-android-36"
    const val COMMAND = "droide-android-doctor"
    const val GRADLE_VERSION = "8.13"
    const val GRADLE_SHA256 = "20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"
    val requirements = AndroidToolchainRequirements(compileSdk = 36, buildToolsVersion = "36.0.0")
    val dependencyKeys = setOf("toolchain.jdk@ubuntu-24.04", "sdk.android@36", "build.android-tools@36.0.0", "build.gradle@8.13")
    fun components(catalog: AndroidLocalComponentCatalogDocument): List<AndroidLocalComponentCatalogEntry> = listOf(
        requireNotNull(AndroidLocalComponentCatalog.find(catalog, "sdk.android", "36")),
        requireNotNull(AndroidLocalComponentCatalog.find(catalog, "build.android-tools", "36.0.0")),
    )
    fun requireGradle(recipe: LocalUbuntuVendorRecipe) {
        require(recipe.familyId == "build.gradle" && recipe.version == GRADLE_VERSION && recipe.assetSha256 == GRADLE_SHA256) {
            "Android workstation requires the original pinned Gradle 8.13 distribution"
        }
        recipe.validate()
    }
    fun lock(catalog: AndroidLocalComponentCatalogDocument) = buildJsonObject {
        put("schema", 1); put("familyId", FAMILY); put("version", VERSION); put("jdk", "Ubuntu OpenJDK 17")
        put("gradleVersion", GRADLE_VERSION); put("gradleSha256", GRADLE_SHA256)
        put("dependencies", JsonArray(dependencyKeys.sorted().map(::JsonPrimitive)))
        put("sdkComponents", buildJsonArray { components(catalog).forEach { component -> add(buildJsonObject {
            put("familyId", component.familyId); put("version", component.version); put("sha256", component.sha256)
            put("upstreamSha1", component.upstreamSha1); put("guestTarget", component.guestTarget)
            put("nativeTools", buildJsonObject { component.nativeTools.sortedBy { it.name }.forEach { put(it.name, it.sha256) } })
        }) } })
    }
    fun hash(lock: JsonObject) = MessageDigest.getInstance("SHA-256").digest(lock.toString().toByteArray()).joinToString("") { "%02x".format(it) }

    fun doctor(gradleCommand: String): String {
        require(gradleCommand.startsWith("/opt/droide/packages/build.gradle/8.13/") &&
            gradleCommand.none { it.code < 32 || it.code == 127 } && gradleCommand.split('/').none { it == "." || it == ".." })
        val probe = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> error("Script generation does not run a process") }.probeScript(requirements)
        return "#!/bin/sh\nset -eu\n" +
            "case \"${'$'}#:${'$'}{1-}\" in 0:|1:--droide-health) ;; 1:--help) printf '%s\\n' 'Check owned OpenJDK 17, Android SDK 36, ARM64 Build Tools 36.0.0 and Gradle 8.13'; exit 0;; *) echo 'Usage: droide-android-doctor [--help]' >&2; exit 2;; esac\n" +
            "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64\n" +
            "export ANDROID_HOME=${LocalExecutionSubstrate.shellQuote(LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT)} ANDROID_SDK_ROOT=${LocalExecutionSubstrate.shellQuote(LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT)}\n" +
            probe + "\nexec ${LocalExecutionSubstrate.shellQuote(gradleCommand)} --version\n"
    }
    fun hostWrapper(launcher: String, guestRoot: String) = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${LocalExecutionSubstrate.shellQuote(launcher)} /bin/sh ${LocalExecutionSubstrate.shellQuote("$guestRoot/payload/bin/$COMMAND")} \"${'$'}@\"\n"
}

internal class LocalAndroidWorkstationInstaller(
    context: Context,
    private val projectRoot: File,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext = context.applicationContext
    private val root = appContext.filesDir.canonicalPath

    suspend fun install(dependencies: List<ManagedPackageRecord>, progress: (String) -> Unit = {}): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(dependencies.map { "${it.familyId}@${it.version}" }.toSet() == LocalAndroidWorkstationPolicy.dependencyKeys && dependencies.size == 4)
        for (dependency in dependencies) require(authority.owns(dependency) && authority.verify(dependency)) { "Android workstation dependency is unhealthy or belongs to another backend" }
        val gradle = dependencies.single { it.familyId == "build.gradle" }
        require(gradle.artifactSha256 == LocalAndroidWorkstationPolicy.GRADLE_SHA256)
        val gradleCommand = requireNotNull(LocalLinuxPackageEnvironment.guestPath(gradle.installRoot, "$root/packages/")) + "/payload/bin/gradle"
        val catalog = AndroidLocalComponentCatalog.load(appContext)
        val lock = LocalAndroidWorkstationPolicy.lock(catalog)
        val paths = LocalPackageInstallJournal.paths(root, LocalAndroidWorkstationPolicy.FAMILY, LocalAndroidWorkstationPolicy.VERSION, LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION)
        LocalPackageInstallJournal.transactionMutex.withLock {
            val previous = authority.recoveryReceipt(LocalAndroidWorkstationPolicy.FAMILY, LocalAndroidWorkstationPolicy.VERSION, LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION)
            LocalPackageInstallJournal.recover(paths, previous?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            check(File(paths.stage, "payload/bin").mkdirs())
            check(File(paths.stage, "payload/host-bin").mkdirs())
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val id = LocalPackageInstallJournal.prepare(paths.stage)
                val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath, "$root/packages/"))
                val doctor = File(paths.stage, "payload/bin/${LocalAndroidWorkstationPolicy.COMMAND}").apply {
                    writeText(LocalAndroidWorkstationPolicy.doctor(gradleCommand)); check(setExecutable(true, true))
                }
                File(paths.stage, "payload/host-bin/${LocalAndroidWorkstationPolicy.COMMAND}").apply {
                    writeText(LocalAndroidWorkstationPolicy.hostWrapper(ubuntu.launcherPath(), guest)); check(setExecutable(true, true))
                }
                File(paths.stage, "WORKSTATION_LOCK.json").writeText(lock.toString())
                val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
                val stageDoctor = requireNotNull(LocalLinuxPackageEnvironment.guestPath(doctor.absolutePath, "$root/packages/"))
                progress("Checking complete Android workstation prerequisites…\n")
                val health = LocalProcessSupervisor.capture(launch.command(listOf("/bin/sh", stageDoctor, "--droide-health")), projectRoot,
                    launch.environment, maxOutputBytes = 128_000, timeoutMs = 60_000, keepTail = true, onOutput = progress)
                check(health.exitCode == 0 && !health.timedOut) { "Android workstation failed its command health check: ${health.output.takeLast(3_000)}" }
                val seal = File(paths.stage, LocalUbuntuNpmPolicy.SEAL)
                seal.writeText(LocalUbuntuNpmPolicy.tree(paths.stage).toString())
                val sealHash = LocalUbuntuNpmPolicy.sha256(seal)
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(LocalAndroidWorkstationPolicy.FAMILY, LocalAndroidWorkstationPolicy.VERSION,
                    ExecutionScope.LOCAL_LINUX_ARM64.name, paths.final.absolutePath, System.currentTimeMillis(),
                    pathEntries = listOf("${paths.final}/payload/host-bin"),
                    commands = mapOf(LocalAndroidWorkstationPolicy.COMMAND to "${paths.final}/payload/host-bin/${LocalAndroidWorkstationPolicy.COMMAND}"),
                    dependencies = LocalAndroidWorkstationPolicy.dependencyKeys.sorted(), artifactSha256 = LocalAndroidWorkstationPolicy.hash(lock), abi = "arm64-v8a",
                    healthChecks = listOf(ManagedPackageHealthCheck("host-bin/${LocalAndroidWorkstationPolicy.COMMAND}", listOf("--droide-health"))),
                    metadata = mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION,
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id, LocalManagedPackageMetadata.SEAL_SHA256_KEY to sealHash),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, it) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("Cannot clean Android workstation staging tree"))
                }
                throw failure
            }
        }
    }
}
