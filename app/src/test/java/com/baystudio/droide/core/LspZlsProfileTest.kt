package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspZlsProfileTest {
    private val spec=LanguageServerRegistry.builtIns.single {it.id=="zls"}
    @Test fun exactCaseRoutingAndZonDataNeverInheritsRunAction() {
        for(path in listOf("a.zig","nested/build.zig.zon")) {
            assertEquals(spec,LanguageServerRegistry.forPath(path).single())
            assertEquals("zig",LanguageServerRegistry.protocolLanguageId(path))
        }
        for(path in listOf("a.ZIG","a.ZON","a.Zig",".zigversion","zls.build.json"))
            assertFalse(LanguageServerRegistry.forPath(path).contains(spec))
        assertEquals("zig",LanguageRegistry.forFile("build.zig.zon")!!.id)
        assertNull(LanguageRegistry.runPlanFor("build.zig.zon"));assertNull(LanguageRegistry.runPlanFor("build.zig.ZON"))
        assertNotNull(LanguageRegistry.runPlanFor("a.zig"));assertEquals(55,LanguageRegistry.all.size)
        assertEquals(listOf("zls"),spec.commands.single())
    }
    @Test fun nearestExactPinOverridesAValidAncestorPin() = root { root ->
        File(root,".zigversion").writeText("0.16.0\n")
        val file=File(root,"nested/a.zig").apply {parentFile.mkdirs()}
        File(file.parentFile,".zigversion").writeText(" 0.16.1\n")
        val project=LspServerProfiles.projectRoot(spec,root,file)
        assertEquals(file.parentFile,project)
        assertEquals("0.16.1",LspZlsProfile.hint(root,project).version)
        assertTrue(LspZlsProfile.hint(root,project).usable)
    }
    @Test fun invalidDescendantPinIsABarrierRatherThanSkippedRoot() = root { root ->
        File(root,"build.zig").writeText("inert")
        val file=File(root,"nested/a.zig").apply {parentFile.mkdirs()}
        val pin=File(file.parentFile,".zigversion");pin.mkdir()
        val project=LspServerProfiles.projectRoot(spec,root,file)
        assertEquals(file.parentFile,project);assertFalse(LspZlsProfile.hint(root,project).usable)
        pin.delete();Files.createSymbolicLink(pin.toPath(),File(root,"missing").toPath())
        assertEquals(file.parentFile,LspServerProfiles.projectRoot(spec,root,file))
        assertFalse(LspZlsProfile.hint(root,project).usable)
    }
    @Test fun pinsAreBoundedStrictDataNeverExecutable() = root { root ->
        val pin=File(root,".zigversion")
        for(value in listOf("", "0.16.0-dev.1", "0.16.0+build", "0.15.2", "00.16.0", "0.16.0\n0.16.1", "$(touch nope)", "x".repeat(129))) {
            pin.writeText(value);assertFalse(value,LspZlsProfile.hint(root,root).usable)
        }
        pin.writeBytes(byteArrayOf(0xc3.toByte(),0x28));assertFalse(LspZlsProfile.hint(root,root).usable)
        pin.writeText("0.16.0\n");assertTrue(LspZlsProfile.hint(root,root).usable)
    }
    @Test fun configurationIdentityBindsMeaningfulPinAndStableLauncherButNotEpoch() = root { root ->
        val absent=LspServerProfiles.analysisConfiguration(spec,root,root)
        File(root,".zigversion").writeText("0.16.0\n")
        val pinned=LspServerProfiles.analysisConfiguration(spec,root,root)
        assertNotEquals(absent,pinned)
        File(root,".zigversion").writeText(" 0.16.0 ")
        assertEquals(pinned,LspServerProfiles.analysisConfiguration(spec,root,root))
        assertTrue(pinned.contains("launcher"));assertTrue(pinned.contains("0.16.0"));assertFalse(pinned.contains("Epoch"))
        assertTrue(LspServerProfiles.refreshAfterPersistedMutation(spec))
    }
    @Test fun staticInitializeRunsFullTrustedCheckOnlyProfileWithoutPathOverrides() {
        val options=LspServerProfiles.initializationOptions(spec,File("."))
        assertEquals(setOf("enable_build_on_save","build_on_save_args","force_autofix"),options.keys)
        assertEquals(JsonPrimitive(true),options["enable_build_on_save"])
        assertEquals(buildJsonArray {add("--check-only")},options["build_on_save_args"])
        assertEquals(JsonPrimitive(false),LspServerProfiles.clientCapabilities(spec)["workspace"]!!.jsonObject["configuration"])
        assertNull(LspServerProfiles.configurationAfterInitialize(spec,null))
        assertEquals(setOf("node","zig"),ManagedLanguageServerPlan.forServer("zls")!!.requiredCommands)
    }
    @Test fun markerlessMissingRootsKeepIdentityAndCustomRoutesGetNoBuiltinProfile() = root { root ->
        val absent=File(root,"missing/a.zig")
        assertEquals(absent.parentFile,LspServerProfiles.projectRoot(spec,root,absent))
        assertFalse(LspServerProfiles.projectAvailable(spec,absent.parentFile))
        val custom=spec.copy(source="custom",commands=listOf(listOf("other-zls")))
        assertTrue(LspServerProfiles.requiresProjectAnalysis(custom,"a.zig"))
        assertFalse(LspServerProfiles.hasProjectRoots(custom));assertFalse(LspServerProfiles.refreshAfterPersistedMutation(custom))
        assertFalse(LspServerProfiles.analysisConfiguration(custom,root,root).contains("launcher"))
    }
    private fun root(block:(File)->Unit) {val root=Files.createTempDirectory("zls-profile-").toFile().canonicalFile
        try {block(root)} finally {PathSecurity.deleteTreeNoFollow(root)}}
}
