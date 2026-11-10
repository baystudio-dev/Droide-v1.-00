package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SkillCatalogTest {
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-skill-catalog-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun skill(root: File, path: String = ".droide/skills/alpha/SKILL.md", name: String = "alpha",
                      description: String = "Description", body: String = "Instructions"): File = root.resolve(path).apply {
        parentFile.mkdirs(); writeText("---\nname: $name\ndescription: $description\n---\n$body")
    }

    @Test fun catalogMetadataMatchesFullDiscoveryAndBodyApiStaysCompatible() = fixture { root ->
        skill(root, body = "x".repeat(15_000))
        skill(root, ".agents/skills/beta/SKILL.md", "beta", "Beta", "Beta instructions")
        val manager = SkillManager(root)
        val full = manager.discover()
        assertEquals(full.map { SkillSummary(it.name, it.description, it.sourceLabel) }, manager.catalog())
        assertEquals(12_000, full.first().body.length)
        assertEquals(full.first(), manager.load("alpha"))
        assertEquals("Beta instructions", manager.load("beta")!!.body)
    }

    @Test fun ordinaryCatalogReadsOnlyHeadersAndSelectedLoadReadsOneBody() = fixture { root ->
        repeat(10) { skill(root, ".droide/skills/s$it/SKILL.md", "s$it", body = "x".repeat(100_000)) }
        val bytes = mutableMapOf<String, Long>()
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { file ->
            object : FilterInputStream(file.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also {
                    if (it > 0) bytes[file.parentFile.name] = (bytes[file.parentFile.name] ?: 0) + it
                }
            }
        }
        assertEquals(10, manager.catalog().size)
        assertEquals(10, bytes.size); assertTrue(bytes.values.all { it <= 512L })
        bytes.clear()
        assertEquals(12_000, manager.load("s9")!!.body.length)
        assertTrue(bytes.getValue("s9") > 100_000L)
        assertTrue(bytes.filterKeys { it != "s9" }.values.all { it <= 512L })
    }

    @Test fun metadataChangesWithSameSizeAndTimestampAreVisibleImmediately() = fixture { root ->
        val file = skill(root, description = "Before")
        val manager = SkillManager(root)
        assertEquals("Before", manager.catalog().single().description)
        val timestamp = Files.getLastModifiedTime(file.toPath())
        file.writeText(file.readText().replace("Before", "After!")); Files.setLastModifiedTime(file.toPath(), timestamp)
        assertEquals("After!", manager.catalog().single().description)
        assertEquals("After!", manager.load("alpha")!!.description)
    }

    @Test fun bodyEditsAdditionsAndDeletionsRequireNoCacheInvalidation() = fixture { root ->
        val file = skill(root)
        val manager = SkillManager(root)
        manager.catalog()
        skill(root, body = "Changed instructions")
        assertEquals("Changed instructions", manager.load("alpha")!!.body)
        skill(root, ".droide/skills/beta/SKILL.md", "beta")
        assertEquals(setOf("alpha", "beta"), manager.catalog().map { it.name }.toSet())
        assertTrue(file.delete()); assertNull(manager.load("alpha"))
        assertEquals(listOf("beta"), manager.catalog().map { it.name })
    }

    @Test fun declaredNamesAndRootPriorityMatchExistingDiscovery() = fixture { root ->
        skill(root, ".droide/skills/folder/SKILL.md", "Shared_Name", body = "first")
        skill(root, ".agents/skills/elsewhere/SKILL.md", "shared_name", body = "second")
        skill(root, ".opencode/skills/third/SKILL.md", "third", body = "third")
        val manager = SkillManager(root)
        assertEquals(listOf("shared_name", "third"), manager.catalog().map { it.name })
        assertEquals("first", manager.load("shared_name")!!.body)
        assertNull(manager.load("Shared_Name"))
        root.resolve(".droide/skills/folder/SKILL.md").delete()
        assertEquals("second", manager.load("shared_name")!!.body)
    }

    @Test fun folderFallbackQuotingUnicodeAndCrlfRemainSupported() = fixture { root ->
        val f = skill(root)
        f.writeText("---\r\ndescription: '日本語 – Deskripsi'\r\n---\r\nBody 😀\r\n")
        val manager = SkillManager(root)
        assertEquals(SkillSummary("alpha", "日本語 – Deskripsi", "workspace"), manager.catalog().single())
        assertEquals("Body 😀", manager.load("alpha")!!.body)
        f.writeText("---\nname: \"HELLO_WORLD\"\n---\nbody")
        assertEquals("hello_world", manager.catalog().single().name)
        assertEquals("hello_world", manager.catalog().single().description)
    }

    @Test fun longHeadersAndSplitDelimitersKeepTheirExistingMeaning() = fixture { root ->
        val f = skill(root)
        for (padding in listOf(480, 481, 482, 483, 484, 16_500)) {
            f.writeText("---\nname: alpha\n#" + "x".repeat(padding) + "\ndescription: Header 😀\n---\nBody")
            val manager = SkillManager(root)
            assertEquals("Header 😀", manager.catalog().single().description)
            assertEquals("Body", manager.load("alpha")!!.body)
        }
    }

    @Test fun invalidOrMissingHeadersAreSkippedWithoutHidingValidSkills() = fixture { root ->
        skill(root, ".droide/skills/valid/SKILL.md", "valid")
        val invalid = skill(root)
        for (text in listOf("no header", "---\nname: alpha\nno closing", "---\nname: invalid name!\n---\nbody", "\uFEFF---\n---")) {
            invalid.writeText(text)
            assertEquals(listOf("valid"), SkillManager(root).catalog().map { it.name })
        }
    }

    @Test fun exactFileLimitAndDescriptionLimitStayCompatible() = fixture { root ->
        val f = skill(root, description = "d".repeat(2_000))
        val header = f.readText().substringBefore("Instructions")
        f.writeText(header + "x".repeat(200_000 - header.length))
        val manager = SkillManager(root)
        assertEquals(1_024, manager.catalog().single().description.length)
        assertEquals(12_000, manager.load("alpha")!!.body.length)
        f.appendText("x")
        assertTrue(manager.catalog().isEmpty()); assertNull(manager.load("alpha"))
    }

    @Test fun catalogLimitStopsBeforeOpeningUnneededSkills() = fixture { root ->
        repeat(90) { skill(root, ".droide/skills/s$it/SKILL.md", "s$it") }
        var opens = 0
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { opens++; it.inputStream() }
        assertEquals(80, manager.catalog(80).size); assertEquals(80, opens)
        opens = 0; assertTrue(manager.catalog(0).isEmpty()); assertEquals(0, opens)
        assertTrue(runCatching { manager.catalog(-1) }.isFailure)
        assertTrue(runCatching { manager.catalog(161) }.isFailure)
    }

    @Test fun maximumDiscoveryCapAndPerRootCapArePreserved() = fixture { root ->
        for (base in listOf(".droide", ".agents")) repeat(110) {
            skill(root, "$base/skills/s$it/SKILL.md", base.removePrefix(".") + "-$it")
        }
        val manager = SkillManager(root)
        assertEquals(160, manager.catalog().size)
        assertEquals(160, manager.discover().size)
        assertEquals(100, manager.catalog().count { it.name.startsWith("droide-") })
    }

    @Test fun inWorkspaceFileAliasesRemainSupportedButEscapingAliasesAreRejected() = fixture { root ->
        val target = root.resolve("shared.md").apply { writeText("---\ndescription: Shared\n---\nBody") }
        val alias = root.resolve(".droide/skills/alias/SKILL.md").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(alias.toPath(), target.toPath())
        assertEquals("Body", SkillManager(root).load("alias")!!.body)
        val outside = Files.createTempFile("droide-outside-skill-", ".md").toFile()
        try {
            outside.writeText("---\nname: outside\n---\nPrivate")
            alias.delete(); Files.createSymbolicLink(alias.toPath(), outside.toPath())
            var opens = 0
            assertTrue(SkillManager(root, AgentPluginSource.EMPTY) { opens++; it.inputStream() }.catalog().isEmpty())
            assertEquals(0, opens)
        } finally { outside.delete() }
    }

    @Test fun symlinkedSkillDirectoryIsNotDiscovered() = fixture { root ->
        val target = root.resolve("shared").apply { mkdir() }
        target.resolve("SKILL.md").writeText("---\nname: shared\n---\nBody")
        root.resolve(".droide/skills").mkdirs()
        Files.createSymbolicLink(root.resolve(".droide/skills/shared").toPath(), target.toPath())
        assertTrue(SkillManager(root).catalog().isEmpty())
    }

    @Test fun concurrentFileGrowthCannotTurnBoundedLoadIntoAnUnboundedRead() = fixture { root ->
        val file = skill(root)
        var opens = 0; var bytes = 0L
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { target ->
            opens++
            if (opens == 2) file.appendText("x".repeat(210_000))
            object : FilterInputStream(target.inputStream()) {
                override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also {
                    if (it > 0) bytes += it
                }
            }
        }
        assertNull(manager.load("alpha")); assertEquals(2, opens)
        assertTrue(bytes <= 200_513L)
        assertTrue(file.length() > 210_000)
    }

    @Test fun changedNameBetweenSelectionAndBodyReadIsNotLoadedUnderOldName() = fixture { root ->
        val file = skill(root)
        var opens = 0
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { target ->
            opens++
            if (opens == 2) file.writeText(file.readText().replace("alpha", "other"))
            target.inputStream()
        }
        assertNull(manager.load("alpha"))
        assertEquals("other", SkillManager(root).load("other")!!.name)
    }

    @Test fun replacedFileDuringReadIsRejected() = fixture { root ->
        val file = skill(root)
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { target ->
            val input = target.inputStream()
            Files.move(file.toPath(), root.resolve("old.md").toPath())
            skill(root, body = "Replacement")
            input
        }
        assertTrue(manager.catalog().isEmpty())
        assertTrue(file.readText().contains("Replacement"))
        assertTrue(root.resolve("old.md").readText().contains("Instructions"))
    }

    @Test fun zeroProgressAndIoFailureDoNotLoopAndCancellationPropagates() = fixture { root ->
        skill(root)
        val zero = SkillManager(root, AgentPluginSource.EMPTY) { object : InputStream() {
            override fun read() = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        } }
        assertTrue(zero.catalog().isEmpty())
        assertTrue(SkillManager(root, AgentPluginSource.EMPTY) { throw java.io.IOException("unreadable") }.catalog().isEmpty())
        val error = runCatching { SkillManager(root, AgentPluginSource.EMPTY) { throw CancellationException("cancelled") }.catalog() }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun missingSkillDoesNotLoadAnyUnrelatedBody() = fixture { root ->
        skill(root, body = "x".repeat(100_000))
        var bytes = 0
        val manager = SkillManager(root, AgentPluginSource.EMPTY) { target -> object : FilterInputStream(target.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, length).also { if (it > 0) bytes += it }
        } }
        assertNull(manager.load("missing")); assertTrue(bytes <= 512)
    }
}
