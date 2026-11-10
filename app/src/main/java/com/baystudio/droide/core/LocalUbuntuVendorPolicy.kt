package com.baystudio.droide.core

import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

internal object LocalUbuntuVendorPolicy {
    const val POLICY_KEY = "droide.local.vendor-policy"
    const val POLICY_VERSION = "publisher-ubuntu-arm64-v1"
    const val VERSION_CHOICE = "Reviewed Ubuntu release"

    fun fromReceipt(record: ManagedPackageRecord): LocalUbuntuVendorRecipe? = runCatching {
        if(record.familyId==LocalUbuntuElpPolicy.FAMILY) return@runCatching LocalUbuntuElpPolicy.recipe().also { require(record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalUbuntuElpPolicy.KIND && record.artifactSha256==it.assetSha256 && record.version==it.version) }
        require(record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE &&
            record.metadata[POLICY_KEY] == POLICY_VERSION)
        val def = requireNotNull(LocalUbuntuVendorCatalog.find(record.familyId))
        val file = File(record.installRoot, "RECIPE_LOCK.json")
        require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..65_536)
        val powerShell = record.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY || record.familyId == LocalUbuntuTomlNodePolicy.FAMILY || record.familyId == LocalUbuntuVueHelperPolicy.FAMILY || LocalUbuntuNimPolicy.isFamily(record.familyId) || record.familyId in setOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)
        val rawLock = if (powerShell) LocalUbuntuPowerShellLaunchAuthority.readText(file, 65_536)
            else if (record.familyId == LocalUbuntuClojurePolicy.FAMILY) LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 65_536)
            else if (record.familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffLaunchAuthority.readBoundedText(file, 65_536) else file.readText()
        val lock = if (powerShell) LocalUbuntuPowerShellRuntimePolicy.strictObject(rawLock, 65_536) else Json.parseToJsonElement(rawLock).jsonObject
        if (powerShell) require(lock["schema"] == JsonPrimitive(1) && lock["assetSize"] is JsonPrimitive && !lock.getValue("assetSize").jsonPrimitive.isString)
        fun text(key: String): String {
            val value = lock.getValue(key).jsonPrimitive
            if (powerShell && key !in setOf("schema", "assetSize")) require(value.isString)
            return value.content
        }
        require(text("schema") == "1" && text("provider") == "LOCAL_UBUNTU_VENDOR" && text("familyId") == record.familyId &&
            text("version") == record.version && text("artifactFormat") == def.format.name && text("command") == def.command &&
            text("treeEntryPoint") == def.entry(record.version))
        LocalUbuntuVendorRecipe(def, record.version, text("releaseTag"), text("assetName"), text("assetUrl"),
            text("assetSha256"), text("assetSize").toLong(), text("provenanceUrl"),
            assetSha512 = lock["assetSha512"]?.jsonPrimitive?.contentOrNull).also {
            it.validate(); require(it.assetSha256 == record.artifactSha256)
        }
    }.getOrNull()

    fun admit(recipe: LocalUbuntuVendorRecipe, file: File) {
        require(recipe.familyId!=LocalUbuntuElpPolicy.FAMILY) { "ELP requires its exact original archive/receipt admission" }
        recipe.validate()
        if (recipe.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY) { LocalUbuntuGraphqlNodePolicy.requireTree(file); return }
        if (recipe.familyId == LocalUbuntuTomlNodePolicy.FAMILY) { LocalUbuntuTomlNodePolicy.requireTree(file); return }
        if (recipe.familyId == LocalUbuntuVueHelperPolicy.FAMILY) {
            LocalUbuntuVueHelperPolicy.requireTree(file); return
        }
        if (LocalUbuntuNimPolicy.isFamily(recipe.familyId)) {
            val depth = recipe.entryPoint.count { it == '/' } + 1
            var tree = file
            repeat(depth) { tree = requireNotNull(tree.parentFile) }
            LocalUbuntuNimLaunchAuthority.inventory(recipe, tree); return
        }
        if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) {
            LocalUbuntuPowerShellEditorServicesPolicy.requireTree(file); return
        }
        if (recipe.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) {
            LocalUbuntuPowerShellRuntimePolicy.requireTree(file); return
        }
        if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) {
            LocalUbuntuClojurePolicy.requireTree(file)
            LocalUbuntuClojurePolicy.requireElfStructure(file)
        }
        if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffPolicy.requireTree(file)
        if (recipe.familyId == LocalUbuntuBufPolicy.FAMILY) LocalUbuntuBufPolicy.requireTree(file, recipe.version)
        if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) {
            LocalUbuntuMakePolicy.requireTree(file)
            LocalUbuntuMakePolicy.requireStaticShape(file)
        }
        if (recipe.familyId == LocalUbuntuTerraformPolicy.FAMILY) LocalUbuntuTerraformPolicy.requireTree(file, recipe.version)
        if (recipe.familyId == LocalUbuntuZlsPolicy.FAMILY) LocalUbuntuZlsPolicy.requireTree(file, recipe.version)
        when (recipe.definition.admission) {
            LocalUbuntuVendorAdmission.GLIBC_ARM64 -> LinuxArm64ElfAdmission.requireUbuntuGlibc(file)
            LocalUbuntuVendorAdmission.STATIC_ARM64 -> LinuxArm64ElfAdmission.requireLinuxStatic(file)
            LocalUbuntuVendorAdmission.INTERPRETED -> {
                require(file.isFile && !PathSecurity.isSymbolicLink(file))
                when (recipe.definition.interpreter.first()) {
                    LocalUbuntuLemMinxPolicy.JAVA -> {
                        require(recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) { "Fixed managed Java is reserved for the reviewed LemMinX recipe" }
                        LocalUbuntuLemMinxPolicy.requireArtifact(file)
                    }
                    "/usr/bin/java" -> ZipFile(file).use { require(it.getEntry("META-INF/MANIFEST.MF") != null) { "Publisher JVM artifact has no JAR manifest" } }
                    "/usr/bin/php" -> require(header(file, 512).contains("<?php")) { "Publisher PHAR has no PHP bootstrap" }
                    "/usr/bin/python3", "/bin/bash" -> require(header(file, 128).startsWith("#!/")) { "Publisher script has no interpreter header" }
                    "/usr/bin/elixir" -> {
                        val bytes = ByteArray(12)
                        java.io.DataInputStream(file.inputStream()).use { it.readFully(bytes) }
                        require(String(bytes, 0, 4, Charsets.US_ASCII) == "FOR1" &&
                            String(bytes, 8, 4, Charsets.US_ASCII) == "BEAM") { "Publisher Hex entry has no BEAM header" }
                    }
                    LocalUbuntuJsDebugPolicy.NODE_GUEST -> LocalUbuntuJsDebugPolicy.requireEntry(recipe, file)
                    else -> error("Unreviewed publisher interpreter")
                }
            }
        }
    }

    fun argv(recipe: LocalUbuntuVendorRecipe, target: String): List<String> = if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) {
        recipe.validate()
        LspPowerShellLaunchPolicy.supervisorCommand(powerShellRoot(recipe, target), health = false)
    } else if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) {
        recipe.validate()
        LspClojureLaunchPolicy.editorCommand(target)
    } else if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) {
        recipe.validate()
        LspLemMinxLaunchPolicy.editorCommand(target)
    } else if (recipe.familyId == "package.hex")
        listOf("/usr/bin/elixir", "-pa", target.substringBeforeLast('/'), "-S", "mix")
        else if (recipe.familyId == LocalUbuntuJsDebugPolicy.FAMILY && !LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe))
            listOf("/usr/bin/env", "node", target)
        else recipe.definition.interpreter + listOf(target) +
        when (recipe.familyId) {
            "package.sbt" -> listOf("--server")
            "build.gradle" -> listOf("--no-daemon")
            "lsp.jdtls" -> (if (recipe.version == recipe.definition.pinnedVersion)
                listOf("--java-executable", "/usr/lib/jvm/java-21-openjdk-arm64/bin/java") else emptyList()) +
                listOf("--jvm-arg=-Xms64m", "--jvm-arg=-Xmx768m")
            "lsp.lua-language-server" -> listOf(
                "--logpath=/root/.cache/droide/lua-language-server/${recipe.version}/log",
                "--metapath=/root/.cache/droide/lua-language-server/${recipe.version}/meta")
            else -> emptyList()
        }
    fun executablePaths(recipe: LocalUbuntuVendorRecipe): Set<String> =
        if (recipe.familyId == LocalUbuntuNimToolchainPolicy.FAMILY) LocalUbuntuNimToolchainPolicy.executablePaths
        else if (recipe.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) LocalUbuntuPowerShellRuntimePolicy.executablePaths(recipe)
        else commands(recipe).values.toSet()
    fun isPowerShell(recipe: LocalUbuntuVendorRecipe) = recipe.familyId in setOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)
    fun healthArgs(recipe: LocalUbuntuVendorRecipe) = if (recipe.familyId == LocalUbuntuVueHelperPolicy.FAMILY) listOf("--health") else if (LocalUbuntuNimPolicy.isFamily(recipe.familyId)) listOf("--health") else if (isPowerShell(recipe)) listOf(LspPowerShellLaunchPolicy.HEALTH_SELECTOR) else if (recipe.definition.lsp || recipe.familyId == "package.hex") listOf("--droide-health") else recipe.healthArgs
    private fun powerShellRoot(recipe: LocalUbuntuVendorRecipe, target: String): String {
        val suffix = "/payload/tree/${recipe.entryPoint}"
        require(target.endsWith(suffix)) { "PowerShell target escaped its reviewed tree layout" }
        return target.removeSuffix(suffix)
    }
    fun healthCommand(recipe: LocalUbuntuVendorRecipe, target: String) =
        if (isPowerShell(recipe)) lspProbe(recipe, powerShellRoot(recipe, target), target) else
        (when (recipe.familyId) {
            "package.hex" -> listOf("/usr/bin/env", "HEX_OFFLINE=1")
            LocalUbuntuAgentDistributions.ANTIGRAVITY -> listOf("/usr/bin/env", "AGY_CLI_DISABLE_AUTO_UPDATE=true")
            else -> emptyList()
        }) + argv(recipe, target) + recipe.healthArgs
    fun commands(recipe: LocalUbuntuVendorRecipe) = linkedMapOf(recipe.command to recipe.entryPoint).apply { putAll(recipe.definition.auxiliaryCommands) }

    fun guestWrapper(recipe: LocalUbuntuVendorRecipe, target: String, guestRoot: String): String =
        if (recipe.familyId == LocalUbuntuGraphqlNodePolicy.FAMILY) LocalUbuntuGraphqlLaunchAuthority.guestWrapper(recipe.familyId,guestRoot)
        else if (recipe.familyId == LocalUbuntuTomlNodePolicy.FAMILY) LocalUbuntuTomlLaunchAuthority.guestWrapper(recipe.familyId,guestRoot)
        else if (recipe.familyId == LocalUbuntuVueHelperPolicy.FAMILY) LocalUbuntuVueLaunchAuthority.guestWrapper(recipe.familyId, guestRoot)
        else if (LocalUbuntuNimPolicy.isFamily(recipe.familyId)) nimWrapper(recipe, target, guestRoot)
        else if (isPowerShell(recipe)) LspPowerShellLaunchPolicy.guestWrapper(recipe, target, guestRoot) else buildString {
        append("#!/bin/sh\nset -eu\n")
        if (recipe.familyId == LocalUbuntuAgentDistributions.ANTIGRAVITY)
            append("export AGY_CLI_DISABLE_AUTO_UPDATE=true\n")
        if (recipe.familyId == "package.hex") {
            append("if [ \"${'$'}{1-}\" = --droide-health ] && [ \"${'$'}#\" -eq 1 ]; then exec ")
                .append(healthCommand(recipe, target).joinToString(" ", transform = ::q)).append("; fi\n")
            append("task=\"${'$'}{1-info}\"\nif [ \"${'$'}#\" -gt 0 ]; then shift; fi\n")
            append("case \"${'$'}task\" in --version) task=info;; ''|*[!A-Za-z0-9._-]*) echo 'Invalid Hex task' >&2; exit 2;; esac\n")
            append("exec ").append(argv(recipe, target).joinToString(" ", transform = ::q)).append(" \"hex.${'$'}task\" \"${'$'}@\"\n")
            return@buildString
        }
        if (recipe.definition.lsp) append("if [ \"${'$'}{1-}\" = --droide-health ] && [ \"${'$'}#\" -eq 1 ]; then exec ")
            .append(lspProbe(recipe, guestRoot, target).joinToString(" ", transform = ::q)).append("; fi\n")
        if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) {
            append("[ \"${'$'}#\" -eq 0 ] || { echo 'Clojure accepts no editor arguments or the sole --droide-health selector' >&2; exit 2; }\n")
            append("exec ").append(argv(recipe, target).joinToString(" ", transform = ::q)).append("\n")
            return@buildString
        }
        if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) {
            append("[ \"${'$'}#\" -eq 0 ] || { echo 'Make LS accepts no editor arguments or the sole --droide-health selector' >&2; exit 2; }\n")
            append("exec ").append(argv(recipe, target).joinToString(" ", transform = ::q)).append("\n")
            return@buildString
        }
        if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) {
            append("[ \"${'$'}#\" -eq 0 ] || { echo 'LemMinX accepts no editor arguments or the sole --droide-health selector' >&2; exit 2; }\n")
            append("exec ").append(argv(recipe, target).joinToString(" ", transform = ::q)).append("\n")
            return@buildString
        }
        append("exec ").append(argv(recipe, target).joinToString(" ", transform = ::q)).append(" \"${'$'}@\"\n")
    }
    private fun nimWrapper(recipe: LocalUbuntuVendorRecipe, target: String, root: String): String {
        recipe.validate()
        val health = when(recipe.familyId) {
            LocalUbuntuNimPolicy.FAMILY -> listOf(LocalUbuntuNimHelperPolicy.EXECUTABLE, "$root/payload/nim-zero-document-health.cjs", "--health", root, target)
            LocalUbuntuNimHelperPolicy.FAMILY -> listOf(target, "$root/payload/nim-helper-identity-health.cjs", "--installed-health", root)
            LocalUbuntuNimToolchainPolicy.FAMILY -> emptyList()
            else -> error("Unknown Nim wrapper")
        }
        return "#!/bin/sh\nset -eu\n[ \"${'$'}#\" -eq 1 ] && [ \"${'$'}1\" = --health ] || { echo 'Nim requires the typed managed launch route' >&2; exit 2; }\n" +
            if (health.isEmpty()) "printf '%s\\n' 'DROIDE_NIM_STATIC_TOOLCHAIN_OK'\n" else "exec /usr/bin/env -i PATH=/usr/bin:/bin HOME=/root LANG=C.UTF-8 LC_ALL=C.UTF-8 TMPDIR=/tmp " + health.joinToString(" ", transform=::q) + "\n"
    }
    fun lspHealthFileName(recipe: LocalUbuntuVendorRecipe): String =
        if (isPowerShell(recipe)) LocalUbuntuPowerShellLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) LocalUbuntuClojureLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) LocalUbuntuMakeLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) LocalUbuntuLemMinxLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuZlsPolicy.FAMILY) LocalUbuntuZlsLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuBufPolicy.FAMILY) LocalUbuntuBufLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuTerraformPolicy.FAMILY) LocalUbuntuTerraformLspHealth.FILE_NAME
        else if (recipe.familyId == LocalUbuntuTexlabPolicy.FAMILY) LocalUbuntuTexlabLspHealth.FILE_NAME else LocalUbuntuNpmLspHealth.FILE_NAME
    fun lspHealthScript(recipe: LocalUbuntuVendorRecipe): String =
        if (isPowerShell(recipe)) LocalUbuntuPowerShellLspHealth.script
        else if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) LocalUbuntuSqruffLspHealth.script
        else if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) LocalUbuntuClojureLspHealth.script
        else if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) LocalUbuntuMakeLspHealth.script
        else if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) LocalUbuntuLemMinxLspHealth.script
        else if (recipe.familyId == LocalUbuntuZlsPolicy.FAMILY) LocalUbuntuZlsLspHealth.scriptForVersion(recipe.version)
        else if (recipe.familyId == LocalUbuntuBufPolicy.FAMILY) LocalUbuntuBufLspHealth.scriptForVersion(recipe.version)
        else if (recipe.familyId == LocalUbuntuTerraformPolicy.FAMILY) LocalUbuntuTerraformLspHealth.script
        else if (recipe.familyId == LocalUbuntuTexlabPolicy.FAMILY) LocalUbuntuTexlabLspHealth.script else LocalUbuntuNpmLspHealth.script

    fun lspProbe(recipe: LocalUbuntuVendorRecipe, root: String, target: String): List<String> {
        if (isPowerShell(recipe)) return LspPowerShellLaunchPolicy.supervisorCommand(root, runtime = recipe.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY)
        if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) {
            recipe.validate()
            return LocalUbuntuSqruffLspHealth.healthCommand("$root/${lspHealthFileName(recipe)}", target)
        }
        if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY) {
            recipe.validate()
            return LspClojureLaunchPolicy.healthCommand("$root/${lspHealthFileName(recipe)}", target)
        }
        if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) {
            recipe.validate()
            return LocalUbuntuMakeLspHealth.healthCommand("$root/${lspHealthFileName(recipe)}", target)
        }
        if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY) {
            recipe.validate()
            return LspLemMinxLaunchPolicy.healthCommand("$root/${lspHealthFileName(recipe)}", target)
        }
        return listOf("/usr/bin/node", "$root/${lspHealthFileName(recipe)}") +
            (when (recipe.familyId) {
                "lsp.lua-language-server" -> listOf("--droide-reviewed-luals-shutdown")
                "lsp.jdtls" -> listOf("--droide-reviewed-jdtls-shutdown")
                else -> emptyList()
            }) + when (recipe.familyId) {
                LocalUbuntuZlsPolicy.FAMILY -> LspZlsLaunchPolicy.healthCommand(argv(recipe, target), "/usr/bin/node")
                LocalUbuntuBufPolicy.FAMILY -> argv(recipe, target) + listOf("--log-format=json", "lsp", "serve")
                LocalUbuntuTerraformPolicy.FAMILY -> LspIsolatedHealthLaunchPolicy.command(argv(recipe, target) + "serve")
                LocalUbuntuTexlabPolicy.FAMILY -> LspTexlabLaunchPolicy.command(argv(recipe, target), health = true)
                else -> argv(recipe, target)
            }
    }
    fun hostWrapper(launcher: String, guestWrapper: String) = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(launcher)} ${q(guestWrapper)} \"${'$'}@\"\n"

    fun verifyIntegration(record: ManagedPackageRecord, recipe: LocalUbuntuVendorRecipe, appRoot: String): Boolean = runCatching {
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a")
        val final = File(appRoot, "packages/${recipe.familyId}/${recipe.version}/arm64-v8a").absolutePath
        require(record.installRoot == final && record.environment.isEmpty() && record.pathEntries == listOf("$final/payload/host-bin"))
        val commands = commands(recipe)
        require(record.commands == commands.keys.associateWith { "$final/payload/host-bin/$it" })
        require(record.healthChecks == listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}", healthArgs(recipe))))
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(final, "$appRoot/packages/"))
        val launcher = "$appRoot/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
        fun read(relative: String): String {
            val file = File(final, relative)
            require(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..65_536)
            return if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY)
                LocalUbuntuClojureLaunchAuthority.readBoundedText(file, 65_536) else if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY)
                LocalUbuntuSqruffLaunchAuthority.readBoundedText(file, 65_536) else if (isPowerShell(recipe)) LocalUbuntuPowerShellLaunchAuthority.readText(file, 65_536) else file.readText(Charsets.UTF_8)
        }
        commands.forEach { (command, relative) ->
            require(read("payload/bin/$command") == guestWrapper(recipe, "$guest/payload/tree/$relative", guest))
            require(read("payload/host-bin/$command") == hostWrapper(launcher, "$guest/payload/bin/$command"))
        }
        if ((recipe.definition.lsp || isPowerShell(recipe)) && !LocalUbuntuNimPolicy.isFamily(recipe.familyId)) require(read(lspHealthFileName(recipe)) == lspHealthScript(recipe))
        if (LocalUbuntuNimPolicy.isFamily(recipe.familyId)) {
            LocalUbuntuNimLaunchAuthority.helperPaths.forEach { relative -> require(NimRuntimeClosure.sha256(read(relative)) == LocalUbuntuNimLaunchAuthority.expectedHelperHashes[relative]) }
            require(read(LocalUbuntuNimLaunchAuthority.NOTICE_FILE) == LocalUbuntuNimLaunchAuthority.noticeText)
        }
        if (isPowerShell(recipe)) require(read(LocalUbuntuPowerShellLaunchAuthority.NOTICE_FILE) == LocalUbuntuPowerShellLaunchAuthority.noticeText)
        if (recipe.familyId == LocalUbuntuClojurePolicy.FAMILY)
            LocalUbuntuClojureLaunchAuthority.requireInstalled(record, recipe, appRoot)
        if (recipe.familyId == LocalUbuntuLemMinxPolicy.FAMILY)
            LocalUbuntuLemMinxPolicy.requireArtifact(File(final, "payload/tree/${recipe.entryPoint}"))
        if (recipe.familyId == LocalUbuntuBufPolicy.FAMILY)
            LocalUbuntuBufPolicy.requireTree(File(final, "payload/tree/${recipe.entryPoint}"), recipe.version)
        if (recipe.familyId == LocalUbuntuMakePolicy.FAMILY) {
            LocalUbuntuMakePolicy.requireTree(File(final, "payload/tree/${recipe.entryPoint}"))
            LocalUbuntuMakePolicy.requireStaticShape(File(final, "payload/tree/${recipe.entryPoint}"))
        }
        if (recipe.familyId == LocalUbuntuSqruffPolicy.FAMILY) {
            LocalUbuntuSqruffPolicy.requireTree(File(final, "payload/tree/${recipe.entryPoint}"))
            LinuxArm64ElfAdmission.requireLinuxStatic(File(final, "payload/tree/${recipe.entryPoint}"))
        }
        if (recipe.familyId == LocalUbuntuZlsPolicy.FAMILY)
            LocalUbuntuZlsPolicy.requireTree(File(final, "payload/tree/${recipe.entryPoint}"), recipe.version)
        true
    }.getOrDefault(false)
    private fun header(file: File, limit: Int): String = file.inputStream().use { input ->
        val bytes = ByteArray(limit); val n = input.read(bytes)
        if (n <= 0) "" else String(bytes, 0, n, Charsets.UTF_8)
    }
    private fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
