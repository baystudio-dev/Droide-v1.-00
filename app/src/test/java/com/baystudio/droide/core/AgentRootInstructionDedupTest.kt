package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentRootInstructionDedupTest {
    private val body = "Preserve user changes and follow this source's directory scope.\n".repeat(35)
    private fun root() = Files.createTempDirectory("droide-root-dedup-").toFile()
    private fun File.write(path: String, text: String) = resolve(path).apply { parentFile.mkdirs(); writeText(text) }
    private fun count(text: String, value: String) = text.windowed(value.length).count { it == value }
    @Test fun sameDirectoryExactCompleteBodiesKeepSourceLabelsOrderAndVerbatimRules() = runBlocking {
        val root = root()
        try {
            root.write("AGENTS.md", body);root.write("GEMINI.md", body)
            val brief=AgentProjectInstructions.load(FileRepository(root),root)
            assertEquals(1,count(brief,body))
            assertTrue(brief.contains("--- Project instructions (AGENTS.md) ---"))
            assertTrue(brief.contains("--- Project instructions (GEMINI.md) ---"))
            assertTrue(brief.indexOf("(AGENTS.md)")<brief.indexOf("(GEMINI.md)"))
            assertTrue(brief.contains("Apply that body independently as GEMINI.md at the project root"))
            assertTrue(brief.length < body.length*2)
            assertFalse(brief.contains("incomplete=true"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun interveningDroideSourceCannotChangeLastWinsOrderingEvenWhenIdentical() = runBlocking {
        val root=root()
        try {
            root.write("AGENTS.md",body);root.write("GEMINI.md",body)
            for (middle in listOf("Different rule B",body)) {
                root.write(".droide/AGENTS.md",middle)
                val brief=AgentProjectInstructions.load(FileRepository(root),root)
                assertEquals(if(middle==body)3 else 2,count(brief,body))
                assertFalse(brief.contains("Exact full-text duplicate"))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun uniqueWhitespaceDifferentAndShortBodiesStayFullyEmitted() = runBlocking {
        val root=root()
        try {
            for ((a,b) in listOf(body to "$body ",body to body.replace("\n", "\r\n"),body to "Unique second rules", "Short" to "Short")) {
                root.write("AGENTS.md",a);root.write("GEMINI.md",b)
                val brief=AgentProjectInstructions.load(FileRepository(root),root)
                assertTrue(brief.contains("--- Project instructions (AGENTS.md) ---\n$a\n"))
                assertTrue(brief.contains("--- Project instructions (GEMINI.md) ---\n$b\n"))
                assertFalse(brief.contains("Exact full-text duplicate"))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun changedFilesInvalidateOnEachLoadAndScopedToolAlwaysRereads() = runBlocking {
        val root=root()
        try {
            root.write("AGENTS.md",body);root.write("GEMINI.md",body)
            val files=FileRepository(root)
            assertTrue(AgentProjectInstructions.load(files,root).contains("Exact full-text duplicate"))
            root.write("AGENTS.md","Changed current instructions")
            val changed=AgentProjectInstructions.load(files,root)
            assertTrue(changed.contains("Changed current instructions"));assertTrue(changed.contains(body))
            assertFalse(changed.contains("Exact full-text duplicate"))
            root.write("src/AGENTS.md",body)
            val scoped=AgentProjectInstructions.loadScoped(files,"src/new.kt")
            assertTrue(scoped.contains("Changed current instructions"));assertTrue(scoped.contains(body))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun partialAndOversizedOrInterveningUnreadSourcesNeverBecomeDuplicateReferences() = runBlocking {
        val root=root()
        try {
            for (size in listOf(8_001,60_000)) {
                root.write("AGENTS.md","x".repeat(size));root.write("GEMINI.md","x".repeat(size))
                val brief=AgentProjectInstructions.load(FileRepository(root),root)
                assertTrue(brief.contains("incomplete=true"));assertTrue(brief.contains("file_limit"))
                assertTrue(brief.contains("AGENTS.md"));assertTrue(brief.contains("GEMINI.md"))
                assertFalse(brief.contains("Exact full-text duplicate"))
            }
            root.write("AGENTS.md",body);root.write("GEMINI.md",body);root.write(".droide/AGENTS.md","x".repeat(60_000))
            val brief=AgentProjectInstructions.load(FileRepository(root),root)
            assertTrue(brief.contains("incomplete=true"));assertEquals(2,count(brief,body))
            assertFalse(brief.contains("Exact full-text duplicate"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun differentRepositoryAndWorkRootNeverShareInstructionBodies() = runBlocking {
        val root=root();val other=root()
        try {
            for (dir in listOf(root,other)) {dir.write("AGENTS.md",body);dir.write("GEMINI.md",body)}
            val brief=AgentProjectInstructions.load(FileRepository(root),other)
            assertEquals(2,count(brief,body))
            assertFalse(brief.contains("Exact full-text duplicate"))
        } finally {PathSecurity.deleteTreeNoFollow(root);PathSecurity.deleteTreeNoFollow(other)}
    }

}
