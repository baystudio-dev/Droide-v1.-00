package com.baystudio.droide.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal object LocalUbuntuNpmPolicy {
    const val SEAL = "NPM_TREE_SEAL.json"
    private val packages = mapOf(
        "plugin.opencode" to "opencode-ai",
        "plugin.codex" to "@agentclientprotocol/codex-acp",
        "plugin.claude-code" to "@agentclientprotocol/claude-agent-acp",
        "plugin.gemini-cli" to "@google/gemini-cli",
        "plugin.github-copilot-cli" to "@github/copilot",
    )

    fun supports(recipe: WorkstationInstallRecipe): Boolean = UbuntuNpmCatalog.supports(recipe) || (packages[recipe.familyId] == recipe.packageName &&
        WorkstationInstallRecipeCatalog.find(recipe.familyId, recipe.version)?.let {
            it.copy(registryIntegrity = recipe.registryIntegrity) == recipe
        } == true)

    // Original CLIs and editor adapters are separate entry points in the same dependency closure.
    fun commands(recipe: WorkstationInstallRecipe): Map<String, List<String>> = when (recipe.familyId) {
        LocalUbuntuMusePolicy.FAMILY -> linkedMapOf("muse-code-acp" to listOf("muse-code-acp"), "muse" to listOf("muse-code-acp", "--cli"))
        "plugin.codex" -> linkedMapOf("codex-acp" to listOf("codex-acp"), "codex" to listOf("codex"))
        "plugin.claude-code" -> linkedMapOf("claude-agent-acp" to listOf("claude-agent-acp"), "claude" to listOf("claude-agent-acp", "--cli"))
        "lsp.python-pyright" -> linkedMapOf("pyright-langserver" to listOf("pyright-langserver"), "pyright" to listOf("pyright"))
        else -> mapOf(recipe.command to listOf(recipe.command))
    }

    fun isLanguageServerCommand(recipe: WorkstationInstallRecipe, command: String): Boolean =
        recipe.familyId.startsWith("lsp.") && command == recipe.command

    fun languageServerArgs(recipe: WorkstationInstallRecipe): List<String> {
        require(recipe.familyId != LocalUbuntuGraphqlPolicy.FAMILY) { "GraphQL requires its typed managed owner" }
        require(recipe.familyId != LocalUbuntuTomlPolicy.FAMILY) { "Taplo requires its typed managed owner" }
        require(supports(recipe) && recipe.familyId.startsWith("lsp.")) { "This recipe has no reviewed language server" }
        return when (recipe.familyId) {
            "lsp.bash-language-server" -> listOf("start")
            else -> listOf("--stdio")
        }
    }

    fun healthArgs(recipe: WorkstationInstallRecipe, command: String = recipe.command): List<String> =
        if (isLanguageServerCommand(recipe, command)) listOf("--droide-health") else listOf("--version")

    fun verifyLanguageServerProbe(root: File, recipe: WorkstationInstallRecipe): Boolean {
        if (recipe.familyId == LocalUbuntuGraphqlPolicy.FAMILY) return false // Dedicated original API/owned-health lane only.
        if (recipe.familyId == LocalUbuntuTomlPolicy.FAMILY) return false // Dedicated original API/owned-health lane only.
        if (!recipe.familyId.startsWith("lsp.")) return true
        if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) return LocalUbuntuSvelteLaunchPolicy.verifyProbe(root)
        if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) return LocalUbuntuPrismaLaunchPolicy.verifyProbe(root)
        val file = File(root, "payload/${LocalUbuntuNpmLspHealth.FILE_NAME}")
        val expected = if (recipe.familyId == LspMdxProfile.FAMILY) LspMdxProfile.healthScript else LocalUbuntuNpmLspHealth.script
        return file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() == expected.toByteArray(Charsets.UTF_8).size.toLong() &&
            file.readText(Charsets.UTF_8) == expected
    }

    fun reviewedSelectorPreload(recipe: WorkstationInstallRecipe): String {
        require(recipe.familyId == "plugin.opencode" && recipe.packageName == "opencode-ai" &&
            recipe.allowLifecycleScripts && supports(recipe)) { "This recipe has no reviewed binary selector" }
        // The original selector catches linkSync failures and copies the original binary instead.
        // PRoot emulates successful hard links outside the managed tree, so request that fallback
        // only for this reviewed lifecycle invocation. Never change the package or its binary.
        return """
            'use strict';
            const fs = require('node:fs');
            fs.linkSync = function () {
              const failure = new Error('Droide managed selector requires an independent binary copy');
              failure.code = 'EXDEV';
              throw failure;
            };
        """.trimIndent() + "\n"
    }

    fun fromReceipt(record: ManagedPackageRecord): WorkstationInstallRecipe? = runCatching {
        if (record.familyId == LocalUbuntuGraphqlPolicy.FAMILY) { require(record.version==LocalUbuntuGraphqlPolicy.VERSION); return@runCatching LocalUbuntuGraphqlPolicy.recipe() }
        if (record.familyId == LocalUbuntuTomlPolicy.FAMILY) { require(record.version==LocalUbuntuTomlPolicy.VERSION); return@runCatching LocalUbuntuTomlPolicy.recipe() }
        if (record.familyId == LocalUbuntuVuePolicy.FAMILY) {
            require(record.version == LocalUbuntuVuePolicy.VERSION)
            return@runCatching LocalUbuntuVuePolicy.recipe()
        }
        val file = File(record.installRoot, "RECIPE_LOCK.json")
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..64_000)
        val obj = if (record.familyId == LocalUbuntuSveltePolicy.FAMILY_ID)
            LocalUbuntuSveltePolicy.strictObject(LocalUbuntuSveltePolicy.readRegular(file,64000)).also { LocalUbuntuSvelteLaunchPolicy.validateReview(it,record.dependencies.singleOrNull()) }
            else if (record.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID)
            LocalUbuntuPrismaPolicy.strictObject(file.readText()).also { LocalUbuntuPrismaLaunchPolicy.validateReview(it, record.dependencies.singleOrNull()) }
            else Json.parseToJsonElement(file.readText()).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.content ?: error("Missing receipt $key")
        val entry = UbuntuNpmCatalog.find(record.familyId) ?: return@runCatching null
        WorkstationInstallRecipe(record.familyId, record.version, str("packageName"), str("packageVersion"),
            entry.command, entry.minimumNodeMajor, entry.provenance, registryIntegrity = str("registryIntegrity"),
            allowLifecycleScripts = obj["allowLifecycleScripts"]?.jsonPrimitive?.booleanOrNull ?: entry.allowLifecycleScripts,
            lifecycleScriptReview = obj["lifecycleScriptReview"]?.jsonPrimitive?.contentOrNull ?: entry.lifecycleScriptReview,
            additionalDependencies = obj["additionalDependencies"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty())
            .also { it.validate(); require(supports(it)) }
    }.getOrNull()

    fun requireRegistryTarball(value: String) {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.host == "registry.npmjs.org" && uri.rawUserInfo == null &&
            uri.port in setOf(-1, 443) && uri.rawQuery == null && uri.rawFragment == null && uri.path.endsWith(".tgz")) {
            "npm artifact escaped the official registry"
        }
    }

    fun requireIntegrity(value: String) {
        require(value.matches(Regex("sha512-[A-Za-z0-9+/=]{86,90}"))) { "npm package must publish SHA-512 integrity" }
        require(java.util.Base64.getDecoder().decode(value.removePrefix("sha512-")).size == 64) { "Invalid npm SHA-512 integrity" }
    }

    suspend fun reusableLock(file: File, expectedSha256: String?, recipe: WorkstationInstallRecipe): File? = runSuspendCatching {
        require(expectedSha256 != null && file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..8_000_000L)
        require(sha256(file) == expectedSha256)
        validateLock(file.readText(), recipe)
        file
    }.getOrNull()

    fun validateLock(text: String, recipe: WorkstationInstallRecipe): JsonObject {
        require(recipe.familyId != LocalUbuntuGraphqlPolicy.FAMILY) { "GraphQL uses exact original data admission, no npm-generated lock" }
        require(recipe.familyId != LocalUbuntuTomlPolicy.FAMILY) { "Taplo uses exact original data admission, no npm-generated lock" }
        require(recipe.familyId != LocalUbuntuVuePolicy.FAMILY) { "Vue uses its authenticated archive/content proof, never an npm-generated lock" }
        if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) return LocalUbuntuSveltePolicy.validateLock(text, recipe)
        if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) return LocalUbuntuPrismaPolicy.validateLock(text, recipe)
        require(text.length <= 8_000_000) { "npm lockfile is too large" }
        if (recipe.familyId in setOf(LocalUbuntuMusePolicy.FAMILY, "plugin.kimi-code"))
            require(supports(recipe)) { "Agent npm recipe differs from its reviewed version and integrity" }
        val lock = Json.parseToJsonElement(text).jsonObject
        require(lock["lockfileVersion"]?.jsonPrimitive?.intOrNull in 2..3) { "npm 10+ lockfile required" }
        val entries = lock["packages"]?.jsonObject ?: error("npm dependency lock is missing")
        require(entries.size in 2..10_000) { "npm dependency closure exceeds the supported limit" }
        if (recipe.familyId == "lsp.vim-language-server" && recipe.version == UbuntuNpmCatalog.find(recipe.familyId)?.reviewedVersion) {
            require(supports(recipe) && entries.keys == setOf("", "node_modules/${recipe.packageName}")) { "Reviewed Vim bundle must not acquire an unreviewed dependency closure" }
        }
        if (recipe.familyId == LocalUbuntuPerlNavigatorPolicy.FAMILY_ID && recipe.version == UbuntuNpmCatalog.find(recipe.familyId)?.reviewedVersion) {
            require(supports(recipe)) { "PerlNavigator recipe is not the reviewed identity" }
            LocalUbuntuPerlNavigatorPolicy.validateLock(entries)
        }
        for ((path, raw) in entries) {
            if (path.isEmpty()) continue
            require(path.startsWith("node_modules/")) { "npm lock contains an external dependency" }
            safeRelative(path)
            val pkg = raw.jsonObject
            require(pkg["link"]?.jsonPrimitive?.booleanOrNull != true) { "npm linked dependency is not allowed" }
            requireRegistryTarball(pkg["resolved"]?.jsonPrimitive?.content ?: error("npm artifact URL is missing"))
            requireIntegrity(pkg["integrity"]?.jsonPrimitive?.content ?: error("npm dependency integrity is missing"))
        }
        val root = entries["node_modules/${recipe.packageName}"]?.jsonObject ?: error("npm root package is missing")
        require(root["version"]?.jsonPrimitive?.content == recipe.packageVersion) { "npm root version mismatch" }
        require(root["integrity"]?.jsonPrimitive?.content == recipe.registryIntegrity) { "npm root integrity mismatch" }
        recipe.additionalDependencies.forEach { (name, version) ->
            require(entries["node_modules/$name"]?.jsonObject?.get("version")?.jsonPrimitive?.content == version) {
                "Additional npm dependency $name is not pinned to $version"
            }
            if (recipe.familyId in setOf("lsp.typescript", LspMdxProfile.FAMILY) && name == "typescript" && version == LocalUbuntuTypeScriptServerPolicy.VERSION) {
                require(entries["node_modules/$name"]?.jsonObject?.get("integrity")?.jsonPrimitive?.content == LocalUbuntuTypeScriptServerPolicy.INTEGRITY) {
                    "Reviewed TypeScript server integrity changed in npm lock"
                }
            }
        }
        if (recipe.familyId == "plugin.codex") require(entries.containsKey("node_modules/@openai/codex")) { "Original OpenAI Codex CLI dependency is missing" }
        if (recipe.familyId == "plugin.claude-code") require(entries.containsKey("node_modules/@anthropic-ai/claude-agent-sdk")) { "Original Claude Agent SDK dependency is missing" }
        if (recipe.familyId == LocalUbuntuMusePolicy.FAMILY) require(
            entries.containsKey("node_modules/@muse-code/sdk")
        ) { "Muse adapter requires its integrity-locked official Meta SDK" }
        return lock
    }

    suspend fun tree(root: File): JsonObject {
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "npm package tree is missing" }
        val base = root.canonicalFile.toPath()
        val files = sortedMapOf<String, String>(); val links = sortedMapOf<String, String>()
        // One scratch buffer and digest per walk; every file is still read and hashed.
        val hashBuffer = ByteArray(64 * 1024)
        val fileDigest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        Files.walk(base).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                val path = iterator.next()
                if (path == base) continue
                val rel = base.relativize(path).toString().replace(File.separatorChar, '/')
                safeRelative(rel)
                if (rel == SEAL) continue
                when {
                    Files.isSymbolicLink(path) -> {
                        val target = Files.readSymbolicLink(path)
                        require(!target.isAbsolute && path.parent.resolve(target).normalize().startsWith(base) &&
                            path.toFile().canonicalFile.toPath().startsWith(base)) { "npm symlink escaped package storage" }
                        links[rel] = target.toString()
                    }
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> Unit
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                        bytes += Files.size(path); require(bytes <= 1_500_000_000L) { "npm package exceeds 1.5 GB" }
                        files[rel] = sha256(path.toFile(), fileDigest, hashBuffer)
                    }
                    else -> error("Unsupported npm package file type")
                }
                require(files.size + links.size <= 40_000) { "npm package contains too many files" }
            }
        }
        return buildJsonObject {
            put("schema", 1); put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) }))
            put("links", JsonObject(links.mapValues { JsonPrimitive(it.value) }))
        }
    }

    suspend fun verifyTree(root: File, expectedSealHash: String): Boolean {
        val seal = File(root, SEAL)
        if (!seal.isFile || Files.isSymbolicLink(seal.toPath()) || seal.length() > 8_000_000L) return false
        if (sha256(seal) != expectedSealHash) return false
        return Json.parseToJsonElement(seal.readText()).jsonObject == tree(root)
    }

    private fun safeRelative(value: String) {
        require(value.length in 1..4096 && !value.startsWith('/') && '\\' !in value &&
            value.none { it.code < 32 || it.code == 127 } && value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe npm package member" }
    }

    suspend fun sha256(file: File): String {
        return sha256(file, MessageDigest.getInstance("SHA-256"), ByteArray(64 * 1024))
    }

    private suspend fun sha256(file: File, digest: MessageDigest, buffer: ByteArray): String {
        digest.reset()
        file.inputStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val bytes = digest.digest()
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 15]
        }
        return String(hex)
    }
}
