package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.ManagedExtensionInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionCatalogRefreshEffectsTest {
    @get:Rule val compose = createComposeRule()
    private val owner = Any()
    private val ready = ManagedExtensionInstaller.State(phase = ManagedExtensionInstaller.Phase.READY)

    @Test fun terminalEpochRefreshesVisibleCatalogButNeverHiddenPane() {
        var visible by mutableStateOf(true)
        var epoch by mutableStateOf(0L)
        var reads=0
        compose.setContent {
            if(visible) ExtensionCatalogRefreshEffects(owner,"a.kt",null,ready,{reads++},{fail(it)},environmentRevision=epoch)
        }
        compose.runOnIdle {assertEquals(1,reads);epoch=1}
        compose.runOnIdle {assertEquals(2,reads);visible=false}
        compose.runOnIdle {epoch=2}
        compose.runOnIdle {assertEquals(2,reads);visible=true}
        compose.runOnIdle {assertEquals(3,reads)}
    }

    @Test fun initialReadyReadsOnceAndUnchangedRecompositionDoesNotReadAgain() {
        var unrelated by mutableStateOf(0)
        var reads = 0
        compose.setContent {
            ExtensionCatalogRefreshEffects(owner, "a.kt", null, ready, { reads++ }, { fail(it) }, environmentRevision = 0L)
            Text("$unrelated")
        }
        compose.runOnIdle { assertEquals(1, reads); unrelated++ }
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun busyProgressDoesNotReadAndCompletedOperationReadsOnce() {
        var state by mutableStateOf(ready.copy(phase = ManagedExtensionInstaller.Phase.INSTALLING, operationActive = true))
        var reads = 0
        compose.setContent {
            ExtensionCatalogRefreshEffects(owner, "a.kt", null, state,
                { if (!state.running) reads++ }, { fail(it) }, environmentRevision = 0L)
        }
        compose.runOnIdle { assertEquals(0, reads); state = state.copy(phase = ManagedExtensionInstaller.Phase.ACTIVATING) }
        compose.runOnIdle { assertEquals(0, reads); state = state.copy(message = "Finishing prerequisite cleanup") }
        compose.runOnIdle { assertEquals(0, reads); state = ready }
        compose.runOnIdle { assertEquals(1, reads); state = ready.copy(log = "unchanged catalog progress detail") }
        compose.runOnIdle { assertEquals(1, reads) }
    }

    @Test fun activeFileAToBToACancelsObsoleteLifecycleReads() {
        var active by mutableStateOf("a.kt")
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        compose.setContent {
            ExtensionCatalogRefreshEffects(owner, active, null, ready, { file ->
                started += file
                var published = false
                try {
                    release.await()
                    currentCoroutineContext().ensureActive()
                    completed += file
                    published = true
                } finally { if (!published) cancelled += file }
            }, { fail(it) }, environmentRevision = 0L)
        }
        compose.runOnIdle { assertEquals(listOf("a.kt"), started); active = "b.kt" }
        compose.runOnIdle { assertEquals(listOf("a.kt", "b.kt"), started); active = "a.kt" }
        compose.runOnIdle {
            assertEquals(listOf("a.kt", "b.kt", "a.kt"), started)
            assertEquals(listOf("a.kt", "b.kt"), cancelled)
            release.complete(Unit)
        }
        compose.runOnIdle { assertEquals(listOf("a.kt"), completed) }
    }

    @Test fun licenseTransitionsReadOnlyWhenInputsOrTerminalStateChange() {
        var state by mutableStateOf(ManagedExtensionInstaller.State())
        var active by mutableStateOf("a.kt")
        var reads = 0
        compose.setContent {
            ExtensionCatalogRefreshEffects(owner, active, null, state, { reads++ }, { fail(it) }, environmentRevision = 0L)
        }
        compose.runOnIdle { assertEquals(1, reads); state = state.copy(phase = ManagedExtensionInstaller.Phase.LICENSE) }
        compose.runOnIdle { assertEquals(1, reads); active = "b.kt" }
        compose.runOnIdle { assertEquals(2, reads); state = ready }
        compose.runOnIdle { assertEquals(3, reads); state = state.copy(phase = ManagedExtensionInstaller.Phase.LICENSE) }
        compose.runOnIdle { assertEquals(3, reads); state = ManagedExtensionInstaller.State() }
        compose.runOnIdle { assertEquals(3, reads) }
    }

    @Test fun bridgeChangeStartsFreshReadAndFailureCanRetry() {
        var connection by mutableStateOf<Any?>(null)
        var reads = 0
        val errors = mutableListOf<String>()
        compose.setContent {
            ExtensionCatalogRefreshEffects(owner, "a.kt", connection, ready, {
                reads++
                if (reads == 1) error("bounded fixture failure")
            }, errors::add, environmentRevision = 0L)
        }
        compose.runOnIdle { assertEquals(1, reads); assertEquals(listOf("bounded fixture failure"), errors); connection = "device-a" }
        compose.runOnIdle { assertEquals(2, reads); connection = "device-b" }
        compose.runOnIdle { assertEquals(3, reads); assertEquals(1, errors.size) }
    }
}
