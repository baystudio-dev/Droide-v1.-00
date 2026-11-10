package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun skillMarkdown(name: String = "review-code", body: String = "Review the code carefully.", flags: String = "") =
    "---\nname: $name\ndescription: Review code changes\n$flags---\n\n$body\n"

class SkillManagementTest {
    @get:Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    private suspend fun fixture(block: suspend (File, File, SkillManagement) -> Unit) {
        val root = Files.createTempDirectory("managed-skills-").toFile()
        val work = root.resolve("workspace").apply { mkdir() }
        val personal = root.resolve("personal")
        try { block(work, personal, SkillManagement(FileRepository(work), personalRoot = personal)) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    @Test fun listingDoesNotCreateStorageAndPersonalSkillsAreSharedAcrossProjects() = runBlocking { fixture { work, personal, management ->
        assertTrue(management.catalog().none { it.sourceLabel != "builtin" }); assertFalse(personal.exists()); assertFalse(work.resolve(".droide").exists())
        management.save(skillMarkdown(), "user")
        val other = work.resolve("other").apply { mkdir() }
        assertEquals("personal", SkillManagement(FileRepository(other), personalRoot = personal).document("review-code").skill.sourceLabel)
        assertFalse(work.resolve(".droide/skills").exists())
    } }

    @Test fun workspaceOverrideDoesNotHidePersonalSkillFromItsSettingsScopeOrReceipt() = runBlocking { fixture { _, _, management ->
        management.save(skillMarkdown(body = "Project instructions"), "workspace")
        val saved = management.save(skillMarkdown(body = "Personal instructions"), "user")
        assertEquals("Personal instructions", saved.skill.body)
        assertEquals("Project instructions", management.document("review-code").skill.body)
        assertEquals("Personal instructions", management.document("review-code", "user").skill.body)
        assertEquals("personal", management.catalog("user").filter { it.sourceLabel != "builtin" }.single().sourceLabel)
    } }

    @Test fun updatesRequireCurrentVersionAndCannotOverwriteNewerEdits() = runBlocking { fixture { work, _, management ->
        val saved = management.save(skillMarkdown(), "workspace")
        assertTrue(runCatching { management.save(skillMarkdown(body = "Unreviewed"), "workspace") }.isFailure)
        val updated = management.save(skillMarkdown(body = "Reviewed update"), "workspace", saved.version.sha256)
        assertEquals("Reviewed update", updated.skill.body)
        val path = work.resolve(".droide/skills/review-code/SKILL.md")
        path.writeText(skillMarkdown(body = "Concurrent user edit"))
        assertTrue(runCatching { management.save(skillMarkdown(body = "Stale update"), "workspace", updated.version.sha256) }.isFailure)
        assertTrue(path.readText().contains("Concurrent user edit"))
    } }

    @Test fun mutationsUseWorkspaceAdmissionAndPreserveDraftsOnRejection() = runBlocking { fixture { work, _, management ->
        val saved = management.save(skillMarkdown(), "workspace")
        val guarded = SkillManagement(FileRepository(work, beforeMutation = { error("Unsaved editor draft") }))
        val failure = runCatching { guarded.save(skillMarkdown(body = "Agent update"), "workspace", saved.version.sha256) }.exceptionOrNull()
        assertTrue(failure!!.message.orEmpty().contains("Unsaved editor draft"))
        assertEquals(saved.markdown, management.document("review-code").markdown)
    } }

    @Test fun archivePreservesResourcesWithoutRunningOrGrantingExecutablePermissions() = runBlocking { fixture { work, _, management ->
        val archive = zip("folder/SKILL.md" to skillMarkdown().toByteArray(), "folder/scripts/run.sh" to "touch SHOULD_NOT_EXIST".toByteArray(), "folder/assets/logo.bin" to byteArrayOf(1, 2, 3))
        management.importFile(archive.inputStream(), "review.zip", "workspace")
        assertEquals(listOf("assets/logo.bin", "scripts/run.sh"), management.resources("review-code"))
        assertFalse(work.resolve("SHOULD_NOT_EXIST").exists())
        assertFalse(work.resolve(".droide/skills/review-code/scripts/run.sh").canExecute())
        assertEquals("touch SHOULD_NOT_EXIST", management.readResource("review-code", "scripts/run.sh"))
        management.copyResource("review-code", "assets/logo.bin", "assets/review.bin")
        assertArrayEquals(byteArrayOf(1, 2, 3), work.resolve("assets/review.bin").readBytes())
        assertTrue(runCatching { management.copyResource("review-code", "assets/logo.bin", "assets/review.bin") }.isFailure)
    } }

    @Test fun invalidArchivesDoNotPublishPartialSkillsOrEscapeTheDestination() = runBlocking { fixture { work, _, management ->
        val valid = "folder/SKILL.md" to skillMarkdown().toByteArray()
        for (archive in listOf(
            zip(valid, "../escape" to byteArrayOf(1)),
            zip(valid, "/absolute" to byteArrayOf(1)),
            zip(valid, "folder/../escape" to byteArrayOf(1)),
            zip(valid, "unrelated.txt" to byteArrayOf(1)),
            zip(valid, "folder/nested/SKILL.md" to skillMarkdown("nested").toByteArray()),
            zip(valid, "folder/huge" to ByteArray(SkillManagement.MAX_RESOURCE_BYTES + 1)),
        )) {
            assertTrue(runCatching { management.importFile(archive.inputStream(), "bad.zip", "workspace") }.isFailure)
            assertTrue(management.catalog().none { it.sourceLabel != "builtin" })
        }
        assertFalse(work.resolve("escape").exists())
    } }

    @Test fun interruptedStagingNeverAppearsAsAnInstalledSkill() = runBlocking { fixture { work, _, management ->
        var writes = 0
        val guarded = SkillManagement(FileRepository(work, beforeMutation = {
            if (it is FileRepository.Mutation.Write && ++writes == 2) throw CancellationException("Import cancelled")
        }))
        val archive = zip("SKILL.md" to skillMarkdown().toByteArray(), "reference.md" to "Reference".toByteArray())
        assertTrue(runCatching { guarded.importFile(archive.inputStream(), "skill.zip", "workspace") }.exceptionOrNull() is CancellationException)
        assertTrue(management.catalog().none { it.sourceLabel != "builtin" }); assertFalse(work.resolve(".droide/skills/review-code").exists())
    } }

    @Test fun removingSkillRequiresVersionAndKeepsSupportingFiles() = runBlocking { fixture { work, _, management ->
        val saved = management.save(skillMarkdown(), "workspace")
        work.resolve(".droide/skills/review-code/reference.md").writeText("Keep me")
        assertTrue(runCatching { management.remove("review-code", "workspace", "stale") }.isFailure)
        management.remove("review-code", "workspace", saved.version.sha256)
        assertTrue(management.catalog().none { it.sourceLabel != "builtin" })
        assertEquals("Keep me", work.resolve(".droide/skills/review-code/reference.md").readText())
    } }

    @Test fun resourcesRejectTraversalSymlinksAndReplacedParents() = runBlocking { fixture { work, _, management ->
        management.save(skillMarkdown(), "workspace")
        val bundle = work.resolve(".droide/skills/review-code")
        val secret = work.resolve("secret").apply { writeText("Do not disclose") }
        Files.createSymbolicLink(bundle.resolve("link").toPath(), secret.toPath())
        assertTrue(runCatching { management.readResource("review-code", "link") }.isFailure)
        assertTrue(runCatching { management.readResource("review-code", "../../../secret") }.isFailure)
        assertTrue(runCatching { management.resources("review-code") }.isFailure)
        bundle.resolve("link").delete(); bundle.resolve("reference").writeText("Original")
        val reader = SkillBundleReader(work, bundle)
        Files.move(bundle.toPath(), bundle.resolveSibling("retained").toPath())
        bundle.mkdir(); bundle.resolve("reference").writeText("Replacement")
        assertTrue(runCatching { reader.read("reference", 100) }.isFailure)
    } }

    @Test fun internalStagesAndRepositoryStateAreNeitherListedNorReadable() = runBlocking { fixture { work, _, management ->
        management.save(skillMarkdown(), "workspace")
        val bundle = work.resolve(".droide/skills/review-code")
        bundle.resolve(".droide-tmp-test").writeText("PRIVATE_STAGE")
        bundle.resolve(".git").mkdir(); bundle.resolve(".git/config").writeText("PRIVATE_REPOSITORY")
        bundle.resolve("guide.md").writeText("Public reference")
        assertEquals(listOf("guide.md"), management.resources("review-code"))
        for (path in listOf(".droide-tmp-test", ".git/config"))
            assertTrue(runCatching { management.readResource("review-code", path) }.isFailure)
        val archive = zip("SKILL.md" to skillMarkdown("other").toByteArray(), ".droide-tmp-test" to byteArrayOf(1))
        assertTrue(runCatching { management.importFile(archive.inputStream(), "skill.zip", "workspace") }.isFailure)
        assertNull(management.manager().load("other"))
    } }

    @Test fun personalScopeDoesNotDiscoverNestedWorkspaceConfiguration() = runBlocking { fixture { _, personal, management ->
        management.save(skillMarkdown(body = "Personal"), "user")
        personal.resolve(".droide/skills/review-code/SKILL.md").apply { parentFile.mkdirs(); writeText(skillMarkdown(body = "Nested project")) }
        assertEquals("Personal", management.document("review-code", "user").skill.body)
        assertEquals(listOf("personal"), management.catalog("user").filter { it.sourceLabel != "builtin" }.map { it.sourceLabel })
    } }

    @Test fun compatibleAliasedEntryPointCannotExposeWholeProjectAsResources() = runBlocking { fixture { work, _, management ->
        val target = work.resolve("shared.md").apply { writeText(skillMarkdown()) }
        val alias = work.resolve(".droide/skills/review-code/SKILL.md").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(alias.toPath(), target.toPath())
        work.resolve("private.txt").writeText("Project data")
        assertNotNull(management.document("review-code"))
        assertNull(management.document("review-code").managedScope)
        assertTrue(runCatching { management.readResource("review-code", "private.txt") }.isFailure)
    } }

    @Test fun frontmatterControlsSeparateManualInvocationFromAutomaticDiscovery() = runBlocking { fixture { _, _, management ->
        management.save(skillMarkdown("manual", flags = "disable-model-invocation: true\n"), "workspace")
        management.save(skillMarkdown("automatic", flags = "user-invocable: false\n"), "workspace")
        assertEquals(listOf("automatic"), management.manager().modelCatalog().filter { it.sourceLabel != "builtin" }.map { it.name })
        assertEquals(listOf("manual"), management.manager().invocationCatalog().filter { it.sourceLabel != "builtin" }.map { it.name })
        assertNotNull(management.document("manual")); assertEquals(2, management.catalog().count { it.sourceLabel != "builtin" })
    } }

    @Test fun invalidDocumentsAreRejectedBeforeCreatingAnyStorage() = runBlocking { fixture { work, personal, management ->
        for (text in listOf(skillMarkdown("../../bad"), skillMarkdown("UPPER"), skillMarkdown(body = ""), skillMarkdown(flags = "user-invocable: maybe\n"),
            skillMarkdown(flags = "name: duplicate\n"), skillMarkdown(body = "\u0000"), skillMarkdown(body = "x".repeat(200_001)))) {
            assertTrue(runCatching { management.save(text, "user") }.isFailure)
        }
        assertFalse(personal.exists()); assertFalse(work.resolve(".droide").exists())
    } }
}
