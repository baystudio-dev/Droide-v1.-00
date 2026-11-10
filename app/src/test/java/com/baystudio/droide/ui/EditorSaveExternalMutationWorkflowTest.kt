package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorSaveExternalMutationWorkflowTest {
    @Test fun terminalWriteWhileSavePreflightSuspendsIsPreserved() = runBlocking {
        val root = Files.createTempDirectory("save-terminal-race-").toFile()
        try {
            val target = root.resolve("Main.kt").apply { writeText("base") }
            val files = FileRepository(root, beforeMutation = { target.writeText("terminal update") })
            val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("editor update")
            assertTrue("Save must refuse a newer external write", doc.save(files).isFailure)
            assertEquals("terminal update", target.readText())
            assertEquals("editor update", doc.content)
            assertTrue(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun terminalDeletionIsNotUndoneBySave() = runBlocking {
        val root = Files.createTempDirectory("save-terminal-delete-").toFile()
        try {
            val target = root.resolve("Main.kt").apply { writeText("base") }
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("unsaved editor text")
            assertTrue(target.delete())
            assertTrue("Deleted baseline requires explicit review", doc.save(files).isFailure)
            assertFalse(target.exists()); assertTrue(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun newEditorFileDoesNotOverwriteFileCreatedByTerminal() = runBlocking {
        val root = Files.createTempDirectory("save-terminal-create-").toFile()
        try {
            val target = root.resolve("new.kt")
            val files = FileRepository(root); val doc = EditorDocument("new.kt")
            doc.ensureLoaded(files); doc.capture("new editor content")
            target.writeText("")
            assertTrue("Existence is part of the editor baseline", doc.save(files).isFailure)
            assertEquals("", target.readText()); assertTrue(doc.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun explicitReloadThenSaveAndNewFileSaveRemainUsable() = runBlocking {
        val root = Files.createTempDirectory("save-review-retry-").toFile()
        try {
            val target = root.resolve("Main.kt").apply { writeText("base") }
            val files = FileRepository(root); val doc = EditorDocument("Main.kt")
            doc.ensureLoaded(files); doc.capture("old editor edit")
            target.writeText("terminal update")
            assertTrue(doc.save(files).isFailure)
            doc.reload(files, discardUnsaved = true)
            doc.capture("reviewed update")
            assertTrue(doc.save(files).isSuccess)
            assertEquals("reviewed update", target.readText()); assertFalse(doc.dirty)
            val fresh = EditorDocument("new.kt")
            fresh.ensureLoaded(files); fresh.capture("created safely")
            assertTrue(fresh.save(files).isSuccess)
            fresh.capture("saved again"); assertTrue(fresh.save(files).isSuccess)
            assertEquals("saved again", root.resolve("new.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
