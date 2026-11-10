package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentRequestInstructionsTest {
    @Test fun addedChangedAndDeletedRootRulesAreFreshOnEveryRequest() = runBlocking {
        val root=Files.createTempDirectory("droide-request-rules-").toFile()
        try {
            val files=FileRepository(root)
            val hook="\n\n[Lifecycle hook context]\nHook output captured once"
            var checks=0
            suspend fun request()=AgentRequestInstructions.refresh(hook,{checks++}) { AgentProjectInstructions.load(files,root) }
            val empty=request();assertFalse(empty.contains("Project instructions"));assertTrue(empty.endsWith(hook))
            root.resolve("AGENTS.md").writeText("Original root rule")
            assertTrue(request().contains("Original root rule"))
            root.resolve("AGENTS.md").writeText("Changed root rule")
            val changed=request();assertTrue(changed.contains("Changed root rule"));assertFalse(changed.contains("Original root rule"))
            assertTrue(root.resolve("AGENTS.md").delete())
            val deleted=request();assertFalse(deleted.contains("Changed root rule"));assertFalse(deleted.contains("Project instructions"))
            root.resolve(".droide/AGENTS.md").apply {parentFile.mkdirs();writeText("New Droide rules")}
            val added=request();assertTrue(added.contains("New Droide rules"));assertEquals(1,Regex("Lifecycle hook context").findAll(added).count())
            assertEquals(10,checks)
        } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun hookContextIsNotReloadedOrAccumulatedAcrossRetries()=runBlocking {
        var loads=0
        val hook="\nHOOK_ONCE\n"
        val results=(1..3).map { AgentRequestInstructions.refresh(hook,{}) {"rules-${++loads}"} }
        assertEquals(listOf("rules-1$hook","rules-2$hook","rules-3$hook"),results)
        assertEquals(3,loads)
    }
    @Test fun ownerIsCheckedBeforeAndAfterSuspendingLoad()=runBlocking {
        val trace=mutableListOf<String>()
        assertEquals("rules",AgentRequestInstructions.refresh("",{trace+="owner"}) {trace+="load";"rules"})
        assertEquals(listOf("owner","load","owner"),trace)
        var calls=0;var returned=false
        try {
            AgentRequestInstructions.refresh("HOOK",{if(++calls==2)throw CancellationException("owner changed")}) {"stale rules"}
            returned=true
        } catch (expected:CancellationException) {assertEquals("owner changed",expected.message)}
        assertFalse(returned);assertEquals(2,calls)
    }
    @Test fun rejectedOwnerPreventsAnyReadAndReadFailureNeverReturnsHookOnly()=runBlocking {
        var reads=0
        try {AgentRequestInstructions.refresh("HOOK",{throw CancellationException("stopped")}) {reads++;"rules"};fail("Must reject")} catch(expected:CancellationException) { }
        assertEquals(0,reads)
        try {AgentRequestInstructions.refresh("HOOK",{}) {throw java.io.IOException("read failed")};fail("Must propagate")} catch(expected:java.io.IOException) {assertEquals("read failed",expected.message)}
    }
}
