package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryEditorSaveContractTest {
    @Test fun explicitlyOpenedWorkspaceConfigurationRemainsEditable() = runBlocking {
        val root = Files.createTempDirectory("droide-save-config-").toFile()
        try {
            val file = root.resolve(".droide/mcp.json").apply { parentFile.mkdirs(); writeText("{}") }
            FileRepository(root).writeEditorTextIfUnchanged(".droide/mcp.json", "{\"servers\":[]}", DiskFileVersion.fromText("{}"))
            assertEquals("{\"servers\":[]}", file.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun editorSavePreservesExecutableAndPrivatePermissions() = runBlocking {
        val root = Files.createTempDirectory("droide-save-mode-").toFile()
        try {
            val file = root.resolve("start.sh").apply { writeText("before\n") }
            val expectedMode = PosixFilePermissions.fromString("rwxr-x---")
            Files.setPosixFilePermissions(file.toPath(), expectedMode)
            FileRepository(root).writeEditorTextIfUnchanged("start.sh", "after\n", DiskFileVersion.fromText("before\n"))
            assertEquals("after\n", file.readText())
            assertEquals(expectedMode, Files.getPosixFilePermissions(file.toPath()))
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".droide-tmp-") })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun editorSaveDoesNotBypassReadOnlyPermissionsThroughReplacement() = runBlocking {
        val root = Files.createTempDirectory("droide-save-readonly-").toFile()
        try {
            val file = root.resolve("main.kt").apply { writeText("before") }
            val mode = PosixFilePermissions.fromString("r--r-----")
            Files.setPosixFilePermissions(file.toPath(), mode)
            val failure = runCatching {
                FileRepository(root).writeEditorTextIfUnchanged("main.kt", "after", DiskFileVersion.fromText("before"))
            }.exceptionOrNull()
            assertNotNull("Read-only source must require an explicit permission change", failure)
            assertEquals("before", file.readText())
            assertEquals(mode, Files.getPosixFilePermissions(file.toPath()))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun editorSaveDoesNotInventMissingParentFolders() = runBlocking {
        val root = Files.createTempDirectory("droide-save-parent-").toFile()
        try {
            val failure = runCatching {
                FileRepository(root).writeEditorTextIfUnchanged("deleted/main.kt", "draft", DiskFileVersion.fromText(null))
            }.exceptionOrNull()
            assertNotNull("A removed folder must be reviewed instead of silently recreated", failure)
            assertFalse(root.resolve("deleted").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun editorSaveRechecksParentAfterSuspendingValidation() = runBlocking {
        val root = Files.createTempDirectory("droide-save-parent-race-").toFile()
        try {
            root.resolve("chosen").mkdir()
            val repository = FileRepository(root, beforeMutation = { root.resolve("chosen").delete() })
            val failure = runCatching {
                repository.writeEditorTextIfUnchanged("chosen/main.kt", "draft", DiskFileVersion.fromText(null))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(root.resolve("chosen").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun editorSaveRetainsUtf8BomAndCrLfBytes() = runBlocking {
        val root = Files.createTempDirectory("droide-save-encoding-").toFile()
        try {
            val before = "\uFEFFhello\r\nworld\r\n"
            val after = "\uFEFFhello\r\nnew world\r\n"
            root.resolve("text.txt").writeText(before)
            val repository = FileRepository(root)
            assertEquals(before, repository.readEditorText("text.txt"))
            repository.writeEditorTextIfUnchanged("text.txt", after, DiskFileVersion.fromText(before))
            assertArrayEquals(after.toByteArray(Charsets.UTF_8), root.resolve("text.txt").readBytes())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
