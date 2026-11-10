package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspTexlabProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "texlab" }
    @Test fun reviewedRoutesHaveExactProtocolIdsWithoutAmbiguousExtensions() {
        for (ext in listOf("tex","sty","cls","lco","bib","bibtex")) {
            assertEquals(spec, LanguageServerRegistry.forPath("a.$ext").single())
            assertEquals(if (ext in listOf("bib","bibtex")) "bibtex" else "latex",LanguageServerRegistry.protocolLanguageId("a.$ext"))
        }
        assertEquals(listOf("texlab"),spec.commands.single())
        for (p in listOf("a.def","a.rnw","a.log","a.toml")) assertFalse(LanguageServerRegistry.forPath(p).contains(spec))
        assertEquals(55,LanguageRegistry.all.size)
    }
    @Test fun markerlessRootIsBoundedAndAlwaysPublished() {
        val parent=Files.createTempDirectory("texlab-root-").toFile().canonicalFile
        try {
            val root=File(parent,"workspace").apply {mkdirs()}
            val file=File(root,"nested/a.tex").apply {parentFile.mkdirs();writeText("inert")}
            File(parent,".texlabroot").writeText("")
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,file))
            assertTrue(LspServerProfiles.publishesWorkspaceRoot(spec,root))
            File(root,"nested/.texlabroot").writeText("")
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
            assertTrue(runCatching {LspServerProfiles.projectRoot(spec,root,File(parent,"outside.tex"))}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(parent)}
    }
    @Test fun typedMarkersIgnoreDirectoriesAndSymlinks() {
        val root=Files.createTempDirectory("texlab-marker-").toFile().canonicalFile
        try {
            val file=File(root,"nested/a.tex").apply {parentFile.mkdirs();writeText("inert")}
            File(file.parentFile,".texlabroot").mkdir()
            val outside=File(root,"target").apply {writeText("inert")}
            Files.createSymbolicLink(File(file.parentFile,".latexmkrc").toPath(),outside.toPath())
            assertEquals(root,LspServerProfiles.projectRoot(spec,root,file))
            File(file.parentFile,"Tectonic.toml").writeText("")
            assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun trustedNativeProfileKeepsUsefulFormattingAndExplicitAutomaticDefaults() {
        val caps=LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(false),caps["configuration"])
        assertEquals(JsonPrimitive(false),caps["didChangeConfiguration"]!!.jsonObject["dynamicRegistration"])
        val settings=LspServerProfiles.configurationAfterInitialize(spec,null)!!["settings"]!!.jsonObject["texlab"]!!.jsonObject
        assertEquals(JsonPrimitive("latexindent"),settings["latexFormatter"])
        assertEquals(JsonPrimitive("texlab"),settings["bibtexFormatter"])
        assertEquals(JsonPrimitive(false),settings["build"]!!.jsonObject["onSave"])
        assertEquals(JsonPrimitive(false),settings["chktex"]!!.jsonObject["onEdit"])
        assertEquals(JsonPrimitive(false),LspServerProfiles.initializationOptions(spec,File("."))["skipDistro"])
        val identity=LspServerProfiles.analysisConfiguration(spec)
        assertTrue(identity.contains("texlab-5.26.0"));assertTrue(identity.contains("latexindent"));assertTrue(identity.contains("runtimeLauncher"))
        assertTrue(LspServerProfiles.approvalExplanation(spec).contains("latexmkrc"))
    }
    @Test fun customRoutesRequireTheirOwnGrantWithoutBorrowingTheNativeProfile() {
        val custom=spec.copy(source="custom",commands=listOf(listOf("custom-texlab")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.tex"))
        assertFalse(LspServerProfiles.isTexlab(custom));assertFalse(LspServerProfiles.hasProjectRoots(custom))
        assertNull(LspServerProfiles.configurationAfterInitialize(custom,null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=emptySet(),extensions=setOf("bib")),"a.unknown"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=setOf("latex"),extensions=emptySet()),"a.unknown"))
    }
}
