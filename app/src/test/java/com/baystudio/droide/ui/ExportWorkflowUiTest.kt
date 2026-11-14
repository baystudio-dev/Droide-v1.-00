package com.baystudio.droide.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
class ExportWorkflowUiTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("export-workflow-ui-").toFile()
    private val app get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val terminals by lazy { TerminalManager(scope, root) }
    private val bridge by lazy { DeviceBridgeManager(app, scope) }
    @Before fun setup() {
        ShadowProcess.setPid(4242); ApplicationWorkAdmission.initialize(app); ApplicationWorkAdmission.reopenFromUser()
        // Robolectric's MimeTypeMap starts empty, unlike Android's platform table.
        shadowOf(android.webkit.MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
            addExtensionMimeTypeMapping("zip", "application/zip")
        }
    }
    @After fun cleanup() { terminals.dispose(false); bridge.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }

    private fun mountExport(path: String, directory: Boolean) {
        val owner = ExportSourceAdmission("project", root, object : WorkspaceDocumentAuthority {
            override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
            override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = emptyList()
        }, {})
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            val export = rememberExplorerExportUi(owner, "Workspace", false, { EditorSaveReport() }, {})
            Button(onClick = { export.open(path, directory) }) { Text("Start test export") }
        } } }
        compose.onNodeWithText("Start test export").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Format").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun selectFormat(label: String) {
        // Dispatch the row's accessibility click after scrolling; modal-sheet
        // animation can move its screen coordinates between host touch events.
        compose.onNodeWithText(label).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    }

    @Test fun switchingArchiveFormatPreservesRealTarExtensionAndCustomNames() {
        root.resolve("backup.tar").writeText("source archive bytes")
        mountExport("backup.tar", false)
        compose.onNode(hasSetTextAction() and hasText("backup.tar")).performScrollTo().assertExists()
        selectFormat("TAR archive")
        compose.onNode(hasSetTextAction() and hasText("backup.tar.tar")).performScrollTo().performTextReplacement("packed.tar")
        selectFormat("Original file")
        compose.onNode(hasSetTextAction() and hasText("backup.tar")).performScrollTo().assertExists()
        selectFormat("TAR archive")
        compose.onNode(hasSetTextAction() and hasText("packed.tar")).performScrollTo().assertExists()
    }

    @Test fun ordinaryFolderDefaultsToSelectedNameAndKeepsUserRenameAcrossFormats() {
        root.resolve("dist").mkdir()
        mountExport("dist", true)
        selectFormat("Ordinary folder")
        compose.onNode(hasSetTextAction() and hasText("dist")).performScrollTo().performTextReplacement("My release")
        selectFormat("TAR archive")
        compose.onNode(hasSetTextAction() and hasText("dist.tar")).performScrollTo().assertExists()
        selectFormat("Ordinary folder")
        compose.onNode(hasSetTextAction() and hasText("My release")).performScrollTo().assertExists()
    }

    @Test fun settingsOffersExportAndHistoryWithRealCallbacks() {
        var exports = 0; var histories = 0; var explorer = 0
        val development = AndroidDevelopmentManager(app, root, bridge)
        val themes = ThemeManager(root); val formatter = FormatterManager(root, terminals)
        val github = GitHubAccountManager(app)
        compose.setContent { DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
            SettingsScreen(themes, formatter, false, false, true, CodingKeyboardMode.COMPACT,
                CodeStyleDefaults(), development, bridge, github, "Export project", PermissionPolicyDocument.EMPTY,
                onWordwrap = {}, onHardwareShortcuts = {}, onAccessoryKeysComfortable = {},
                onCodingKeyboardMode = {}, onDetectIndentation = {}, onIndentStyle = {}, onTabWidth = {},
                onIndentSize = {}, onContinuationIndent = {}, onTheme = {},
                onPermissionRuleChange = { _, _ -> PermissionPolicyDocument.EMPTY },
                onOpenProviders = {}, onOpenModels = {}, onOpenExtensions = {}, onOpenFiles = { explorer++ },
                onOpenTerminal = {}, onOpenGit = {}, onOpenRunDebug = {}, onOpenAgent = {},
                onOpenAgentSessions = {}, onOpenCapabilityHealth = {}, mcpHealth = emptyList(),
                onRetryMcp = { "" }, onOpenMcpConfig = {}, onExportWorkspace = { exports++ },
                onExportStatus = { histories++ }, initialCategoryName = "FILES", onDismiss = {})
        } } }
        compose.onNodeWithText("Export all").performScrollTo().performClick()
        compose.onNodeWithText("Export status").performScrollTo().performClick()
        compose.onNodeWithText("Open Files workbench").performScrollTo().performClick()
        assertEquals(1, exports); assertEquals(1, histories); assertEquals(1, explorer)
        listOf("export", "export all", "export folder", "export status", "device storage").forEach {
            assertTrue(it, SettingsDiscovery.matches("FILES", it))
        }
    }

    @Test fun pickerUsesCorrectMimeAndCreatesOnlyFreshOutputWithReadbackGrants() {
        for ((name, mime) in listOf("app.apk" to "application/vnd.android.package-archive",
            "Photo.JPG" to "image/jpeg", "archive.zip" to "application/zip", "blob.unknown-file-type" to "application/octet-stream")) {
            val intent = exportPickerIntent(ExportFormat.RAW_FILE, name)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(mime, intent.type)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, intent.flags)
        }
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, exportPickerIntent(ExportFormat.TREE, "dist").action)
        assertEquals("application/x-tar", exportPickerIntent(ExportFormat.TAR, "release.tar").type)
        assertEquals("application/vnd.android.package-archive", exportPickerIntent(ExportFormat.RAW_FILE, "My release", "build/app.apk").type)
        assertThrows(IllegalArgumentException::class.java) { exportPickerIntent(ExportFormat.TREE, "../outside") }
    }

    @Test fun invalidWorkspaceAndUnixLeafNamesHaveEditableSafeSuggestions() {
        assertEquals("My_app", ExportFileNames.suggested("", "My/app"))
        assertEquals("line_break.txt", ExportFileNames.suggested("src/line\nbreak.txt", "Workspace"))
        assertEquals("droide-export", ExportFileNames.suggested("", ".."))
        assertEquals("😀.apk", ExportFileNames.suggested("dist/😀.apk", "Workspace"))
    }
}
