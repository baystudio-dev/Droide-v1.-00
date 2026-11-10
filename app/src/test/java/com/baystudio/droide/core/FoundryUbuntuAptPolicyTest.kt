package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FoundryUbuntuAptPolicyTest {
    @Test fun layerHealthChecksAllRequestedPackagesWithOneQueryAndFailsClosed() {
        val root = Files.createTempDirectory("droide-layer-health").toFile()
        try {
            val bin = File(root, "dpkg-query").apply {
                writeText("#!/bin/sh\nprintf 'called\\n' >> \"${'$'}{0%/*}/calls\"\ncat \"${'$'}{0%/*}/rows\"\nexit \"${'$'}{DROIDE_QUERY_EXIT:-0}\"\n")
                setExecutable(true)
            }
            val command = FoundryUbuntuAptPolicy.installedPackagesHealthCommand(listOf("alpha", "beta"))
            fun run(rows: String, exit: String = "0"): Int {
                File(root, "rows").writeText(rows)
                val process = ProcessBuilder("/bin/sh", "-c", command).apply {
                    environment()["PATH"] = root.path + ":" + System.getenv("PATH")
                    environment()["DROIDE_QUERY_EXIT"] = exit
                }.start()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS)); return process.exitValue()
            }
            val good = "alpha\tarm64\tinstall ok installed\nbeta\tall\tinstall ok installed\n"
            assertEquals(0, run(good))
            assertEquals(1, File(root, "calls").readLines().size)
            assertNotEquals(0, run(good.replace("arm64", "amd64")))
            assertNotEquals(0, run(good.replace("install ok installed", "install ok unpacked")))
            assertNotEquals(0, run("alpha\tarm64\tinstall ok installed\n"))
            assertNotEquals(0, run(good, "1"))
            assertNotEquals(0, run(good + "extra\tarm64\tinstall ok installed\n"))
        } finally { root.deleteRecursively() }
    }
    private class Fixture(val root: File) {
        val bin = File(root, "bin").apply { mkdirs() }
        val planDirectory = File(root, "transaction '/plan")
        val calls = File(bin, "cache-calls")
        val cacheArgs = File(bin, "cache-args")
        val getArgs = File(bin, "get-args")
        val configArgs = File(bin, "config-args")

        init {
            File(bin, "simulation").writeText("")
            File(bin, "uris").writeText("")
            File(bin, "metadata").writeText("")
            File(bin, "architecture").writeText("droide_native_arch='arm64'\n")
            File(bin, "apt-get").apply {
                writeText("""#!/bin/sh
                    set -eu
                    base="${'$'}{0%/*}"
                    printf '%s\n' "${'$'}@" >> "${'$'}base/get-args"
                    for arg in "${'$'}@"; do
                      case "${'$'}arg" in
                        -s) cat "${'$'}base/simulation"; exit 0 ;;
                        --print-uris) cat "${'$'}base/uris"; exit 0 ;;
                      esac
                    done
                    echo 'unexpected apt-get mutation' >&2
                    exit 91
                """.trimIndent())
                check(setExecutable(true))
            }
            File(bin, "apt-cache").apply {
                writeText("""#!/bin/sh
                    set -eu
                    base="${'$'}{0%/*}"
                    printf 'invocation\n' >> "${'$'}base/cache-calls"
                    printf '%s\n' "${'$'}@" >> "${'$'}base/cache-args"
                    cat "${'$'}base/metadata"
                    exit "${'$'}{APT_CACHE_FIXTURE_EXIT:-0}"
                """.trimIndent())
                check(setExecutable(true))
            }
            File(bin, "apt-config").apply {
                writeText("""#!/bin/sh
                    set -eu
                    base="${'$'}{0%/*}"
                    printf '%s\n' "${'$'}@" >> "${'$'}base/config-args"
                    [ "${'$'}#" = 3 ] && [ "${'$'}1" = shell ] && [ "${'$'}2" = droide_native_arch ] && [ "${'$'}3" = APT::Architecture ] || exit 92
                    cat "${'$'}base/architecture"
                    exit "${'$'}{APT_CONFIG_FIXTURE_EXIT:-0}"
                """.trimIndent())
                check(setExecutable(true))
            }
        }

        fun simulation(vararg names: String) {
            File(bin, "simulation").writeText(names.joinToString("\n", postfix = if (names.isEmpty()) "" else "\n") { "Inst $it (1.0 fixture [arm64])" })
        }

        fun metadata(text: String) { File(bin, "metadata").writeText(text) }
        fun uris(text: String) { File(bin, "uris").writeText(text) }
        fun architectureOutput(text: String) { File(bin, "architecture").writeText(text) }

        fun run(command: String, environment: Map<String, String> = emptyMap()): Pair<Int, String> {
            val process = ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true).apply {
                environment().put("PATH", bin.path + ":" + System.getenv("PATH"))
                environment().putAll(environment)
            }.start()
            try {
                val output = process.inputStream.bufferedReader().readText()
                assertTrue("APT fixture did not exit", process.waitFor(5, TimeUnit.SECONDS))
                return process.exitValue() to output
            } finally { process.destroyForcibly() }
        }

        fun plan(environment: Map<String, String> = emptyMap()) =
            run(FoundryUbuntuAptPolicy.planCommand(listOf("alpha"), planDirectory.path), environment)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("droide apt policy '").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    @Test fun fullPlanBatchesMetadataAndSumsDownloadAndInstalledSizes() = fixture { f ->
        f.simulation("alpha", "beta", "gamma")
        f.uris("'https://example.invalid/alpha.deb' alpha.deb 12 SHA256:fixture\n" +
            "'https://example.invalid/beta.deb' beta.deb 18 SHA256:fixture\n")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 10\n\n" +
            "Package: beta\nInstalled-Size: 20\nArchitecture: arm64\n\n" +
            "Package: gamma\nArchitecture: arm64\nInstalled-Size: 3\n\n")
        assertEquals(0 to "download=30\ninstalled_kib=33\n", f.plan())
        assertEquals(listOf("invocation"), f.calls.readLines())
        assertEquals(listOf("show", "--no-all-versions", "alpha", "beta", "gamma"), f.cacheArgs.readLines())
        val getArgs = f.getArgs.readLines()
        assertEquals(2, getArgs.count { it == "--no-install-recommends" })
        assertEquals(2, getArgs.count { it == "--no-remove" })
        assertEquals(2, getArgs.count { it == "APT::Update::Error-Mode=any" })
        assertEquals(2, getArgs.count { it == "Acquire::Languages=none" })
        assertFalse(getArgs.any { "unauthenticated" in it || "AllowInsecure" in it })
    }

    @Test fun alreadyInstalledPlanReturnsZeroWithoutStartingAptCache() = fixture { f ->
        assertEquals(0 to "download=0\ninstalled_kib=0\n", f.plan())
        assertFalse(f.calls.exists())
    }

    @Test fun humanPackageCountsNeverBecomeArchiveBytes() = fixture { f ->
        f.simulation("alpha")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 7\n\n")
        val summary = "1 upgraded, 117 newly installed, 0 to remove and 5 not upgraded.\n" +
            "Need to get 0 B/269 MB of archives.\nAfter this operation, 856 MB of additional disk space will be used.\n"
        f.uris(summary)
        assertEquals(0 to "download=0\ninstalled_kib=7\n", f.plan())
        f.uris(summary + "'https://example.invalid/alpha.deb' alpha.deb 269000000 SHA256:fixture\n")
        assertEquals(0 to "download=269000000\ninstalled_kib=7\n", f.plan())
    }

    @Test fun planningUsesTheSuppliedPrivateDownloadCacheWithoutTouchingSharedCache() = fixture { f ->
        val shared = File(f.root, "shared archives").apply { mkdirs() }
        val existing = File(shared, "alpha.deb").apply { writeText("shared archive") }
        val cache = File(f.root, "transaction '/actual archives")
        assertEquals(0 to "download=0\ninstalled_kib=0\n", f.run(
            FoundryUbuntuAptPolicy.planCommand(listOf("alpha"), f.planDirectory.path, cache.path)))
        assertTrue(File(cache, "partial").isDirectory)
        assertTrue(f.getArgs.readLines().contains("Dir::Cache::archives=${cache.path}"))
        assertEquals("shared archive", existing.readText())
    }

    @Test fun malformedQuotedArchiveRecordsCannotPublishStorageTotals() = fixture { f ->
        for (record in listOf("'https://example.invalid/alpha.deb' alpha.deb invalid SHA256:fixture\n",
            "'https://example.invalid/alpha.deb' alpha.deb 12\n")) {
            f.uris(record)
            val (code, output) = f.plan()
            assertNotEquals(0, code)
            assertFalse(output.contains("download="))
            assertFalse(output.contains("installed_kib="))
        }
    }

    @Test fun missingInstalledSizeOrMetadataKeepsTheFormerZeroContribution() = fixture { f ->
        f.simulation("alpha", "no-size", "missing")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 7\n\n" +
            "Package: no-size\nArchitecture: arm64\n\n")
        assertEquals(0 to "download=0\ninstalled_kib=7\n", f.plan())
        assertEquals(1, f.calls.readLines().size)
    }

    @Test fun firstCandidateAndExplicitMultiarchNamesMatchTheFormerSequentialLookup() = fixture { f ->
        f.simulation("alpha", "beta:arm64")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 10\n\n" +
            "Package: alpha\nArchitecture: arm64\nInstalled-Size: 999\n\n" +
            "Package: beta\nInstalled-Size: 100\nArchitecture: amd64\n\n" +
            "Package: beta\nInstalled-Size: 30\nArchitecture: arm64\n\n" +
            "Package: beta\nArchitecture: arm64\nInstalled-Size: 999\n\n")
        assertEquals(0 to "download=0\ninstalled_kib=40\n", f.plan())
        assertEquals(listOf("show", "--no-all-versions", "alpha", "beta:arm64"), f.cacheArgs.readLines())
    }

    @Test fun aptCacheFailureStopsPlanningBeforePublishingSuccessfulStorageTotals() = fixture { f ->
        f.simulation("alpha")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 10\n\n")
        val (code, output) = f.plan(mapOf("APT_CACHE_FIXTURE_EXIT" to "17"))
        assertNotEquals(0, code)
        assertFalse(output.contains("download="))
        assertFalse(output.contains("installed_kib="))
        assertEquals(1, f.calls.readLines().size)
    }

    @Test fun nativeAndForeignKeysSelectTheirOwnRecordsInEitherMetadataOrder() = fixture { f ->
        f.simulation("mixed:amd64", "mixed")
        val native = "Package: mixed\nArchitecture: arm64\nInstalled-Size: 900\n\n"
        val foreign = "Package: mixed\nArchitecture: amd64\nInstalled-Size: 3\n\n"
        for (metadata in listOf(foreign + native, native + foreign)) {
            f.metadata(metadata)
            assertEquals(0 to "download=0\ninstalled_kib=903\n", f.plan())
        }
    }

    @Test fun architectureAllRecordsSatisfyQualifiedAndUnqualifiedAptNames() = fixture { f ->
        f.metadata("Package: neutral\nInstalled-Size: 428\nArchitecture: all\n\n")
        for (names in listOf(listOf("neutral"), listOf("neutral:arm64"), listOf("neutral:amd64"), listOf("neutral", "neutral:arm64"))) {
            f.simulation(*names.toTypedArray())
            assertEquals(0 to "download=0\ninstalled_kib=${428 * names.size}\n", f.plan())
        }
    }

    @Test fun unqualifiedNamesUseTheTargetedConfiguredNativeArchitecture() = fixture { f ->
        f.architectureOutput("droide_native_arch='amd64'\n")
        f.simulation("mixed")
        f.metadata("Package: mixed\nArchitecture: arm64\nInstalled-Size: 900\n\n" +
            "Package: mixed\nArchitecture: amd64\nInstalled-Size: 3\n\n")
        assertEquals(0 to "download=0\ninstalled_kib=3\n", f.plan())
        assertEquals(listOf("shell", "droide_native_arch", "APT::Architecture"), f.configArgs.readLines())
    }

    @Test fun failedMissingOrInvalidNativeArchitectureCannotPublishStorageTotals() = fixture { f ->
        f.simulation("alpha")
        f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: 10\n\n")
        fun assertRejected(environment: Map<String, String> = emptyMap()) {
            val (code, output) = f.plan(environment)
            assertNotEquals("Invalid native architecture accepted", 0, code)
            assertFalse(output.contains("download="))
            assertFalse(output.contains("installed_kib="))
        }
        assertRejected(mapOf("APT_CONFIG_FIXTURE_EXIT" to "17"))
        val marker = File(f.root, "architecture-must-not-eval")
        for (output in listOf("", "droide_native_arch=''\n", "junk\n",
            "droide_native_arch='arm64'\ndroide_native_arch='amd64'\n",
            "droide_native_arch='arm64 invalid'\n",
            "droide_native_arch='arm64'; touch '${marker.path}'\n")) {
            f.architectureOutput(output)
            assertRejected()
        }
        assertFalse("apt-config output was evaluated as shell code", marker.exists())
    }

    @Test fun malformedInstalledSizeIsRejectedInsteadOfBecomingAStorageEstimate() = fixture { f ->
        f.simulation("alpha")
        for (value in listOf("-3", "2oops", "12 trailing")) {
            f.metadata("Package: alpha\nArchitecture: arm64\nInstalled-Size: $value\n\n")
            val (code, output) = f.plan()
            assertNotEquals("Malformed Installed-Size $value accepted", 0, code)
            assertFalse(output.contains("installed_kib="))
        }
    }

    @Test fun cleanupRemovesOnlyItsTransactionAndPreservesSharedIndexBytes() = fixture { f ->
        val lists = File(f.root, "var/lib/apt/lists")
        val signedIndex = File(lists, "noble_InRelease").apply { parentFile.mkdirs(); writeBytes(ByteArray(1_024) { (it % 251).toByte() }) }
        val packageIndex = File(lists, "noble_arm64_Packages").apply { writeText("Package: fixture\nArchitecture: arm64\n") }
        val partial = File(lists, "partial/incoming").apply { parentFile.mkdirs(); writeText("resumable shared index") }
        val before = listOf(signedIndex, packageIndex, partial).associateWith { it.readBytes() }
        val transaction = File(f.root, "var/cache/droide-foundry/transaction '").apply { mkdirs() }
        File(transaction, "owned.deb").writeText("transaction artifact")
        assertEquals(0 to "", f.run(FoundryUbuntuAptPolicy.cleanupCommand(transaction.path)))
        assertFalse(transaction.exists())
        before.forEach { (file, bytes) -> assertTrue(file.path, file.isFile); assertArrayEquals(file.path, bytes, file.readBytes()) }
    }

    @Test fun invalidRecipeNamesAndNulPathsAreRejectedBeforeStartingApt() = fixture { f ->
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuAptPolicy.planCommand(emptyList(), f.planDirectory.path) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuAptPolicy.planCommand(listOf("alpha;touch injected"), f.planDirectory.path) }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuAptPolicy.planCommand(listOf("alpha"), "bad\u0000path") }
        assertThrows(IllegalArgumentException::class.java) { FoundryUbuntuAptPolicy.cleanupCommand("bad\u0000path") }
        assertFalse(f.getArgs.exists())
    }
}
