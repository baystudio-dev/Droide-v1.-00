package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Independent review fixtures are intentionally preserved as evidence, not cleaned up. */
class DirectoryLifecycleIndependentTest {
    private fun root(label: String): File = Files.createTempDirectory("f151-independent-$label-").toFile().also {
        println("PRESERVED_FIXTURE ${it.absolutePath}")
    }
    private fun original(root: File, path: String = "src"): File = root.resolve(path).apply {
        check(mkdirs()); resolve("deep/level").mkdirs(); resolve("deep/level/source.txt").writeText("original")
    }
    private fun stages(root: File): List<File> = root.resolve(".droide/local-delete-custody").listFiles()
        ?.filter { it.isDirectory }?.map { it.resolve("original") }?.filter { it.isDirectory }.orEmpty()
    private fun key(file: File): Any = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    private suspend fun thrown(action: suspend () -> Unit): Throwable {
        try { action() } catch (failure: Throwable) { return failure }
        throw AssertionError("Expected failure")
    }

    @Test fun deeplyNestedDescriptorAndNewDescendantRemainReachableAfterUsableSuccess() = runBlocking {
        val root = root("late-fd"); val source = original(root); val sourceKey = key(source)
        var calls = 0
        RandomAccessFile(source.resolve("deep/level/source.txt"), "rw").use { writer ->
            val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Delete) {
                calls++
                val stage = stages(root).single(); assertEquals(sourceKey, key(stage))
                val before = Files.readAttributes(stage.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                writer.setLength(0); writer.write("late descriptor bytes".toByteArray()); writer.fd.sync()
                stage.resolve("deep/level/new.txt").writeText("late new descendant")
                val after = Files.readAttributes(stage.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.size(), after.size())
                assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
            } })
            assertTrue(repo.delete("src")); assertEquals(1, calls); assertFalse(source.exists())
            val stage = stages(root).single(); assertEquals("late descriptor bytes", stage.resolve("deep/level/source.txt").readText())
            assertEquals("late new descendant", stage.resolve("deep/level/new.txt").readText())
            assertEquals(stage.relativeTo(root).invariantSeparatorsPath, repo.retainedWork.value.notices.single().candidate.path)
            assertFalse(repo.retainedWork.value.notices.single().historicalWriteReturned)
        }
    }

    @Test fun noObserverFastpathRetainsDirectoryAndStillDeletesOrdinaryFiles() = runBlocking {
        val root = root("fastpath"); val source = original(root); val sourceKey = key(source)
        val repo = FileRepository(root)
        assertTrue(repo.delete("src")); assertFalse(source.exists()); assertEquals(sourceKey, key(stages(root).single()))
        root.resolve("ordinary.txt").writeText("ordinary"); assertTrue(repo.delete("ordinary.txt")); assertFalse(root.resolve("ordinary.txt").exists())
        assertEquals(1, stages(root).size)
    }

    @Test fun ordinaryObserverFailureKeepsLogicalSuccessAndDurableRestartInspection() = runBlocking {
        val root = root("observer"); val source = original(root); var calls = 0
        val repo = FileRepository(root, onMutation = { calls++; throw IOException("independent observer failure") })
        assertTrue(repo.delete("src")); assertEquals(1, calls); assertFalse(source.exists())
        val stage = stages(root).single(); val restarted = FileRepository(root)
        val inspection = restarted.inspectRetainedWork(".droide/local-delete-custody")
        assertTrue("Restart inspection must expose durable retained location: $inspection", inspection.candidates.any { it.path == stage.parentFile.relativeTo(root).invariantSeparatorsPath })
        assertEquals("original", stage.resolve("deep/level/source.txt").readText())
    }

    @Test fun observerCancellationAndFatalErrorKeepOriginalMeaningAndNeverReleasePayload() = runBlocking {
        for (failure in listOf(CancellationException("independent cancel"), AssertionError("independent fatal"))) {
            val root = root("observer-terminal"); val source = original(root)
            val repo = FileRepository(root, onMutation = { throw failure })
            val actual = thrown { repo.delete("src") }
            assertTrue("Original terminal failure must remain reachable", actual === failure || generateSequence(actual) { it.cause }.any { it === failure })
            assertFalse(source.exists()); assertEquals("original", stages(root).single().resolve("deep/level/source.txt").readText())
            assertFalse(repo.retainedWork.value.notices.single().historicalWriteReturned)
        }
    }

    @Test fun replacementDuringSuspendingAdmissionCannotBeDeletedUnderOldSourceIntent() = runBlocking {
        val root = root("admission-replacement"); val source = original(root); val originalKey = key(source); var calls = 0
        val repo = FileRepository(root, beforeMutation = {
            if (calls++ == 0) {
                Files.move(source.toPath(), root.resolve("prior-src").toPath())
                original(root).resolve("new.txt").writeText("new generation")
            }
        })
        thrown { repo.delete("src") }
        assertEquals(originalKey, key(root.resolve("prior-src")))
        assertEquals("new generation", source.resolve("new.txt").readText()); assertTrue(stages(root).isEmpty())
    }

    @Test fun laterAncestorDeletionDoesNotHideEarlierCustodyOrEraseItsLateWrites() = runBlocking {
        val root = root("ancestor"); val source = original(root, "parent/child"); val sourceKey = key(source)
        val repo = FileRepository(root); assertTrue(repo.delete("parent/child"))
        val older = stages(root).single(); assertEquals(sourceKey, key(older))
        older.resolve("deep/level/source.txt").appendText(" then late")
        assertTrue(repo.delete("parent")); assertFalse(root.resolve("parent").exists())
        assertEquals(2, stages(root).size); assertEquals(sourceKey, key(older))
        assertEquals("original then late", older.resolve("deep/level/source.txt").readText())
        val inspection = FileRepository(root).inspectRetainedWork(".droide/local-delete-custody")
        assertTrue("Old custody remains discoverable despite removed source ancestor: $inspection", inspection.candidates.any { it.path == older.parentFile.relativeTo(root).invariantSeparatorsPath })
    }

    @Test fun ordinaryRepositoryMutationsCannotDeleteOrMoveCustodyOrItsMetadataAncestor() = runBlocking {
        val root = root("protected"); original(root); val repo = FileRepository(root)
        assertTrue(repo.delete("src")); val stage = stages(root).single(); val sourceKey = key(stage)
        for (path in listOf(".droide", ".droide/local-delete-custody", stage.relativeTo(root).invariantSeparatorsPath)) {
            thrown { repo.delete(path) }
            thrown { repo.move(path, "moved-custody") }
            assertEquals(sourceKey, key(stage)); assertEquals("original", stage.resolve("deep/level/source.txt").readText())
        }
    }

    private fun metadata(root: File): Map<String, String> = root.resolve(LocalDirectoryDeleteCustody.HOME).listFiles().orEmpty()
        .flatMap { operation -> operation.listFiles().orEmpty().filter { it.isFile }.map {
            it.relativeTo(root).invariantSeparatorsPath to java.util.Base64.getEncoder().encodeToString(it.readBytes())
        } }.toMap()
    private fun operation(root: File): String = root.resolve(LocalDirectoryDeleteCustody.HOME).listFiles()!!.single().name
    private fun repository(root: File, custody: LocalDirectoryDeleteCustody,
        observer: (suspend (FileRepository.Mutation) -> Unit)? = null) =
        FileRepository(root, onMutation = observer, newFilePublisher = NewProjectFilePublisher.platform, directoryCustody = custody)

    @Test fun preparedOnlyRestartInspectionAndReconciliationNeverMoveTheSource() = runBlocking {
        val root = root("prepared"); val source = original(root); val selectedKey = key(source)
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "prepared") throw IOException("lost preparation return") })
        thrown { repository(root, custody).delete("src") }
        val before = metadata(root); val restarted = LocalDirectoryDeleteCustody(root)
        val inspection = restarted.inspect(operation(root)); assertEquals(LocalDirectoryDeleteCustody.State.PREPARED, inspection.state)
        assertEquals(before, metadata(root)); assertEquals(selectedKey, key(source)); assertTrue(stages(root).isEmpty())
        val result = FileRepository(root).reconcileDirectoryDeletion(inspection.token!!)
        assertEquals(LocalDirectoryDeleteCustody.State.PREPARED, result.state)
        assertEquals(before, metadata(root)); assertEquals(selectedKey, key(source)); assertTrue(stages(root).isEmpty())
    }

    @Test fun movedBeforeReceiptRestartRequiresExactReconfirmationAndNeverReplaysObserver() = runBlocking {
        val root = root("moved-unconfirmed"); val source = original(root); val selectedKey = key(source); var observed = 0
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "after-move") throw IOException("lost move return") })
        thrown { repository(root, custody) { observed++ }.delete("src") }
        assertFalse(source.exists()); assertEquals(selectedKey, key(stages(root).single())); assertEquals(0, observed)
        val before = metadata(root); val inspection = LocalDirectoryDeleteCustody(root).inspect(operation(root))
        assertEquals(LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED, inspection.state); assertEquals(before, metadata(root))
        val restarted = FileRepository(root, onMutation = { observed++ })
        repeat(2) {
            val result = restarted.reconcileDirectoryDeletion(inspection.token!!)
            assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, result.state)
            assertEquals(LocalDirectoryDeleteCustody.ObserverState.NOT_STARTED, result.observer)
            assertEquals(0, observed); assertEquals(1, stages(root).size); assertEquals(selectedKey, key(stages(root).single()))
        }
    }

    @Test fun nondurablePublishedMoveIsNotUsableSuccessButCanBeExplicitlyReconfirmed() = runBlocking {
        val root = root("force-uncertain"); original(root); var moves = 0
        val custody = LocalDirectoryDeleteCustody(root, moveDirectory = { files, source, destination ->
            moves++; assertTrue(files.moveNoReplaceDurable(source, files, destination).durable)
            AnchoredWorkspaceFiles.MoveReceipt(true, 5)
        })
        thrown { repository(root, custody).delete("src") }
        val inspection = LocalDirectoryDeleteCustody(root).inspect(operation(root))
        assertEquals(LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED, inspection.state); assertEquals(1, moves)
        assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, FileRepository(root).reconcileDirectoryDeletion(inspection.token!!).state)
        assertEquals(1, moves); assertEquals("original", stages(root).single().resolve("deep/level/source.txt").readText())
    }

    @Test fun exactTokenRetryPreservesRecreatedSourceAndDoesNotRepeatObserverOrMove() = runBlocking {
        val root = root("same-name"); original(root); var observed = 0
        val repo = FileRepository(root, onMutation = {
            observed++; original(root).resolve("generation.txt").writeText("new selected generation")
        })
        assertTrue(repo.delete("src")); val stage = stages(root).single(); val selectedKey = key(stage)
        val token = repo.retainedWork.value.notices.single().candidate.directoryDeletion!!.token!!
        val restarted = FileRepository(root, onMutation = { observed++ })
        repeat(2) {
            val result = restarted.reconcileDirectoryDeletion(token)
            assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, result.state)
            assertEquals(LocalDirectoryDeleteCustody.SourceState.RECREATED, result.sourceState)
            assertEquals("new selected generation", root.resolve("src/generation.txt").readText())
            assertEquals(1, observed); assertEquals(1, stages(root).size); assertEquals(selectedKey, key(stage))
        }
    }

    @Test fun recreatedSourceBeforeRetainedReceiptPreventsReconfirmationRatherThanGuessing() = runBlocking {
        val root = root("ambiguous-both"); original(root)
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "after-move") {
            original(root).resolve("generation.txt").writeText("new source"); throw IOException("lost move with recreation")
        } })
        thrown { repository(root, custody).delete("src") }
        val inspection = LocalDirectoryDeleteCustody(root).inspect(operation(root)); val before = metadata(root)
        assertEquals(LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, inspection.state)
        val result = FileRepository(root).reconcileDirectoryDeletion(inspection.token!!)
        assertEquals(LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, result.state)
        assertEquals(before, metadata(root)); assertEquals("new source", root.resolve("src/generation.txt").readText())
        assertEquals("original", stages(root).single().resolve("deep/level/source.txt").readText())
    }

    private fun competingLock(lock: File): Int {
        val script = "import fcntl,sys; f=open(sys.argv[1], 'r+b');\ntry: fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)\nexcept BlockingIOError: sys.exit(41)\nsys.exit(42)"
        val process = ProcessBuilder("python3", "-c", script, lock.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        val result = process.exitValue(); assertTrue("Unexpected external lock probe result $result: $output", result == 41 || result == 42)
        return result
    }

    @Test fun readOnlyInspectionDoesNotReleaseLiveCrossRuntimeMetadataLease() = runBlocking {
        val root = root("lease"); original(root); var probed = false; lateinit var lock: File
        val custody = LocalDirectoryDeleteCustody(root, checkpoint = { if (it == "retained") {
            val id = operation(root); lock = root.resolve(LocalDirectoryDeleteCustody.HOME).resolve(id).resolve("lock")
            assertEquals(41, competingLock(lock))
            repeat(3) { assertEquals(LocalDirectoryDeleteCustody.State.RETAINED, LocalDirectoryDeleteCustody(root).inspect(id).state) }
            assertEquals(41, competingLock(lock)); probed = true
        } })
        assertTrue(repository(root, custody).delete("src")); assertTrue(probed)
        assertEquals(42, competingLock(lock))
    }

    @Test fun participantPreparationSuspensionRequiresFreshEditorAdmissionBeforeMove() = runBlocking {
        val root = root("recheck-editor"); val source = original(root); var dirty = false; var admissions = 0
        val participant = object : WorkspaceMutationParticipant {
            override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareLocal(preparation: WorkspaceLocalPreparation) { kotlinx.coroutines.yield(); dirty = true }
                    override suspend fun synchronize() {}
                }
        }
        val repo = FileRepository(root, beforeMutation = { admissions++; check(!dirty) { "Editor became dirty during preparation" } },
            newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        thrown { repo.delete("src") }
        assertTrue("Fresh editor admission must run after participant preparation", admissions >= 2)
        assertTrue(source.isDirectory); assertEquals("original", source.resolve("deep/level/source.txt").readText()); assertTrue(stages(root).isEmpty())
    }

    private fun completionHookReplacement(hook: String) = runBlocking {
        val root = root("completion-$hook"); original(root); var replaced = false
        fun replace() {
            val retained = stages(root).single(); Files.move(retained.toPath(), root.resolve("held-original").toPath())
            check(retained.mkdir()); retained.resolve("foreign.txt").writeText("foreign custody entry"); replaced = true
        }
        val participant = object : WorkspaceMutationParticipant {
            override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun synchronize() {}
                    override fun retainsDeleteStage(receipt: WorkspaceLocalReceipt): Boolean { if (hook == "retains") replace(); return false }
                    override fun finish(outcome: WorkspaceMutationOutcome) { if (hook == "finish") replace() }
                }
        }
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val outcome = runCatching { repo.delete("src") }
        assertTrue("Completion hook must have executed; outcome=$outcome", replaced)
        assertEquals("original", root.resolve("held-original/deep/level/source.txt").readText())
        assertEquals("foreign custody entry", stages(root).single().resolve("foreign.txt").readText())
        val notice = repo.retainedWork.value.notices.single().candidate.directoryDeletion!!
        assertEquals("Completion must not leave a stale verified-retained notice; outcome=$outcome", LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED, notice.state)
    }
    @Test fun retentionHookCannotLeaveAStaleVerifiedCustodyNotice() = completionHookReplacement("retains")
    @Test fun finishHookCannotLeaveAStaleVerifiedCustodyNotice() = completionHookReplacement("finish")

    @Test fun processEnteringSourceDuringFinalEditorAdmissionStillPreventsNamespaceMove() = runBlocking {
        val root = root("recheck-process"); val source = original(root); val selectedKey = key(source)
        var admissions = 0; var process: Process? = null
        val repo = FileRepository(root, beforeMutation = {
            if (++admissions == 2) {
                process = ProcessBuilder("python3", "-c", "import sys; print('ready', flush=True); sys.stdin.read()")
                    .directory(source).redirectErrorStream(true).start()
                assertEquals("ready", process!!.inputStream.bufferedReader().readLine())
            }
        })
        try {
            thrown { repo.delete("src") }
            assertEquals(2, admissions); assertEquals(selectedKey, key(source)); assertTrue(stages(root).isEmpty())
        } finally {
            process?.outputStream?.close()
            assertTrue(process == null || process!!.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    private fun fileReplacedByDirectoryDuringAdmission(observer: Boolean) = runBlocking {
        val root = root("file-to-directory-$observer"); root.resolve("src").writeText("selected original file")
        var changed = false
        val repo = FileRepository(root, beforeMutation = {
            if (!changed) { changed = true; Files.move(root.resolve("src").toPath(), root.resolve("held-file").toPath()); original(root) }
        }, onMutation = if (observer) ({ _ -> Unit }) else null)
        val result = runCatching { repo.delete("src") }
        assertTrue("New directory must not bypass custody via file route: $result", root.resolve("src/deep/level/source.txt").isFile)
        assertEquals("original", root.resolve("src/deep/level/source.txt").readText())
        assertEquals("selected original file", root.resolve("held-file").readText()); assertTrue(result.isFailure)
        assertTrue(stages(root).isEmpty())
    }
    @Test fun noObserverFileRouteRejectsDirectoryReplacementDuringAdmission() = fileReplacedByDirectoryDuringAdmission(false)
    @Test fun observerFileRouteRejectsDirectoryReplacementDuringAdmission() = fileReplacedByDirectoryDuringAdmission(true)

    @Test fun regularFileStageSelectionCannotAdoptAndRecursivelyEraseReplacementDirectory() = runBlocking {
        val root = root("file-selection-directory"); root.resolve("src").writeText("selected original file")
        val participant = object : WorkspaceMutationParticipant {
            override suspend fun begin(guard: WorkspaceMutationGuard, mutation: FileRepository.Mutation) =
                object : WorkspaceMutationSession(guard, mutation) {
                    override suspend fun prepareDeleteStage(): String? {
                        Files.move(root.resolve("src").toPath(), root.resolve("held-file").toPath()); original(root); return null
                    }
                    override suspend fun synchronize() {}
                }
        }
        val repo = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        val result = runCatching { repo.delete("src") }
        assertTrue("Stage selection must not admit a replacement directory into recursive cleanup: $result", root.resolve("src/deep/level/source.txt").isFile)
        assertEquals("selected original file", root.resolve("held-file").readText()); assertTrue(result.isFailure)
    }

    @Test fun canonicalAliasesCannotMutateProtectedPayloadOrMetadataThroughOrdinaryApis() = runBlocking {
        val root = root("alias-protection"); original(root); val repo = FileRepository(root); assertTrue(repo.delete("src"))
        val stage = stages(root).single(); Files.createSymbolicLink(root.resolve("alias").toPath(), stage.parentFile.toPath())
        val before = metadata(root); root.resolve("ordinary.txt").writeText("ordinary")
        thrown { repo.delete("alias/prepared.json") }
        thrown { repo.delete("alias/original/deep/level/source.txt") }
        thrown { repo.writeText("alias/original/deep/level/source.txt", "replacement") }
        thrown { repo.createDirectory("alias/new") }
        thrown { repo.move("alias/original", "moved") }
        thrown { repo.move("ordinary.txt", "alias/new-file") }
        assertEquals(before, metadata(root)); assertEquals("original", stage.resolve("deep/level/source.txt").readText())
        assertEquals("ordinary", root.resolve("ordinary.txt").readText()); assertFalse(stage.parentFile.resolve("new").exists())
    }

    @Test fun aliasIntroducedDuringAdmissionCannotRedirectRegularFileDeletionIntoCustody() = runBlocking {
        val root = root("late-alias-protection"); original(root); assertTrue(FileRepository(root).delete("src"))
        val stage = stages(root).single(); root.resolve("work/deep/level").mkdirs(); root.resolve("work/deep/level/source.txt").writeText("selected outside file")
        var redirected = false
        val repo = FileRepository(root, beforeMutation = {
            if (!redirected) {
                redirected = true; Files.move(root.resolve("work").toPath(), root.resolve("held-work").toPath())
                Files.createSymbolicLink(root.resolve("work").toPath(), stage.toPath())
            }
        })
        thrown { repo.delete("work/deep/level/source.txt") }
        assertEquals("original", stage.resolve("deep/level/source.txt").readText())
        assertEquals("selected outside file", root.resolve("held-work/deep/level/source.txt").readText())
    }
}
