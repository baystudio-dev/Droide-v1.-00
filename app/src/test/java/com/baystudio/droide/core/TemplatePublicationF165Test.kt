package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Native-backed tests; fixture evidence is intentionally retained for harness inspection. */
class TemplatePublicationF165Test {
    private class Hooks {
        var before: (String) -> Unit = {}
        var after: (String) -> Unit = {}
        var receipt: (String, AnchoredWorkspaceFiles.MoveReceipt) -> AnchoredWorkspaceFiles.MoveReceipt = { _, r -> r }
        val events = mutableListOf<String>()
        fun before(event: String) { events += event; before.invoke(event) }
    }
    private class Hooked(private val actual: TemplateDirectory, private val hooks: Hooks,
                         private val prefix: String = "") : TemplateDirectory {
        private fun <T> event(name: String, action: () -> T): T {
            hooks.before(name)
            return action().also { hooks.after(name) }
        }
        override fun kind(name: String) = event("kind:$prefix$name") { actual.kind(name) }
        override fun directoryKey() = event("key:$prefix") { actual.directoryKey() }
        override fun openDirectory(name: String): TemplateDirectory = event("open:$prefix$name") {
            Hooked(actual.openDirectory(name), hooks, "$prefix$name/")
        }
        override fun createDirectory(name: String) = event("mkdir:$prefix$name") {
            hooks.receipt("mkdir:$prefix$name", actual.createDirectory(name))
        }
        override fun fileKey(name: String) = event("fileKey:$prefix$name") { actual.fileKey(name) }
        override fun createNew(name: String): TemplateOutput = event("create:$prefix$name") {
            val output = actual.createNew(name)
            object : TemplateOutput {
                override fun key() = output.key()
                override fun write(bytes: ByteArray) = event("write:$prefix$name") { output.write(bytes) }
                override fun force() = event("force:$prefix$name") { output.force() }
                override fun close() { output.close(); event("fileClose:$prefix$name") {} }
            }
        }
        override fun publish(staged: String, target: String) = event("publish:$prefix$target") {
            hooks.receipt("publish:$prefix$target", actual.publish(staged, target))
        }
        override fun close() { actual.close(); event("close:$prefix") {} }
    }
    private fun root() = Files.createTempDirectory("droide-f165-template-").toFile()
    private fun template(vararg entries: Pair<String, String>) =
        ProjectTemplate("test", "test", "test", linkedMapOf(*entries), entries.firstOrNull()?.first)
    private fun write(root: File, template: ProjectTemplate, hooks: Hooks = Hooks()) =
        ProjectTemplateWriter.write(root, template) {
            Hooked(AnchoredTemplateDirectory(AnchoredWorkspaceFiles(it)), hooks)
        }
    private fun failure(action: () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected failure")
    }
    private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    private fun staged(root: File) = root.walkTopDown().filter { it.isFile && ".template-" in it.name }.toList()
    private fun assertRetainedStage(root: File, bytes: String = "payload") {
        assertEquals(listOf(bytes), staged(root).map { it.readText() })
    }

    @Test fun everyBuiltinWritesExactBytesAndNoStagedFilesIncludingEmpty() {
        ProjectTemplateRegistry.all.forEach { template ->
            val root = root()
            val originalKey = key(root)
            ProjectTemplateWriter.write(root, template)
            assertEquals(originalKey, key(root))
            val actual = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            assertEquals(template.files, actual)
            assertTrue(staged(root).isEmpty())
            template.primaryFile?.let { assertTrue(File(root, it).isFile) }
        }
    }

    @Test fun existingFilesDirectoriesAndDanglingSymlinksAreNotReplaced() {
        for (kind in listOf("file", "directory", "symlink")) {
            val root = root()
            val target = File(root, "main.py")
            when (kind) {
                "file" -> target.writeText("user")
                "directory" -> target.mkdir()
                else -> Files.createSymbolicLink(target.toPath(), File(root, "missing").toPath())
            }
            val original = key(target)
            val error = failure { ProjectTemplateWriter.write(root, ProjectTemplateRegistry.byId("python")!!) }
            assertEquals(IllegalArgumentException::class.java, error.javaClass)
            assertEquals("Template target already exists: main.py", error.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertEquals(original, key(target))
            if (kind == "file") assertEquals("user", target.readText())
            assertEquals(ProjectTemplateRegistry.byId("python")!!.files["pyproject.toml"], File(root, "pyproject.toml").readText())
            assertTrue(staged(root).isEmpty())
        }
    }

    @Test fun racingFilesDirectoriesAndSymlinksCannotBeOverwritten() {
        for (kind in listOf("file", "directory", "symlink")) {
            val root = root()
            val target = File(root, "target")
            var original: Any? = null
            val hooks = Hooks().apply { before = { event ->
                if (event == "publish:target") {
                    when (kind) {
                        "file" -> target.writeText("racing user")
                        "directory" -> target.mkdir()
                        else -> Files.createSymbolicLink(target.toPath(), File(root, "missing").toPath())
                    }
                    original = key(target)
                }
            } }
            assertTrue(failure { write(root, template("target" to "payload"), hooks) } is IOException)
            assertEquals(original, key(target))
            if (kind == "file") assertEquals("racing user", target.readText())
            assertRetainedStage(root)
            assertEquals(1, hooks.events.count { it == "publish:target" })
        }
    }

    @Test fun missingAndSymlinkRootsAreRejectedEvenForEmptyTemplates() {
        val parent = root()
        val missing = File(parent, "missing")
        failure { ProjectTemplateWriter.write(missing, ProjectTemplateRegistry.byId("empty")!!) }
        assertFalse(missing.exists())
        val outside = root()
        File(outside, "sentinel").writeText("outside")
        val link = File(parent, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        failure { ProjectTemplateWriter.write(link, template("new" to "payload")) }
        assertEquals(listOf("sentinel"), outside.list()!!.toList())
        assertEquals("outside", File(outside, "sentinel").readText())
    }

    @Test fun symlinkParentsNeverEscapeTheRoot() {
        val root = root()
        val outside = root()
        val sentinel = File(outside, "main").apply { writeText("outside") }
        val original = key(sentinel)
        Files.createSymbolicLink(File(root, "src").toPath(), outside.toPath())
        failure { write(root, template("src/main" to "payload")) }
        assertEquals(original, key(sentinel))
        assertEquals("outside", sentinel.readText())
        assertEquals(listOf("main"), outside.list()!!.toList())
    }

    @Test fun replacedParentBeforePublicationRetainsStageAndDoesNotFollowSymlink() {
        val root = root()
        val outside = root()
        File(outside, "sentinel").writeText("outside")
        val hooks = Hooks().apply { before = { event ->
            if (event == "publish:src/main") {
                Files.move(File(root, "src").toPath(), File(root, "retained-src").toPath())
                Files.createSymbolicLink(File(root, "src").toPath(), outside.toPath())
            }
        } }
        failure { write(root, template("src/main" to "payload"), hooks) }
        assertRetainedStage(File(root, "retained-src"))
        assertEquals(listOf("sentinel"), outside.list()!!.toList())
        assertFalse(File(outside, "main").exists())
    }

    @Test fun replacedRootBeforePublicationDoesNotPopulateReplacement() {
        val parent = root()
        val root = File(parent, "project").apply { mkdir() }
        val retained = File(parent, "retained")
        val hooks = Hooks().apply { before = { event ->
            if (event == "publish:main") {
                Files.move(root.toPath(), retained.toPath())
                root.mkdir()
                File(root, "user").writeText("replacement")
            }
        } }
        failure { write(root, template("main" to "payload"), hooks) }
        assertRetainedStage(retained)
        assertEquals(listOf("user"), root.list()!!.toList())
    }

    @Test fun forceAndOutputCloseFailuresRetainStageWithoutPublishingOrContinuing() {
        for (eventPrefix in listOf("force:", "fileClose:")) {
            val root = root()
            val hooks = Hooks().apply { before = { event ->
                if (event.startsWith(eventPrefix)) throw IOException("injected $eventPrefix")
            } }
            failure { write(root, template("main" to "payload", "later" to "later"), hooks) }
            assertRetainedStage(root)
            assertFalse(File(root, "main").exists())
            assertFalse(File(root, "later").exists())
            assertFalse(hooks.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun nondurableDirectoryReceiptRetainsDirectoryAndStops() {
        val root = root()
        val hooks = Hooks().apply { receipt = { event, actual ->
            if (event.startsWith("mkdir:")) AnchoredWorkspaceFiles.MoveReceipt(true, 5) else actual
        } }
        failure { write(root, template("src/main" to "payload", "later" to "later"), hooks) }
        assertTrue(File(root, "src").isDirectory)
        assertFalse(File(root, "src/main").exists())
        assertFalse(File(root, "later").exists())
        assertFalse(hooks.events.any { it.startsWith("create:") })
    }

    @Test fun prepublicationFailureHasNoRetryAndRetainsAllStagedEvidence() {
        val root = root()
        val hooks = Hooks().apply { before = { event ->
            if (event == "publish:main") throw IOException("before publication")
        } }
        failure { write(root, template("main" to "payload", "later" to "later"), hooks) }
        assertRetainedStage(root)
        assertFalse(File(root, "main").exists())
        assertFalse(File(root, "later").exists())
        assertEquals(1, hooks.events.count { it == "publish:main" })
    }

    @Test fun publishedButUnsyncedAndThrowAfterPublicationRetainFinalEvidence() {
        for (throwAfter in listOf(false, true)) {
            val root = root()
            val hooks = Hooks().apply {
                receipt = { event, actual ->
                    if (event == "publish:main") AnchoredWorkspaceFiles.MoveReceipt(true, 5) else actual
                }
                after = { event -> if (throwAfter && event == "publish:main") throw IOException("after publication") }
            }
            failure { write(root, template("main" to "payload", "later" to "later"), hooks) }
            assertEquals("payload", File(root, "main").readText())
            assertTrue(staged(root).isEmpty())
            assertFalse(File(root, "later").exists())
            assertEquals(1, hooks.events.count { it == "publish:main" })
        }
    }

    @Test fun parentAndRootCloseFailuresFailSuccessAndRetainPublishedFiles() {
        for (nested in listOf(false, true)) {
            val root = root()
            val path = if (nested) "src/main" else "main"
            val hooks = Hooks().apply { before = { event ->
                if (event == if (nested) "close:src/" else "close:") throw IOException("directory close")
            } }
            failure { write(root, template(path to "payload"), hooks) }
            assertEquals("payload", File(root, path).readText())
        }
    }

    @Test fun injectedCancellationAndFatalErrorKeepIdentityEvidenceAndStopLaterEntries() {
        for (problem in listOf(CancellationException("cancelled"), AssertionError("fatal"))) {
            val root = root()
            val hooks = Hooks().apply { before = { event ->
                if (event.startsWith("force:")) throw problem
            } }
            assertSame(problem, failure { write(root, template("main" to "payload", "later" to "later"), hooks) })
            assertRetainedStage(root)
            assertFalse(File(root, "later").exists())
            assertFalse(hooks.events.any { it.startsWith("publish:") })
        }
    }

    @Test fun stagedNameReplacementCannotBeReportedAsSuccessfulPublication() {
        for (atPublish in listOf(false, true)) {
            val root = root()
            var swapped = false
            val hooks = Hooks().apply { before = { event ->
                if (!swapped && (if (atPublish) event == "publish:main" else event.startsWith("fileKey:.main.template-"))) {
                    swapped = true
                    val stage = staged(root).single()
                    Files.move(stage.toPath(), File(root, "original-stage-evidence").toPath())
                    stage.writeText("concurrent replacement")
                }
            } }
            failure { write(root, template("main" to "payload", "later" to "later"), hooks) }
            assertEquals("payload", File(root, "original-stage-evidence").readText())
            assertFalse(File(root, "later").exists())
            if (atPublish) assertEquals("concurrent replacement", File(root, "main").readText())
            else {
                assertFalse(File(root, "main").exists())
                assertRetainedStage(root, "concurrent replacement")
            }
        }
    }

    @Test fun aPreviouslyUsedParentCannotBeReplacedBetweenTemplateEntries() {
        val root = root()
        var swapped = false
        val hooks = Hooks().apply { before = { event ->
            if (!swapped && event == "open:src" && File(root, "src/first").exists()) {
                swapped = true
                Files.move(File(root, "src").toPath(), File(root, "retained-src").toPath())
                File(root, "src").mkdir()
                File(root, "src/user").writeText("replacement user work")
            }
        } }
        failure { write(root, template("src/first" to "payload", "src/second" to "later"), hooks) }
        assertEquals("payload", File(root, "retained-src/first").readText())
        assertEquals(listOf("user"), File(root, "src").list()!!.toList())
    }

    @Test fun aPreviouslyUsedParentRemovedBetweenEntriesIsNeverRecreated() {
        val root = root()
        var removed = false
        val hooks = Hooks().apply { before = { event ->
            if (!removed && event == "kind:src" && File(root, "src/first").exists()) {
                removed = true
                Files.move(File(root, "src").toPath(), File(root, "retained-src").toPath())
            }
        } }
        failure { write(root, template("src/first" to "payload", "src/second" to "later"), hooks) }
        assertTrue(removed)
        assertEquals("payload", File(root, "retained-src/first").readText())
        assertFalse(Files.exists(File(root, "src").toPath(), NOFOLLOW_LINKS))
        assertEquals(1, hooks.events.count { it == "mkdir:src" })
        assertEquals(1, hooks.events.count { it.startsWith("publish:") })
    }

    @Test fun unsafeRelativePathsNeverCreateOutsideOrIntermediateContent() {
        for (path in listOf("../outside", "/absolute", "src/../main", "src//main", "src\\main", "./main", "", "main\u0000bad")) {
            val root = root()
            assertTrue(failure { write(root, template(path to "payload")) } is IllegalArgumentException)
            assertTrue(root.list()!!.isEmpty())
        }
    }
}
