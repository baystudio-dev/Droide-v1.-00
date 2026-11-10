package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class LocalUserToolchainEnvironmentTest {
    @get:Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Fixture : AutoCloseable {
        val parent = Files.createTempDirectory("toolchain-env-bridge-").toFile()
        val work = File(parent, "work").apply { mkdir() }
        val guest = File(parent, "guest").apply { mkdir() }
        val base = TerminalLaunchSpec(prefix = listOf("/test/proot", "-r", guest.path, "-w", work.path,
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/opt/droide/core-bin:/root/.local/bin:/usr/bin",
            "JAVA_HOME=/managed/jdk", "ANDROID_HOME=/managed/sdk"), shell = "/bin/bash")
        fun frame(script: String, values: Map<String, String>): UserToolchainEnvironmentCache.Capture {
            val nonce = Regex("droide-env-begin-([a-f0-9]{32})").find(script)!!.groupValues[1]
            return UserToolchainEnvironmentCache.Capture("banner\n" + "droide-env-begin-$nonce\u0000__DROIDE_CAPTURE_CWD=/root\u0000" +
                UserToolchainEnvironment.keys.joinToString("") { "$it=${values[it].orEmpty()}\u0000" } + "droide-env-end-$nonce\u0000", 0)
        }
        fun environment(spec: TerminalLaunchSpec): Map<String, String> = spec.prefix.dropWhile { it != "/usr/bin/env" }.drop(2)
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        override fun close() { parent.deleteRecursively(); LocalUserToolchainEnvironment.invalidate() }
    }

    @Test fun captureRunsAtHomeButReturnedToolSpecRetainsProjectAndNoninteractiveTransport() = runBlocking {
        Fixture().use { f ->
            val result = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap()) { capture, script ->
                assertEquals("/root", capture.prefix[capture.prefix.indexOf("-w") + 1])
                f.frame(script, mapOf("PATH" to "/user/bin:/usr/bin", "JAVA_HOME" to "/user/jdk"))
            }
            assertEquals(f.work.path, result.prefix[result.prefix.indexOf("-w") + 1])
            assertEquals(listOf("/bin/bash", "-c", "compiler --stdio"), result.shellCommand("compiler --stdio").takeLast(3))
            assertEquals("/user/jdk", f.environment(result)["JAVA_HOME"])
            assertEquals("/opt/droide/core-bin:/user/bin:/usr/bin:/root/.local/bin", f.environment(result)["PATH"])
        }
    }
    @Test fun managedExplicitJavaAndSdkAreNotOverriddenByRc() = runBlocking {
        Fixture().use { f ->
            val result = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work,
                mapOf(LocalUbuntuJdkPolicy.FAMILY to "17", "sdk.android" to "36")) { _, script ->
                f.frame(script, mapOf("JAVA_HOME" to "/user/jdk", "ANDROID_HOME" to "/user/sdk", "PATH" to "/user/bin"))
            }
            assertEquals("/managed/jdk", f.environment(result)["JAVA_HOME"])
            assertEquals("/managed/sdk", f.environment(result)["ANDROID_HOME"])
        }
    }
    @Test fun sameEnvironmentObservationIsReusedAcrossLookupAndLaunch() = runBlocking {
        Fixture().use { f ->
            var calls = 0
            val capture: suspend (TerminalLaunchSpec, String) -> UserToolchainEnvironmentCache.Capture = { _, script ->
                calls++; f.frame(script, mapOf("PATH" to "/user/bin"))
            }
            val first = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap(), capture)
            val second = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap(), capture)
            assertEquals(first, second); assertEquals(1, calls)
            LocalUserToolchainEnvironment.invalidate()
            LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap(), capture)
            assertEquals(2, calls)
        }
    }
    @Test fun failedShellKeepsExactOldLaunchAndDoesNotRepeatStartup() = runBlocking {
        Fixture().use { f ->
            var calls = 0
            repeat(2) {
                val result = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap()) { _, _ ->
                    calls++; UserToolchainEnvironmentCache.Capture("private diagnostic", 2)
                }
                assertSame(f.base, result)
            }
            assertEquals(1, calls)
        }
    }
    @Test fun forbiddenExportDoesNotEnterLaunchEnvironment() = runBlocking {
        Fixture().use { f ->
            val result = LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap()) { _, script ->
                val good = f.frame(script, mapOf("PATH" to "/user/bin"))
                good.copy(output = good.output.replace("JAVA_HOME=\u0000", "LD_PRELOAD=/bad\u0000"))
            }
            assertSame(f.base, result)
            assertFalse(f.environment(result).containsKey("LD_PRELOAD"))
        }
    }
    @Test fun replacedWorkspaceCannotReceiveCapturedEnvironment() = runBlocking {
        Fixture().use { f ->
            val failure = runCatching {
                LocalUserToolchainEnvironment.resolve(f.base, f.work, f.work, emptyMap()) { _, script ->
                    check(f.work.renameTo(File(f.parent, "old-work"))); check(f.work.mkdir())
                    f.frame(script, mapOf("PATH" to "/user/bin"))
                }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("moved or replaced"))
        }
    }
    @Test fun nativeHostAndSpecializedNonBashLaunchNeverRunUserStartup() = runBlocking {
        Fixture().use { f ->
            for (spec in listOf(TerminalLaunchSpec(), f.base.copy(shell = "/bin/sh"))) {
                val result = LocalUserToolchainEnvironment.resolve(spec, f.work, f.work, emptyMap()) { _, _ -> error("Unexpected startup") }
                assertSame(spec, result)
            }
        }
    }
}
