package com.baystudio.droide.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.ExplorerSortOrder
import com.baystudio.droide.core.FileEntry
import com.baystudio.droide.core.FileRepository
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

/** Real Explorer dialogs, Android picker callbacks and repository bytes, without a device claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplorerProjectWorkflowTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("droide-explorer-project-").toFile()
    private var syncFailurePath: String? = null
    private var folderSyncFailurePath: String? = null
    private val files = FileRepository(root, onMutation = { mutation ->
        if (mutation is FileRepository.Mutation.Write && mutation.path == syncFailurePath) {
            throw IOException("Workspace synchronization unavailable")
        }
        if (mutation is FileRepository.Mutation.CreateDirectory && mutation.path == folderSyncFailurePath) {
            throw IOException("Workspace folder synchronization unavailable")
        }
    })
    private val state = ExplorerWorkspaceState()
    private val opened = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val guarded = mutableListOf<String>()
    private var blockedPaths = emptySet<String>()
    private lateinit var mainWindowRoot: View
    private val picker = PickerRegistry()
    private val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var imports: com.baystudio.droide.core.WorkspaceImportController? = null
    private val explorerVisible = androidx.compose.runtime.mutableStateOf(true)
    private val pickerOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = picker
    }

    @After fun release() {
        kotlinx.coroutines.runBlocking { imports?.closeAndJoin() }
        importScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        root.deleteRecursively()
    }

    private fun mount() {
        val controller = com.baystudio.droide.core.WorkspaceImportController(RuntimeEnvironment.getApplication(), files, importScope)
        imports = controller
        compose.setContent {
            mainWindowRoot = LocalView.current.rootView
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerOwner) {
                DroideTheme {
                    Box(Modifier.width(360.dp).height(640.dp)) {
                        if (explorerVisible.value) compose.TestFileExplorer(files, state, onOpen = { opened += it }, imports = controller,
                            canMutatePath = { guarded += it; it !in blockedPaths },
                            onRenamed = { from, to -> renamed += from to to })
                    }
                }
            }
        }
        await { state.children[""] != null }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.mainClock.advanceTimeByFrame()
        // Import progress returns from real I/O through Android's Main handler,
        // which is distinct from the Compose test clock even without a dialog.
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        // Repository completions can enable the field or insert an error after
        // the button action has returned. Traverse that same native dialog
        // before each normal semantics fetch, just as after focus/text changes.
        if (hasNativeDialogWindow()) traverseNativeDialogWindow()
        condition()
    }

    private fun awaitText(text: String) = await {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun row(leaf: String) = compose.onNode(hasText(leaf) and hasClickAction())

    private fun form() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private fun assertExactName(name: String) = form().assert(
        SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(name)),
    )

    private fun hasNativeDialogWindow(): Boolean {
        if (!::mainWindowRoot.isInitialized) return false
        var present = false
        compose.runOnUiThread {
            val environment = ReflectionHelpers.getField<Any>(compose, "environment")
            val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
            val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
            present = owners.any { owner ->
                val view = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                view.rootView !== mainWindowRoot && view.isAttachedToWindow && view.isShown &&
                    view.windowVisibility == View.VISIBLE
            }
        }
        return present
    }

    // Same bounded SDK 35 window traversal used by BrowserHumanDialogTest. The
    // default host traversal can strand the dialog text-selection layout, then
    // accumulate ShadowTrace records while the normal idler repeatedly lays it
    // out. Perform real owner work; do not clear requests, disable animation or
    // enlarge memory/time budgets. A recurring layout still fails this check.
    private fun traverseNativeDialogWindow() {
        assertTrue("Automatic Compose idling stays enabled", compose.mainClock.autoAdvance)
        var idleAfterTraversal = false
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.runOnUiThread {
                val environment = ReflectionHelpers.getField<Any>(compose, "environment")
                val registry = ReflectionHelpers.getField<Any>(environment, "composeRootRegistry")
                val idler = ReflectionHelpers.getField<Any>(environment, "composeIdlingResource")
                ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
                val owners: Set<Any> = ReflectionHelpers.callInstanceMethod(registry, "getRegisteredComposeRoots")
                for (owner in owners) {
                    val ownerView = ReflectionHelpers.callInstanceMethod<View>(owner, "getView")
                    if (ownerView.rootView !== mainWindowRoot && ownerView.isAttachedToWindow && ownerView.isShown &&
                        ownerView.windowVisibility == View.VISIBLE &&
                        ReflectionHelpers.callInstanceMethod<Boolean>(owner, "getHasPendingMeasureOrLayout")) {
                        ReflectionHelpers.callInstanceMethod<Void>(ownerView, "measureAndLayoutForTest")
                    }
                }
                idleAfterTraversal = ReflectionHelpers.callInstanceMethod<Boolean>(idler, "isIdleNow")
            }
        }
        assertTrue("Native dialog layout and the normal Compose clock must settle", idleAfterTraversal)
    }

    private fun replaceForm(text: String) {
        traverseNativeDialogWindow()
        val input = form()
        input.performSemanticsAction(SemanticsActions.RequestFocus)
        traverseNativeDialogWindow()
        input.assertIsFocused().performTextReplacement(text)
        traverseNativeDialogWindow()
    }

    private fun dialogButton(label: String): SemanticsNodeInteraction {
        compose.mainClock.advanceTimeBy(400)
        traverseNativeDialogWindow()
        return compose.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))
    }

    private fun chooseLanguage(label: String, scroll: Boolean = false) {
        // DropdownMenu creates another native Popup owner inside the dialog. Complete its
        // actual host traversal before the normal semantics idler tries to query its items.
        // Animation/timeouts/idling policies stay unchanged; this is the same SDK35 fixture
        // traversal used for the parent AlertDialog, not a direct model/callback invocation.
        compose.mainClock.advanceTimeBy(400)
        traverseNativeDialogWindow()
        val item = compose.onNode(hasText(label) and hasClickAction())
        if (scroll) {
            item.performScrollTo()
            traverseNativeDialogWindow()
        }
        item.performClick()
        compose.mainClock.advanceTimeBy(400)
        traverseNativeDialogWindow()
    }

    private fun openActions(leaf: String) {
        // Target the button in this row rather than relying on row index or sort order.
        compose.onNodeWithText(leaf, useUnmergedTree = true).onParent().onChildren()
            .filter(hasClickAction() and hasAnyDescendant(hasContentDescription("File actions")))
            .onFirst().performClick()
    }

    private fun create(kind: String, name: String) {
        compose.onNodeWithContentDescription("New $kind").performClick()
        compose.mainClock.advanceTimeBy(400)
        replaceForm(name)
        dialogButton("Create").performClick()
        await { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
    }

    private fun openMove(leaf: String, destination: String) {
        openActions(leaf)
        dialogButton("Move to…").performClick()
        compose.mainClock.advanceTimeBy(400)
        replaceForm(destination)
    }

    private fun openRecovery() {
        compose.onNodeWithContentDescription("Explorer options").performClick()
        compose.onNodeWithText("Recover misplaced files").performClick()
        awaitText("Move only your own files and folders. Droide system files are protected.")
    }

    private fun recover(leaf: String) {
        await {
            compose.onAllNodes(hasText(leaf) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(leaf) and hasAnyAncestor(isDialog()), useUnmergedTree = true).onParent().onChildren()
            .filter(hasClickAction() and hasAnyDescendant(hasText("Move out"))).onFirst().performClick()
        dialogButton("Move").performClick()
    }

    @Test fun restoredInternalSelectionCreatesFileAndFolderAtProjectRoot() {
        root.resolve(".droide/run").mkdirs()
        root.resolve(".droide/run/runtime.json").writeText("runtime state")
        root.resolve(".droide/MainActivity.kt").writeText("misplaced user source")
        root.resolve(".gitignore").writeText("build/\n")
        root.resolve(".vscode").mkdir()
        state.activeDirectory = ".droide"
        state.expandedPaths = listOf(".droide", ".droide/run")
        state.children[""] = listOf(FileEntry(".droide", true, 0, 0))
        state.children[".droide"] = listOf(FileEntry(".droide/MainActivity.kt", false, 10, 0))
        state.moreAfter[".droide"] = DirectoryPageCursor(".droide/MainActivity.kt", false)
        mount()
        await { state.children[""]?.any { it.path == ".gitignore" } == true }
        compose.onNodeWithText(".droide").assertDoesNotExist()
        row(".gitignore").assertExists()
        row(".vscode").assertExists()
        create("file", "hello.py")
        assertEquals(listOf("hello.py"), opened)
        assertTrue(root.resolve("hello.py").isFile)
        assertFalse(root.resolve(".droide/hello.py").exists())
        create("folder", "src")
        assertTrue(root.resolve("src").isDirectory)
        assertFalse(root.resolve(".droide/src").exists())
        assertEquals("runtime state", root.resolve(".droide/run/runtime.json").readText())
        assertEquals("misplaced user source", root.resolve(".droide/MainActivity.kt").readText())
    }

    @Test fun committedNewFolderWithSyncFailureClosesTheDialogAndSelectsTheAvailableFolder() {
        folderSyncFailurePath = "src"
        mount()
        create("folder", "src")
        assertTrue(root.resolve("src").isDirectory)
        assertEquals("src", state.activeDirectory)
        assertTrue("src" in state.expandedPaths)
        assertTrue(opened.isEmpty())
        awaitText("Folder /src is available. Workspace sync is pending; do not repeat creation.")
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun newFolderKeepsExactUserNameAndRejectsBlankOrOversizeUtf8BeforeCreating() {
        mount()
        compose.onNodeWithContentDescription("New folder").performClick()
        dialogButton("Create").performClick()
        awaitText("Enter a folder name")
        assertFalse(root.resolve("new_folder").exists())
        val tooLong = "界".repeat(84) + "a.kt"
        replaceForm(tooLong)
        dialogButton("Create").performClick()
        awaitText("Name exceeds the filesystem limit of 255 UTF-8 bytes")
        assertExactName(tooLong)
        replaceForm(" spaced-界 ")
        dialogButton("Create").performClick()
        await { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        assertTrue(root.resolve(" spaced-界 ").isDirectory)
        assertFalse(root.resolve("spaced-界").exists())
    }

    @Test fun folderAndFileSelectionUseFolderParentAndExplicitRootForCreation() {
        root.resolve("src").mkdir()
        root.resolve("src/existing.kt").writeText("existing")
        mount()
        row("src").performClick()
        await { state.children["src"] != null }
        assertEquals("src", state.activeDirectory)
        create("folder", "nested")
        assertTrue(root.resolve("src/nested").isDirectory)
        row("existing.kt").performClick()
        assertEquals("src", state.activeDirectory)
        create("file", "sibling.py")
        assertTrue(root.resolve("src/sibling.py").isFile)
        compose.onNode(hasText("/") and hasClickAction()).performClick()
        assertEquals("", state.activeDirectory)
        create("file", "root.py")
        assertTrue(root.resolve("root.py").isFile)
        assertFalse(root.resolve("src/root.py").exists())
    }

    @Test fun newFilePreservesExplicitExtensionlessDotfileUnicodeAndSpacedNames() {
        mount()
        val names = listOf("Dockerfile", "Makefile", "LICENSE", ".env", "café-猫", " spaced ")
        for (name in names) create("file", name)
        assertEquals(names, opened)
        for (name in names) {
            assertTrue("Exact requested name $name", root.resolve(name).isFile)
            assertFalse("No implicit Kotlin suffix", root.resolve("$name.kt").exists())
        }
        assertEquals("FROM alpine\n", root.resolve("Dockerfile").readText())
        assertEquals("all:\n\techo Hello\n", root.resolve("Makefile").readText())
        assertEquals("", root.resolve("LICENSE").readText())
        assertFalse(root.resolve("spaced").exists())
    }

    @Test fun blankNewFileNameDoesNotPublishAndCanBeCorrected() {
        mount()
        compose.onNodeWithContentDescription("New file").performClick()
        dialogButton("Create").performClick()
        awaitText("Enter a file name")
        assertFalse(root.resolve("untitled.txt").exists())
        assertTrue(opened.isEmpty())
        replaceForm("   ")
        dialogButton("Create").performClick()
        awaitText("Enter a file name")
        assertExactName("   ")
        replaceForm("NOTICE")
        dialogButton("Create").performClick()
        await { "NOTICE" in opened }
        assertEquals("", root.resolve("NOTICE").readText())
        assertFalse(root.resolve("NOTICE.kt").exists())
    }

    @Test fun languageSelectionSuggestsAVisibleNameOnlyWhenTheFieldIsBlank() {
        mount()
        compose.onNodeWithContentDescription("New file").performClick()
        dialogButton("Kotlin").performClick()
        chooseLanguage("Python")
        assertExactName("untitled.py")
        replaceForm("Dockerfile")
        dialogButton("Python").performClick()
        chooseLanguage("JavaScript")
        assertExactName("Dockerfile")
        dialogButton("Create").performClick()
        await { "Dockerfile" in opened }
        assertEquals("FROM alpine\n", root.resolve("Dockerfile").readText())
        assertFalse(root.resolve("Dockerfile.js").exists())
        assertFalse(root.resolve("untitled.py").exists())
    }

    @Test fun filenameOnlyLanguageCanBeSelectedWithoutAnExtensionOrCrash() {
        val extensionId = "explorer.filename-only.fixture"
        com.baystudio.droide.core.LanguageRegistry.registerDeclarative(extensionId, listOf(
            com.baystudio.droide.core.DeclarativeLanguageSpec(
                id = "project-config-fixture", name = "Project Config Fixture", extensions = emptyList(),
                filenames = listOf("Toolfile"),
            ),
        ))
        try {
            mount()
            compose.onNodeWithContentDescription("New file").performClick()
            dialogButton("Kotlin").performClick()
            chooseLanguage("Project Config Fixture", scroll = true)
            assertExactName("Toolfile")
            dialogButton("Create").performClick()
            await { "Toolfile" in opened }
            assertEquals("", root.resolve("Toolfile").readText())
            assertFalse(root.resolve("Toolfile.kt").exists())
        } finally {
            com.baystudio.droide.core.LanguageRegistry.unregisterDeclarative(extensionId)
        }
    }

    @Test fun newFileDialogKeepsItsLaunchDirectoryWhenSelectionChanges() {
        root.resolve("src").mkdir()
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("New file").performClick()
        compose.mainClock.advanceTimeBy(400)
        replaceForm("frozen.py")
        compose.runOnIdle { state.selectRoot() }
        compose.onNodeWithText("Location: /src").assertExists()
        dialogButton("Create").performClick()
        await { root.resolve("src/frozen.py").isFile }
        await { "src/frozen.py" in opened }
        assertFalse(root.resolve("frozen.py").exists())
        assertEquals(listOf("src/frozen.py"), opened)
    }

    @Test fun newFolderDialogKeepsItsLaunchDirectoryWhenSelectionChanges() {
        root.resolve("src").mkdir()
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.mainClock.advanceTimeBy(400)
        replaceForm("frozen-folder")
        compose.runOnIdle { state.selectRoot() }
        compose.onNodeWithText("Location: /src").assertExists()
        dialogButton("Create").performClick()
        await { root.resolve("src/frozen-folder").isDirectory }
        assertFalse(root.resolve("frozen-folder").exists())
    }

    @Test fun deletedNewFileDestinationStaysDeletedAndDialogPreservesEnteredName() {
        root.resolve("src").mkdir()
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("New file").performClick()
        compose.mainClock.advanceTimeBy(400)
        replaceForm("pending.py")
        root.resolve("src").deleteRecursively()
        dialogButton("Create").performClick()
        awaitText("Destination folder no longer exists; select a project folder again")
        form().assertTextContains("pending.py")
        assertFalse(root.resolve("src").exists())
        assertFalse(root.resolve("pending.py").exists())
        assertTrue(opened.isEmpty())
    }

    @Test fun moveFileIntoExistingFolderPreservesBytesAndNotifiesEditor() {
        root.resolve("source.kt").writeText("source bytes\n")
        root.resolve("destination").mkdir()
        mount()
        openMove("source.kt", "destination")
        dialogButton("Move").performClick()
        await { root.resolve("destination/source.kt").isFile }
        await { renamed.isNotEmpty() }
        assertFalse(root.resolve("source.kt").exists())
        assertEquals("source bytes\n", root.resolve("destination/source.kt").readText())
        assertEquals(listOf("source.kt" to "destination/source.kt"), renamed)
        assertTrue("source.kt" in guarded)
        assertTrue("destination/source.kt" in guarded)
    }

    @Test fun movingExpandedFolderRemapsSelectionAndExpandedDescendants() {
        root.resolve("src/sub").mkdirs()
        root.resolve("src/sub/main.kt").writeText("source")
        root.resolve("destination").mkdir()
        mount()
        row("src").performClick()
        await { state.children["src"] != null }
        row("sub").performClick()
        await { state.children["src/sub"] != null }
        openMove("src", "destination")
        dialogButton("Move").performClick()
        await { renamed.isNotEmpty() }
        assertEquals("destination/src/sub", state.activeDirectory)
        assertTrue("destination/src" in state.expandedPaths)
        assertTrue("destination/src/sub" in state.expandedPaths)
        assertFalse("src" in state.expandedPaths)
        assertEquals("source", root.resolve("destination/src/sub/main.kt").readText())
        assertFalse(root.resolve("src").exists())
        assertEquals(listOf("src" to "destination/src"), renamed)
    }

    @Test fun moveCollisionPreservesBothFilesAndAllowsDifferentDestination() {
        root.resolve("source.kt").writeText("original source")
        root.resolve("occupied").mkdir()
        root.resolve("occupied/source.kt").writeText("existing destination")
        root.resolve("free").mkdir()
        mount()
        openMove("source.kt", "occupied")
        dialogButton("Move").performClick()
        awaitText("A file or folder already exists at /occupied/source.kt; existing data was preserved")
        assertEquals("original source", root.resolve("source.kt").readText())
        assertEquals("existing destination", root.resolve("occupied/source.kt").readText())
        assertTrue(renamed.isEmpty())
        replaceForm("free")
        dialogButton("Move").performClick()
        await { renamed.isNotEmpty() }
        assertEquals("original source", root.resolve("free/source.kt").readText())
    }

    @Test fun dirtySourceOrDestinationBlocksMoveWithoutChangingBytes() {
        root.resolve("source.kt").writeText("authoritative disk")
        root.resolve("destination").mkdir()
        blockedPaths = setOf("source.kt")
        mount()
        openMove("source.kt", "destination")
        dialogButton("Move").performClick()
        awaitText("Save or discard unsaved editor changes before moving this path")
        assertEquals("authoritative disk", root.resolve("source.kt").readText())
        assertFalse(root.resolve("destination/source.kt").exists())
        assertTrue(renamed.isEmpty())
        compose.runOnIdle { blockedPaths = setOf("destination/source.kt") }
        dialogButton("Move").performClick()
        await { "destination/source.kt" in guarded }
        assertEquals("authoritative disk", root.resolve("source.kt").readText())
        assertFalse(root.resolve("destination/source.kt").exists())
        assertTrue(renamed.isEmpty())
    }

    @Test fun changingSortFromMenuReplacesPagedOrderingWithoutChangingDestination() {
        root.resolve("selected").mkdir()
        repeat(251) { index -> root.resolve("file-${index.toString().padStart(3, '0')}.kt").writeText("$index") }
        mount()
        compose.runOnIdle { state.selectEntry(FileEntry("selected", true, 0, 0)) }
        val oldCursor = state.moreAfter[""]
        assertNotNull(oldCursor)
        assertEquals("file-000.kt", state.children[""]!!.first { !it.isDir }.path)
        compose.onNodeWithContentDescription("Explorer options").performClick()
        compose.onNodeWithText("Name · Z to A").performClick()
        await { state.sortOrder == ExplorerSortOrder.NAME_DESC && state.loadedRefreshToken == state.refreshToken && state.children[""] != null }
        assertEquals("selected", state.activeDirectory)
        assertEquals("file-250.kt", state.children[""]!!.first { !it.isDir }.path)
        assertEquals(250, state.children[""]!!.size)
        assertNotEquals(oldCursor, state.moreAfter[""])
        assertEquals(ExplorerSortOrder.NAME_DESC, state.moreAfter[""]!!.options.sortOrder)
        assertTrue(state.moreAfter[""]!!.options.hideDroideInternal)
    }

    @Test fun recoveryMovesOnlyExplicitUserFileAndKeepsRuntimeAndConfiguration() {
        root.resolve(".droide/run").mkdirs()
        root.resolve(".droide/run/native.bin").writeBytes(byteArrayOf(1, 2, 3))
        root.resolve(".droide/MainActivity.kt").writeText("user source")
        root.resolve(".droide/AGENTS.md").writeText("user agent configuration")
        root.resolve(".droide/.secret").writeText("private")
        mount()
        openRecovery()
        awaitText("MainActivity.kt")
        compose.onNodeWithText("MainActivity.kt").assertExists()
        compose.onNodeWithText("run").assertDoesNotExist()
        compose.onNodeWithText("AGENTS.md").assertDoesNotExist()
        compose.onNodeWithText(".secret").assertDoesNotExist()
        recover("MainActivity.kt")
        await { root.resolve("MainActivity.kt").isFile }
        await { renamed.isNotEmpty() }
        assertEquals("user source", root.resolve("MainActivity.kt").readText())
        assertFalse(root.resolve(".droide/MainActivity.kt").exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), root.resolve(".droide/run/native.bin").readBytes())
        assertEquals("user agent configuration", root.resolve(".droide/AGENTS.md").readText())
        assertEquals("private", root.resolve(".droide/.secret").readText())
        assertEquals(listOf(".droide/MainActivity.kt" to "MainActivity.kt"), renamed)
    }

    @Test fun recoveryCollisionKeepsBothCopiesAndDoesNotReportMove() {
        root.resolve(".droide").mkdir()
        root.resolve(".droide/MainActivity.kt").writeText("misplaced source")
        root.resolve("MainActivity.kt").writeText("project source")
        mount()
        openRecovery()
        recover("MainActivity.kt")
        awaitText("A file or folder already exists at /MainActivity.kt; existing data was preserved")
        assertEquals("project source", root.resolve("MainActivity.kt").readText())
        assertEquals("misplaced source", root.resolve(".droide/MainActivity.kt").readText())
        assertTrue(renamed.isEmpty())
    }

    @Test fun importUsesPickerLaunchFolderEvenWhenSelectionChangesBeforeResult() {
        root.resolve("src").mkdir()
        val source = provideImport("download.py", "imported bytes")
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("Import file").performClick()
        assertNotNull(picker.requestCode)
        compose.runOnIdle { state.selectRoot(); picker.deliver(source) }
        await { root.resolve("src/download.py").isFile }
        assertEquals("imported bytes", root.resolve("src/download.py").readText())
        assertFalse(root.resolve("download.py").exists())
        assertTrue(opened.isEmpty())
    }

    @Test fun acceptedImportSurvivesFilesPaneDisposalAndShowsProgressWhenReopened() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val source = provideImport("download.bin", "continued import") {
            object : ByteArrayInputStream("continued import".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Test import was not released" }
                    return super.read(bytes, offset, length)
                }
                override fun close() { proceed.countDown(); super.close() }
            }
        }
        mount()
        compose.onNodeWithContentDescription("Import file").performClick()
        compose.runOnIdle { picker.deliver(source) }
        await { entered.count == 0L }
        compose.runOnIdle { explorerVisible.value = false }
        compose.waitForIdle()
        assertTrue(checkNotNull(imports).state.value.busy)
        compose.runOnIdle { explorerVisible.value = true }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertTrue(checkNotNull(imports).state.value.busy)
        proceed.countDown()
        await { root.resolve("download.bin").isFile && checkNotNull(imports).state.value.active == null }
        assertEquals("continued import", root.resolve("download.bin").readText())
        await { state.children[""]?.any { it.path == "download.bin" } == true }
    }

    @Test fun importCollisionAtCapturedDestinationPreservesExistingBytes() {
        root.resolve("src").mkdir()
        root.resolve("src/download.py").writeText("keep existing")
        val source = provideImport("download.py", "download bytes")
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("Import file").performClick()
        compose.runOnIdle { state.selectRoot(); picker.deliver(source) }
        awaitText("A file named download.py already exists here")
        assertEquals("keep existing", root.resolve("src/download.py").readText())
        assertFalse(root.resolve("download.py").exists())
    }

    @Test fun committedImportWithSyncFailureKeepsTheOriginalAndRefreshesItsSelectedFolder() {
        root.resolve("src").mkdir()
        root.resolve("editor.kt").writeText("existing editor source")
        syncFailurePath = "src/download.py"
        val source = provideImport("download.py", "exact imported original")
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("Import file").performClick()
        compose.runOnIdle { state.selectRoot(); picker.deliver(source) }
        awaitText("Imported locally at src/download.py; synchronization pending. Refresh; do not import again.")
        await { state.children["src"]?.any { it.path == "src/download.py" } == true }
        assertEquals("exact imported original", root.resolve("src/download.py").readText())
        assertEquals("src", state.activeDirectory)
        assertEquals("existing editor source", root.resolve("editor.kt").readText())
        assertFalse(root.resolve("download.py").exists())
        assertTrue("Import must not replace the currently open editor", opened.isEmpty())
        compose.onNodeWithContentDescription("Import file").assertIsEnabled()
        compose.onNodeWithText("File import failed").assertDoesNotExist()
    }

    @Test fun committedNewFileWithSyncFailureClosesTheDialogAndOpensTheSavedFile() {
        syncFailurePath = "created.py"
        root.resolve("editor.kt").writeText("existing editor source")
        mount()
        create("file", "created.py")
        awaitText("Saved /created.py. Workspace sync is pending; the project file is available.")
        assertEquals(com.baystudio.droide.core.LanguageRegistry.forFile("created.py")!!.template,
            root.resolve("created.py").readText())
        assertEquals(listOf("created.py"), opened)
        assertEquals("existing editor source", root.resolve("editor.kt").readText())
        assertTrue(state.children[""]!!.any { it.path == "created.py" })
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test fun deletedPickerLaunchFolderDoesNotReappearOrRedirectImportToRoot() {
        root.resolve("src").mkdir()
        val source = provideImport("download.py", "download bytes")
        mount()
        row("src").performClick()
        compose.onNodeWithContentDescription("Import file").performClick()
        root.resolve("src").deleteRecursively()
        compose.runOnIdle { state.selectRoot(); picker.deliver(source) }
        awaitText("Destination folder no longer exists; select a project folder again")
        await { state.loadedRefreshToken == state.refreshToken }
        awaitText("Destination folder no longer exists; select a project folder again")
        val observedResult = state.lastImportResult
        compose.runOnIdle { explorerVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { explorerVisible.value = true }
        awaitText("Destination folder no longer exists; select a project folder again")
        assertSame("Recreating the pane must not consume or refresh the same result again", observedResult, state.lastImportResult)
        assertFalse(root.resolve("src").exists())
        assertFalse(root.resolve("download.py").exists())
        assertTrue(opened.isEmpty())
    }

    @Test fun syncPendingImportKeepsProviderCloseWarningAfterRefreshAndPaneRecreation() {
        syncFailurePath = "download.txt"
        val source = provideImport("download.txt", "saved original") {
            object : ByteArrayInputStream("saved original".toByteArray()) {
                override fun close() { throw IOException("Provider close failed") }
            }
        }
        mount()
        compose.onNodeWithContentDescription("Import file").performClick()
        compose.runOnIdle { picker.deliver(source) }
        await { imports?.state?.value?.result != null && state.loadedRefreshToken == state.refreshToken }
        val result = checkNotNull(imports?.state?.value?.result)
        assertEquals(com.baystudio.droide.core.WorkspaceImportResultStatus.SYNC_PENDING, result.status)
        assertTrue(result.message.contains("Source handle cleanup failed"))
        awaitText(result.message)
        compose.runOnIdle { explorerVisible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { explorerVisible.value = true }
        awaitText(result.message)
        assertEquals("saved original", root.resolve("download.txt").readText())
        compose.onNodeWithContentDescription("Import file").assertIsEnabled()
    }

    private fun provideImport(name: String, content: String, stream: (() -> java.io.InputStream)? = null): Uri {
        val authority = "droide.test.${root.name}"
        val uri = Uri.parse("content://$authority/document/one")
        val bytes = content.toByteArray()
        ShadowContentResolver.registerProviderInternal(authority, object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
                MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                    addRow(arrayOf<Any>(name, bytes.size.toLong()))
                }
            override fun getType(uri: Uri) = "text/plain"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        })
        val application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application.contentResolver).registerInputStreamSupplier(uri) { stream?.invoke() ?: ByteArrayInputStream(bytes) }
        return uri
    }

    private class PickerRegistry : ActivityResultRegistry() {
        var requestCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { this.requestCode = requestCode }
        fun deliver(uri: Uri) {
            val code = checkNotNull(requestCode)
            requestCode = null
            check(dispatchResult(code, Activity.RESULT_OK, Intent().setData(uri)))
        }
    }
}
