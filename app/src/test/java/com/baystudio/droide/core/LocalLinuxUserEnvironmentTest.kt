package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LocalLinuxUserEnvironmentTest {
    @Test fun implicitManagedJavaDoesNotOverrideTheUsersSelectedPath() {
        val result = LocalLinuxUserEnvironment.guestEnvironment(
            mapOf("JAVA_HOME" to "/opt/droide/packages/old-jdk/tree", "OTHER_HOME" to "/opt/droide/other"), emptyMap())
        assertFalse(result.containsKey("JAVA_HOME"))
        assertEquals("/opt/droide/other", result["OTHER_HOME"])
        assertEquals("/root/.local", result["NPM_CONFIG_PREFIX"])
    }

    @Test fun explicitProjectJdkAndAndroidPackPreserveTheirMatchingJavaHome() {
        for (family in listOf("toolchain.jdk", LocalAndroidWorkstationPolicy.FAMILY)) {
            val result = LocalLinuxUserEnvironment.guestEnvironment(mapOf("JAVA_HOME" to "/owned/jdk"), mapOf(family to "17"))
            assertEquals(family, "/owned/jdk", result["JAVA_HOME"])
        }
    }

    @Test fun userSdkOverridesImplicitReceiptWhileAnExplicitProjectSelectionWins() {
        val managed = mapOf("ANDROID_HOME" to "/opt/droide/android-sdk", "ANDROID_SDK_ROOT" to "/opt/droide/android-sdk")
        val user = "/root/.local/share/android-sdk"
        val normal = LocalLinuxUserEnvironment.guestEnvironment(managed, emptyMap(), user)
        assertEquals(user, normal["ANDROID_HOME"])
        assertEquals(user, normal["ANDROID_SDK_ROOT"])
        for (family in listOf("sdk.android", "build.android-tools", "toolchain.android-ndk", "build.cmake", LocalAndroidWorkstationPolicy.FAMILY)) {
            val selected = LocalLinuxUserEnvironment.guestEnvironment(managed, mapOf(family to "36"), user)
            assertEquals(family, "/opt/droide/android-sdk", selected["ANDROID_HOME"])
        }
    }

    @Test fun incompleteUserSdkDoesNotHideTheInstalledManagedSdk() {
        val rootfs = Files.createTempDirectory("droide-user-sdk-").toFile()
        try {
            val sdk = rootfs.resolve("root/.local/share/android-sdk")
            check(sdk.resolve("platforms").mkdirs())
            assertNull(LocalLinuxUserEnvironment.installedUserAndroidSdkRoot(rootfs))
            check(sdk.resolve("build-tools").mkdirs())
            assertNull(LocalLinuxUserEnvironment.installedUserAndroidSdkRoot(rootfs))
            check(sdk.resolve("platforms/android-36").mkdirs()); check(sdk.resolve("build-tools/36.0.0").mkdirs())
            assertEquals("/root/.local/share/android-sdk", LocalLinuxUserEnvironment.installedUserAndroidSdkRoot(rootfs))
            val preferred = rootfs.resolve("root/Android/Sdk")
            check(preferred.resolve("platforms/android-36").mkdirs()); check(preferred.resolve("build-tools/36.0.0").mkdirs())
            assertEquals("/root/Android/Sdk", LocalLinuxUserEnvironment.installedUserAndroidSdkRoot(rootfs))
        } finally { PathSecurity.deleteTreeNoFollow(rootfs) }
    }

    @Test fun globalNpmBinIsDiscoverableOutsideTheManagedPayload() {
        val prefix = requireNotNull(LocalLinuxUserEnvironment.environment["NPM_CONFIG_PREFIX"])
        assertFalse(prefix.startsWith("/opt/droide/packages/"))
        assertTrue(LocalLinuxUserEnvironment.path(emptyList()).split(':').contains("$prefix/bin"))
    }

    @Test fun userSdkCommandsResolveInTheSameShellAheadOfImplicitManagedTools() {
        val home = Files.createTempDirectory("droide-sdk-path-").toFile()
        try {
            val managed = home.resolve("managed")
            script(managed.resolve("adb"), "old-managed-adb")
            script(home.resolve("Android/Sdk/platform-tools/adb"), "user-adb")
            script(home.resolve("Android/Sdk/cmdline-tools/latest/bin/sdkmanager"), "user-sdkmanager")
            val path = LocalLinuxUserEnvironment.path(listOf(managed.path)).replace("/root/", home.path + "/")
            assertEquals(0 to "user-adb", run("/bin/bash", "adb", path))
            assertEquals(0 to "user-sdkmanager", run("/bin/sh", "sdkmanager", path))
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }
    private fun script(file: File, output: String) {
        check(file.parentFile.isDirectory || file.parentFile.mkdirs())
        file.writeText("#!/bin/sh\nprintf '%s\\n' '$output'\n")
        check(file.setExecutable(true))
    }

    private fun run(shell: String, command: String, path: String): Pair<Int, String> {
        val child = ProcessBuilder(shell, "-c", command).apply { environment()["PATH"] = path }
            .redirectErrorStream(true).start()
        try {
            assertTrue("Shell probe timed out", child.waitFor(5, TimeUnit.SECONDS))
            return child.exitValue() to child.inputStream.bufferedReader().readText().trim()
        } finally { child.destroyForcibly() }
    }

    @Test fun alreadyOpenBashFindsOpenCodeInstalledAfterItsPathWasSet() {
        val home = Files.createTempDirectory("droide-late-cli-").toFile()
        try {
            val path = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", home.path + "/")
            // The child creates the executable after its environment is established, as the official
            // installer does. It never reloads .bashrc or recreates the shell.
            val child = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c",
                "command -v opencode >/dev/null && exit 71; " +
                    "mkdir -p \"\$HOME/.opencode/bin\"; " +
                    "printf '#!/bin/sh\\nprintf late-install\\n' > \"\$HOME/.opencode/bin/opencode\"; " +
                    "chmod 755 \"\$HOME/.opencode/bin/opencode\"; opencode")
                .apply { environment()["PATH"] = path; environment()["HOME"] = home.path }
                .redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                val output = child.inputStream.bufferedReader().readText().trim()
                assertEquals(output, 0, child.exitValue())
                assertEquals("late-install", output)
            } finally { child.destroyForcibly() }
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun allAgentCommandsResolveInInteractiveAndNoninteractiveShells() {
        val home = Files.createTempDirectory("droide-agent-path-").toFile()
        val commands = mapOf(
            "opencode" to ".opencode/bin", "codex" to ".npm-global/bin",
            "codex-acp" to ".npm-global/bin", "claude" to ".local/bin",
            "claude-agent-acp" to ".local/share/pnpm", "gemini" to ".npm-global/bin",
            "copilot" to ".bun/bin",
        )
        try {
            commands.forEach { (name, directory) -> script(home.resolve("$directory/$name"), name) }
            val path = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", home.path + "/")
            for (shell in listOf("/bin/bash", "/bin/sh")) {
                for (command in commands.keys) {
                    assertEquals(command, 0 to command, run(shell, "exec $command", path))
                }
            }
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun managedSelectionStillWinsAndFallsBackToExternalAfterRemoval() {
        val home = Files.createTempDirectory("droide-agent-priority-").toFile()
        try {
            val managed = home.resolve("managed")
            script(managed.resolve("opencode"), "managed")
            script(home.resolve(".opencode/bin/opencode"), "external")
            val path = LocalLinuxUserEnvironment.path(listOf(managed.path, managed.path), preferredManagedBins = listOf(managed.path)).replace("/root/", home.path + "/")
            assertEquals(1, path.split(':').count { it == managed.path })
            assertEquals(0 to "managed", run("/bin/bash", "opencode", path))
            check(managed.resolve("opencode").delete())
            assertEquals(0 to "external", run("/bin/bash", "opencode", path))
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun aUserUpdatedToolOverridesTheUnselectedManagedDefault() {
        val home = Files.createTempDirectory("droide-user-update-priority-").toFile()
        try {
            val managed = home.resolve("managed")
            script(managed.resolve("opencode"), "old-managed")
            script(home.resolve(".local/bin/opencode"), "new-user")
            val path = LocalLinuxUserEnvironment.path(listOf(managed.path)).replace("/root/", home.path + "/")
            assertEquals(0 to "new-user", run("/bin/bash", "opencode", path))
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun oneRunningBashDiscoversAnUpdatedUserToolWithoutReopeningTheTerminal() {
        val home = Files.createTempDirectory("droide-live-path-update-").toFile()
        try {
            val managed = home.resolve("managed")
            script(managed.resolve("opencode"), "old-managed")
            val target = home.resolve(".local/bin/opencode")
            val path = LocalLinuxUserEnvironment.path(listOf(managed.path)).replace("/root/", home.path + "/")
            val child = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-i").redirectErrorStream(true).apply {
                environment()["PATH"] = path
                environment().putAll(TerminalPromptPresentation.bashEnvironment())
            }.start()
            try {
                child.outputStream.bufferedWriter().use { writer ->
                    writer.write("opencode\n")
                    writer.write("mkdir -p '${target.parent}'; printf '#!/bin/sh\\nprintf new-user\\n' > '${target.path}'; chmod 755 '${target.path}'\n")
                    writer.write("opencode\nexit\n")
                }
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                val output = child.inputStream.bufferedReader().readText()
                assertTrue(output, output.contains("old-managed"))
                assertTrue(output, output.contains("new-user"))
                assertEquals(0, child.exitValue())
            } finally { child.destroyForcibly() }
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun discoveryDoesNotSourceUserShellStartupOrCreateManagedReceipts() {
        val home = Files.createTempDirectory("droide-agent-rc-").toFile()
        try {
            val rc = home.resolve(".bashrc").apply { writeText("echo unexpected; touch '${home.path}/rc-executed'\n") }
            script(home.resolve(".local/bin/gemini"), "gemini")
            val path = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", home.path + "/")
            assertEquals(0 to "gemini", run("/bin/bash", "gemini", path))
            assertFalse(home.resolve("rc-executed").exists())
            assertFalse(home.resolve("packages").exists())
            assertTrue(rc.readText().contains("unexpected"))
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun alpineShellFindsLatePublisherAndNpmInstallsWithoutStartingUbuntu() {
        val home = Files.createTempDirectory("droide-alpine-late-cli-").toFile()
        try {
            val path = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", home.path + "/")
            val prefix = requireNotNull(LocalLinuxUserEnvironment.environment["NPM_CONFIG_PREFIX"]).replace("/root/", home.path + "/")
            val child = ProcessBuilder("/bin/sh", "-c",
                "command -v opencode >/dev/null && exit 71; " +
                    "mkdir -p \"\$HOME/.opencode/bin\" \"\$NPM_CONFIG_PREFIX/bin\"; " +
                    "printf '#!/bin/sh\\nprintf alpine-opencode\\n' > \"\$HOME/.opencode/bin/opencode\"; " +
                    "printf '#!/bin/sh\\nprintf alpine-npm-global\\n' > \"\$NPM_CONFIG_PREFIX/bin/codex\"; " +
                    "chmod 755 \"\$HOME/.opencode/bin/opencode\" \"\$NPM_CONFIG_PREFIX/bin/codex\"; " +
                    "opencode; printf ':'; codex")
                .apply { environment()["PATH"] = path; environment()["HOME"] = home.path; environment()["NPM_CONFIG_PREFIX"] = prefix }
                .redirectErrorStream(true).start()
            try {
                assertTrue(child.waitFor(5, TimeUnit.SECONDS))
                val output = child.inputStream.bufferedReader().readText().trim()
                assertEquals(output, 0, child.exitValue())
                assertEquals("alpine-opencode:alpine-npm-global", output)
            } finally { child.destroyForcibly() }
            assertFalse(path.contains("/opt/droide/packages"))
            assertFalse(path.contains("/opt/droide/bin"))
        } finally { PathSecurity.deleteTreeNoFollow(home) }
    }

    @Test fun identicalUserBinNamesDoNotSharePayloadsBetweenGuests() {
        val root = Files.createTempDirectory("droide-guest-isolation-").toFile()
        try {
            val ubuntu = root.resolve("ubuntu/root").apply { check(mkdirs()) }
            val alpine = root.resolve("alpine/root").apply { check(mkdirs()) }
            script(ubuntu.resolve(".opencode/bin/opencode"), "ubuntu-payload")
            script(alpine.resolve(".opencode/bin/opencode"), "alpine-payload")
            for ((guest, expected) in listOf(ubuntu to "ubuntu-payload", alpine to "alpine-payload")) {
                val path = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", guest.path + "/")
                assertEquals(0 to expected, run("/bin/sh", "opencode", path))
            }
            assertTrue(alpine.resolve(".opencode/bin/opencode").delete())
            val alpinePath = LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", alpine.path + "/")
            assertEquals(127, run("/bin/sh", "opencode", alpinePath).first)
            assertEquals(0 to "ubuntu-payload", run("/bin/sh", "opencode", LocalLinuxUserEnvironment.path(emptyList()).replace("/root/", ubuntu.path + "/")))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}

object LocalLinuxUserEnvironmentHostProbe {
    @JvmStatic fun main(args: Array<String>) {
        println("LOCAL_LINUX_USER_ENVIRONMENT_JSON=" + buildJsonObject {
            put("PATH", LocalLinuxUserEnvironment.path(listOf("/opt/droide/bin")))
            LocalLinuxUserEnvironment.environment.forEach { (key, value) -> put(key, value) }
        })
    }
}
