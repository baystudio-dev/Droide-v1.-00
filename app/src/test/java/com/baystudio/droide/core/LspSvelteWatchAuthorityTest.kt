package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSvelteWatchAuthorityTest {
    private fun fixture(block: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("svelte-watch-").toFile()
        try { block(root) } finally {
            coroutineContext.cancelChildren()
            Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
    private fun registration(id: String = "native", kind: Int? = null): JsonObject = buildJsonObject {
        put("registrations", buildJsonArray { add(buildJsonObject {
            put("id", id); put("method", LspSvelteWatchAuthority.METHOD)
            put("registerOptions", buildJsonObject { put("watchers", buildJsonArray { add(buildJsonObject {
                put("globPattern", LspSvelteFilePoller.GLOB); if (kind != null) put("kind", kind)
            }) }) })
        }) })
    }
    private fun unregister(vararg ids: String): JsonObject = buildJsonObject {
        put("unregisterations", buildJsonArray { ids.forEach { id -> add(buildJsonObject {
            put("id", id); put("method", LspSvelteWatchAuthority.METHOD)
        }) } })
    }
    private fun CoroutineScope.authority(root: File,
        invalidated: (LspSvelteWatchAuthority.Invalidation) -> Unit = {},
        events: suspend (LspSvelteWatchAuthority.EventDrain) -> Unit = {},
        settings: LspSvelteWatchAuthority.Settings = LspSvelteWatchAuthority.Settings(),
        clock: () -> Long = System::nanoTime,
        initial: LspSvelteFilePoller.Snapshot? = null,
        mapper: (File) -> String? = { it.toURI().toString() },
    ) = LspSvelteWatchAuthority("owner-1", root, this, mapper, invalidated, events,
        settings = settings, clockNanos = clock, initialSnapshot = initial)

    @Test fun completeObservationCallbackRunsOutsideLockForEachGeneration() = fixture { root ->
        File(root, "a.ts").writeText("saved")
        var now = 1_000_000L
        val seen = mutableListOf<LspSvelteWatchAuthority.Observation>()
        lateinit var authority: LspSvelteWatchAuthority
        authority = LspSvelteWatchAuthority("owner-1", root, this, { it.toURI().toString() }, {}, {},
            clockNanos = { now }, onObservation = { token ->
                val checked = CountDownLatch(1)
                val current = AtomicBoolean(false)
                val worker = Thread { try { current.set(authority.isCurrent(token)) } finally { checked.countDown() } }
                worker.start()
                assertTrue("Owner callback must not retain the observer lock", checked.await(1, TimeUnit.SECONDS))
                worker.join(); assertTrue(current.get()); seen.add(token)
            })
        authority.register(registration())
        val old = authority.observation(); assertSame(old, seen.single())
        now += 10_001_000_000L
        val next = authority.observation()
        assertEquals(2, seen.size); assertSame(next, seen.last()); assertTrue(next.generation > old.generation)
        authority.close()
    }

    @Test fun registrationRequiresRealCompleteBaselineAndInitialFilesAreNotCreated() = fixture { root ->
        File(root, "a.ts").writeText("saved")
        val eventCount = AtomicInteger()
        val authority = authority(root, events = { eventCount.incrementAndGet() })
        val result = authority.register(registration())
        assertTrue(result is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        val observation = authority.awaitRegisteredObservation()
        assertTrue(authority.isCurrent(observation))
        assertTrue(authority.registrationCurrent(result))
        assertFalse(authority.registrationCurrent((result as LspSvelteWatchAuthority.RegistrationResult.Accepted).copy()))
        assertEquals(0, eventCount.get())
        assertTrue(observation.snapshot.entries.containsKey("a.ts"))
        authority.close()
        assertFalse(authority.isCurrent(observation))
        assertFalse(authority.registrationCurrent(result))
    }

    @Test fun simultaneousRequestsShareOneImmutableObservation() = fixture { root ->
        repeat(200) { File(root, "$it.ts").writeText("x".repeat(1024)) }
        val authority = authority(root)
        assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        val observations = List(80) { async { authority.observation() } }.awaitAll()
        observations.forEach { assertSame(observations.first(), it) }
        authority.close()
    }

    @Test fun expiredCoverageCoalescesAndOldGenerationIsNotPromoted() = fixture { root ->
        File(root, "a.ts").writeText("saved")
        var now = 1_000_000L
        val authority = authority(root, clock = { now })
        authority.register(registration())
        val old = authority.observation()
        now += 10_001_000_000L
        assertFalse(authority.isCurrent(old))
        val fresh = List(30) { async { authority.observation() } }.awaitAll()
        fresh.forEach { assertSame(fresh.first(), it) }
        assertEquals(old.generation + 1, fresh.first().generation)
        assertFalse(authority.isCurrent(old))
        assertTrue(authority.isCurrent(fresh.first()))
        authority.close()
    }

    @Test fun realChangeInvalidatesBeforeEventAndDrainTokenDiesOnClose() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val sequence = mutableListOf<String>()
        val drained = CompletableDeferred<LspSvelteWatchAuthority.EventDrain>()
        lateinit var authority: LspSvelteWatchAuthority
        authority = authority(root, invalidated = { sequence.add("invalidate") }, events = { token ->
            assertEquals(listOf("invalidate"), sequence)
            assertTrue(authority.isDrainCurrent(token))
            assertFalse(authority.isDrainCurrent(token.copy()))
            assertFalse(authority.isDrainCurrent(token.copy(events = emptyList())))
            sequence.add("events"); drained.complete(token)
        }, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration())
        val old = authority.observation()
        File(root, "a.ts").writeText("new")
        val drain = withTimeout(3000) { drained.await() }
        assertFalse(authority.isCurrent(old))
        assertEquals(2, drain.events.single().type)
        assertEquals(File(root, "a.ts").toURI().toString(), drain.events.single().uri)
        assertEquals(setOf("native"), drain.registrationIds)
        authority.close(); authority.close()
        assertFalse(authority.isDrainCurrent(drain))
    }

    @Test fun createDeleteAndSentinelEventsAreFaithful() = fixture { root ->
        suspend fun change(path: String, before: String?, after: String?, type: Int?) {
            val file = File(root, path)
            if (before != null) file.writeText(before)
            val drained = CompletableDeferred<LspSvelteWatchAuthority.EventDrain>()
            val authority = authority(root, events = { drained.complete(it) }, settings = LspSvelteWatchAuthority.Settings(20, 100))
            authority.register(registration())
            if (after == null) file.delete() else file.writeText(after)
            val token = withTimeout(3000) { drained.await() }
            if (type == null) assertTrue(token.events.isEmpty()) else assertEquals(type, token.events.single().type)
            authority.close()
        }
        change("created.ts", null, "new", 1)
        change("deleted.js", "old", null, 3)
        change("unsupported.tsx", "old", "new", null)
        change("yarn.lock", "old", "new", null)
    }

    @Test fun configChangeIsReportedInCompletedDrain() = fixture { root ->
        File(root, "svelte.config.js").writeText("old")
        val observed = CompletableDeferred<LspSvelteWatchAuthority.Invalidation>()
        val drained = CompletableDeferred<LspSvelteWatchAuthority.EventDrain>()
        val authority = authority(root, { observed.complete(it) }, { drained.complete(it) }, LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration())
        File(root, "svelte.config.js").writeText("new")
        assertTrue(withTimeout(3000) { observed.await() }.criticalChange)
        assertTrue(withTimeout(3000) { drained.await() }.criticalChanged)
        authority.close()
    }

    @Test fun startupSnapshotDetectsMutationBeforeRegistration() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val initial = LspSvelteFilePoller(root).scan()
        File(root, "a.ts").writeText("new")
        val observed = mutableListOf<LspSvelteWatchAuthority.Invalidation>()
        val authority = authority(root, invalidated = { observed.add(it) }, initial = initial)
        assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
        assertEquals(LspSvelteWatchAuthority.InvalidationKind.DIFFERENCE, observed.first().kind)
        authority.close()
    }

    @Test fun malformedBatchesRejectAtomicallyAndRetire() = fixture { root ->
        val good = registration().toString()
        val variants = listOf(
            "{}", "{\"registrations\":[]}", good.replace("native", ""),
            good.replace("workspace/didChangeWatchedFiles", "workspace/anything"),
            good.replace(LspSvelteFilePoller.GLOB, "**/*.ts"),
            good.replace("\"registerOptions\":", "\"unknown\":null,\"registerOptions\":"),
            good.replace("\"watchers\":", "\"unknown\":true,\"watchers\":"),
            good.replace("\"globPattern\":", "\"unknown\":true,\"globPattern\":"),
            registration(kind = 0).toString(), registration(kind = 8).toString(),
            registration(kind = 7).toString().replace("\"kind\":7", "\"kind\":\"7\""),
            registration(kind = 7).toString().replace("\"kind\":7", "\"kind\":7.0"),
            registration(kind = 7).toString().replace("\"kind\":7", "\"kind\":null"),
            good.replace("\"globPattern\":\"${LspSvelteFilePoller.GLOB}\"", "\"globPattern\":{\"pattern\":\"${LspSvelteFilePoller.GLOB}\",\"baseUri\":\"file:///\"}"),
        )
        for (variant in variants) {
            val invalidations = mutableListOf<LspSvelteWatchAuthority.Invalidation>()
            val authority = authority(root, invalidated = { invalidations.add(it) })
            val result = authority.register(Json.parseToJsonElement(variant))
            assertTrue("Must reject $variant", result is LspSvelteWatchAuthority.RegistrationResult.Rejected)
            assertEquals(1, invalidations.size)
            assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
            authority.close()
        }
    }

    @Test fun duplicateBatchAndExistingIdsAreRejected() = fixture { root ->
        val native = registration()["registrations"]!!.jsonArray.single()
        val duplicate = buildJsonObject { put("registrations", JsonArray(listOf(native, native))) }
        var authority = authority(root)
        assertTrue(authority.register(duplicate) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
        authority.close()
        authority = authority(root)
        assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
        authority.close()
    }

    @Test fun exactMasksComposeCoverageAndRequiredUnregisterInvalidates() = fixture { root ->
        val batch = buildJsonObject { put("registrations", JsonArray(listOf(1, 2, 4).map { mask ->
            registration("mask-$mask", mask)["registrations"]!!.jsonArray.single()
        })) }
        val invalidations = mutableListOf<LspSvelteWatchAuthority.Invalidation>()
        val authority = authority(root, invalidated = { invalidations.add(it) })
        assertTrue(authority.register(batch) is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        val observation = authority.observation()
        assertTrue(authority.unregister(unregister("mask-1")) is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        assertFalse(authority.isCurrent(observation))
        assertEquals(LspSvelteWatchAuthority.InvalidationKind.UNREGISTERED, invalidations.single().kind)
        authority.close()
    }

    @Test fun redundantUnregisterKeepsCoverageButInvalidatesPriorGeneration() = fixture { root ->
        val authority = authority(root)
        authority.register(registration())
        authority.register(registration("redundant"))
        val prior = authority.observation()
        val result = authority.unregister(unregister("redundant"))
        assertTrue(result is LspSvelteWatchAuthority.RegistrationResult.Accepted)
        assertFalse(authority.isCurrent(prior))
        assertTrue(authority.registrationCurrent(result))
        assertTrue(authority.isCurrent(authority.observation()))
        authority.close()
    }

    @Test fun unknownUnregisterAndUnknownFieldsFailClosed() = fixture { root ->
        val variants = listOf(unregister("unknown"), Json.parseToJsonElement(unregister("native").toString().replace("unregisterations", "unregistrations")),
            Json.parseToJsonElement(unregister("native").toString().replace("\"id\":", "\"extra\":1,\"id\":")))
        for (params in variants) {
            val authority = authority(root)
            authority.register(registration())
            assertTrue(authority.unregister(params) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
            authority.close()
        }
    }

    @Test fun missingRegistrationWaitIsBoundedAndInvalidatesCoverage() = fixture { root ->
        val invalidations = mutableListOf<LspSvelteWatchAuthority.Invalidation>()
        val authority = authority(root, invalidated = { invalidations.add(it) }, settings = LspSvelteWatchAuthority.Settings(20, 100, 40))
        try { authority.awaitRegisteredObservation(); fail("Missing registration cannot become ready") }
        catch (_: LspSvelteWatchAuthority.UnavailableException) { }
        assertEquals(LspSvelteWatchAuthority.InvalidationKind.COVERAGE_FAILURE, invalidations.single().kind)
        authority.close()
    }

    @Test fun lostMappingRetiresWithoutInventedEvents() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val invalidations = CompletableDeferred<Unit>()
        val events = AtomicInteger()
        val authority = authority(root, invalidated = { if (it.kind == LspSvelteWatchAuthority.InvalidationKind.COVERAGE_FAILURE) invalidations.complete(Unit) },
            events = { events.incrementAndGet() }, settings = LspSvelteWatchAuthority.Settings(20, 100), mapper = { null })
        authority.register(registration())
        File(root, "a.ts").writeText("new")
        withTimeout(3000) { invalidations.await() }
        assertEquals(0, events.get())
        authority.close()
    }

    @Test fun closePreventsPollingAndCallbackResurrection() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val callbacks = AtomicInteger()
        val authority = authority(root, invalidated = { callbacks.incrementAndGet() }, events = { callbacks.incrementAndGet() }, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration())
        authority.close(); File(root, "a.ts").writeText("new")
        delay(100)
        assertEquals(0, callbacks.get())
        assertTrue(authority.register(registration()) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
    }

    @Test fun callbackCanReenterCurrentGuardAndCloseWithoutLockInversion() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val done = CompletableDeferred<Unit>()
        lateinit var authority: LspSvelteWatchAuthority
        lateinit var observation: LspSvelteWatchAuthority.Observation
        authority = authority(root, invalidated = {
            assertFalse(authority.isCurrent(observation))
            authority.close(); done.complete(Unit)
        }, events = { fail("Closed owner cannot drain") }, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration()); observation = authority.observation()
        File(root, "a.ts").writeText("new")
        withTimeout(3000) { done.await() }
    }
    @Test fun unchangedScheduledPassStillReplacesGeneration() = fixture { root ->
        File(root, "a.ts").writeText("saved")
        val authority = authority(root, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration())
        val original = authority.observation()
        val next = withTimeout(3000) {
            var result = authority.observation()
            while (result.generation == original.generation) { delay(5); result = authority.observation() }
            result
        }
        assertEquals(original.snapshot.aggregateDigest, next.snapshot.aggregateDigest)
        assertFalse(authority.isCurrent(original))
        assertTrue(authority.isCurrent(next))
        authority.close()
    }

    @Test fun registrationBatchBoundsAndPartialCoverageAreExplicitFailures() = fixture { root ->
        val oversized = buildJsonObject { put("registrations", JsonArray((0..8).map {
            registration("id-$it")["registrations"]!!.jsonArray.single()
        })) }
        val requests = listOf(oversized, registration("x".repeat(129)), registration("bad\nid")) + (1..6).map { registration(kind = it) }
        for (request in requests) {
            val authority = authority(root)
            assertTrue(authority.register(request) is LspSvelteWatchAuthority.RegistrationResult.Rejected)
            authority.close()
        }
    }

    @Test fun ambiguousCoverageAndLaterSymlinkStopWithoutEvents() = fixture { root ->
        val invalidations = CompletableDeferred<Unit>()
        val events = AtomicInteger()
        val authority = authority(root, invalidated = {
            if (it.kind == LspSvelteWatchAuthority.InvalidationKind.COVERAGE_FAILURE) invalidations.complete(Unit)
        }, events = { events.incrementAndGet() }, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration())
        Files.createSymbolicLink(File(root, "link").toPath(), File("missing").toPath())
        withTimeout(3000) { invalidations.await() }
        assertEquals(0, events.get())
        authority.close()
    }

    @Test fun registrationWaitCoalescesWithActualStartupHandler() = fixture { root ->
        val authority = authority(root)
        val waiters = List(15) { async { authority.awaitRegisteredObservation() } }
        delay(20)
        assertTrue(waiters.none { it.isCompleted })
        val result = authority.register(registration()) as LspSvelteWatchAuthority.RegistrationResult.Accepted
        waiters.awaitAll().forEach { assertSame(result.observation, it) }
        authority.close()
    }

    @Test fun invalidationCallbackNeverOwnsTheHelperMonitor() = fixture { root ->
        File(root, "a.ts").writeText("old")
        val done = CompletableDeferred<Unit>()
        lateinit var authority: LspSvelteWatchAuthority
        lateinit var observation: LspSvelteWatchAuthority.Observation
        authority = authority(root, invalidated = {
            val checked = CountDownLatch(1)
            val other = Thread { authority.isCurrent(observation); checked.countDown() }
            other.start()
            val released = checked.await(1, TimeUnit.SECONDS)
            if (released) done.complete(Unit) else done.completeExceptionally(AssertionError("Callback held the helper monitor"))
        }, settings = LspSvelteWatchAuthority.Settings(20, 100))
        authority.register(registration()); observation = authority.observation()
        File(root, "a.ts").writeText("new")
        withTimeout(3000) { done.await() }
        authority.close()
    }

    @Test fun freshCompletedGenerationIsReusableWhileNextPassIsInFlight() = fixture { root ->
        File(root, "a.ts").writeText("saved")
        val caller = Thread.currentThread().id
        val holdNextPass = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val authority = authority(root, settings = LspSvelteWatchAuthority.Settings(30, 2_000), clock = {
            if (Thread.currentThread().id != caller && holdNextPass.compareAndSet(true, false)) {
                entered.complete(Unit)
                check(release.await(3, TimeUnit.SECONDS))
            }
            System.nanoTime()
        })
        authority.register(registration())
        val completed = authority.observation()
        holdNextPass.set(true)
        try {
            withTimeout(3000) { entered.await() }
            assertTrue(authority.isCurrent(completed))
            repeat(30) { assertSame(completed, authority.observation()) }
        } finally {
            release.countDown()
            authority.close()
        }
    }

}
