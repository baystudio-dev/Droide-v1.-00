package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentToolsInputReviewTest {
    @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private class Terminal:ITerminalSession by AgentWorkflowTerminal(),InteractivePtyCommandSession {
        override val supportsInteractivePty=true
        val commands=java.util.Collections.synchronizedList(mutableListOf<String>())
        val inputs=java.util.Collections.synchronizedList(mutableListOf<String>())
        var closes=0
        val ready=CompletableDeferred<Unit>()
        override suspend fun execStreaming(cmd:String,timeoutMs:Long,onOutput:(String)->Unit):ExecResult {
            commands.add(cmd);return ExecResult(0,"ok",false)
        }
        override suspend fun execInteractivePty(command:String,timeoutMs:Long,input:ProcessInputBuffer?,onOutput:(String)->Unit)=execStreaming(command,timeoutMs,onOutput)
        override suspend fun execStreamingWithInput(cmd:String,timeoutMs:Long,input:ProcessInputBuffer,onOutput:(String)->Unit):ExecResult {
            input.bindPty({inputs.add(it.toString(Charsets.UTF_8));true},{closes++;true})
            ready.complete(Unit);awaitCancellation()
        }
    }
    private fun full(review:ApprovalInputReview)=buildString {
        var i=0
        while(true) {val p=review.readPage(i++);append(p.text);if(!p.hasNext)break}
    }
    private suspend fun pending(a:ApprovalManager,previous:Long=-1)=withTimeout(5000){a.pending.filterNotNull().first{it.id!=previous}}
    private fun fixture(block:suspend CoroutineScope.(File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("agent-input-review").toFile()
        try {withTimeout(20000){block(root)}} finally {root.deleteRecursively()}
    }
    @Test fun longOriginalCommandAndOptionsReachRealAskForRunBashExecAndInteractive()=fixture {root->
        for((name,tty) in listOf("run_command" to false,"bash" to false,"exec_command" to false,"exec_command" to true)) {
            val terminal=Terminal();val manager=BackgroundCommandManager(terminal)
            val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK)))
            val c=agentWorkflowContext(root,manager,permissions=perms,terminal=terminal)
            val command=": #"+"x".repeat(12000)+"\nprintf exact_suffix"
            try {
                val task=async {AgentTools.execute(name,buildJsonObject {put(if(name=="exec_command")"cmd" else "command",command);put("requires_tty",tty);put("timeout_ms",5000);put("yield_time_ms",0)},c)}
                val request=pending(c.approvals);val review=requireNotNull(request.inputReview)
                assertEquals(command,full(review));assertEquals(command,request.resource)
                assertEquals("",request.sessionGrantResource);assertFalse(request.canAllowForSession)
                assertTrue(review.optionsSummary.contains("Project: ${root.absolutePath}"))
                assertTrue(review.optionsSummary.contains("Interactive: $tty"));assertTrue(review.optionsSummary.contains("Timeout ms: 5000"))
                c.approvals.resolve(request.id,ApprovalResolution.ALLOW_ONCE)
                task.await();withTimeout(5000){while(terminal.commands.isEmpty())delay(1)}
                assertEquals(listOf(command),terminal.commands.toList())
                assertTrue(runCatching{full(review)}.isFailure)
            } finally {manager.close()}
        }
    }
    @Test fun containmentAsksShowOriginalNotHashedResourceOrWrapper()=fixture {root->
        val terminal=Terminal();val c=agentWorkflowContext(root,terminal=terminal,permissions=PermissionEngine(listOf(PermRule("*","*",PermEffect.ASK))))
        val command=": #"+"x".repeat(9000)+"\nprintf x > /outside/review-file\neval \"\$VALUE\""
        val task=async {AgentTools.execute("run_command",buildJsonObject{put("command",command)},c)}
        var previous=-1L
        for(action in listOf("external_directory","shell_escape","shell")) {
            val r=pending(c.approvals,previous);previous=r.id
            assertEquals(action,r.action);assertEquals(command,full(requireNotNull(r.inputReview)))
            if(action=="shell_escape")assertEquals(ShellContainmentPreflight.shellEscapeResource(command),r.resource)
            c.approvals.resolve(r.id,if(action=="shell")ApprovalResolution.DENY else ApprovalResolution.ALLOW_ONCE)
        }
        assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.commands.isEmpty())
    }
    @Test fun revokedOwnerCannotAllowSessionOrExecuteAndOldReviewExpires()=fixture {root->
        val terminal=Terminal();val c=agentWorkflowContext(root,terminal=terminal,provenance=AgentRequestProvenance.primary("review-session"),permissions=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK))))
        var current=true
        c.localRecoveryOwner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
        val owner=ApprovalOwnerAdmission(Any()){current}
        val task=async {c.approvals.withApprovalOwner(owner){AgentTools.execute("run_command",buildJsonObject{put("command","printf no")},c)}}
        val r=pending(c.approvals);val review=requireNotNull(r.inputReview)
        current=false
        c.approvals.resolve(r.id,ApprovalResolution.ALLOW_SESSION)
        assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.commands.isEmpty());assertTrue(runCatching{full(review)}.isFailure)
    }
    @Test fun stdinFullTextAndActualCloseInterruptFlagsMatchDispatch()=fixture {root->
        val terminal=Terminal();val manager=BackgroundCommandManager(terminal)
        try {
            val job=manager.start("fixture",keepStdinOpen=true);terminal.ready.await()
            val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK),PermRule("shell_escape","*",PermEffect.ASK)))
            val c=agentWorkflowContext(root,manager,permissions=perms,terminal=terminal)
            val chars="q".repeat(15000)+"EXACT_END\n"
            val task=async {AgentTools.execute("write_stdin",buildJsonObject{put("session_id",job.id);put("chars",chars);put("close_stdin",true);put("yield_time_ms",0)},c)}
            var previous=-1L
            for(action in listOf("shell_escape","shell")) {
                val r=pending(c.approvals,previous);previous=r.id
                val review=requireNotNull(r.inputReview)
                assertEquals(action,r.action);assertEquals(chars,full(review));assertEquals(ApprovalInputReview.Label.STDIN,review.label)
                assertTrue(review.optionsSummary.contains("Close stdin: true"));assertTrue(review.optionsSummary.contains("Interrupt: false"))
                assertTrue(r.resource.endsWith(":close=true:interrupt=false"));c.approvals.resolve(r.id,ApprovalResolution.ALLOW_ONCE)
            }
            task.await();assertEquals(listOf(chars),terminal.inputs.toList());assertEquals(1,terminal.closes)
            val stop=async {AgentTools.execute("write_stdin",buildJsonObject{put("session_id",job.id);put("interrupt",true);put("yield_time_ms",0)},c)}
            val r=pending(c.approvals,previous);val review=requireNotNull(r.inputReview)
            assertEquals("",full(review));assertTrue(review.optionsSummary.contains("Close stdin: false"));assertTrue(review.optionsSummary.contains("Interrupt: true"))
            c.approvals.resolve(r.id,ApprovalResolution.ALLOW_ONCE);stop.await()
            assertEquals(1,terminal.inputs.size);assertEquals(BackgroundCommandManager.State.KILLED,manager.snapshot(job.id)?.state)
        } finally {manager.close()}
    }
    @Test fun allowPathDoesNotConstructReviewOrNarrowSmallInput()=fixture {root->
        val terminal=Terminal();val c=agentWorkflowContext(root,terminal=terminal)
        // Malformed Unicode would fail review construction; ALLOW path remains old behavior.
        val command="printf 'small\uD800'"
        AgentTools.execute("run_command",buildJsonObject{put("command",command)},c)
        assertEquals(listOf(command),terminal.commands.toList());assertNull(c.approvals.pending.value)
    }
}
