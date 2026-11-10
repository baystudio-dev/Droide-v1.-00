package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalAndroidToolchainDiscoveryTest {
    private val requirements = AndroidToolchainRequirements(compileSdk = 36, buildToolsVersion = "36.0.0")

    @Test fun cancellationEscapesResolution() = runBlocking {
        val cancelled = CancellationException("User stopped discovery")
        val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> throw cancelled }
        try {
            discovery.resolve(requirements)
            error("Cancellation was converted to a toolchain failure")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun projectRequirementsCarryAgpCompatibilityWithoutSeparateArgument() = runBlocking {
        val output = """JAVA_HOME=/opt/jdk
JAVA_VERSION=11
SDK_ROOT=/opt/sdk
AAPT2=/opt/sdk/build-tools/36.0.0/aapt2
ZIPALIGN=/opt/sdk/build-tools/36.0.0/zipalign
APKSIGNER=/opt/sdk/build-tools/36.0.0/apksigner
SDKS=36
BUILD_TOOLS=36.0.0
NDKS=
CMAKES=
"""
        val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> ExecResult(0, output, false) }
        val projectRequirements = requirements.copy(androidGradlePluginVersions = setOf("8.13.2"))
        val result = discovery.resolve(projectRequirements)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("requires JDK 17"))
        // The retained legacy argument cannot erase requirements already attached to the project.
        assertNull(discovery.resolve(projectRequirements, emptySet()).snapshot)
    }

    @Test fun ordinaryProbeFailureKeepsDiagnostic() = runBlocking {
        val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ -> error("Guest launcher failed") }
        val result = discovery.resolve(requirements)
        assertNull(result.snapshot)
        assertEquals("Guest launcher failed", result.failure)
    }

    @Test fun stoppingActiveProbeDoesNotReturnAnIncompleteToolchain() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var returned = false
        val job = launch {
            val discovery = LocalAndroidToolchainDiscovery(File(".")) { _, _ ->
                started.complete(Unit)
                awaitCancellation()
            }
            discovery.resolve(requirements)
            returned = true
        }
        started.await()
        job.cancelAndJoin()
        assertFalse(returned)
    }

    @Test fun completeJdkIsAcceptedWithPathsContainingSpaces() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val result = resolveGuest(root, javaHome)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
        assertEquals(17, result.snapshot?.javaVersion)
    }

    @Test fun runtimeWithoutCompilerIsRejected() = withGuest { root, javaHome, _ ->
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    @Test fun compilerFromAnotherPathCannotRescueIncompleteJdk() = withGuest { root, javaHome, _ ->
        val otherJdk = File(root, "other-jdk")
        compiler(otherJdk, "17.0.20")
        val result = resolveGuest(root, javaHome, extraPath = File(otherJdk, "bin").path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    @Test fun compilerVersionMismatchIsRejected() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "21.0.1")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("versions do not match"))
    }

    @Test fun compilerThatCannotExecuteIsRejected() = withGuest { root, javaHome, _ ->
        executable(File(javaHome, "bin/javac"), "exit 126")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler cannot execute"))
    }

    @Test fun java8JreLayoutResolvesToEnclosingJdk() = withGuest(javaVersion = "1.8") { root, javaHome, javaBin ->
        val jreJava = File(javaHome, "jre/bin/java").apply { parentFile.mkdirs() }
        check(javaBin.renameTo(jreJava))
        runtime(jreJava, File(javaHome, "jre"), "1.8")
        Files.createSymbolicLink(javaBin.toPath(), jreJava.toPath())
        compiler(javaHome, "1.8.0_472")
        val result = resolveGuest(root, javaHome)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
        assertEquals(8, result.snapshot?.javaVersion)
    }

    @Test fun explicitJavaHomeOverridesJavaFoundOnPath() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val selected = File(root, "selected-jdk")
        runtime(File(selected, "bin/java"), selected, "21")
        compiler(selected, "21.0.1")
        val result = resolveGuest(root, javaHome, selectedHome = selected.path)
        assertNull(result.failure)
        assertEquals(selected.canonicalPath, result.snapshot?.javaHome)
        assertEquals(21, result.snapshot?.javaVersion)
    }

    @Test fun updatedUserJavaIsUsedWhenAnOldManagedHomeIsOnlyAnImplicitDefault() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val updated = File(root, "updated-user-jdk")
        runtime(File(updated, "bin/java"), updated, "21")
        compiler(updated, "21.0.1")
        val environment = LocalLinuxUserEnvironment.guestEnvironment(mapOf("JAVA_HOME" to javaHome.path), emptyMap())
        val result = resolveGuest(root, updated, selectedHome = environment["JAVA_HOME"])
        assertNull(result.failure)
        assertEquals(updated.canonicalPath, result.snapshot?.javaHome)
        assertEquals(21, result.snapshot?.javaVersion)
    }

    @Test fun userSdkFallbackIsSearchedBeforeTheImplicitManagedRoot() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val userSdk = File(root, "root/.local/share/android-sdk")
        check(File(root, "sdk").copyRecursively(userSdk))
        listOf("aapt2", "apksigner", "zipalign").forEach { check(File(userSdk, "build-tools/36.0.0/$it").setExecutable(true)) }
        val result = resolveGuest(root, javaHome, implicitSdkFallbackRoot = root)
        assertNull(result.failure)
        assertEquals(userSdk.canonicalPath, result.snapshot?.sdkRoot)
    }

    @Test fun incompleteUserSdkFallsBackWithoutHidingTheInstalledManagedRoot() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val userSdk = File(root, "root/.local/share/android-sdk")
        check(File(userSdk, "platforms").mkdirs()); check(File(userSdk, "build-tools").mkdirs())
        val result = resolveGuest(root, javaHome, implicitSdkFallbackRoot = root)
        assertNull(result.failure)
        assertEquals(File(root, "sdk").canonicalPath, result.snapshot?.sdkRoot)
    }

    @Test fun invalidSelectedHomeDoesNotFallBackToPath() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val result = resolveGuest(root, javaHome, selectedHome = File(root, "missing").path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("Selected JAVA_HOME"))
    }

    @Test fun selectedRuntimeWithoutCompilerDoesNotUseOtherJdkCompiler() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val selected = File(root, "selected-jre")
        runtime(File(selected, "bin/java"), selected, "17")
        val result = resolveGuest(root, javaHome, selectedHome = selected.path)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("compiler is missing"))
    }

    @Test fun managedPathWrapperReportsTheActualJdkHome() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        val wrapperHome = File(root, "managed payload")
        executable(File(wrapperHome, "bin/java"), "exec ${quote(javaBin.path)} \"\$@\"")
        compiler(wrapperHome, "17.0.20")
        val result = resolveGuest(root, wrapperHome)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
    }

    @Test fun selectedWrapperAlsoResolvesToTheActualJdkHome() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        val wrapperHome = File(root, "selected wrapper")
        executable(File(wrapperHome, "bin/java"), "exec ${quote(javaBin.path)} \"\$@\"")
        val result = resolveGuest(root, javaHome, selectedHome = wrapperHome.path)
        assertNull(result.failure)
        assertEquals(javaHome.canonicalPath, result.snapshot?.javaHome)
    }

    @Test fun runtimeFailureCannotBeHiddenByValidLookingSettings() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        runtime(javaBin, javaHome, "17", "exit 126")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("JDK executable failed"))
    }

    @Test fun missingRuntimeHomeCannotFallBackToTheWrapperDirectory() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        executable(javaBin, "printf '    java.specification.version = 17\\n'")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("unique JDK home"))
    }

    @Test fun ambiguousRuntimeHomeIsRejected() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        runtime(javaBin, javaHome, "17", "printf '%s\\n' ${quote("    java.home = /other-jdk")}")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("unique JDK home"))
    }

    @Test fun ambiguousRuntimeVersionIsRejected() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        runtime(javaBin, javaHome, "17", "printf '    java.specification.version = 21\\n'")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("unique JDK version"))
    }

    @Test fun relativeRuntimeHomeIsRejected() = withGuest { root, javaHome, javaBin ->
        compiler(javaHome, "17.0.20")
        runtime(javaBin, File("relative-jdk"), "17")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("non-absolute JDK home"))
    }

    @Test fun zipalignDoesNotNeedAnUnsupportedHelpOption() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        val result = resolveGuest(root, javaHome)
        assertNull(result.failure)
        assertTrue(File(root, "temp").listFiles().orEmpty().isEmpty())
    }

    @Test fun failingZipAlignmentIsRejectedAndScratchFilesAreRemoved() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        executable(File(root, "sdk/build-tools/36.0.0/zipalign"), "exit 2")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("align and verify"))
        assertTrue(File(root, "temp").listFiles().orEmpty().isEmpty())
    }

    @Test fun zipalignThatProducesNoArchiveCannotPassItsHealthCheck() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        executable(File(root, "sdk/build-tools/36.0.0/zipalign"), "exit 0")
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("align and verify"))
    }

    @Test fun failedVerificationIsRejectedAndScratchFilesAreRemoved() = withGuest { root, javaHome, _ ->
        compiler(javaHome, "17.0.20")
        executable(File(root, "sdk/build-tools/36.0.0/zipalign"), """
            case "${'$'}1" in
              -f) while [ "${'$'}#" -gt 2 ]; do shift; done; cp -- "${'$'}1" "${'$'}2";;
              -c) exit 1;;
              *) exit 2;;
            esac
        """.trimIndent())
        val result = resolveGuest(root, javaHome)
        assertNull(result.snapshot)
        assertTrue(result.failure.orEmpty().contains("align and verify"))
        assertTrue(File(root, "temp").listFiles().orEmpty().isEmpty())
    }

    private fun resolveGuest(root: File, javaHome: File, extraPath: String = "", selectedHome: String? = null,
        implicitSdkFallbackRoot: File? = null) = runBlocking {
        LocalAndroidToolchainDiscovery(root) { script, _ ->
            val actualScript = if (implicitSdkFallbackRoot == null) script else script
                .replace("'/root/", "'${implicitSdkFallbackRoot.path}/root/")
                .replace("/opt/droide/android-sdk", File(root, "sdk").path.let(::quote))
            val process = ProcessBuilder("/bin/sh", "-c", actualScript).redirectErrorStream(true).apply {
                environment().remove("JAVA_HOME")
                if (selectedHome != null) environment()["JAVA_HOME"] = selectedHome
                environment()["PATH"] = listOf(File(javaHome, "bin").path, extraPath, "/usr/bin", "/bin")
                    .filter(String::isNotEmpty).joinToString(":")
                if (implicitSdkFallbackRoot == null) {
                    environment()["ANDROID_HOME"] = File(root, "sdk").path
                    environment()["ANDROID_SDK_ROOT"] = File(root, "sdk").path
                } else {
                    environment().remove("ANDROID_HOME"); environment().remove("ANDROID_SDK_ROOT")
                }
                environment()["TMPDIR"] = File(root, "temp").apply { mkdirs() }.path
            }.start()
            try {
                val completed = process.waitFor(5, TimeUnit.SECONDS)
                if (!completed) process.destroyForcibly()
                ExecResult(if (completed) process.exitValue() else -1, process.inputStream.bufferedReader().readText(), !completed)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.resolve(requirements)
    }

    private fun withGuest(javaVersion: String = "17", block: (File, File, File) -> Unit) {
        assumeTrue(File("/bin/sh").isFile)
        val root = Files.createTempDirectory("droide guest ").toFile()
        try {
            val javaHome = File(root, "jdk home")
            val javaBin = File(javaHome, "bin/java")
            runtime(javaBin, javaHome, javaVersion)
            File(root, "sdk/platforms/android-36").mkdirs()
            val tools = File(root, "sdk/build-tools/36.0.0")
            listOf("aapt2", "apksigner").forEach { executable(File(tools, it), "exit 0") }
            executable(File(tools, "zipalign"), """
                case "${'$'}1" in
                  -f) while [ "${'$'}#" -gt 2 ]; do shift; done; cp -- "${'$'}1" "${'$'}2";;
                  -c) test -s "${'$'}5";;
                  *) exit 2;;
                esac
            """.trimIndent())
            block(root, javaHome, javaBin)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun compiler(javaHome: File, version: String) = executable(File(javaHome, "bin/javac"), "printf 'javac $version\\n'")

    private fun runtime(file: File, home: File, version: String, extra: String = "") = executable(
        file,
        "printf '%s\\n' ${quote("    java.home = ${home.path}")} ${quote("    java.specification.version = $version")}\n$extra",
    )

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun executable(file: File, body: String) {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n$body\n")
        check(file.setExecutable(true))
    }
}
