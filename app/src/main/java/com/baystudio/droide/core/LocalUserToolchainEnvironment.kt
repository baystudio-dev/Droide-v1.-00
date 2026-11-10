package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object LocalUserToolchainEnvironment {
    private val cache = UserToolchainEnvironmentCache()

    fun invalidate() { cache.invalidate() }

    data class Resolution(val spec: TerminalLaunchSpec, val userEnvironmentLoaded: Boolean)

    suspend fun resolve(base: TerminalLaunchSpec, cwd: File, workspaceRoot: File,
        selections: Map<String, String>,
        captureShell: (suspend (TerminalLaunchSpec, String) -> UserToolchainEnvironmentCache.Capture)? = null,
    ): TerminalLaunchSpec = resolveDetailed(base, cwd, workspaceRoot, selections, captureShell).spec

    suspend fun resolveDetailed(base: TerminalLaunchSpec, cwd: File, workspaceRoot: File,
        selections: Map<String, String>,
        captureShell: (suspend (TerminalLaunchSpec, String) -> UserToolchainEnvironmentCache.Capture)? = null,
    ): Resolution = withContext(Dispatchers.IO) {
        // Only the ordinary Ubuntu env -i launcher is supported; specialized sealed health/runtime
        // launches and host shells keep their existing environment.
        if (base.shell != "/bin/bash" || base.prefix.isEmpty()) return@withContext Resolution(base, false)
        val envIndex = base.prefix.indexOfLast { it == "/usr/bin/env" }
        if (envIndex < 0 || base.prefix.getOrNull(envIndex + 1) != "-i") return@withContext Resolution(base, false)
        val baseEnvironment = base.prefix.drop(envIndex + 2).associate { field ->
            val index = field.indexOf('=')
            require(index > 0) { "Guest environment prefix is invalid" }
            field.substring(0, index) to field.substring(index + 1)
        }
        val rootIndex = base.prefix.indexOf("-r")
        require(rootIndex >= 0 && base.prefix.getOrNull(rootIndex + 1) != null) { "Guest root is missing" }
        val rootfs = File(base.prefix[rootIndex + 1])
        val rootIdentity = WorkspaceDirectoryIdentity(workspaceRoot)
        val cwdIdentity = WorkspaceDirectoryIdentity(cwd)
        val guestIdentity = WorkspaceDirectoryIdentity(rootfs)
        val context = currentCoroutineContext()
        val epoch = ApplicationWorkAdmission.requireOpen()
        fun requireCurrent() {
            context.ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "User environment belongs to an expired application session" }
            rootIdentity.requireCurrent(); cwdIdentity.requireCurrent(); guestIdentity.requireCurrent()
        }
        requireCurrent()
        // Metadata is only cache invalidation. It does not grant execution authority.
        fun stamp(file: File): String = runCatching {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            "${attrs.fileKey()}:${attrs.size()}:${attrs.lastModifiedTime()}"
        }.getOrDefault("absent")
        val capturePrefix = base.prefix.toMutableList()
        val cwdIndex = capturePrefix.indexOf("-w")
        require(cwdIndex >= 0 && cwdIndex + 1 < envIndex) { "Guest cwd is missing" }
        capturePrefix[cwdIndex + 1] = "/root"
        // Working subdirectories do not change a HOME startup. Share one snapshot across lookup/run
        // consumers in this workspace instead of sourcing rc again.
        val keyData = (capturePrefix + base.environment.toSortedMap().map { "${it.key}=${it.value}" } +
            listOf(workspaceRoot.canonicalPath, stamp(rootfs), stamp(File(rootfs, "etc/profile")),
                stamp(File(rootfs, "etc/profile.d")), stamp(File(rootfs, "root/.profile")),
                stamp(File(rootfs, "root/.bashrc")), stamp(File(rootfs, "root/.nvm/alias/default"))) +
            selections.toSortedMap().map { "${it.key}=${it.value}" }).joinToString("\u0000")
        val key = MessageDigest.getInstance("SHA-256").digest(keyData.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val observed = cache.resolve(key, cwd.canonicalPath) capture@{ script ->
            requireCurrent()
            // Preserve the legacy login profile, then read the terminal's Bash rc without prompt hooks.

            val captureBase = base.copy(prefix = capturePrefix)
            captureShell?.let { return@capture it(captureBase, script) }
            val lease = RemoteProcessLease.createLocal("toolchain-env")
            val argv = captureBase.command(
                UserToolchainEnvironment.captureArgv(script), lease.environment)
            val result = UserToolchainEnvironmentCleanup.capture { completed ->
                LocalProcessSupervisor.captureWithAdmission(
                    listOf(if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh", "-c",
                        lease.wrap(argv.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote))),
                    cwd, base.environment + lease.environment,
                    maxOutputBytes = UserToolchainEnvironment.MAX_CAPTURE_CHARS,
                    timeoutMs = 4_000,
                    cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                    onCleanupCompleted = completed,
                    admitStart = { create -> withWorkspaceProcessCreation(workspaceRoot) {
                        requireCurrent(); create().also { requireCurrent() }
                    } },
                )
            }
            requireCurrent()
            UserToolchainEnvironmentCache.Capture(result.output, result.exitCode, result.timedOut)
        }
        requireCurrent()
        if (observed.fallback != null) {
            // Preserve the existing standard/managed tool path. Never leak rc text.
            android.util.Log.w("DroideToolchain", "User shell environment unavailable (${observed.fallback}); using standard toolchain environment")
            return@withContext Resolution(base, false)
        }
        val protected = buildSet {
            val pack = LocalAndroidWorkstationPolicy.FAMILY in selections
            if (pack || LocalUbuntuJdkPolicy.FAMILY in selections) add("JAVA_HOME")
            if (pack || selections.keys.any { it in setOf("sdk.android", "build.android-tools", "toolchain.android-ndk", "build.cmake") }) {
                addAll(listOf("ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"))
            }
        }
        val preferredBins = baseEnvironment["PATH"].orEmpty().split(':').takeWhile { it != "/root/.local/bin" }
        val values = try {
            UserToolchainEnvironment.merge(observed.environment, baseEnvironment, protected, preferredBins)
        } catch (_: IllegalArgumentException) { return@withContext Resolution(base, false) }
        ProcessSecurityPolicy.validateEnvironment(values)
        requireCurrent()
        Resolution(base.copy(prefix = base.prefix + values.map { (key, value) -> "$key=$value" }), true)
    }
}
