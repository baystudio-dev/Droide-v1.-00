package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UserToolchainEnvironmentTest {
    private val nonce = "a".repeat(32)
    private fun frame(values: Map<String, String> = emptyMap(), token: String = nonce, cwd: String = "/work"): String =
        "droide-env-begin-$token\u0000__DROIDE_CAPTURE_CWD=$cwd\u0000" + UserToolchainEnvironment.keys.joinToString("") { "$it=${values[it].orEmpty()}\u0000" } +
            "droide-env-end-$token\u0000"
    private fun capture(script: String, values: Map<String, String> = emptyMap()): UserToolchainEnvironmentCache.Capture {
        val token = Regex("droide-env-begin-([a-f0-9]{32})").find(script)!!.groupValues[1]
        return UserToolchainEnvironmentCache.Capture(frame(values, token), 0)
    }
    private fun invalid(output: String) {
        try { UserToolchainEnvironment.parse(output, nonce, "/work"); fail("Invalid frame accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun noiseIsNotProtocolAndOnlyAllowlistedValuesSurvive() {
        val actual = UserToolchainEnvironment.parse("rc banner\n" + frame(mapOf("PATH" to "/custom/bin:/usr/bin", "JAVA_HOME" to "/java")) + "after\n", nonce, "/work")
        assertEquals(mapOf("PATH" to "/custom/bin:/usr/bin", "JAVA_HOME" to "/java"), actual)
    }
    @Test fun relativePathIsPinnedToObservedDirectory() {
        val values = UserToolchainEnvironment.parse(frame(mapOf("PATH" to "./node_modules/.bin::../sdk/bin:/usr/bin"), cwd = "/work/project"), nonce, "/work")
        assertEquals("/work/project/node_modules/.bin:/work/project:/work/sdk/bin:/usr/bin", values["PATH"])
    }
    @Test fun missingEndOrDuplicateFrameIsRejected() {
        invalid(frame().substringBefore("droide-env-end")); invalid(frame() + frame())
    }
    @Test fun missingOrDuplicateKeyIsRejected() {
        invalid(frame().replace("JAVA_HOME=\u0000", ""))
        invalid(frame().replace("JAVA_HOME=\u0000", "JAVA_HOME=\u0000JAVA_HOME=\u0000"))
    }
    @Test fun unexpectedAuthorityKeyIsRejected() {
        invalid(frame().replace("JAVA_HOME=\u0000", "LD_PRELOAD=/bad\u0000"))
    }
    @Test fun malformedOrNonAbsoluteHomeIsRejected() {
        invalid(frame(mapOf("JAVA_HOME" to "../java")))
        invalid(frame(mapOf("JAVA_HOME" to "/java\ncommand")))
    }
    @Test fun oversizedCaptureOrValueIsRejected() {
        invalid("x".repeat(UserToolchainEnvironment.MAX_CAPTURE_CHARS + 1))
        invalid(frame(mapOf("PATH" to "/" + "a".repeat(UserToolchainEnvironment.MAX_VALUE_CHARS))))
    }
    @Test fun explicitSelectionsAndCoreBinaryStayAheadOfUserPaths() {
        val merged = UserToolchainEnvironment.merge(mapOf("PATH" to "/user/bin:/usr/bin", "JAVA_HOME" to "/user/java", "ANDROID_HOME" to "/user/sdk"),
            mapOf("PATH" to "/opt/droide/core-bin:/managed/bin:/usr/bin", "JAVA_HOME" to "/managed/java"),
            setOf("JAVA_HOME"), listOf("/opt/droide/core-bin", "/managed/bin"))
        assertFalse(merged.containsKey("JAVA_HOME"))
        assertEquals("/user/sdk", merged["ANDROID_HOME"])
        assertEquals("/opt/droide/core-bin:/managed/bin:/user/bin:/usr/bin", merged["PATH"])
    }
    @Test fun absentUserValuesDoNotDeleteBaseEnvironment() {
        assertTrue(UserToolchainEnvironment.merge(emptyMap(), mapOf("JAVA_HOME" to "/jdk"), emptySet(), emptyList()).isEmpty())
    }
    @Test fun captureScriptDoesNotReadGenericSecrets() {
        val script = UserToolchainEnvironment.script(nonce)
        assertFalse(script.contains("env -0")); assertFalse(script.contains("API_KEY")); assertFalse(script.contains("LD_PRELOAD"))
        assertTrue(script.contains("PATH=\${PATH-}"))
    }
    @Test fun observationFailureRetainsLegacyAndroidLoginProbe() {
        assertEquals(listOf("/bin/sh", "-lc", "probe"), UserToolchainEnvironment.probeArgv(false, "/bin/bash", "probe"))
        assertEquals(listOf("/bin/bash", "-c", "probe"), UserToolchainEnvironment.probeArgv(true, "/bin/bash", "probe"))
        val capture = UserToolchainEnvironment.captureArgv("probe")
        assertEquals(listOf("/bin/sh", "-lc"), capture.take(2))
        assertTrue(capture[2].contains("--rcfile /root/.bashrc")); assertEquals("probe", capture.last())
    }
    @Test fun profileOnlySdkIsPreservedAndBashRcCanOverrideItsUserValues() {
        val root = Files.createTempDirectory("droide-profile-env-").toFile()
        try {
            val profile = File(root, "profile").apply { writeText("export JAVA_HOME=/profile/jdk; ANDROID_SDK_ROOT=/profile/sdk; export PATH=/profile/bin:\"\$PATH\"\n") }
            val rc = File(root, "bashrc").apply { writeText("export JAVA_HOME=/bash/jdk\n") }
            // The fixture substitutes private profile/rc paths rather than modifying
            // the host's real /etc/profile or user's home configuration.
            val process = ProcessBuilder("/bin/sh", "-c",
                ". \"\$1\"; " + UserToolchainEnvironment.exportDefinedLoginVariables() + "; exec /bin/bash --noprofile --rcfile \"\$2\" -ic \"\$3\"", "profile-fixture",
                profile.path, rc.path, UserToolchainEnvironment.script(nonce)).redirectErrorStream(true)
                .apply { environment().clear(); environment().putAll(mapOf("PATH" to "/usr/bin:/bin", "HOME" to root.path)) }.start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue())
            val values = UserToolchainEnvironment.parse(process.inputStream.readBytes().toString(Charsets.UTF_8), nonce, root.path)
            assertEquals("/profile/sdk", values["ANDROID_SDK_ROOT"]); assertEquals("/bash/jdk", values["JAVA_HOME"])
            val bins = values.getValue("PATH").split(':')
            // System Bash rc may prepend legitimate environment-manager bins.
            assertTrue(bins.toString(), "/profile/bin" in bins)
            assertTrue(bins.toString(), bins.indexOf("/profile/bin") < bins.indexOf("/usr/bin"))
        } finally { root.deleteRecursively() }
    }
    @Test fun concurrentConsumersResolveOnlyOnce() = runBlocking {
        val cache = UserToolchainEnvironmentCache()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val jobs = (1..8).map { async {
            cache.resolve("same", "/work") { script -> calls++; gate.await(); capture(script, mapOf("PATH" to "/sdk/bin")) }
        } }
        yield(); gate.complete(Unit)
        jobs.awaitAll().forEach { assertEquals("/sdk/bin", it.environment["PATH"]) }
        assertEquals(1, calls)
    }
    @Test fun failureIsCachedUntilInvalidation() = runBlocking {
        val cache = UserToolchainEnvironmentCache(); var calls = 0
        repeat(3) { assertEquals(UserToolchainEnvironmentCache.Fallback.SHELL_FAILED,
            cache.resolve("same", "/work") { calls++; UserToolchainEnvironmentCache.Capture("private error", 1) }.fallback) }
        assertEquals(1, calls); cache.invalidate()
        assertNull(cache.resolve("same", "/work") { script -> calls++; capture(script) }.fallback)
        assertEquals(2, calls)
    }
    @Test fun ownTimeoutFallsBackButParentCancellationEscapes() = runBlocking {
        val cache = UserToolchainEnvironmentCache(timeoutMs = 20)
        assertEquals(UserToolchainEnvironmentCache.Fallback.TIMED_OUT, cache.resolve("hang", "/work") { awaitCancellation() }.fallback)
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.resolve("cancel", "/work") { started.complete(Unit); awaitCancellation() } }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(cache.resolve("cancel", "/work") { capture(it) }.fallback)
    }
    @Test fun invalidationDuringCaptureDoesNotPublishStaleSnapshot() = runBlocking {
        val cache = UserToolchainEnvironmentCache(); val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val old = async { cache.resolve("same", "/work") { script -> started.complete(Unit); gate.await(); capture(script, mapOf("PATH" to "/old")) } }
        started.await(); cache.invalidate(); gate.complete(Unit)
        assertEquals(UserToolchainEnvironmentCache.Fallback.INVALIDATED, old.await().fallback)
        assertEquals("/new", cache.resolve("same", "/work") { capture(it, mapOf("PATH" to "/new")) }.environment["PATH"])
    }
    @Test fun cacheDoesNotCrossEnvironmentKeysAndEvictsBoundedly() = runBlocking {
        val cache = UserToolchainEnvironmentCache(maxEntries = 1); var calls = 0
        for (key in listOf("a", "b", "a")) {
            assertEquals("/$key", cache.resolve(key, "/work") { calls++; capture(it, mapOf("PATH" to "/$key")) }.environment["PATH"])
        }
        assertEquals(3, calls)
    }
    @Test fun observationExpiresWithoutRunningStartupForEveryCommand() = runBlocking {
        var now = 0L; var calls = 0
        val cache = UserToolchainEnvironmentCache(maxAgeMs = 30_000, nanoTime = { now })
        suspend fun resolve() = cache.resolve("same", "/work") { calls++; capture(it) }
        resolve(); now = 29_999_000_000L; resolve(); assertEquals(1, calls)
        now = 30_000_000_000L; resolve(); assertEquals(2, calls)
    }
    @Test fun actualBashObservationMakesCustomCompilerAvailableWithoutInteractiveToolTransport() {
        val directory = Files.createTempDirectory("droide-user-env-").toFile()
        try {
            val bin = File(directory, "custom sdk/bin").apply { mkdirs() }
            File(bin, "droide-fixture-compiler").apply { writeText("#!/bin/sh\nprintf compiled"); setExecutable(true) }
            val rc = File(directory, "test.bashrc").apply { writeText("printf 'startup banner\\n'; export PATH='${bin.path}':\"\$PATH\"; export API_KEY='not-for-capture'\n") }
            val probe = ProcessBuilder("/bin/bash", "--noprofile", "--rcfile", rc.path, "-ic", UserToolchainEnvironment.script(nonce))
                .redirectErrorStream(true).apply { environment().clear(); environment().putAll(mapOf("HOME" to directory.path, "PATH" to "/usr/bin:/bin")) }.start()
            assertTrue(probe.waitFor(5, TimeUnit.SECONDS)); val output = probe.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, probe.exitValue()); assertFalse(output.contains("not-for-capture"))
            val values = UserToolchainEnvironment.parse(output, nonce, directory.path)
            val process = ProcessBuilder("/bin/bash", "-c", "droide-fixture-compiler").redirectErrorStream(true)
                .apply { environment().clear(); environment().putAll(values) }.start()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals("compiled", process.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals(0, process.exitValue())
        } finally { directory.deleteRecursively() }
    }
}
