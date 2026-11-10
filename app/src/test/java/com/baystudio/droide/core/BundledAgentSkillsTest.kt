package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BundledAgentSkillsTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    @Test fun freshWorkspaceHasReadOnlySkillsWithoutCreatingFilesOrLoadingBodiesIntoMetadata() = runBlocking {
        val root = Files.createTempDirectory("builtin-skills-").toFile()
        try {
            val management = SkillManagement(FileRepository(root))
            assertEquals(setOf("documents", "audio", "image-generation"), management.catalog().map { it.name }.toSet())
            assertTrue(root.listFiles().orEmpty().isEmpty())
            assertTrue(management.catalog().all { it.sourceLabel == "builtin" && it.description.length < 150 })
            for (entry in management.catalog()) {
                val doc = management.document(entry.name)
                assertTrue(doc.bundled); assertNull(doc.managedScope); assertFalse(doc.file.exists())
                assertTrue(doc.userInvocable); assertTrue(management.resources(entry.name).isEmpty())
                assertEquals(entry.name, SkillDocumentFormat.validate(doc.markdown))
            }
            val tags = AgentMentionCatalog.load(root, AgentPluginSource.EMPTY).map { it.token }.toSet()
            assertTrue(tags.containsAll(setOf("@skill/documents", "@skill/audio", "@skill/image-generation")))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun userOverrideAndInvocationFlagsTakePrecedenceOverBuiltinFallback() = runBlocking {
        val root = Files.createTempDirectory("builtin-override-").toFile()
        try {
            val management = SkillManagement(FileRepository(root))
            management.save(skillMarkdown("audio", "Use our local speech service", "disable-model-invocation: true\nuser-invocable: false\n"), "workspace")
            assertFalse(management.document("audio").bundled)
            assertEquals("Use our local speech service", management.document("audio").skill.body)
            assertTrue(management.manager().modelCatalog().none { it.name == "audio" })
            assertTrue(management.manager().invocationCatalog().none { it.name == "audio" })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
