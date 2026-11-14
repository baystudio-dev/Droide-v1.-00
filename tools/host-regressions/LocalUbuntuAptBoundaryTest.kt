package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuAptBoundaryTest {
    private suspend fun fixture(block: suspend (java.io.File, LocalManagedPackageAuthority, FoundryUbuntuGuestEnvironmentManager, LocalUbuntuAptInstaller) -> Unit) {
        val root = Files.createTempDirectory("droide-apt").toFile()
        try {
            val authority = LocalManagedPackageAuthority()
            val guest = FoundryUbuntuGuestEnvironmentManager()
            block(root, authority, guest, LocalUbuntuAptInstaller(android.content.Context(root), authority, guest))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun actualInstallerSealsIntegrationAndSharedLayerDependency() = runBlocking {
        fixture { root, authority, guest, installer ->
            val messages = mutableListOf<String>()
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val record = installer.install(recipe, messages::add)
            assertEquals(recipe.packages, guest.packages)
            assertEquals(listOf("runtime.ubuntu-deps.fixture@24.04.5.r2"), record.dependencies)
            assertEquals(LocalManagedPackageMetadata.KIND_UBUNTU_APT, record.metadata[LocalManagedPackageMetadata.KIND_KEY])
            assertTrue(java.io.File(record.installRoot, "APT_PACKAGES.txt").isFile)
            val manifest = java.io.File(record.installRoot, "SHA256SUMS")
            assertEquals(record.artifactSha256, LocalUbuntuNpmPolicy.sha256(manifest))
            assertTrue(messages.any { "Checking git" in it })
            assertEquals(record, authority.ownedReceipt(record.familyId, record.version))
        }
    }
    @Test fun failedHealthDoesNotAdoptIntegration() = runBlocking {
        fixture { root, authority, guest, installer ->
            guest.healthResult = ExecResult(9, "real failure tail", false)
            try { installer.install(UbuntuAptCatalog.find("cli.git")!!); fail("Unhealthy command activated") }
            catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("real failure tail")) }
            assertNull(authority.ownedReceipt("cli.git", UbuntuAptCatalog.VERSION))
            assertFalse(java.io.File(root, "packages/.staging/cli.git-ubuntu-24.04-local-ubuntu-apt").exists())
            assertTrue(guest.packages.contains("git"))
        }
    }
    @Test fun failedReceiptRollsBackOnlyIntegration() = runBlocking {
        fixture { root, authority, guest, installer ->
            authority.failFamily = "cli.git"
            try { installer.install(UbuntuAptCatalog.find("cli.git")!!); fail("Receipt failure ignored") }
            catch (_: IllegalStateException) { }
            assertFalse(java.io.File(root, "packages/cli.git/ubuntu-24.04/arm64-v8a").exists())
            assertNull(authority.ownedReceipt("cli.git", UbuntuAptCatalog.VERSION))
            assertTrue(authority.receipts.containsKey("runtime.ubuntu-deps.fixture@24.04.5.r2"))
        }
    }
    @Test fun cancellationCleansStageAndRetainsSharedPackages() = runBlocking {
        fixture { root, authority, guest, installer ->
            val started = CompletableDeferred<Unit>()
            guest.executeHandler = { started.complete(Unit); awaitCancellation() }
            val job = launch { installer.install(UbuntuAptCatalog.find("cli.git")!!) }
            started.await(); withTimeout(2_000) { job.cancelAndJoin() }
            assertNull(authority.ownedReceipt("cli.git", UbuntuAptCatalog.VERSION))
            assertFalse(java.io.File(root, "packages/.staging/cli.git-ubuntu-24.04-local-ubuntu-apt").exists())
            assertEquals(listOf("git"), guest.packages)
        }
    }
    @Test fun arbitraryPackagesAreRejectedBeforeGuestMutation() = runBlocking {
        fixture { root, authority, guest, installer ->
            val forged = UbuntuAptCatalog.find("cli.git")!!.copy(packages = listOf("bash"))
            try { installer.install(forged); fail("Unreviewed recipe accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(guest.packages.isEmpty())
        }
    }
    @Test fun arbitraryCommandArgumentsAreRejectedBeforeGuestMutation() = runBlocking {
        fixture { _, _, guest, installer ->
            val forged = UbuntuAptCatalog.find("cli.git")!!.copy(commandArgs = mapOf("git" to listOf("-c", "unreviewed")))
            try { installer.install(forged); fail("Unreviewed command prefix accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(guest.packages.isEmpty())
        }
    }
    @Test fun moduleCommandUsesReviewedPrefixInProbeAndBothOriginalCommandWrappers() = runBlocking {
        fixture { _, _, guest, installer ->
            val recipe = UbuntuAptCatalog.find("debug.python-debugpy")!!
            val record = installer.install(recipe)
            assertEquals(listOf(listOf("/usr/bin/python3", "-m", "debugpy", "--version")), guest.executedCommands)
            assertEquals(listOf(60_000L), guest.healthTimeouts)
            assertEquals(1, guest.ensureCount)
            val guestWrapper = java.io.File(record.installRoot, "payload/bin/debugpy").readText()
            val hostWrapper = java.io.File(record.installRoot, "payload/host-bin/debugpy").readText()
            assertTrue(guestWrapper.contains("exec '/usr/bin/python3' '-m' 'debugpy' \"${'$'}@\""))
            assertTrue(hostWrapper.contains("'/usr/bin/python3' '-m' 'debugpy' \"${'$'}@\""))
            assertEquals(listOf(ManagedPackageHealthCheck("host-bin/debugpy", listOf("--version"))), record.healthChecks)
        }
    }

    @Test fun repeatedAndConcurrentRequestsPreserveTheInstalledIntegrationGeneration() = runBlocking {
        fixture { root, authority, guest, installer ->
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val first = installer.install(recipe)
            val activationId = first.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]
            val manifest = java.io.File(first.installRoot, "SHA256SUMS").readBytes()
            val initialProbeCount = guest.executedCommands.size
            var verifications = 0
            authority.verifyHandler = { record ->
                verifications++
                LocalPackageInstallJournal.matchesActivation(java.io.File(record.installRoot), activationId!!) &&
                    LocalUbuntuNpmPolicy.sha256(java.io.File(record.installRoot, "SHA256SUMS")) == record.artifactSha256
            }
            val anotherCaller = LocalUbuntuAptInstaller(android.content.Context(root), authority, guest)
            val messages = mutableListOf<String>()
            val results = coroutineScope {
                (1..10).map { async { anotherCaller.install(recipe, messages::add) } }.awaitAll()
            }
            assertTrue(results.all { it.active && it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] == activationId })
            assertEquals(10, verifications)
            assertEquals(initialProbeCount, guest.executedCommands.size)
            assertArrayEquals(manifest, java.io.File(first.installRoot, "SHA256SUMS").readBytes())
            assertTrue(messages.any { "no command integration rebuild" in it })
        }
    }

    @Test fun anInactiveHealthyIntegrationIsActivatedWithoutReplacingItsFiles() = runBlocking {
        fixture { _, authority, guest, installer ->
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val first = installer.install(recipe)
            authority.receipts["${first.familyId}@${first.version}"] = first.copy(active = false)
            val count = guest.executedCommands.size
            val result = withTimeout(2_000) { installer.install(recipe) }
            assertTrue(result.active)
            assertTrue(authority.ownedReceipt(first.familyId, first.version)!!.active)
            assertEquals(first.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY], result.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            assertEquals(count, guest.executedCommands.size)
        }
    }

    @Test fun changedReviewedWrapperOrLauncherRequiresANewVerifiedIntegration() = runBlocking {
        fixture { _, authority, guest, installer ->
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val first = installer.install(recipe)
            val wrapper = java.io.File(first.installRoot, "payload/host-bin/git")
            wrapper.writeText(wrapper.readText().replace("/fixture/ubuntu-launcher", "/fixture/obsolete-launcher"))
            val result = installer.install(recipe)
            assertNotEquals(first.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY], result.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            assertTrue(wrapper.readText().contains("/fixture/ubuntu-launcher"))
            assertEquals(2, guest.executedCommands.size)
            assertEquals(result, authority.ownedReceipt(recipe.familyId, recipe.version))
        }
    }

    @Test fun failedRuntimeOrIntegrityVerificationCannotBeReused() = runBlocking {
        fixture { _, authority, guest, installer ->
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val first = installer.install(recipe)
            authority.verifyHandler = { false }
            val result = installer.install(recipe)
            assertNotEquals(first.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY], result.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            assertEquals(2, guest.executedCommands.size)
            assertTrue(guest.packages.contains("git"))
        }
    }

    @Test fun cancellationDuringReuseVerificationPreservesTheOriginalGeneration() = runBlocking {
        fixture { root, authority, _, installer ->
            val recipe = UbuntuAptCatalog.find("cli.git")!!
            val first = installer.install(recipe)
            authority.verifyHandler = { throw CancellationException("cancel current verification") }
            try { installer.install(recipe); fail("Cancellation became a fresh install") }
            catch (expected: CancellationException) { assertEquals("cancel current verification", expected.message) }
            assertEquals(first, authority.ownedReceipt(recipe.familyId, recipe.version))
            assertTrue(LocalPackageInstallJournal.matchesActivation(java.io.File(first.installRoot),
                first.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]!!))
            val paths = LocalPackageInstallJournal.paths(root.canonicalPath, recipe.familyId, recipe.version,
                LocalManagedPackageMetadata.KIND_UBUNTU_APT)
            assertFalse(paths.stage.exists()); assertFalse(paths.previous.exists())
        }
    }
}
