package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuRubyPolicyTest {
    private val recipe = LocalUbuntuRubyCatalog.rubyLsp

    @Test fun unreviewedVersionGemDigestClosureOrCommandIsRejected() {
        listOf(recipe.copy(version = "0.27.0"), recipe.copy(command = "other"), recipe.copy(gems = recipe.gems.drop(1)),
            recipe.copy(gems = recipe.gems.map { it.copy(sha256 = "a".repeat(64)) })).forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuRubyPolicy.requireReviewed(it) }
        }
        LocalUbuntuRubyPolicy.requireReviewed(recipe)
        assertEquals(6, recipe.gems.size)
        assertEquals(setOf("prism", "rbs"), LocalUbuntuRubyPolicy.nativePaths.map { it.substringBefore('-') }.toSet())
    }

    @Test fun artifactsCannotResolveToAnotherPlatformOrAnUnboundedDownload() {
        recipe.gems.forEach { gem ->
            val artifact = gem.artifact()
            artifact.validateStructure()
            assertEquals("https://rubygems.org/downloads/${gem.name}-${gem.version}.gem", artifact.url)
            assertEquals(gem.sizeBytes, artifact.expectedBytes)
            assertEquals(gem.sizeBytes, artifact.maxBytes)
            assertFalse(artifact.fileName.contains("java.gem"))
        }
    }

    @Test fun contaminatedProjectEnvironmentCannotSelectAnotherRubyBundle() {
        val root = "/opt/droide/packages/lsp.ruby-lsp/0.26.11/arm64-v8a"
        val process = ProcessBuilder(LocalUbuntuRubyPolicy.environment(root) + listOf("/usr/bin/env")).apply {
            environment().putAll(mapOf("GEM_HOME" to "/untrusted", "GEM_PATH" to "/untrusted", "RUBYOPT" to "-runtrusted",
                "RUBYLIB" to "/untrusted", "BUNDLE_GEMFILE" to "/workspace/Gemfile", "BUNDLE_WITHOUT" to "default",
                "BUNDLE_DEPLOYMENT" to "true", "BUNDLE_FORCE_RUBY_PLATFORM" to "false", "BUNDLER_VERSION" to "other"))
        }.start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val environment = process.inputStream.bufferedReader().readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(0, process.exitValue())
        for (key in listOf("GEM_HOME", "GEM_PATH", "RUBYOPT", "RUBYLIB", "BUNDLE_WITHOUT", "BUNDLE_DEPLOYMENT", "BUNDLER_VERSION"))
            assertFalse(key, key in environment)
        assertEquals("$root/payload/ruby/Gemfile", environment["BUNDLE_GEMFILE"])
        assertEquals("$root/payload/ruby/gems", environment["BUNDLE_PATH"])
        assertEquals("true", environment["BUNDLE_FROZEN"])
        assertEquals("true", environment["BUNDLE_DISABLE_SHARED_GEMS"])
        assertEquals("/usr/bin:/bin", environment["PATH"])
    }

    @Test fun languageServerHealthUsesTheFullProtocolAndOriginalExecutable() {
        val root = "/opt/droide/packages/lsp.ruby-lsp/0.26.11/arm64-v8a"
        val health = LocalUbuntuRubyPolicy.health(root)
        assertEquals(listOf("/usr/bin/node", "$root/payload/${LocalUbuntuNpmLspHealth.FILE_NAME}"), health.take(2))
        assertEquals(listOf("/usr/bin/bundle", "exec", "ruby-lsp"), health.takeLast(3))
        assertFalse("--version" in health)
        assertFalse(health.any { it == "install" || it == "update" })
        assertTrue(LocalUbuntuRubyPolicy.guestWrapper(root).contains("\"\$#\" -eq 1"))
    }

    @Test fun incompleteBundleChangedLockAndSubstitutedProtocolProbeCannotBeVerified() = runBlocking {
        val root = Files.createTempDirectory("droide-ruby-policy-").toFile()
        fun write(path: String, value: String) = File(root, path).apply { parentFile?.mkdirs(); writeText(value) }
        try {
            write("payload/ruby/Gemfile", LocalUbuntuRubyPolicy.gemfile)
            write("payload/ruby/Gemfile.lock", LocalUbuntuRubyPolicy.lock)
            write("RECIPE_LOCK.json", LocalUbuntuRubyPolicy.review(recipe).toString())
            val probe = write("payload/${LocalUbuntuNpmLspHealth.FILE_NAME}", LocalUbuntuNpmLspHealth.script)
            assertFalse(LocalUbuntuRubyPolicy.verifyFiles(root, recipe))
            write("payload/ruby/Gemfile.lock", LocalUbuntuRubyPolicy.lock.replace("rbs (4.1.3)", "rbs (3.9.0)"))
            assertFalse(LocalUbuntuRubyPolicy.verifyFiles(root, recipe))
            write("payload/ruby/Gemfile.lock", LocalUbuntuRubyPolicy.lock)
            probe.writeText("console.log('OK');\n")
            assertFalse(LocalUbuntuRubyPolicy.verifyFiles(root, recipe))
        } finally { root.deleteRecursively() }
    }

    @Test fun recipeReceiptsCannotChangeScopeDependencyLockOrInstallRoot() {
        val record = ManagedPackageRecord(recipe.familyId, recipe.version, ExecutionScope.EDITOR.name,
            "/owned/packages/${recipe.familyId}/${recipe.version}/arm64-v8a", 1,
            artifactSha256 = LocalUbuntuRubyPolicy.lockSha256, abi = "arm64-v8a")
        assertFalse(LocalUbuntuRubyPolicy.verifyIntegration(record, "/owned"))
        assertFalse(LocalUbuntuRubyPolicy.verifyIntegration(record.copy(scope = ExecutionScope.LOCAL_LINUX_ARM64.name), "/owned"))
    }
}
