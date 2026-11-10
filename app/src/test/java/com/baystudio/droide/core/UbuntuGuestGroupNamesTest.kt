package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Runs the actual generated policy only with fail-closed id/getent/groupadd stand-ins. */

class UbuntuGuestGroupNamesTest {
    private class Fixture {
        val base = Files.createTempDirectory("ubuntu-group-names-").toFile()
        val rootfs = File(base, "guest/rootfs").apply { mkdirs() }
        val etc = File(rootfs, "etc").apply { mkdir() }
        val group = File(etc, "group").apply { writeText("root:x:0:\nteam:x:777:alice,bob\n") }
        val shadow = File(etc, "gshadow").apply { writeText("root:*::\nteam:!::alice,bob\n") }
        val ids = File(base, "ids").apply { writeText("0 " + UbuntuStartupGroupFixture.SCREENSHOT_GIDS.joinToString(" ") + "\n") }
        val calls = File(base, "adds")
        val diagnostics = mutableListOf<String>()
        var runs = 0
        var now = 0L
        val repairer = UbuntuGuestGroupNames { now }
        init {
            File(etc, ".pwd.lock").writeText("")
            File(etc, "nsswitch.conf").writeText("passwd: files\ngroup: files\nshadow: files\ngshadow: files\n")
            File(base, "INERT_FIXTURE").writeText("never-real-account-commands")
            tool("usr/bin/id", """
                [ "${'$'}#" -eq 1 ] && [ "${'$'}1" = -G ] || exit 96
                while IFS= read -r line; do printf '%s\n' "${'$'}line"; done < "${'$'}FIXTURE_BASE/ids"
            """)
            tool("usr/bin/getent", """
                [ "${'$'}#" -eq 4 ] && [ "${'$'}1" = -s ] && [ "${'$'}2" = files ] || exit 96
                case "${'$'}3" in group|gshadow) ;; *) exit 96;; esac
                while IFS=: read -r name password gid members; do
                    if [ "${'$'}4" = "${'$'}name" ] || { [ "${'$'}3" = group ] && [ "${'$'}4" = "${'$'}gid" ]; }; then
                        printf '%s:%s:%s:%s\n' "${'$'}name" "${'$'}password" "${'$'}gid" "${'$'}members"; exit 0
                    fi
                done < "${'$'}FIXTURE_BASE/guest/rootfs/etc/${'$'}3"
                exit 2
            """)
            tool("usr/sbin/groupadd", """
                [ "${'$'}#" -eq 3 ] && [ "${'$'}1" = --gid ] || exit 96
                [ "${'$'}3" = "droide_android_${'$'}2" ] || exit 96
                printf '%s|%s|%s\n' "${'$'}1" "${'$'}2" "${'$'}3" >> "${'$'}FIXTURE_BASE/adds"
                printf '%s:x:%s:\n' "${'$'}3" "${'$'}2" >> "${'$'}FIXTURE_BASE/guest/rootfs/etc/group"
                if [ -e "${'$'}FIXTURE_BASE/fail-after-group" ]; then printf 'fixture: gshadow publication failed\n' >&2; exit 10; fi
                if [ -e "${'$'}FIXTURE_BASE/guest/rootfs/etc/gshadow" ]; then
                    printf '%s:!::\n' "${'$'}3" >> "${'$'}FIXTURE_BASE/guest/rootfs/etc/gshadow"
                fi
            """)
        }
        fun tool(path: String, body: String) {
            File(rootfs, path).apply {
                parentFile!!.mkdirs()
                writeText("#!/bin/sh\nset -eu\n" +
                    "[ \"${'$'}{INERT_GROUP_FIXTURE:-}\" = yes ] && [ -f \"${'$'}FIXTURE_BASE/INERT_FIXTURE\" ] || exit 97\n" + body.trimIndent() + "\n")
                check(setExecutable(true, true))
            }
        }
        suspend fun repair(execute: suspend (List<String>, Int, Long) -> ExecResult = ::run) {
            repairer.repair(rootfs, execute, diagnostic = diagnostics::add)
        }
        suspend fun run(argv: List<String>, limit: Int, timeout: Long): ExecResult {

            check(argv == UbuntuGuestGroupNames.command() && argv.size == 8)
            check(argv.take(2) == listOf("/bin/sh", "-c"))
            check(argv.drop(4) == listOf("/etc", "/usr/bin/id", "/usr/bin/getent", "/usr/sbin/groupadd"))
            check(limit == 16_384 && timeout == 5_000L)
            runs++
            val mapped = argv.take(4) + listOf(etc.path, File(rootfs, "usr/bin/id").path,
                File(rootfs, "usr/bin/getent").path, File(rootfs, "usr/sbin/groupadd").path)
            val output = File(base, "runner-output")
            val process = ProcessBuilder(mapped).redirectErrorStream(true).redirectOutput(output).apply {
                environment().clear()
                environment().putAll(mapOf("PATH" to "/no-host-tools", "INERT_GROUP_FIXTURE" to "yes", "FIXTURE_BASE" to base.path))
            }.start()
            try {
                check(process.waitFor(timeout, TimeUnit.MILLISECONDS)) { "Inert policy fixture timed out" }
                check(output.length() <= limit) { "Inert policy fixture exceeded its output bound" }
                return ExecResult(process.exitValue(), output.readText(), false)
            } finally { process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS) }
        }
        fun additions(): List<String> = if (calls.exists()) calls.readLines() else emptyList()
        fun clean() { PathSecurity.deleteTreeNoFollow(base) }
    }
    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture()
        try { f.block() } finally { f.clean() }
    }

    @Test(timeout = 60_000) fun namingRepairRemovesSimulatedAutomaticStartupWarningsAndPreservesRecords() = runBlocking { fixture {
        val preserved = listOf("root/.bashrc", "etc/passwd", "etc/sudoers").associateWith { path ->
            File(rootfs, path).apply { parentFile!!.mkdirs(); writeText("user-owned $path\n") }.readText()
        }
        val before = UbuntuStartupGroupFixture.openBash(rootfs, UbuntuStartupGroupFixture.SCREENSHOT_GIDS)
        assertTrue(before.output.contains("cannot find name for group ID"))
        repair()
        assertTrue(diagnostics.toString(), diagnostics.isEmpty())
        val after = UbuntuStartupGroupFixture.openBash(rootfs, UbuntuStartupGroupFixture.SCREENSHOT_GIDS)
        assertFalse(after.output, after.output.contains("cannot find name for group ID"))
        assertTrue(after.output.contains("REACHED_PROMPT"))
        assertTrue(group.readLines().contains("team:x:777:alice,bob"))
        assertTrue(shadow.readLines().contains("team:!::alice,bob"))
        for (gid in UbuntuStartupGroupFixture.SCREENSHOT_GIDS) {
            assertTrue(group.readLines().contains("droide_android_$gid:x:$gid:"))
            assertTrue(shadow.readLines().contains("droide_android_$gid:!::"))
        }
        assertEquals(5, additions().size)
        preserved.forEach { (path, text) -> assertEquals(text, File(rootfs, path).readText()) }
        assertFalse(File(rootfs, "root/.hushlogin").exists())
    } }

    @Test(timeout = 60_000) fun existingGidNamesAreKeptAndReopenIsIdempotentIncludingAfterCacheExpiry() = runBlocking { fixture {
        group.appendText("my_inet:x:3003:alice\n")
        repair(); val old = group.readText()
        repeat(20) { repair() }
        assertEquals(1, runs); assertEquals(4, additions().size)
        now += UbuntuGuestGroupNames.SUCCESS_CACHE_NANOS
        repair()
        assertEquals(2, runs); assertEquals(4, additions().size)
        assertEquals(old, group.readText())
        assertFalse(group.readText().contains("droide_android_3003"))
    } }

    @Test(timeout = 60_000) fun dynamicIdsAndDatabaseChangesRefreshOnlyTheSeparateNamingCache() = runBlocking { fixture {
        repair()
        ids.writeText("0 3003 40001 123456789\n")
        now += UbuntuGuestGroupNames.SUCCESS_CACHE_NANOS
        repair()
        assertEquals(7, additions().size)
        assertTrue(group.readText().contains("droide_android_123456789:x:123456789:"))
        group.writeText(group.readText().replace("droide_android_40001:x:40001:\n", ""))
        shadow.writeText(shadow.readText().replace("droide_android_40001:!::\n", ""))
        repair(); assertEquals(8, additions().size)
        val other = Fixture()
        try {
            repairer.repair(other.rootfs, other::run, diagnostic = other.diagnostics::add)
            assertEquals(1, other.runs)
        } finally { other.clean() }
    } }

    @Test(timeout = 60_000) fun nameCollisionLeavesExistingGroupAndMembershipUntouchedAndAllowsContinuation() = runBlocking { fixture {
        ids.writeText("3003\n")
        group.appendText("droide_android_3003:x:54321:alice,bob\n")
        val old = group.readText()
        repair()
        assertEquals(old, group.readText()); assertTrue(additions().isEmpty())
        assertTrue(diagnostics.joinToString().contains("name collision"))
        repair(); assertEquals(1, runs)
    } }

    @Test(timeout = 60_000) fun orphanShadowNameCollisionPreservesPasswordAdminsAndMembers() = runBlocking { fixture {
        ids.writeText("3003\n")
        shadow.appendText("droide_android_3003:locked-fixture-value:admin:alice,bob\n")
        val oldGroup = group.readText(); val oldShadow = shadow.readText()
        repair()
        assertEquals(oldGroup, group.readText()); assertEquals(oldShadow, shadow.readText())
        assertTrue(additions().isEmpty())
        assertTrue(diagnostics.joinToString().contains("shadow name collision"))
    } }

    @Test(timeout = 60_000) fun absentOptionalGshadowDoesNotInventOrReplaceAShadowDatabase() = runBlocking { fixture {
        check(shadow.delete()); ids.writeText("3003\n")
        repair()
        assertTrue(diagnostics.toString(), diagnostics.isEmpty())
        assertEquals(1, additions().size)
        assertTrue(group.readText().contains("droide_android_3003:x:3003:"))
        assertFalse(shadow.exists())
    } }

    @Test(timeout = 60_000) fun malformedOversizeAndTooManyIdsDoNotPartiallyAddAnything() = runBlocking {
        for (ids in listOf("0 3003;touch", "4294967295", "-1", "", (1..65).joinToString(" "), "9".repeat(705))) fixture {
            this.ids.writeText("$ids\n"); repair()
            assertTrue(additions().isEmpty()); assertTrue(diagnostics.isNotEmpty())
        }
    }

    @Test(timeout = 60_000) fun customNssMissingToolReadonlyAndPendingLockSkipWithoutLaunching() = runBlocking {
        for (kind in listOf("nss", "tool", "readonly", "lock", "temp")) fixture {
            when (kind) {
                "nss" -> File(etc, "nsswitch.conf").writeText("group: files ldap\n")
                "tool" -> File(rootfs, "usr/sbin/groupadd").delete()
                "readonly" -> Files.setPosixFilePermissions(group.toPath(), PosixFilePermissions.fromString("r--r--r--"))
                "lock" -> File(etc, "group.lock").writeText("existing lock")
                "temp" -> File(etc, "gshadow+").writeText("pending publication")
            }
            repair()
            assertEquals(kind, 0, runs); assertTrue(diagnostics.isNotEmpty()); assertTrue(additions().isEmpty())
        }
    }

    @Test(timeout = 60_000) fun symlinkedDatabaseBackupTemporaryAndLockPathsNeverTouchTheirTargets() = runBlocking {
        for (name in listOf("group", "gshadow", "group-", "gshadow-", "group+", "gshadow+", "group.lock", "gshadow.lock", ".pwd.lock", "nsswitch.conf")) fixture {
            val outside = File(base, "preserve").apply { writeText("do not touch") }
            val path = File(etc, name); path.delete(); Files.createSymbolicLink(path.toPath(), outside.toPath())
            repair()
            assertEquals(name, 0, runs); assertEquals("do not touch", outside.readText()); assertTrue(diagnostics.isNotEmpty())
        }
    }

    @Test(timeout = 60_000) fun symlinkedEtcAndHardlinkedDatabaseAreRejected() = runBlocking {
        fixture {
            val moved = File(rootfs, "saved-etc"); check(etc.renameTo(moved)); Files.createSymbolicLink(etc.toPath(), moved.toPath())
            repair(); assertEquals(0, runs); assertTrue(diagnostics.isNotEmpty())
        }
        fixture {
            Files.createLink(File(base, "group-alias").toPath(), group.toPath())
            repair(); assertEquals(0, runs); assertTrue(diagnostics.isNotEmpty())
        }
    }

    @Test(timeout = 60_000) fun replacementImmediatelyBeforeGroupaddIsCheckedAgainByTheGuestPolicy() = runBlocking { fixture {
        ids.writeText("3003\n")
        val outside = File(base, "preserve").apply { writeText("do not touch") }
        repair { argv, limit, timeout ->
            Files.createSymbolicLink(File(etc, "group-").toPath(), outside.toPath())
            run(argv, limit, timeout)
        }
        assertEquals(1, runs); assertTrue(additions().isEmpty()); assertEquals("do not touch", outside.readText())
        assertTrue(diagnostics.joinToString().contains("unsafe or pending"))
    } }

    @Test(timeout = 60_000) fun parentlessGuestRootSkipsWithAnExplicitDiagnosticWithoutExecution() = runBlocking {
        var calls = 0
        val diagnostics = mutableListOf<String>()
        UbuntuGuestGroupNames().repair(
            File("/"),
            execute = { _, _, _ -> calls++; error("Parentless guest must not execute") },
            diagnostic = diagnostics::add,
        )
        assertEquals(0, calls)
        assertEquals(listOf("Skipped optional group naming: Guest root has no parent"), diagnostics)
    }

    @Test(timeout = 60_000) fun hardlinkedOptionalDatabasesBackupsAndAdvisoryLockArePreserved() = runBlocking {
        for (name in listOf("gshadow", "group-", "gshadow-", ".pwd.lock")) fixture {
            val path = File(etc, name)
            if (!path.exists()) path.writeText("preserve optional $name\n")
            val before = path.readBytes()
            val alias = File(base, "preserved-alias")
            Files.createLink(alias.toPath(), path.toPath())
            repair()
            assertEquals(name, 0, runs)
            assertTrue(additions().isEmpty())
            assertArrayEquals(before, path.readBytes())
            assertArrayEquals(before, alias.readBytes())
            assertTrue(diagnostics.joinToString().contains("Hard-linked $name"))
        }
    }

    @Test(timeout = 60_000) fun readonlyOptionalDatabasesBackupsAndAdvisoryLockArePreserved() = runBlocking {
        for (name in listOf("gshadow", "group-", "gshadow-", ".pwd.lock")) fixture {
            val path = File(etc, name)
            if (!path.exists()) path.writeText("preserve optional $name\n")
            val before = path.readBytes()
            val permissions = PosixFilePermissions.fromString("r--r--r--")
            Files.setPosixFilePermissions(path.toPath(), permissions)
            repair()
            assertEquals(name, 0, runs)
            assertTrue(additions().isEmpty())
            assertArrayEquals(before, path.readBytes())
            assertEquals(permissions, Files.getPosixFilePermissions(path.toPath()))
            assertTrue(diagnostics.joinToString().contains("Read-only $name"))
        }
    }

    @Test(timeout = 60_000) fun partialPublicationIsPreservedAndReportedWithoutFalseSuccessOrRollback() = runBlocking { fixture {
        ids.writeText("3003 9997\n")
        File(base, "fail-after-group").writeText("")
        repair()
        assertTrue(group.readText().contains("droide_android_3003:x:3003:"))
        assertFalse(shadow.readText().contains("droide_android_3003"))
        assertEquals(1, additions().size)
        assertTrue(diagnostics.joinToString().contains("fixture: gshadow publication failed"))
        assertTrue(diagnostics.joinToString().contains("incomplete"))
        repair(); assertEquals(1, runs)
    } }

    @Test(timeout = 60_000) fun timeoutIncompleteOutputAndIoFailureAreBestEffortAndDiagnosticsAreBounded() = runBlocking {
        for (kind in listOf("timeout", "incomplete", "exception")) fixture {
            var attempted = 0
            repair { _, _, _ ->
                attempted++
                when (kind) {
                    "exception" -> throw IOException("fixture unavailable")
                    "timeout" -> ExecResult(124, "raw stderr\n", true)
                    else -> ExecResult(0, "x".repeat(20_000) + "raw stderr", false, outputIncomplete = true)
                }
            }
            assertEquals(1, attempted); assertTrue(diagnostics.isNotEmpty())
            assertTrue(diagnostics.single().length < 5_000)
            assertTrue(additions().isEmpty())
        }
    }

    @Test(timeout = 60_000) fun retryAfterPartialPublicationPreservesEarlierRecordsAndNamesRemainingIds() = runBlocking { fixture {
        ids.writeText("3003 9997\n")
        val failure = File(base, "fail-after-group").apply { writeText("") }
        repair()
        val partiallyPublishedGroup = group.readText()
        val preservedShadow = shadow.readText()
        assertEquals(1, runs)
        assertTrue(diagnostics.joinToString().contains("incomplete"))
        check(failure.delete())
        repair()
        assertEquals(1, runs)
        now += UbuntuGuestGroupNames.FAILURE_CACHE_NANOS
        repair()
        assertEquals(2, runs)
        assertEquals(2, additions().size)
        assertTrue(group.readText().startsWith(partiallyPublishedGroup))
        assertTrue(shadow.readText().startsWith(preservedShadow))
        assertTrue(group.readLines().contains("droide_android_9997:x:9997:"))
        assertTrue(shadow.readLines().contains("droide_android_9997:!::"))
        // Naming existing GIDs is not a repair of a previously partial shadow database.
        assertFalse(shadow.readText().contains("droide_android_3003"))
        assertEquals(1, group.readLines().count { it == "droide_android_3003:x:3003:" })
    } }

    @Test(timeout = 60_000) fun cancellationFromRunnerOrPreparationPropagatesWithoutAdmission() = runBlocking { fixture {
        try { repair { _, _, _ -> throw CancellationException("cancel runner") }; fail("Expected cancellation") }
        catch (expected: CancellationException) { assertEquals("cancel runner", expected.message) }
        try {
            repairer.repair(rootfs, ::run, checkCancelled = { throw CancellationException("cancel prepare") }, diagnostic = diagnostics::add)
            fail("Expected cancellation")
        } catch (expected: CancellationException) { assertEquals("cancel prepare", expected.message) }
        assertTrue(diagnostics.isEmpty()); assertEquals(0, runs)
        repair(); assertEquals(1, runs)
    } }

    @Test(timeout = 60_000) fun serializedCallersReuseOneNamingAttempt() = runBlocking { fixture {
        val gate = Mutex()
        coroutineScope { (1..12).map { async { gate.withLock { repair() } } }.awaitAll() }
        assertEquals(1, runs); assertEquals(5, additions().size)
    } }

    @Test(timeout = 60_000) fun cancellationAfterSuccessfulExecutionDoesNotPublishACacheAdmission() = runBlocking { fixture {
        var cancelled = false
        try {
            repairer.repair(
                rootfs,
                execute = { argv, limit, timeout -> run(argv, limit, timeout).also { cancelled = true } },
                checkCancelled = { if (cancelled) throw CancellationException("cancel after output") },
                diagnostic = diagnostics::add,
            )
            fail("Expected cancellation after execution")
        } catch (expected: CancellationException) { assertEquals("cancel after output", expected.message) }
        assertEquals(1, runs)
        assertEquals(5, additions().size)
        assertTrue(diagnostics.isEmpty())
        repair()
        assertEquals(2, runs)
        assertEquals(5, additions().size)
    } }

    @Test(timeout = 60_000) fun separateNamingPreparationDoesNotInjectAnInteractiveCommandWrapperIntoPipeOrStdio() {
        val spec = TerminalLaunchSpec(prefix = listOf("/system/bin/sh", "/guest/launch"), shell = "/bin/bash",
            interactiveArgs = listOf("--noprofile", "--rcfile", "/opt/droide/terminal/bashrc-v1.sh", "-i"))
        assertEquals(listOf("/system/bin/sh", "/guest/launch", "TERM=dumb", "/bin/bash", "--noprofile", "--rcfile",
            "/opt/droide/terminal/bashrc-v1.sh", "-s"), spec.pipeCommand())
        assertEquals(listOf("/system/bin/sh", "/guest/launch", "/bin/bash", "-c", "printf protocol-clean"), spec.shellCommand("printf protocol-clean"))
        assertFalse(spec.interactiveArgs.contains("-c"))
    }

    @Test(timeout = 60_000) fun sourceWiringIsBeforeEachHealthyAdmissionAndOutsideThePackageAttachFastPath() {
        // Structural guard only: this does not instantiate the Android manager or prove a device
        // startup.
        val relative = "src/main/java/com/baystudio/droide/core/FoundryUbuntuGuestEnvironment.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        assertEquals(3, Regex("repairGuestGroupNamesUnlocked\\(checkCancelled\\)\\s+readinessCache\\.admit").findAll(source).count())
        val fastPath = source.indexOf("if (packageAttachAdmission.mayAttach()) return@withContext finalRoot")
        val gate = source.indexOf("FoundryUbuntuGuestMutationGate.mutex.withLock", fastPath)
        assertTrue(fastPath >= 0 && gate > fastPath)
        assertFalse(source.substring(fastPath, gate).contains("guestGroupNames"))
        val seal = source.substringAfter("private fun readinessSeal").substringBefore("private suspend fun repairExistingLauncher")
        assertFalse(seal.contains("etc/group")); assertFalse(seal.contains("guestGroupNames"))
        val script = UbuntuGuestGroupNames.command()[2]
        assertTrue(script.contains("exec \"${'$'}groupadd\" --gid \"${'$'}gid\" \"${'$'}name\""))
        assertTrue(script.contains("\"${'$'}db.${'$'}${'$'}\""))
        assertFalse(script.contains("2>/dev/null")); assertFalse(script.contains(".hushlogin"))
    }
}
