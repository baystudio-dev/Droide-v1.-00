package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Continuous real tool/approval/session/supervisor path, with explicit generated-program HOST
 * layout substitution. Not an Android, PRoot, native PTY or device qualification test. */
class TerminalSessionApprovalHostLayoutTest {
    @get:org.junit.Rule val admission:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private val limits=CountedShellArgv.Limits(4096,2_097_152,8)
    private fun fixture(block:suspend CoroutineScope.(File,TerminalSession,TerminalHostLayoutTestAuthority)->Unit,
                        query:()->CountedShellArgv.Limits?={limits})=runBlocking {
        val root=Files.createTempDirectory("terminal-host-layout").toFile()
        val field=LocalExecutionSubstrate::class.java.getDeclaredField("filesDir").apply{isAccessible=true}
        val previous=field.get(null);field.set(null,root)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val host=TerminalHostLayoutTestAuthority(query)
        val terminal=TerminalSession(root,scope,TerminalLaunchSpec(shell="/bin/sh"),host)
        try {withTimeout(20000){block(root,terminal,host)}} finally {
            terminal.destroy();scope.cancel();field.set(null,previous);root.deleteRecursively()
        }
    }
    private fun context(root:File,terminal:TerminalSession)=agentWorkflowContext(root,terminal=terminal,
        provenance=AgentRequestProvenance.primary("host-approval"),permissions=PermissionEngine(listOf(
            PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK))))
    private suspend fun pending(c:AgentTools.Ctx)=withTimeout(5000){c.approvals.pending.filterNotNull().first()}
    private fun full(r:ApprovalInputReview)=buildString {var i=0;while(true){val p=r.readPage(i++);append(p.text);if(!p.hasNext)break}}
    private fun command()=": #"+"a".repeat(40000)+"\nprintf 'EXACT_SUFFIX /system/bin/sh /system/bin/toybox setsid' > proof.txt; cat proof.txt"

    @Test fun fullAskToExpandedActualSessionPreservesExactUserSuffixAndOneCreation()=fixture({root,terminal,host->
        val c=context(root,terminal);val original=command()
        val task=async{AgentTools.execute("run_command",buildJsonObject{put("command",original)},c)}
        val req=pending(c);assertEquals(original,full(requireNotNull(req.inputReview)))
        assertEquals(original,req.resource);assertFalse(root.resolve("proof.txt").exists())
        c.approvals.resolve(req.id,ApprovalResolution.ALLOW_ONCE)
        val output=task.await();assertTrue(output,output.contains("exit=0"));assertTrue(output,output.contains("EXACT_SUFFIX"))
        assertEquals("EXACT_SUFFIX /system/bin/sh /system/bin/toybox setsid",root.resolve("proof.txt").readText())
        assertEquals(1,host.nativeCreationEntries)
    })
    @Test fun denyAndExpiredOwnerNeverReachNativeCreation()=fixture({root,terminal,host->
        val c=context(root,terminal)
        val denied=async{AgentTools.execute("run_command",buildJsonObject{put("command",command())},c)}
        val request=pending(c);c.approvals.resolve(request.id,ApprovalResolution.DENY)
        assertTrue(denied.await().startsWith("DENIED"));assertEquals(0,host.nativeCreationEntries)
        var current=true
        c.localRecoveryOwner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
        val owner=ApprovalOwnerAdmission(Any()){current}
        val stale=async{c.approvals.withApprovalOwner(owner){AgentTools.execute("run_command",buildJsonObject{put("command",command())},c)}}
        val second=pending(c);current=false;c.approvals.resolve(second.id,ApprovalResolution.ALLOW_ONCE)
        assertTrue(stale.await().startsWith("DENIED"));assertEquals(0,host.nativeCreationEntries)
        assertFalse(root.resolve("proof.txt").exists())
    })
    @Test fun cancellationDuringLimitRefreshPreventsCreateAfterApproval() {
        lateinit var task:Deferred<String>
        var queries=0
        fixture({root,terminal,host->
            val c=context(root,terminal)
            task=async(start=CoroutineStart.LAZY){AgentTools.execute("run_command",buildJsonObject{put("command",command())},c)}
            task.start();val req=pending(c);c.approvals.resolve(req.id,ApprovalResolution.ALLOW_ONCE)
            try {task.await();fail("Cancellation swallowed")}catch(_:CancellationException){}
            assertEquals(0,host.nativeCreationEntries);assertFalse(root.resolve("proof.txt").exists())
        },query={queries++;if(queries==2)task.cancel();limits})
    }
    @Test fun smallUnicodeUsesUnexpandedRouteAndGeneratedCleanupDefaultRemainsExact()=fixture({root,terminal,host->
        val c=context(root,terminal);val original="printf 'small 日本語'"
        val task=async{AgentTools.execute("run_command",buildJsonObject{put("command",original)},c)}
        val req=pending(c);assertEquals(original,full(requireNotNull(req.inputReview)))
        c.approvals.resolve(req.id,ApprovalResolution.ALLOW_ONCE)
        val result=task.await();assertTrue(result,result.contains("small 日本語"));assertEquals(1,host.nativeCreationEntries)
        val lease=RemoteProcessLease.createLocal("parity")
        val before=lease.terminateCommand()
        val simulated=host.cleanupScript(lease)
        assertEquals(before,simulated.replace("/usr/bin/tr ","/system/bin/toybox tr ").replace("/usr/bin/grep ","/system/bin/toybox grep "))
        assertEquals(before,lease.terminateCommand())
        val target="printf '/system/bin/sh untouched'"
        val expected=lease.wrap(target)
        // Only generated executable occurrences differ; quoted user payload remains exact.
        val hostRendered=lease.wrapForHostTest(target,host)
        assertEquals(expected,hostRendered
            .replace("exec ${host.setsid} ${host.shell} -c ","exec /system/bin/toybox setsid /system/bin/sh -c ")
            .replace("exec ${host.shell} -c ","exec /system/bin/sh -c "))
        assertTrue(expected.contains("exec /system/bin/toybox setsid /system/bin/sh -c "))
        assertEquals(expected,lease.wrap(target))
    },query={error("Small path must not query expanded native limits")})
}
