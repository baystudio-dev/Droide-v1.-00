package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CatalogModeAdmissionTest {
    private fun pendingWriter(active: Boolean) = runBlocking {
        val app = Files.createTempDirectory("catalog-mode-admission-").toFile()
        val release = CountDownLatch(1)
        try {
            val root = app.resolve("projects/default").apply { mkdirs() }
            val initial = DroideProject("default", "Default", root.canonicalPath)
            val disk = FileProjectStore(app)
            disk.save(listOf(initial)); disk.saveActiveId("default")
            val storage = app.resolve("catalog-startup").apply { mkdir() }
            val admission = ProjectCatalogAdmission(app, storage, 1_000_000)
            val guard = DefaultCatalogModeGuard(app)
            val entered = CompletableDeferred<Unit>()
            val guarded = FileProjectStore(app, StreamPublicationDurability.platform, { from, to, options ->
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Test publication barrier timed out" }
                Files.move(from, to, *options)
                Unit
            }, guard::requireDefaultMode)
            val expected = disk.loadCatalog().revision
            val writer = async(Dispatchers.IO) { runCatching {
                if (active) guarded.saveActiveId("later")
                else guarded.saveCatalog(listOf(initial.copy(name = "Legacy publication")), expected)
            } }
            withTimeout(10_000) { entered.await() }
            lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
            var writerFailure: Throwable? = null
            try {
                // The guarded writer started before enrollment existed, so the race is reachable.
                enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000)
                    .createWithMembership(app.resolve(".droide"), ProjectCatalogUpdate.AUTHORITY_ID,
                        admission.updateMembership, EmptyCoroutineContext)
                val choice = runCatching { admission.recordUpdateChoice(enrollment) }
                assertNotNull("Explicit mode choice crossed an in-flight legacy metadata publication", choice.exceptionOrNull())
                assertFalse(storage.resolve("startup-admission.json").exists())
                assertFalse(storage.resolve(".startup-admission.json.pending").exists())
            } finally {
                release.countDown()
                writerFailure = withTimeout(10_000) { writer.await() }.exceptionOrNull()
            }
            assertNotNull(writerFailure)
            assertTrue(ProjectMetadataPublication.isUnconfirmed(writerFailure!!))
            admission.recordUpdateChoice(enrollment)
            assertNotNull(admission.inspectUpdateChoice())
        } finally { release.countDown(); PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun updateChoiceRefusesAnAlreadyAdmittedLegacyCatalogPublication() = pendingWriter(false)
    @Test fun updateChoiceRefusesAnAlreadyAdmittedLegacyActivePublication() = pendingWriter(true)
    private class Fixture(val app: File) {
        val initial = DroideProject("default", "Default", app.resolve("projects/default").apply { mkdirs() }.canonicalPath)
        val disk = FileProjectStore(app)
        val storage = app.resolve("catalog-startup").apply { mkdir() }
        var hook: (String) -> Unit = {}
        val admission = ProjectCatalogAdmission(app, storage, 1_000_000) { hook(it) }
        lateinit var enrollment: CanonicalFileOperationJournal.Enrollment
        suspend fun start() {
            disk.save(listOf(initial)); disk.saveActiveId("default")
            enrollment = CanonicalJournalBootstrap(admission.updateSlot, 1_000_000)
                .createWithMembership(app.resolve(".droide"), ProjectCatalogUpdate.AUTHORITY_ID,
                    admission.updateMembership, EmptyCoroutineContext)
        }
        fun requireReleased() {
            val mutex = ProjectCatalogMutationLocks.forDirectory(app)
            val owner = Any()
            assertTrue(mutex.tryLock(owner))
            mutex.unlock(owner)
        }
    }
    private fun fixture(action: suspend (Fixture) -> Unit) = runBlocking {
        val app = Files.createTempDirectory("mode-choice-fixture-").toFile()
        try { val f = Fixture(app); f.start(); action(f) }
        finally { PathSecurity.deleteTreeNoFollow(app) }
    }

    @Test fun busyChoiceRefusesBeforeCallbacksOrChoiceArtifactsAndPreservesEnrollment() = fixture { f ->
        val before = f.storage.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() }
        var callbacks = 0
        f.hook = { callbacks++ }
        val mutex = ProjectCatalogMutationLocks.forDirectory(f.app)
        val owner = Any()
        check(mutex.tryLock(owner))
        try {
            assertNotNull(runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
            assertEquals(0, callbacks)
            assertEquals(before, f.storage.walkTopDown().filter { it.isFile }.associate { it.path to it.readBytes().toList() })
        } finally { mutex.unlock(owner) }
        f.admission.recordUpdateChoice(f.enrollment)
        f.requireReleased()
    }

    @Test fun choiceFailureBeforePublicationReleasesAndAllowsExplicitRetry() = fixture { f ->
        val expected = IOException("before choice publication")
        f.hook = { if (it == "update-choice-validated") throw expected }
        assertSame(expected, runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
        f.requireReleased()
        assertFalse(f.storage.resolve("startup-admission.json").exists())
        assertFalse(f.storage.resolve(".startup-admission.json.pending").exists())
        f.hook = {}
        f.admission.recordUpdateChoice(f.enrollment)
    }

    @Test fun failureAfterPublicationReleasesButDoesNotEraseOrRepeatChoice() = fixture { f ->
        val expected = IOException("lost choice return")
        f.hook = { if (it == "update-choice-after-publication") throw expected }
        assertSame(expected, runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
        f.requireReleased()
        val record = f.storage.resolve("startup-admission.json").readBytes()
        f.hook = {}
        assertNotNull(f.admission.inspectUpdateChoice())
        assertNotNull(runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
        assertArrayEquals(record, f.storage.resolve("startup-admission.json").readBytes())
    }

    @Test fun actualCallerCancellationReleasesChoiceWithoutPublishing() = fixture { f ->
        val job = Job()
        f.hook = { if (it == "update-choice-validated") job.cancel(CancellationException("cancel choice")) }
        val failure = runCatching { withContext(job) { f.admission.recordUpdateChoice(f.enrollment) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        f.requireReleased()
        assertFalse(f.storage.resolve("startup-admission.json").exists())
        assertFalse(f.storage.resolve(".startup-admission.json.pending").exists())
        f.hook = {}
        f.admission.recordUpdateChoice(f.enrollment)
    }

    @Test fun fatalCheckpointFailureReleasesWithoutChangingFailureType() = fixture { f ->
        val expected = AssertionError("fatal fixture")
        f.hook = { if (it == "update-choice-validated") throw expected }
        assertSame(expected, runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
        f.requireReleased()
        assertFalse(f.storage.resolve("startup-admission.json").exists())
    }

    @Test fun canonicalAliasSharesAdmissionAndUnrelatedApplicationRemainsUsable() = fixture { f ->
        val alias = File(f.app.parentFile, f.app.name + "/../" + f.app.name)
        assertSame(ProjectCatalogMutationLocks.forDirectory(f.app), ProjectCatalogMutationLocks.forDirectory(alias))
        val other = Files.createTempDirectory("unrelated-mode-app-").toFile()
        try {
            f.hook = { if (it == "update-choice-validated") runBlocking {
                FileProjectStore(other).saveActiveId("independent")
            } }
            f.admission.recordUpdateChoice(f.enrollment)
            assertEquals("independent", FileProjectStore(other).loadActiveId())
        } finally { PathSecurity.deleteTreeNoFollow(other) }
    }

    @Test fun replacedApplicationIdentityRefusesAndReleasesAdmission() = fixture { f ->
        val preserved = File(f.app.parentFile, f.app.name + "-preserved")
        try {
            f.hook = { if (it == "update-choice-validated") {
                Files.move(f.app.toPath(), preserved.toPath())
                f.app.mkdir()
            } }
            assertNotNull(runCatching { f.admission.recordUpdateChoice(f.enrollment) }.exceptionOrNull())
            f.requireReleased()
            assertFalse(preserved.resolve("catalog-startup/startup-admission.json").exists())
        } finally { PathSecurity.deleteTreeNoFollow(preserved) }
    }

    @Test fun successfulChoiceHoldsAdmissionAndThenBlocksModeGuardedCatalogAndActiveWrites() = fixture { f ->
        f.hook = { if (it == "update-choice-validated") {
            val owner = Any()
            val mutex = ProjectCatalogMutationLocks.forDirectory(f.app)
            val acquired = mutex.tryLock(owner)
            if (acquired) mutex.unlock(owner)
            assertFalse("Choice must hold app metadata admission through validation", acquired)
        } }
        f.admission.recordUpdateChoice(f.enrollment)
        var replacements = 0
        val guard = DefaultCatalogModeGuard(f.app)
        val guarded = FileProjectStore(f.app, StreamPublicationDurability.platform, { from, to, options ->
            replacements++; Files.move(from, to, *options); Unit
        }, guard::requireDefaultMode)
        val before = f.app.resolve(".droide/projects.json").readBytes()
        assertTrue(runCatching { guarded.save(listOf(f.initial.copy(name = "late"))) }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        assertTrue(runCatching { guarded.saveActiveId("late") }.exceptionOrNull() is DefaultCatalogModeGuard.Blocked)
        assertEquals(0, replacements)
        assertArrayEquals(before, f.app.resolve(".droide/projects.json").readBytes())
        assertEquals("default", f.disk.loadActiveId())
    }

    @Test fun waitingCatalogAndActivePublishersCannotEnterAdmissionCallbackDuringChoice() = fixture { f ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val waiting = mutableListOf<Deferred<Result<Any?>>>()
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val guard = DefaultCatalogModeGuard(f.app)
        val guarded = FileProjectStore(f.app, StreamPublicationDurability.platform,
            ProjectMetadataPublication::replace) { callbacks.incrementAndGet(); guard.requireDefaultMode() }
        try {
            f.hook = { if (it == "update-choice-validated") {
                for (active in listOf(false, true)) {
                    val started = CountDownLatch(1)
                    val waiter = scope.async {
                        started.countDown()
                        runCatching<Any?> {
                            if (active) guarded.saveActiveId("late")
                            else guarded.save(listOf(f.initial.copy(name = "late")))
                        }
                    }
                    waiting += waiter
                    check(started.await(5, TimeUnit.SECONDS))
                    assertNull("Publisher must remain held, not merely fail a later mode check",
                        runBlocking { withTimeoutOrNull(150) { waiter.await() } })
                }
                assertEquals(0, callbacks.get())
            } }
            f.admission.recordUpdateChoice(f.enrollment)
            waiting.forEach { assertTrue(it.await().exceptionOrNull() is DefaultCatalogModeGuard.Blocked) }
            assertEquals(2, callbacks.get())
            assertEquals("default", f.disk.loadActiveId())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun cancelledActivePublisherWaitingOnSharedAdmissionPerformsNoWrite() = fixture { f ->
        val mutex = ProjectCatalogMutationLocks.forDirectory(f.app)
        val owner = Any()
        check(mutex.tryLock(owner))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        var callbacks = 0
        val store = FileProjectStore(f.app, StreamPublicationDurability.platform,
            ProjectMetadataPublication::replace) { callbacks++ }
        try {
            val waiter = scope.launch { entered.complete(Unit); store.saveActiveId("cancelled") }
            entered.await()
            waiter.cancelAndJoin()
            assertEquals(0, callbacks)
            assertEquals("default", f.disk.loadActiveId())
        } finally { mutex.unlock(owner); scope.coroutineContext.job.cancelAndJoin() }
        f.requireReleased()
    }

}
