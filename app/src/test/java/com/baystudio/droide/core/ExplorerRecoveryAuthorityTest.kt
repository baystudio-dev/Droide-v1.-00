package com.baystudio.droide.core

import com.baystudio.droide.ui.ExplorerRecoveryPolicy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExplorerRecoveryAuthorityTest {
    @Test fun copyAuthorityIsNotOfferedAsMisplacedUserContent() {
        assertFalse(ExplorerRecoveryPolicy.canRecover(".droide/copy-recovery"))
        assertFalse(ExplorerRecoveryPolicy.canRecover(".droide/copy-recovery-archive"))
        assertTrue(ExplorerRecoveryPolicy.canRecover(".droide/MyProject"))
    }

    @Test fun actualCopyAuthorityCannotBeMovedByRecoveryMutation() = runBlocking {
        val base = Files.createTempDirectory("explorer-authority-red").toFile()
        try {
            val root = base.resolve("workspace").apply { mkdirs() }
            val journal = ProjectCopyJournal(root)
            val owner = ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
                "run-red", ProjectCopyJournal.explorerSourceId("content://fixture/red"))
            journal.begin(owner, "asset.bin").close()
            val authority = root.resolve(".droide/copy-recovery")
            assertTrue(authority.isDirectory)
            val files = FileRepository(root)
            val failure = runCatching {
                files.moveObserved(files.observeMove(".droide/copy-recovery", "copy-recovery"))
            }.exceptionOrNull()
            assertNotNull("Reserved authority was moved through ordinary recovery mutation", failure)
            assertTrue(authority.isDirectory)
            assertFalse(root.resolve("copy-recovery").exists())
        } finally { base.deleteRecursively() }
    }
    private fun fixture(body: suspend (java.io.File, FileRepository) -> Unit) = runBlocking {
        val base = Files.createTempDirectory("copy-namespace").toFile()
        try {
            val root = base.resolve("workspace").apply { mkdirs() }
            val journal = ProjectCopyJournal(root)
            journal.begin(ProjectCopyJournal.Owner(ProjectCopyJournal.Kind.EXPLORER_IMPORT, null,
                "run", ProjectCopyJournal.explorerSourceId("content://fixture/test")), "asset.bin").close()
            root.resolve(".droide/copy-recovery-archive").mkdirs()
            body(root, FileRepository(root))
        } finally { base.deleteRecursively() }
    }

    private fun snapshot(root: java.io.File): Map<String, String> = root.walkTopDown().associate {
        it.relativeTo(root).path to if (it.isDirectory) "directory" else it.readBytes().joinToString(",")
    }

    @Test fun actualJournalAndArchiveRefuseOrdinaryDeleteWithoutChangingEvidence() = fixture { root, files ->
        val before = snapshot(root)
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            assertNotNull(runCatching { files.delete(".droide/$home") }.exceptionOrNull())
            assertEquals(before, snapshot(root))
        }
    }

    @Test fun actualJournalAndArchiveRefuseWriteAndDirectoryInsertion() = fixture { root, files ->
        val before = snapshot(root)
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            assertNotNull(runCatching { files.writeText(".droide/$home/injected", "bad") }.exceptionOrNull())
            assertNotNull(runCatching { files.createDirectory(".droide/$home/injected") }.exceptionOrNull())
            assertEquals(before, snapshot(root))
        }
    }

    @Test fun actualArchiveAndJournalDescendantsCannotBeRecoveredByMove() = fixture { root, files ->
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            root.resolve(".droide/$home/retained").apply { mkdirs(); resolve("payload").writeText("preserve") }
            val before = snapshot(root)
            assertNotNull(runCatching { files.moveObserved(files.observeMove(".droide/$home/retained", "recovered")) }.exceptionOrNull())
            assertEquals(before, snapshot(root))
        }
    }

    @Test fun destinationInsertionAndAncestorRemovalStayBlocked() = fixture { root, files ->
        root.resolve("ordinary").writeText("preserve")
        val before = snapshot(root)
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            assertNotNull(runCatching { files.move("ordinary", ".droide/$home/new") }.exceptionOrNull())
        }
        assertNotNull(runCatching { files.moveObserved(files.observeMove(".droide", "internal")) }.exceptionOrNull())
        assertNotNull(runCatching { files.delete(".droide") }.exceptionOrNull())
        assertEquals(before, snapshot(root))
    }

    @Test fun canonicalAliasAndDotSegmentsCannotBypassOwnerGuard() = fixture { root, _ ->
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            val alias = root.resolve("alias-$home")
            Files.createSymbolicLink(alias.toPath(), root.resolve(".droide/$home").toPath())
            val paths = listOf(alias.name + "/item", ".droide/./$home/item", ".droide/other/../$home/item",
                ".droide\\$home\\item")
            for (path in paths) {
                assertNotNull(path, runCatching {
                    ProjectCopyJournal.requireOrdinaryMutation(FileRepository.Mutation.Write(path), root)
                }.exceptionOrNull())
            }
        }
    }

    @Test fun bothMutationOperandsAndAllOrdinaryKindsAreChecked() = fixture { root, _ ->
        for (home in listOf("copy-recovery", "copy-recovery-archive")) {
            for (path in listOf(".droide/$home", ".droide/$home/child")) {
                val mutations = listOf(FileRepository.Mutation.Write(path), FileRepository.Mutation.Delete(path),
                    FileRepository.Mutation.CreateDirectory(path), FileRepository.Mutation.Move(path, "out"),
                    FileRepository.Mutation.Move("in", path))
                mutations.forEach { assertNotNull(runCatching { ProjectCopyJournal.requireOrdinaryMutation(it, root) }.exceptionOrNull()) }
            }
        }
    }

    @Test fun legitimateMisplacedProjectAndNearPrefixRemainRecoverable() = fixture { root, files ->
        for (name in listOf("MyProject", "copy-recovery-user", "copy-recovery-archive-user")) {
            assertTrue(ExplorerRecoveryPolicy.canRecover(".droide/$name"))
            root.resolve(".droide/$name").apply { mkdirs(); resolve("data").writeText("user") }
            assertTrue(files.moveObserved(files.observeMove(".droide/$name", name)))
            assertEquals("user", root.resolve("$name/data").readText())
        }
    }

    @Test fun guardDoesNotCreateAuthorityOrChangeSyncSemantics() = runBlocking {
        val root = Files.createTempDirectory("pure-copy-guard").toFile()
        try {
            ProjectCopyJournal.requireOrdinaryMutation(FileRepository.Mutation.CreateDirectory(".droide"), root)
            ProjectCopyJournal.requireOrdinaryMutation(FileRepository.Mutation.Sync(".droide/copy-recovery"), root)
            assertNotNull(runCatching { ProjectCopyJournal.requireOrdinaryMutation(
                FileRepository.Mutation.Write(".droide/copy-recovery/new"), root) }.exceptionOrNull())
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun refusalOccursBeforeMutationCallbacks() = fixture { root, _ ->
        var callbacks = 0
        val files = FileRepository(root, beforeMutation = { callbacks++ }, onMutation = { callbacks++ })
        assertNotNull(runCatching { files.writeText(".droide/copy-recovery/new", "bad") }.exceptionOrNull())
        assertEquals(0, callbacks)
    }

    @Test fun callbackAliasSubstitutionIsRefusedBeforeProtectedLocalEffect() = fixture { root, _ ->
        val ordinary = root.resolve("ordinary").apply { mkdirs() }
        val authority = root.resolve(".droide/copy-recovery")
        val before = snapshot(authority)
        var callbacks = 0
        val files = FileRepository(root, beforeMutation = {
            callbacks++
            check(ordinary.renameTo(root.resolve("saved-ordinary")))
            Files.createSymbolicLink(ordinary.toPath(), authority.toPath())
        })
        assertNotNull(runCatching { files.writeText("ordinary/injected", "bad") }.exceptionOrNull())
        assertEquals(1, callbacks)
        assertEquals(before, snapshot(authority))
        assertFalse(authority.resolve("injected").exists())
    }

}
