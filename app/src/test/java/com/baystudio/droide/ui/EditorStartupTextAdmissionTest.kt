package com.baystudio.droide.ui

import com.baystudio.droide.core.EditorRecoveryBuffer
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.PathSecurity
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorStartupTextAdmissionTest {
    @Test fun binaryBeyondTheHeaderNeverBecomesAnEditableRestoredFile() = runBlocking {
        val root = Files.createTempDirectory("droide-startup-binary-").toFile()
        try {
            val bytes = ("a".repeat(16_384) + '\u0000' + "tail").toByteArray()
            root.resolve("last.txt").writeBytes(bytes)
            val files = FileRepository(root)
            val state = EditorWorkspaceState()
            val recovered = EditorRecoverySnapshot(listOf("last.txt"), "last.txt", emptyMap())
            val tabs = EditorTabRetention.restoreDocuments(recovered, files, state)
            assertEquals(listOf("last.txt"), tabs)
            val document = state.document("last.txt")
            document.ensureLoaded(files)
            assertEquals(EditorDocumentKind.BINARY, document.kind)
            assertFalse(document.editable)
            assertFalse(document.dirty)
            assertArrayEquals(bytes, root.resolve("last.txt").readBytes())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun binaryReloadKeepsTheAlreadyLoadedTextAndItsDiskBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-startup-binary-reload-").toFile()
        try {
            root.resolve("last.txt").writeText("original")
            val files = FileRepository(root)
            val document = EditorWorkspaceState().document("last.txt")
            document.ensureLoaded(files)
            val baseline = document.savedDiskVersion
            val bytes = ("a".repeat(8_192) + '\u0000').toByteArray()
            root.resolve("last.txt").writeBytes(bytes)
            document.reload(files)
            assertEquals("original", document.content)
            assertEquals(baseline, document.savedDiskVersion)
            assertTrue(document.status.contains("Binary file"))
            assertArrayEquals(bytes, root.resolve("last.txt").readBytes())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun validUnicodeAndLineEndingsStillRestoreExactly() = runBlocking {
        val root = Files.createTempDirectory("droide-startup-unicode-").toFile()
        try {
            val text = "\uFEFFé漢字\uD83D\uDE00\t\r\nمرحبا\nlast"
            root.resolve("last.txt").writeText(text)
            val document = EditorWorkspaceState().document("last.txt")
            document.ensureLoaded(FileRepository(root))
            assertTrue(document.editable)
            assertEquals(text, document.content)
            assertFalse(document.dirty)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun recoveredBinaryDraftRemainsOwnedAndAvailableForCheckpointing() = runBlocking {
        val root = Files.createTempDirectory("droide-startup-recovered-binary-").toFile()
        try {
            root.resolve("last.txt").writeText("saved version")
            val text = "a".repeat(8_192) + '\u0000' + "unsaved changes"
            val reference = EditorRecoveryBuffer("last.txt", "draft.bin", null, Any()) { text }
            val state = EditorWorkspaceState()
            state.document("last.txt").restoreDeferred(reference)
            state.document("last.txt").ensureLoaded(FileRepository(root))
            val checkpoint = checkNotNull(state.recoverySnapshot(listOf("last.txt"), "last.txt"))
            assertEquals(text, checkpoint.dirtyBuffers["last.txt"])
            assertTrue(state.document("last.txt").dirty)
            assertEquals("saved version", root.resolve("last.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
