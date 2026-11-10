package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FoundryUbuntuGuestCommandTest {
    private fun hostCommand(launcher: File, argv: List<String>): String {
        val command = FoundryUbuntuGuestCommand.build(launcher.absolutePath, argv)
        assertTrue(command.startsWith("/system/bin/sh "))
        // The production Android argv serializer is unchanged; only the host shell path differs.
        return "/bin/sh " + command.removePrefix("/system/bin/sh ")
    }

    private fun invoke(command: String): ByteArray {
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.readBytes()
            assertTrue("Fixture did not exit", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(output.toString(Charsets.UTF_8), 0, process.exitValue())
            return output
        } finally { process.destroyForcibly() }
    }

    @Test fun lineBreaksEmptyArgumentsUnicodeAndMetacharactersRemainLiteralHostArgv() {
        val root = Files.createTempDirectory("droide guest '").toFile()
        try {
            val marker = File(root, "must-not-exist")
            val launcher = File(root, "launch '").apply { writeText("#!/bin/sh\nprintf '%s\\000' \"${'$'}@\"\n") }
            val argv = listOf("/bin/printf", "", "spaces and 'quotes'", "baris satu\nbaris dua\r\n", "日本語",
                "${'$'}(touch '${marker.path}')", "`touch '${marker.path}'`", "; touch '${marker.path}'")
            val expected = argv.joinToString("\u0000", postfix = "\u0000").toByteArray(Charsets.UTF_8)
            assertArrayEquals(expected, invoke(hostCommand(launcher, argv)))
            assertFalse("Argument escaped into the host shell", marker.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun aBoundedMultilineGuestScriptLongerThanFourThousandCharactersExecutes() {
        val root = Files.createTempDirectory("droide guest script").toFile()
        try {
            val launcher = File(root, "launch").apply { writeText("#!/bin/sh\nexec \"${'$'}@\"\n") }
            val script = ":\n".repeat(3_000) + "printf '%s' long-script-ok\n"
            assertTrue(script.length > 4_000)
            assertEquals("long-script-ok", invoke(hostCommand(launcher, listOf("/bin/sh", "-c", script))).toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }

    @Test fun downloadedDebMeasurementUsesTheProductionMultilineScriptAndSerializer() {
        val root = Files.createTempDirectory("droide deb argv '").toFile()
        try {
            val launcher = File(root, "launch").apply { writeText("#!/bin/sh\nexec \"${'$'}@\"\n") }
            val packageDir = File(root, "package")
            File(packageDir, "DEBIAN/control").apply {
                parentFile.mkdirs()
                check(parentFile.setReadable(true, false) && parentFile.setExecutable(true, false))
                writeText("Package: droide-guest-argv-fixture\nVersion: 1\nArchitecture: all\n" +
                    "Maintainer: Droide <test@example.invalid>\nDescription: Guest argv regression fixture\nInstalled-Size: 7\n")
            }
            File(packageDir, "usr/share/droide-fixture.txt").apply { parentFile.mkdirs(); writeText("fixture\n") }
            val archives = File(root, "archives '").apply { mkdirs() }
            val archive = File(archives, "fixture.deb")
            val build = ProcessBuilder("dpkg-deb", "--build", packageDir.path, archive.path).redirectErrorStream(true).start()
            val buildOutput = build.inputStream.bufferedReader().readText()
            assertTrue(build.waitFor(5, TimeUnit.SECONDS))
            assertEquals(buildOutput, 0, build.exitValue())
            val argv = FoundryUbuntuGuestCommand.downloadedArchiveSizesCommand(archives.absolutePath)
            assertTrue("Keep the original valid multiline argument", argv[2].contains('\n'))
            assertEquals("download=${archive.length()}\ninstalled_kib=7\n",
                invoke(hostCommand(launcher, argv)).toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }

    @Test fun emptyCommandsNulAndTooManyArgumentsAreRejected() {
        val launcher = "/owned/ubuntu/launch"
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build(launcher, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build(launcher, listOf("")) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build(launcher, listOf("sh", "bad\u0000arg")) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build("/bad\u0000launcher", listOf("true")) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build(launcher, List(FoundryUbuntuGuestCommand.MAX_ARGUMENTS + 1) { "true" }) }
        assertNotNull(FoundryUbuntuGuestCommand.build(launcher, listOf("true") + List(FoundryUbuntuGuestCommand.MAX_ARGUMENTS - 1) { "" }))
    }

    @Test fun argumentLimitsCountUtf8BytesRatherThanCharacters() {
        val oversized = "界".repeat(FoundryUbuntuGuestCommand.MAX_ARGUMENT_BYTES / 3 + 1)
        assertTrue(oversized.length < FoundryUbuntuGuestCommand.MAX_ARGUMENT_BYTES)
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build("/owned/launch", listOf("true", oversized)) }
    }

    @Test fun individuallyBoundedArgumentsCannotExceedTheTotalUtf8CommandBudget() {
        val payload = "界".repeat(5_000)
        assertNotNull(FoundryUbuntuGuestCommand.build("/owned/launch", listOf("true", payload, payload)))
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuGuestCommand.build("/owned/launch", listOf("true", payload, payload, payload)) }
    }

    @Test fun shellQuoteExpansionIsIncludedInTheCommandBudget() {
        val payload = "'".repeat(FoundryUbuntuGuestCommand.MAX_COMMAND_BYTES / 4)
        assertTrue(payload.length < FoundryUbuntuGuestCommand.MAX_ARGUMENT_BYTES)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FoundryUbuntuGuestCommand.build("/owned/launch", listOf("true", payload))
        }
        assertEquals("Ubuntu guest command is too large", failure.message)
    }

    @Test fun nestedProcessLeaseQuoteExpansionHasItsOwnByteBudget() {
        val payload = "'".repeat(2_600)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            FoundryUbuntuGuestCommand.build("/owned/launch", listOf("true", payload))
        }
        assertEquals("Ubuntu guest command exceeds the process-lease argument limit", failure.message)
    }
}
