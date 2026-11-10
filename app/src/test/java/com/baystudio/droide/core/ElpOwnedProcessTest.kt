package com.baystudio.droide.core
import java.io.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ElpOwnedProcessTest {
    @Test fun blockingWriterDoesNotOwnFence() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val writer = ElpAsyncWriter(object: OutputStream() { override fun write(b: Int) { entered.countDown(); release.await() } }) { }
        writer.offer(byteArrayOf(1), 0, 1); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val begin = System.nanoTime(); writer.fence()
        assertTrue(System.nanoTime() - begin < 250_000_000)
        assertFalse(writer.settled); release.countDown()
        runBlocking { withTimeout(2000) { while (!writer.settled) delay(10) } }
    }
    @Test fun unknownFactoryRetainsPrivateCustody() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-owner").toFile()
        val parent = File(app, "runtime-tmp"); parent.mkdir()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = ElpExecutionOwner.allocate(app, "analysis:/fixture", { true }, ElpDeadline.after(5000))
        owner.beginFactory { entered.complete(Unit); release.await(); error("authored delayed factory failure") }
        entered.await(); owner.fence()
        val receipt = owner.retire(ElpDeadline.after(50))
        assertFalse(receipt.complete); assertFalse(receipt.factorySettled); assertTrue(owner.localRoot.isDirectory)
        release.complete(Unit); delay(50)
        assertFalse(owner.retire(ElpDeadline.after(500)).complete)
    }
    @Test fun privateTreeNeverTraversesSymlinks() {
        val parent = Files.createTempDirectory("elp-authored-private").toFile()
        Files.setPosixFilePermissions(parent.toPath(), PosixFilePermissions.fromString("rwx------"))
        val outside = Files.createTempFile("elp-authored-outside", ".txt").toFile().apply { writeText("preserve") }
        Files.createSymbolicLink(File(parent, "escape").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { ElpPrivateTree.inspect(parent, GraphqlWire.directoryIdentity(parent), true, ElpDeadline.after(1000)) }
        assertEquals("preserve", outside.readText())
    }
    @Test fun readerQueueOverflowFencesOwnerWithoutUnboundedGrowth() {
        val failed = CountDownLatch(1)
        val input = ElpAsyncInput(ByteArrayInputStream(ByteArray(100_000)), 16_384, false) { failed.countDown() }
        assertTrue(failed.await(2, TimeUnit.SECONDS)); assertTrue(input.failure != null)
        input.close()
    }

    private class CompletedFake: Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
    }
    @Test fun fastExitedOwnedProbeCanUseItsExactDurableBirthRecord() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-fast-exit").toFile(); File(app,"runtime-tmp").mkdir()
        val owner = ElpExecutionOwner.allocate(app,"probe:authored-fast-exit",{true},ElpDeadline.after(5000))
        owner.beginFactory { owner.create { CompletedFake() } }; owner.awaitFactory(ElpDeadline.after(2000))
        GraphqlWire.write(File(owner.localRoot,"host.json"), ("{\"schema\":1,\"token\":\"${owner.token}\",\"pid\":2147483000,\"start\":\"1\",\"group\":2147483000}").toByteArray())
        owner.awaitBirth(ElpDeadline.after(500))
        assertEquals(2147483000, owner.hostBirth!!.pid)
        assertTrue(owner.retire(ElpDeadline.after(2000)).complete)
    }

    @Test fun factoryAdmissionGateNeverContainsTheBlockingNativeCall() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-factory-gate").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = ElpExecutionOwner.allocate(app, "probe:factory-gate", { true }, ElpDeadline.after(5000))
        owner.beginFactory { owner.create { entered.countDown(); release.await(); CompletedFake() } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val gateEntered = CountDownLatch(1)
        Thread { ElpGenerationAdmission.withAdmission { gateEntered.countDown() } }.apply { isDaemon = true; start() }
        assertTrue("Blocking native factory held the generation gate", gateEntered.await(250, TimeUnit.MILLISECONDS))
        GraphqlWire.write(File(owner.localRoot,"host.json"), ("{\"schema\":1,\"token\":\"${owner.token}\",\"pid\":2147482999,\"start\":\"1\",\"group\":2147482999}").toByteArray())
        owner.fence(); release.countDown()
        withTimeout(3000) { while (owner.localRoot.exists()) delay(10) }
        assertTrue(owner.retire(ElpDeadline.after(500)).complete)
    }

    @Test fun revokedGenerationRejectsTheIrreversibleFactoryClaim() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-revoked-generation").toFile()
        var created = false
        val owner = ElpExecutionOwner.allocate(app, "probe:revoked-generation", { true }, ElpDeadline.after(5000), admissionCurrent = { false })
        owner.beginFactory { owner.create { created = true; CompletedFake() } }
        try { owner.awaitFactory(ElpDeadline.after(1000)); fail("Revoked generation was admitted") }
        catch (_: IllegalStateException) { }
        assertFalse(created)
        assertFalse(owner.retire(ElpDeadline.after(100)).complete)
    }

    private class DeliveryOwner : ElpPreparedOwner {
        override val identity = ElpManagedLaunchIdentity("lsp.elp", "2026.10.14-otp27.3", "policy", "a".repeat(64), "b".repeat(64), "c".repeat(64), "elp", "/native", "activation", "d".repeat(64), 1, emptyMap())
        private val observation = ElpSelectionInspection("/source", "/source/main.erl", "/workspace/main.erl", emptyList(), null, null, "observed")
        override val request = ElpOwnerRequest("/source", "/workspace", "life", 1, observation,
            ElpProjectAnalysisPermit("permit", "observed", "selection", identity.consentIdentity, false) { true })
        override val runtimeEvidence = ElpRuntimeEvidence("27.3", 27, "15.2", "aarch64-linux", "/otp", "/otp/lib", null, "selection", "library", false)
        override val token = "e".repeat(32)
        override val isCurrent get() = !fenced
        var fenced = false; var retirement: ElpDeadline? = null
        override fun fence() { fenced = true }
        override fun close() { fence() }
        override suspend fun retire(deadline: ElpDeadline): ElpTeardownReceipt {
            retirement = deadline
            return ElpTeardownReceipt(true, true, true, true, true, true, true, true, true)
        }
    }

    @Test fun dispatcherReturnCancellationRetiresOnlyItsUndeliveredOwner() = runBlocking {
        val captured = DeliveryOwner(); val replacement = DeliveryOwner(); val originalDeadline = ElpDeadline.after(5000)
        launch {
            try { ElpPreparedDelivery.deliver(originalDeadline) { currentCoroutineContext().cancel(); captured } }
            catch (_: CancellationException) { }
        }.join()
        assertTrue(captured.fenced); assertSame(originalDeadline, captured.retirement)
        assertFalse(replacement.fenced); assertNull(replacement.retirement)
    }

    @Test fun successfullyDeliveredOwnerIsNotRetiredByItsDeliveryWrapper() = runBlocking {
        val owner = DeliveryOwner()
        assertSame(owner, ElpPreparedDelivery.deliver(ElpDeadline.after(5000)) { owner })
        assertFalse(owner.fenced); assertNull(owner.retirement)
    }

    private fun activity(root: File) = LocalUbuntuElpLaunchAuthority.acquireActivity(root.absolutePath, "11111111-1111-4111-8111-111111111111")

    @Test fun captureActivityReleasesOnlyAfterItsExactCompleteCleanup() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-probe-activity").toFile()
        val owner = ElpExecutionOwner.allocateCapture(app, "probe:lease-complete", { true }, ElpDeadline.after(5000)) { activity(app) }
        assertThrows(IllegalStateException::class.java) { LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath) }
        assertTrue(owner.retire(ElpDeadline.after(2000)).complete)
        LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath)
    }

    @Test fun partiallyAllocatedCaptureNeverSynthesizesAReleaseReceipt() {
        val app = Files.createTempDirectory("elp-authored-probe-allocation-failure").toFile()
        val prior = ElpExecutionOwner.allocate(app, "probe:duplicate-lease", { true }, ElpDeadline.after(5000))
        assertThrows(IllegalArgumentException::class.java) {
            ElpExecutionOwner.allocateCapture(app, "probe:duplicate-lease", { true }, ElpDeadline.after(5000)) { activity(app) }
        }
        assertTrue(prior.localRoot.isDirectory)
        assertEquals(2, File(app, "runtime-tmp").listFiles()!!.count { it.name.startsWith("droide-elp-") })
        assertThrows(IllegalStateException::class.java) { LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath) }
    }

    @Test fun unknownCaptureFactoryRetainsItsActivityLease() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-probe-unknown").toFile()
        val owner = ElpExecutionOwner.allocateCapture(app, "probe:unknown-lease", { true }, ElpDeadline.after(5000)) { activity(app) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        owner.beginFactory { entered.complete(Unit); release.await(); error("authored unobserved factory return") }
        entered.await(); owner.fence()
        assertFalse(owner.retire(ElpDeadline.after(50)).complete)
        assertThrows(IllegalStateException::class.java) { LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath) }
        release.complete(Unit)
        Unit
    }

    @Test fun installerClaimRejectsAnalysisCaptureBeforeAllocation() {
        val app = Files.createTempDirectory("elp-authored-probe-installer").toFile()
        val installer = LocalUbuntuElpLaunchAuthority.acquireInstaller(app.absolutePath)
        try {
            assertThrows(IllegalStateException::class.java) {
                ElpExecutionOwner.allocateCapture(app, "probe:blocked-analysis", { true }, ElpDeadline.after(5000)) { activity(app) }
            }
            assertFalse(File(app, "runtime-tmp").exists())
        } finally { installer.releaseNeverEntered() }
    }

    @Test fun healthCaptureKeepsItsExistingInstallerAuthority() = runBlocking {
        val app = Files.createTempDirectory("elp-authored-probe-health").toFile()
        val installer = LocalUbuntuElpLaunchAuthority.acquireInstaller(app.absolutePath)
        val owner = ElpExecutionOwner.allocateCapture(app, "health:lease-separation", { true }, ElpDeadline.after(5000), null)
        assertTrue(owner.retire(ElpDeadline.after(2000)).complete)
        LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath, installer)
        assertThrows(IllegalStateException::class.java) { LocalUbuntuElpLaunchAuthority.requireNoActivity(app.absolutePath) }
        installer.releaseNeverEntered()
    }
}
