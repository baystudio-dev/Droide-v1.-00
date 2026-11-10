package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

/** Command integration for tools already shipped inside an admitted SDK, without copied SDK bytes. */
object LocalUbuntuBundleAliasPolicy {
    const val VERSION_CHOICE = "SDK bundled"
    const val POLICY_KEY = "droide.local.bundle-alias.policy"
    const val POLICY_VERSION = "sdk-command-alias-v1"
    const val PRIMARY_KEY = "droide.local.bundle-alias.primary"
    const val LOCK_FILE = "BUNDLE_ALIAS_LOCK.txt"
    private const val ACTIVATION_FILE = "DROIDE_ACTIVATION_ID"

    data class Command(val primaryCommand: String, val fixedArgs: List<String>, val healthArgs: List<String>, val healthFixedArgs: List<String> = fixedArgs)
    @ConsistentCopyVisibility
    data class Recipe internal constructor(
        val familyId: String,
        val primaryFamilyId: String,
        val bundle: String,
        val commands: Map<String, Command>,
    ) {
        fun version(primary: ManagedPackageRecord): String {
            require(primary.familyId == primaryFamilyId) { "Bundle alias requires $primaryFamilyId" }
            require(primary.version.matches(Regex("[A-Za-z0-9._+-]{1,100}")) && primary.version !in setOf(".", "..")) {
                "Invalid primary SDK version"
            }
            return "$bundle-${primary.version}"
        }
    }
    data class Plan(val recipe: Recipe, val primary: ManagedPackageRecord, val version: String, val primaryGuestRoot: String) {
        val primaryKey: String get() = "${primary.familyId}@${primary.version}"
        fun healthChecks(): List<ManagedPackageHealthCheck> = recipe.commands.map { (name, command) ->
            ManagedPackageHealthCheck("host-bin/$name", if (command.healthFixedArgs == command.fixedArgs) command.healthArgs else listOf("--droide-health"))
        }
    }

    private fun command(name: String, vararg args: String, health: String = "--help") =
        Command(name, java.util.Collections.unmodifiableList(args.toList()), listOf(health))
    private fun recipe(family: String, primary: String, bundle: String, commands: Map<String, Command>) =
        Recipe(family, primary, bundle, java.util.Collections.unmodifiableMap(commands))
    private val recipes = listOf(
        recipe("package.npm", "runtime.node", "node", linkedMapOf(
            "npm" to command("npm", health = "--version"), "npx" to command("npx", health = "--version"),
        )),
        recipe("package.gradle", "build.gradle", "gradle", mapOf("gradle" to command("gradle", health = "--version"))),
        recipe("test.gradle", "build.gradle", "gradle", mapOf("gradle-test" to command("gradle", "test", health = "--version"))),
        recipe("package.pub", "runtime.dart", "dart", mapOf("pub" to command("dart", "pub"))),

        recipe("test.dart", "runtime.dart", "dart", mapOf("dart-test" to Command("dart", listOf("test"), listOf("help", "test"), emptyList()))),

        recipe("lsp.dart", "runtime.dart", "dart", mapOf("dart" to command("dart", health = "--version"))),
        recipe("lsp.sourcekit-lsp", "toolchain.swift", "swift", mapOf("sourcekit-lsp" to command("sourcekit-lsp"))),
    )

    fun find(familyId: String): Recipe? = recipes.firstOrNull { it.familyId == familyId }
    fun all(): List<Recipe> = recipes.toList()

    fun plan(familyId: String, primary: ManagedPackageRecord, appRoot: String): Plan {
        val recipe = find(familyId) ?: error("Unreviewed SDK bundle alias")
        val version = recipe.version(primary)
        require(primary.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && primary.abi == "arm64-v8a" &&
            primary.metadata[PackageBackendContract.METADATA_KEY] == PackageBackendId.LOCAL_APP.name &&
            primary.metadata[LocalManagedPackageMetadata.KIND_KEY] == (if (recipe.primaryFamilyId == "build.gradle")
                LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE else LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE) &&
            primary.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            primary.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name &&
            primary.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true) {
            "Bundle alias requires a verified app-local Ubuntu ARM64 SDK"
        }
        val expectedRoot = File(appRoot, "packages/${primary.familyId}/${primary.version}/arm64-v8a").absolutePath
        require(primary.installRoot == expectedRoot) { "Primary SDK receipt has an unexpected destination" }
        val requiredCommands = recipe.commands.values.map { it.primaryCommand }.toSet() +
            if (primary.familyId == "runtime.node") setOf("node") else emptySet()
        require(requiredCommands.all { primary.commands[it] == "$expectedRoot/payload/host-bin/$it" }) {
            "Primary SDK does not provide every required bundled command"
        }
        return Plan(recipe, primary, version, guestPath(expectedRoot, appRoot))
    }

    /** A bundle alias owns its named command; uninstalling it restores the SDK projection. */
    fun projectCommands(records: List<ManagedPackageRecord>, appRoot: String): List<ManagedPackageRecord> {
        val active = records.filter { it.active }
        val suppress = mutableMapOf<String, MutableSet<String>>()
        active.filter { it.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS }.forEach { alias ->
            val recipe = requireNotNull(find(alias.familyId))
            val primaryKey = alias.metadata[PRIMARY_KEY]
            val primary = records.singleOrNull { "${it.familyId}@${it.version}" == primaryKey } ?: error("SDK alias primary receipt is missing")
            val planned = plan(alias.familyId, primary, appRoot)
            require(alias.dependencies == listOf(planned.primaryKey) && alias.version == planned.version &&
                alias.commands.keys == recipe.commands.keys && alias.metadata[POLICY_KEY] == POLICY_VERSION)
            val names = recipe.commands.keys.intersect(primary.commands.keys)
            if (names.isNotEmpty()) suppress.getOrPut(recipe.primaryFamilyId) { mutableSetOf() }.addAll(names)
        }
        return active.map { record ->
            val suppressed = suppress[record.familyId].orEmpty()
            if (suppressed.isEmpty()) record else record.copy(commands = record.commands.filterKeys { it !in suppressed })
        }
    }

    fun launcherPath(appRoot: String): String =
        File(appRoot, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch").absolutePath

    fun files(plan: Plan, installRoot: String, appRoot: String): Map<String, String> {
        val aliasGuestRoot = guestPath(installRoot, appRoot)
        val out = linkedMapOf(LOCK_FILE to lock(plan))
        plan.recipe.commands.forEach { (name, command) ->
            val argv = listOf("${plan.primaryGuestRoot}/payload/bin/${command.primaryCommand}") + command.fixedArgs
            out["payload/bin/$name"] = "#!/bin/sh\nset -eu\n" +
                "export PATH=${q("${plan.primaryGuestRoot}/payload/bin")}:\"${'$'}PATH\"\n" +
                (if (command.healthFixedArgs == command.fixedArgs) "" else
                    "if [ \"${'$'}{1-}\" = --droide-health ] && [ \"${'$'}#\" -eq 1 ]; then exec " +
                        (listOf("${plan.primaryGuestRoot}/payload/bin/${command.primaryCommand}") + command.healthFixedArgs + command.healthArgs)
                            .joinToString(" ", transform = ::q) + "; fi\n") +
                "exec ${argv.joinToString(" ", transform = ::q)} \"${'$'}@\"\n"
            out["payload/host-bin/$name"] = "#!/system/bin/sh\nset -eu\n" +
                "exec /system/bin/sh ${q(launcherPath(appRoot))} ${q("$aliasGuestRoot/payload/bin/$name")} \"${'$'}@\"\n"
        }
        return out
    }

    fun metadata(plan: Plan, activationId: String, seal: String): Map<String, String> = mapOf(
        PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activationId,
        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS,
        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
        LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,
        LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,
        POLICY_KEY to POLICY_VERSION, PRIMARY_KEY to plan.primaryKey,
    )

    fun manifest(root: File, relativeFiles: Collection<String>): String = relativeFiles.sorted().joinToString("\n", postfix = "\n") {
        "${sha256(File(root, it))}  $it"
    }

    /** Validates the alias itself; its durable primary receipt must also pass the authority's verifier. */
    fun verifyReceipt(record: ManagedPackageRecord, primary: ManagedPackageRecord, appRoot: String): Boolean = runCatching {
        val plan = plan(record.familyId, primary, appRoot)
        val installRoot = File(appRoot, "packages/${record.familyId}/${plan.version}/arm64-v8a").absolutePath
        require(record.version == plan.version && record.installRoot == installRoot &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.dependencies == listOf(plan.primaryKey) && record.environment.isEmpty() &&
            record.pathEntries == listOf("$installRoot/payload/host-bin") &&
            record.commands == plan.recipe.commands.keys.associateWith { "$installRoot/payload/host-bin/$it" } &&
            record.healthChecks == plan.healthChecks() && record.checksumFile == "SHA256SUMS")
        val seal = record.artifactSha256 ?: error("Alias seal is missing")
        require(seal.matches(Regex("[0-9a-f]{64}")))
        val id = record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] ?: error("Alias activation ID is missing")
        require(UUID.fromString(id).toString() == id && record.metadata == metadata(plan, id, seal))
        val root = File(installRoot)
        LocalPackageInstallJournal.requireSafeParents(root)
        require(root.isDirectory && !PathSecurity.isSymbolicLink(root))
        val expected = files(plan, installRoot, appRoot) + (ACTIVATION_FILE to id)
        val actualFiles = mutableSetOf<String>()
        val actualDirs = mutableSetOf<String>()
        Files.walk(root.toPath()).use { paths ->
            val iterator = paths.iterator()
            var count = 0
            while (iterator.hasNext()) {
                val path = iterator.next()
                require(++count <= 16 && !Files.isSymbolicLink(path)) { "Alias tree has an unexpected member" }
                if (path == root.toPath()) continue
                val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                when {
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> actualDirs += relative
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> actualFiles += relative
                    else -> error("Alias tree has an unsupported member")
                }
            }
        }
        require(actualDirs == setOf("payload", "payload/bin", "payload/host-bin") &&
            actualFiles == expected.keys + "SHA256SUMS")
        expected.forEach { (relative, text) ->
            val file = File(root, relative)
            require(file.length() in 1..65_536 && file.readText(Charsets.UTF_8) == text)
            if (relative.startsWith("payload/")) require(file.canExecute())
        }
        val checksum = File(root, "SHA256SUMS")
        require(checksum.length() in 1..8_000 && checksum.readText() == manifest(root, expected.keys) && sha256(checksum) == seal)
        true
    }.getOrDefault(false)

    fun sha256(file: File): String {
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..65_536) { "Alias file is unavailable or oversized" }
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun lock(plan: Plan) = buildString {
        append("schema=1\nfamily=").append(plan.recipe.familyId).append("\nversion=").append(plan.version)
        append("\nbundle=").append(plan.recipe.bundle).append("\nprimary=").append(plan.primaryKey).append('\n')
        plan.recipe.commands.forEach { (name, command) ->
            append("command=").append(name).append('\t').append(command.primaryCommand)
            command.fixedArgs.forEach { append('\t').append(it) }
            append('\n')
        }
    }
    private fun guestPath(path: String, appRoot: String): String {
        require(appRoot.startsWith('/') && !appRoot.endsWith('/') && appRoot.none { it.code < 32 || it.code == 127 })
        val prefix = "$appRoot/packages/"
        require(path.startsWith(prefix)) { "SDK command escaped app-owned package storage" }
        val relative = path.removePrefix(prefix)
        require(relative.isNotBlank() && relative.split('/').none { it.isBlank() || it in setOf(".", "..") } &&
            path.none { it.code < 32 || it.code == 127 }) { "Unsafe SDK command projection path" }
        return "/opt/droide/packages/$relative"
    }
    private fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"
}
