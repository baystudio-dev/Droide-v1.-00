package com.baystudio.droide.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceImportController
import com.baystudio.droide.core.WorkspaceImportSource
import com.baystudio.droide.core.WorkspaceImportSourceMetadata
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Authored real-Explorer menu regression; execution remains a separate approval gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
class ExplorerImportRecoveryMenuTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("explorer-copy-recovery-").toFile()
    private val files = FileRepository(root)
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val imports = WorkspaceImportController(files, lifetime, object : WorkspaceImportSource {
        override suspend fun metadata(uri: Uri): WorkspaceImportSourceMetadata = error("No picker request is authorized in this test")
        override suspend fun open(uri: Uri): InputStream = error("Recovery must not open an import source")
    })

    @After fun cleanup() { runBlocking { imports.closeAndJoin() }; lifetime.cancel(); root.deleteRecursively() }

    @Test fun explorerHasOneMenuEntryAndNoAlwaysVisibleRecoveryControl() {
        val state = ExplorerWorkspaceState()
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            compose.TestFileExplorer(files, state, onOpen = {}, imports = imports)
        } } }
        compose.onNodeWithText("Import recovery").assertDoesNotExist()
        compose.onNodeWithText("Inspect").assertDoesNotExist()
        assertFalse(root.resolve(".droide/copy-recovery").exists())
        compose.onNodeWithContentDescription("Explorer options").performClick()
        compose.onAllNodesWithText("Import recovery").assertCountEquals(1)
        val entry = compose.onNodeWithText("Import recovery")
        entry.assertIsEnabled()
        assertTrue(entry.getUnclippedBoundsInRoot().size.height.value >= 48f)
        compose.onNodeWithText("Recover misplaced files").assertExists()
        compose.onNodeWithText("Inspect").assertDoesNotExist()
    }
}
