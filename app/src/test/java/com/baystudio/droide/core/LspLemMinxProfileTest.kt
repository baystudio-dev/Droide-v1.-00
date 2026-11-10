package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspLemMinxProfileTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "lemminx" }
    @Test fun originalCaseAliasesKeepOneNonRunnableEditorFamilyAndTwoProtocolIds() {
        for (ext in LspLemMinxProfile.extensions) {
            assertEquals(listOf(spec), LanguageServerRegistry.forPath("nested/a.$ext"))
            assertEquals(if(ext=="dtd") "dtd" else "xml", LanguageServerRegistry.protocolLanguageId("a.$ext"))
            assertEquals("xml", LanguageRegistry.forFile("a.$ext")?.id)
            assertNull(LanguageRegistry.forFile("a.$ext")?.runner)
        }
        for (p in listOf("a.XML","a.DTD","a.Xml","a.ent","a.mod","a.rnc")) assertFalse(p, spec in LanguageServerRegistry.forPath(p))
        assertEquals(listOf("lemminx"), spec.commands.single()); assertEquals(LanguageRegistry.all.size, LanguageRegistry.all.map { it.id }.distinct().size)
        assertEquals(LanguageServerRegistry.builtIns.size, LanguageServerRegistry.builtIns.map { it.id }.distinct().size)
    }
    @Test fun rootsUseOnlyRealVcsDirectoriesAndPreserveMissingIdentity() {
        val root=Files.createTempDirectory("xml-roots-").toFile().canonicalFile
        try {
            val file=File(root,"module/nested/a.xml").apply {parentFile.mkdirs()}
            File(root,"module/.git").mkdir()
            assertEquals(File(root,"module"),LspServerProfiles.projectRoot(spec,root,file))
            File(file.parentFile,".git").writeText("not a directory")
            File(file.parentFile,"pom.xml").writeText("inert")
            File(file.parentFile,"catalog.xml").writeText("inert")
            assertEquals(File(root,"module"),LspServerProfiles.projectRoot(spec,root,file))
            Files.createSymbolicLink(File(file.parentFile,".hg").toPath(),File(root,"module/.git").toPath())
            assertEquals(File(root,"module"),LspServerProfiles.projectRoot(spec,root,file))
            val missing=File(root,"absent/a.xml")
            assertEquals(missing.parentFile,LspServerProfiles.projectRoot(spec,root,missing))
            assertFalse(LspServerProfiles.projectAvailable(spec,missing.parentFile))
            assertTrue(runCatching {LspServerProfiles.projectRoot(spec,root,File(root.parentFile,"outside.xml"))}.isFailure)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun fullSettingsAndCapabilitiesUseOneIndentationPolicy() {
        val init=LspServerProfiles.initializationOptions(spec,File("."))
        val xml=init["settings"]!!.jsonObject["xml"]!!.jsonObject
        assertEquals(JsonPrimitive(true),xml["downloadExternalResources"]!!.jsonObject["enabled"])
        assertEquals(JsonPrimitive(false),xml["validation"]!!.jsonObject["resolveExternalEntities"])
        assertEquals(JsonPrimitive(false),xml["validation"]!!.jsonObject["xInclude"]!!.jsonObject["enabled"])
        assertEquals(JsonPrimitive(2),xml["format"]!!.jsonObject["tabSize"])
        assertEquals(init["settings"],LspServerProfiles.configurationAfterInitialize(spec,null)!!["settings"])
        assertTrue(init["extendedClientCapabilities"]!!.jsonObject.values.all {it==JsonPrimitive(false)})
        val caps=LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(true),caps["configuration"])
        assertEquals(JsonPrimitive(false),caps["applyEdit"])
        assertEquals(JsonPrimitive(false),caps["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
    }
    private fun query(vararg pairs:Pair<String?,String?>)=buildJsonObject {put("items",buildJsonArray {
        pairs.forEach {(section,scope)->add(buildJsonObject {section?.let{put("section",it)};scope?.let{put("scopeUri",it)}})}
    })}
    @Test fun scalarReplyPreservesOrderDuplicatesAndUnknownNulls() {
        val uri="file:///workspace/a.xml"
        val result=LspLemMinxProfile.configurationReply(query("xml.format.tabSize" to uri,"xml.format.insertSpaces" to uri,"xml.format.tabSize" to uri,"xml" to uri,"xml.format.tabSize" to null)) {it==uri}
        assertEquals(Json.parseToJsonElement("[2,true,2,null,null]"),result)
    }
    @Test fun malformedBatchFailsWholeAndEmptyBatchStaysEmpty() {
        for (params in listOf(JsonNull,buildJsonObject {},Json.parseToJsonElement("{\"items\":[3]}"),Json.parseToJsonElement("{\"items\":[{\"section\":false}]}"),buildJsonObject {put("items",JsonArray(List(65){buildJsonObject{}}))}))
            assertTrue(runCatching {LspLemMinxProfile.configurationReply(params){true}}.isFailure)
        assertEquals(JsonArray(emptyList()),LspLemMinxProfile.configurationReply(query()){error("no ownership work")})
    }
    @Test fun uriEnvelopeRefusesAuthorityQueryFragmentOpaqueAndOversizeBeforeMapping() {
        for(uri in listOf("file://foreign/workspace/a.xml","file:///workspace/a.xml?q=1","file:///workspace/a.xml#part","https://example/a.xml","file:relative","not a URI","file:///"+"x".repeat(8193))) {
            assertEquals(Json.parseToJsonElement("[null]"),LspLemMinxProfile.configurationReply(query("xml.format.tabSize" to uri)){error("must not map")})
        }
    }
    @Test fun customRoutesCannotInheritProfileOrGrantButStillNeedConsent() {
        val custom=spec.copy(source="custom",commands=listOf(listOf("custom-xml")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.xml"))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom.copy(id="other",languageIds=setOf("dtd"),extensions=emptySet()),"a.unknown"))
        assertFalse(LspServerProfiles.hasProjectRoots(custom));assertFalse(LspServerProfiles.refreshAfterPersistedMutation(custom))
        assertTrue(LspServerProfiles.initializationOptions(custom,File(".")).isEmpty())
        assertNull(LspServerProfiles.configurationAfterInitialize(custom,null))
        assertNotEquals(LspServerProfiles.analysisConfiguration(spec),LspServerProfiles.analysisConfiguration(custom))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
    }
    @Test fun identityAndCopyDiscloseNetworkOutOfRootAndNonAttestedManagedState() {
        val identity=LspServerProfiles.analysisConfiguration(spec)
        for(word in listOf("lemminx-0.31.2","launcher","rootPolicy","downloadExternalResources")) assertTrue(word,identity.contains(word))
        assertFalse(identity.contains("workspaceMutationEpoch"))
        for(word in listOf("outside","remote","URLs","not sandboxed","next supported request")) assertTrue(word,LspServerProfiles.approvalExplanation(spec).contains(word))
        assertEquals(setOf("node","java"),ManagedLanguageServerPlan.forServer("lemminx")!!.requiredCommands)
    }
}
