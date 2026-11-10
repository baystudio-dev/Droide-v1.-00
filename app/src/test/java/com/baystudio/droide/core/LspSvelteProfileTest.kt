package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspSvelteProfileTest {
    @Test fun exactRouteDoesNotTakeOrdinaryTypeScriptOrUppercaseFiles() {
        val spec=LanguageServerRegistry.forPath("App.svelte").single()
        assertEquals(listOf(listOf("svelteserver","--stdio")),spec.commands)
        assertEquals("svelte",LanguageServerRegistry.protocolLanguageId("App.svelte"))
        assertTrue(LanguageServerRegistry.forPath("App.SVELTE").isEmpty())
        assertEquals("typescript",LanguageServerRegistry.forPath("dep.ts").first().id)
        assertEquals(LanguageServerRegistry.builtIns.size, LanguageServerRegistry.builtIns.map { it.id }.distinct().size);assertEquals(LanguageRegistry.all.size, LanguageRegistry.all.map { it.id }.distinct().size)
    }
    @Test fun customOverridesHaveConsentButNoReviewedProfileOrBackgroundAdapter() {
        val custom=LanguageServerRegistry.forPath("App.svelte").single().copy(source="custom",commands=listOf(listOf("other","--stdio")))
        assertFalse(LspServerProfiles.isSvelte(custom));assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"App.svelte"))
        assertNull(LspBackgroundPreparationPolicy.forSpec(custom))
        assertTrue(LspServerProfiles.optionalToolNote(custom)?.contains("generic") == true)
    }
    @Test fun rootMarkersAreNearestTypedAndInvalidPresentMarkersAreBarriers()=workspace{r->
        File(r,".git").mkdir();val nested=File(r,"nested").apply{mkdir()};val doc=File(nested,"App.svelte").apply{writeText("")}
        assertEquals(r,LspSvelteProfile.projectRoot(r,doc))
        File(nested,"svelte.config.ts").writeText("// inert")
        assertEquals(nested,LspSvelteProfile.projectRoot(r,doc));assertTrue(LspSvelteProfile.projectAvailable(nested))
        File(nested,"svelte.config.ts").delete();File(nested,"svelte.config.ts").mkdir()
        assertEquals(nested,LspSvelteProfile.projectRoot(r,doc));assertFalse(LspSvelteProfile.projectAvailable(nested))
    }
    @Test fun missingDocumentParentNeverBorrowsApprovedAncestor()=workspace{r->
        File(r,".git").mkdir();val doc=File(r,"missing/App.svelte")
        assertEquals(doc.parentFile,LspSvelteProfile.projectRoot(r,doc));assertFalse(LspSvelteProfile.projectAvailable(doc.parentFile))
        doc.parentFile.mkdirs();assertEquals(r,LspSvelteProfile.projectRoot(r,doc))
    }
    @Test fun companionPolicyExcludesNativeDiskOverwriteAndUnsupportedSuffixes() {
        for(p in listOf("src/a.ts","src/a.js"))assertTrue(p,LspSvelteProfile.supportedCompanion(p))
        for(p in listOf("a.d.ts","a.TS","a.tsx","a.jsx","a.mts","a.cts","node_modules/a.ts","some-node_modules-copy/a.js","dist/a.ts",".svelte-kit/a.ts"))
            assertFalse(p,LspSvelteProfile.supportedCompanion(p))
    }
    @Test fun capabilitiesTruthfullyRequireObserverAndDeclineCommandApplication() {
        val spec=LanguageServerRegistry.forPath("App.svelte").single();val caps=LspServerProfiles.clientCapabilities(spec)
        val workspace=caps["workspace"]!!.jsonObject
        assertEquals(JsonPrimitive(false),workspace["configuration"]);assertEquals(JsonPrimitive(false),workspace["applyEdit"])
        assertEquals(JsonPrimitive(true),workspace["didChangeWatchedFiles"]!!.jsonObject["dynamicRegistration"])
        assertEquals(JsonPrimitive(false),workspace["didChangeWatchedFiles"]!!.jsonObject["relativePatternSupport"])
        assertEquals(30_000L,LspBackgroundPreparationPolicy.forSpec(spec)!!.initializeMs)
        assertEquals(90_000L,LspBackgroundPreparationPolicy.forSpec(spec)!!.attemptMs)
    }
    @Test fun primerEmptyArrayIsSuccessButNullAndMalformedLocationsAreNot() {
        val c=LspSvelteCompanionAuthority.Capture("a.ts","file:///workspace/a.ts","",Any())
        val ledger=LspSvelteCompanionAuthority();ledger.acceptPrimer(c,JsonArray(emptyList()));ledger.recordWritten(c);assertTrue(ledger.current(c))
        for(json in listOf("null","{}","[{}]", """[{"uri":"","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]""",
            """[{"uri":"relative.ts","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]""",
            """[{"uri":"file:///a.ts","range":{"start":{"line":1,"character":0},"end":{"line":0,"character":1}}}]"""))
            assertTrue(json,runCatching{LspSvelteCompanionAuthority().acceptPrimer(c,Json.parseToJsonElement(json))}.isFailure)
    }
    @Test fun companionCustomChangeHasOneEmptyFullTextAndNoInventedVersion() {
        val capture=LspSvelteCompanionAuthority.Capture("a.js","file:///workspace/a.js","",Any())
        assertEquals(Json.parseToJsonElement("""{"uri":"file:///workspace/a.js","changes":[{"text":""}]}"""),LspSvelteCompanionAuthority.change(capture))
    }
    @Test fun closeRetiresPrimerAndWrittenOwnership() {
        val c=LspSvelteCompanionAuthority.Capture("a.ts","file:///workspace/a.ts","x",Any());val ledger=LspSvelteCompanionAuthority()
        ledger.acceptPrimer(c,JsonArray(emptyList()));ledger.recordWritten(c);ledger.close()
        assertFalse(ledger.current(c));assertFalse(ledger.owns("a.ts"));assertTrue(runCatching{ledger.recordWritten(c)}.isFailure)
    }
    private fun workspace(block:(File)->Unit) {val r=Files.createTempDirectory("svelte-profile-").toFile().canonicalFile;try{block(r)}finally{PathSecurity.deleteTreeNoFollow(r)}}
}
