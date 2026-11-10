package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled storage timing, without reading or storing any real provider credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentProviderConnectionStatusTest {
    @get:Rule val compose = createComposeRule()

    private class Backend {
        val revision = MutableStateFlow(0L)
        val calls = AtomicInteger()
        val completed = AtomicInteger()
        val readOnMain = AtomicBoolean()
        val reader = AtomicReference<(String) -> Boolean>({ false })

        fun read(providerId: String): Boolean {
            calls.incrementAndGet()
            if (Looper.myLooper() == Looper.getMainLooper()) readOnMain.set(true)
            try {
                return reader.get()(providerId)
            } finally {
                completed.incrementAndGet()
            }
        }
    }

    private class BlockedRead(private val result: Boolean) {
        val entered = AtomicBoolean()
        val finished = AtomicBoolean()
        val release = CountDownLatch(1)

        fun read(): Boolean {
            entered.set(true)
            try {
                // Intentionally non-suspending, like a storage read that cancellation cannot interrupt.
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release its storage read" }
                return result
            } finally {
                finished.set(true)
            }
        }
    }

    private data class Render(val backend: Backend, val providerId: String, val connected: Boolean, val tick: Int)

    private var backend by mutableStateOf(Backend())
    private var providerId by mutableStateOf("alpha")
    private var tick by mutableStateOf(0)
    private var visible by mutableStateOf(true)
    private val rendered = AtomicReference<Render?>()
    private val history = CopyOnWriteArrayList<Render>()
    private val blockedReads = mutableListOf<BlockedRead>()

    private fun block(result: Boolean) = BlockedRead(result).also { blockedReads += it }

    @After fun releasePendingReads() {
        blockedReads.forEach { it.release.countDown() }
    }

    private fun mount() {
        compose.setContent {
            if (visible) {
                val source = backend
                val selected = providerId
                val generation = tick
                val connected = rememberAgentProviderConnected(source, selected, source.revision, source::read)
                SideEffect {
                    val frame = Render(source, selected, connected, generation)
                    rendered.set(frame)
                    history += frame
                }
                Text("$selected:$connected:$generation")
            } else {
                Text("Closed")
            }
        }
    }

    private fun awaitUiCondition(predicate: () -> Boolean) {
        // runOnIdle waits before its action, not for the resulting snapshot/frame work.

        compose.waitUntil(5_000) {
            compose.waitForIdle()
            predicate()
        }
    }

    private fun awaitRender(predicate: (Render) -> Boolean) {
        awaitUiCondition { rendered.get()?.let(predicate) == true }
    }

    @Test fun storageReadRunsOffMainAndUnrelatedRecompositionDoesNotRepeatIt() {
        backend.reader.set { true }
        mount()
        awaitRender { it.connected }
        repeat(5) { value ->
            compose.runOnIdle { tick = value + 1 }
            awaitRender { it.tick == value + 1 }
        }
        assertFalse(backend.readOnMain.get())
        assertEquals(1, backend.calls.get())
    }

    @Test fun revisionRefreshObservesConnectAndDisconnectWithoutPaneRecreation() {
        mount()
        awaitUiCondition { backend.calls.get() == 1 }
        compose.runOnIdle {
            backend.reader.set { true }
            backend.revision.value++
        }
        awaitRender { it.connected }
        val disconnect = block(false)
        compose.runOnIdle {
            backend.reader.set { disconnect.read() }
            backend.revision.value++
        }
        awaitUiCondition { disconnect.entered.get() }
        awaitRender { !it.connected }
        disconnect.release.countDown()
        awaitUiCondition { disconnect.finished.get() }
        compose.runOnIdle { assertFalse(rendered.get()!!.connected) }
        assertEquals(3, backend.calls.get())
    }

    @Test fun switchedProviderNeverInheritsPreviousConnectedStateEvenWhileNewReadIsBlocked() {
        val next = block(false)
        backend.reader.set { id -> if (id == "alpha") true else next.read() }
        mount()
        awaitRender { it.connected }
        compose.runOnIdle { providerId = "beta" }
        awaitUiCondition { next.entered.get() }
        awaitRender { it.providerId == "beta" && !it.connected }
        next.release.countDown()
        awaitUiCondition { next.finished.get() }
        compose.waitForIdle()
        assertTrue(history.filter { it.providerId == "beta" }.all { !it.connected })
    }

    @Test fun obsoleteBlockedProviderReadCannotConnectTheNewSelection() {
        val old = block(true)
        backend.reader.set { id -> if (id == "alpha") old.read() else false }
        mount()
        awaitUiCondition { old.entered.get() }
        compose.runOnIdle { providerId = "beta" }
        awaitUiCondition { backend.calls.get() == 2 }
        awaitRender { it.providerId == "beta" && !it.connected }
        old.release.countDown()
        awaitUiCondition { old.finished.get() }
        compose.waitForIdle()
        assertTrue(history.filter { it.providerId == "beta" }.all { !it.connected })
    }

    @Test fun replacementBackendWithSameRevisionStartsDisconnectedAndIgnoresOldCompletion() {
        val original = backend
        original.reader.set { true }
        mount()
        awaitRender { it.connected }
        val obsolete = block(true)
        compose.runOnIdle {
            original.reader.set { obsolete.read() }
            original.revision.value++
        }
        awaitUiCondition { obsolete.entered.get() }
        val replacement = Backend().also { it.revision.value = original.revision.value }
        val next = block(false)
        replacement.reader.set { next.read() }
        compose.runOnIdle { backend = replacement }
        awaitUiCondition { next.entered.get() }
        awaitRender { it.backend === replacement && !it.connected }
        obsolete.release.countDown()
        next.release.countDown()
        awaitUiCondition { obsolete.finished.get() && next.finished.get() }
        compose.waitForIdle()
        assertTrue(history.filter { it.backend === replacement }.all { !it.connected })
    }

    @Test fun changingOnlyBackendDoesNotReuseItsPredecessorsConnectedValue() {
        backend.reader.set { true }
        mount()
        awaitRender { it.connected }
        val next = block(false)
        val replacement = Backend().also { it.reader.set { next.read() } }
        compose.runOnIdle { backend = replacement }
        awaitUiCondition { next.entered.get() }
        awaitRender { it.backend === replacement && !it.connected }
        next.release.countDown()
        awaitUiCondition { next.finished.get() }
        compose.waitForIdle()
        assertTrue(history.filter { it.backend === replacement }.all { !it.connected })
    }

    @Test fun newerRevisionDiscardsAnOlderBlockedConnectedReadForTheSameProvider() {
        val old = block(true)
        backend.reader.set { old.read() }
        mount()
        awaitUiCondition { old.entered.get() }
        compose.runOnIdle {
            backend.reader.set { false }
            backend.revision.value++
        }
        awaitUiCondition { backend.calls.get() == 2 && backend.completed.get() == 1 }
        old.release.countDown()
        awaitUiCondition { old.finished.get() }
        compose.waitForIdle()
        assertTrue(history.all { !it.connected })
    }

    @Test fun ordinaryReadFailureStaysDisconnectedAndLaterRevisionCanRecover() {
        backend.reader.set { throw IllegalStateException("Storage temporarily unavailable") }
        mount()
        awaitUiCondition { backend.completed.get() == 1 }
        awaitRender { !it.connected }
        compose.runOnIdle {
            backend.reader.set { true }
            backend.revision.value++
        }
        awaitRender { it.connected }
        assertEquals(2, backend.calls.get())
    }

    @Test fun disposedReadCannotPopulateTheReopenedPane() {
        val old = block(true)
        backend.reader.set { old.read() }
        mount()
        awaitUiCondition { old.entered.get() }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            backend.reader.set { false }
            tick++
            visible = true
        }
        awaitUiCondition { backend.calls.get() == 2 }
        awaitRender { it.tick == 1 && !it.connected }
        old.release.countDown()
        awaitUiCondition { old.finished.get() }
        compose.waitForIdle()
        assertTrue(history.filter { it.tick == 1 }.all { !it.connected })
    }
}
