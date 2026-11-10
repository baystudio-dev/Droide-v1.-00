package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuNpmLockReuseTest {
    private val integrity = "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { 9 })
    private val recipe = WorkstationInstallRecipe("lsp.typescript", "6.0.1", "typescript-language-server", "6.0.1",
        "typescript-language-server", 22, "https://github.com/typescript-language-server/typescript-language-server",
        registryIntegrity = integrity, additionalDependencies = mapOf("typescript" to "6.0.3"))
    private fun lock(companion: String) = buildJsonObject {
        put("lockfileVersion", 3)
        put("packages", buildJsonObject {
            put("", buildJsonObject {})
            for ((name, version) in mapOf("typescript-language-server" to "6.0.1", "typescript" to companion))
                put("node_modules/$name", buildJsonObject {
                    put("version", version); put("integrity", if (name == "typescript" && version == "6.0.3") LocalUbuntuTypeScriptServerPolicy.INTEGRITY else integrity)
                    put("resolved", "https://registry.npmjs.org/$name/-/$name-$version.tgz")
                })
        })
    }.toString()
    private suspend fun fixture(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("droide-lock-reuse").toFile()
        try { block(File(root, "package-lock.json")) } finally { root.deleteRecursively() }
    }
    @Test fun matchingAuthenticatedLockCanBeReused() = runBlocking { fixture { file ->
        file.writeText(lock("6.0.3"))
        assertEquals(file, LocalUbuntuNpmPolicy.reusableLock(file, LocalUbuntuNpmPolicy.sha256(file), recipe))
    } }
    @Test fun anOldCompanionVersionCannotBeReusedForTheCorrectedRecipe() = runBlocking { fixture { file ->
        val old = lock("7.0.2"); file.writeText(old)
        assertNull(LocalUbuntuNpmPolicy.reusableLock(file, LocalUbuntuNpmPolicy.sha256(file), recipe))
        assertEquals(old, file.readText())
    } }
    @Test fun alteredOrLinkedLocksCannotBeReused() = runBlocking { fixture { file ->
        file.writeText(lock("6.0.3")); val sha = LocalUbuntuNpmPolicy.sha256(file)
        file.appendText(" "); assertNull(LocalUbuntuNpmPolicy.reusableLock(file, sha, recipe))
        val target = File(file.parentFile, "other.json"); assertTrue(file.renameTo(target))
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertNull(LocalUbuntuNpmPolicy.reusableLock(file, LocalUbuntuNpmPolicy.sha256(target), recipe))
    } }
    @Test fun changedCompanionIntegrityIsRejectedEvenWhenItsVersionMatches() = runBlocking { fixture { file ->
        val lock = Json.parseToJsonElement(lock("6.0.3")).jsonObject
        val packages = lock.getValue("packages").jsonObject
        val companion = packages.getValue("node_modules/typescript").jsonObject
        val changed = JsonObject(lock + ("packages" to JsonObject(packages +
            ("node_modules/typescript" to JsonObject(companion + ("integrity" to JsonPrimitive(integrity)))))))
        file.writeText(changed.toString())
        assertNull(LocalUbuntuNpmPolicy.reusableLock(file, LocalUbuntuNpmPolicy.sha256(file), recipe))
    } }
}
