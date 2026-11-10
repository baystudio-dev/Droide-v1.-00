package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Reconstructed after reset: real profile/registry code, inert files only. */
class LspPerlProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "perlnavigator" }
    @Test fun exactPerlRoutesUseOriginalStdioAndProtocol() {
        for (path in listOf("main.pl", "lib/Example.pm")) {
            assertEquals("perl", LanguageServerRegistry.protocolLanguageId(path))
            assertEquals(spec, LanguageServerRegistry.forPath(path).single())
            assertEquals(listOf("perlnavigator", "--stdio"), spec.commands.single())
        }
        assertEquals(55, LanguageRegistry.all.size)
        assertEquals(46, LanguageServerRegistry.builtIns.size)
    }
    @Test fun nearestProjectMarkersStayInsideWorkspaceAndFallbackIsWorkspace() {
        val parent = Files.createTempDirectory("perl-profile-").toFile().canonicalFile
        try {
            val root = File(parent,"workspace").apply { mkdirs() }
            val source = File(root,"nested/deep/a.pl").apply { parentFile.mkdirs(); writeText("# inert") }
            File(parent,"cpanfile").writeText("# outside")
            assertEquals(root, LspServerProfiles.projectRoot(spec,root,source))
            File(root,"Makefile.PL").writeText("# marker only")
            File(root,"nested/cpanfile").writeText("# marker only")
            assertEquals(File(root,"nested"),LspServerProfiles.projectRoot(spec,root,source))
            File(root,"nested/deep/Build.PL").writeText("# marker only")
            assertEquals(source.parentFile,LspServerProfiles.projectRoot(spec,root,source))
            assertTrue(runCatching { LspServerProfiles.projectRoot(spec,root,File(parent,"outside.pl")) }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }
    @Test fun symlinkMarkerDoesNotChooseAnOutsideProject() {
        val parent = Files.createTempDirectory("perl-symlink-").toFile().canonicalFile
        try {
            val root = File(parent,"workspace").apply { mkdirs() }
            val file = File(root,"nested/a.pl").apply { parentFile.mkdirs(); writeText("# inert") }
            val outside = File(parent,"outside").apply { writeText("# inert") }
            Files.createSymbolicLink(File(root,"nested/cpanfile").toPath(),outside.toPath())
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,file))
        } finally { PathSecurity.deleteTreeNoFollow(parent) }
    }
    @Test fun reviewedConfigurationPreservesFullIntelligenceAfterConsent() {
        assertEquals(JsonPrimitive(false),LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject["configuration"])
        val settings = LspServerProfiles.configurationAfterInitialize(spec,"/usr/bin/perl")!!["settings"]!!.jsonObject["perlnavigator"]!!.jsonObject
        assertEquals(JsonPrimitive("/usr/bin/perl"),settings["perlPath"])
        for (key in listOf("includeLib","enableWarnings","perlCompileEnabled","perlcriticEnabled","perltidyEnabled")) assertEquals(key,JsonPrimitive(true),settings[key])
        for (key in listOf("perlimportsLintEnabled","perlimportsTidyEnabled","logging","enableProgress")) assertEquals(key,JsonPrimitive(false),settings[key])
        assertEquals(JsonArray(emptyList()),settings["includePaths"])
        assertEquals(JsonArray(emptyList()),settings["perlParams"])
        assertTrue(runCatching { LspServerProfiles.configurationAfterInitialize(spec,null) }.isFailure)
        assertTrue(runCatching { LspServerProfiles.configurationAfterInitialize(spec,"perl") }.isFailure)
        assertTrue(LspServerProfiles.optionalToolNote(spec)!!.contains("unverified"))
    }
    @Test fun customPerlRoutesNeedFreshConsentWithoutBuiltinProfile() {
        val custom = spec.copy(source="custom",commands=listOf(listOf("custom-server")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.pl"))
        assertFalse(LspServerProfiles.isPerl(custom)); assertFalse(LspServerProfiles.hasProjectRoots(custom))
        assertNull(LspServerProfiles.configurationAfterInitialize(custom,null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=emptySet(),extensions=setOf("pm")),"unknown.file"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=setOf("perl"),extensions=emptySet()),"unknown.file"))
    }
    @Test fun unsafeReleasedServerPathCharactersAreRejectedBeforeAnalysis() {
        for (path in listOf("/workspace/has'quote.pl","/workspace/has\"quote.pl","/workspace/back\\slash.pl","/workspace/line\nbreak.pl","/workspace/ctrl\u007f.pl"))
            assertTrue(path,runCatching { LspServerProfiles.requireSupportedDocumentPath(spec,path) }.isFailure)
        LspServerProfiles.requireSupportedDocumentPath(spec,"/workspace/space and unicode-λ.pl")
    }
}
