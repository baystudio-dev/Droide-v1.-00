package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.ManagedExtensionInstaller
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionCatalogRefreshLateCancellationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun obsoleteNonCooperativeFailureCannotPublishErrorOrHandledInputs() {
        val owner = Any()
        var active by mutableStateOf("a.kt")
        var state by mutableStateOf(ManagedExtensionInstaller.State())
        val release = CompletableDeferred<Unit>()
        val reads = mutableListOf<String>()
        val errors = mutableListOf<String>()
        try {
            compose.setContent {
                ExtensionCatalogRefreshEffects(owner, active, null, state, { file ->
                    reads += file
                    if (file == "a.kt") withContext(NonCancellable) {
                        release.await()
                        throw IOException("obsolete failure")
                    }
                }, errors::add, environmentRevision = 0L)
            }
            compose.runOnIdle { assertEquals(listOf("a.kt"), reads); active = "b.kt" }
            compose.runOnIdle { assertEquals(listOf("a.kt", "b.kt"), reads); release.complete(Unit) }
            compose.runOnIdle {
                assertTrue(errors.isEmpty())
                state = state.copy(phase = ManagedExtensionInstaller.Phase.LICENSE)
            }
            compose.runOnIdle {
                assertTrue(errors.isEmpty())
                assertEquals(listOf("a.kt", "b.kt"), reads)
            }
        } finally { release.complete(Unit) }
    }
}
