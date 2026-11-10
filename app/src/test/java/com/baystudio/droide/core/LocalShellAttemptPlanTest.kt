package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

class LocalShellAttemptPlanTest {
    private val limits=CountedShellArgv.Limits(4096,2_097_152,8)
    private fun node(s:String)=CountedShellArgv.Node.literal(s)
    @Test fun realLegacyLocalAndProotRenderDifferential() {
        val field=LocalExecutionSubstrate::class.java.getDeclaredField("filesDir").apply{isAccessible=true}
        val old=field.get(null)
        val root=Files.createTempDirectory("shell-plan-lease").toFile()
        field.set(null,root)
        try {
            val lease=RemoteProcessLease.createLocal("test-plan")
            val commands=listOf("", "true", "printf '%s' 'a b'", "cat <<'EOF'\nx\nEOF\n", "# trailing comment\n", "x".repeat(32000))
            for(spec in listOf(TerminalLaunchSpec(shell="/bin/sh"),TerminalLaunchSpec(prefix=listOf("proot","-r","/guest root"),shell="/bin/sh"))) {
                for(cmd in commands) {
                    val target=spec.shellCommand(cmd,lease.environment).joinToString(" ",transform=LocalExecutionSubstrate::shellQuote)
                    val expected=listOf("/bin/sh","-c",lease.wrap(target))
                    val n=lease.countedLocalWrap(LocalShellAttemptPlan.joinTarget(LocalShellAttemptPlan.shellArgv(spec,cmd,lease.environment)))
                    LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),n),limits,cmd.length.toLong()).use {p->assertEquals(expected,p.render())}
                }
            }
        } finally {field.set(null,old);root.deleteRecursively()}
    }
    @Test fun inheritedEnvironmentAndLargeDashCThroughActualSupervisor() = runBlocking {
        val command="printf '%s' \"\$PATH\"; : #"+"a".repeat(40000)
        val root=Files.createTempDirectory("shell-large-env").toFile()
        try { LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node(command)),limits,command.length.toLong()).use {p->
            val r=LocalProcessSupervisor.capture(p.render(),root)
            assertEquals(0,r.exitCode);assertEquals(System.getenv("PATH"),r.output)
        } } finally {root.deleteRecursively()}
    }
    @Test fun budgetAndCancellationRelease() {
        val budget=LocalShellAttemptPlan.MemoryBudget(1_000_000)
        var cancelled=false
        val p=LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node("x".repeat(40000))),limits,40000,{check(!cancelled)},budget)
        assertTrue(budget.heldBytes()>0)
        try {
            try {LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node("x".repeat(40000))),limits,40000,budget=budget);fail("Budget overbooked")} catch(_:IllegalArgumentException){}
            cancelled=true
            try{p.render();fail("Cancelled render accepted")}catch(_:IllegalStateException){}
        } finally {p.close();p.close()}
        assertEquals(0,budget.heldBytes())
    }
    @Test fun unicodeAndNativeOverflowRejectBeforeRendering() {
        for(value in listOf("🙂".repeat(33000),"'".repeat(140000))) {
            try {LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node(value)),limits,value.length.toLong());fail("Invalid expansion")}catch(_:IllegalArgumentException){}
        }
        val p=LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node("x".repeat(40000))),limits,40000)
        p.use {
            try{it.revalidate(null);fail("Unknown refreshed limits")}catch(_:IllegalStateException){}
            try{it.revalidate(CountedShellArgv.Limits(4096,10,8));fail("Changed limits")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun reservationReleasedOnProvenNoStartAndUnknownStartWithoutRetry() = runBlocking {
        val root=Files.createTempDirectory("shell-attempt-start").toFile()
        val budget=LocalShellAttemptPlan.MemoryBudget()
        try {
            val invalid=root.resolve("invalid-interpreter").apply {writeText("#!/definitely/no/interpreter\n");setExecutable(true)}
            for(executable in listOf(root.resolve("missing"),invalid)) {
                var starts=0
                val plan=LocalShellAttemptPlan.prepare(listOf(node(executable.path),node("x".repeat(40000))),limits,40000,budget=budget)
                val failure=runCatching {plan.use {starts++;LocalProcessSupervisor.capture(it.render(),root)}}.exceptionOrNull()
                assertTrue(failure is java.io.IOException)
                assertEquals(1,starts);assertEquals(0,budget.heldBytes())
                assertEquals(executable==invalid,WorkspaceProcessWriteAdmission.observe(root).unknownFactoryRoots.isNotEmpty())
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun containmentStillInspectsTailBeyondOld32kBoundary() {
        val root=Files.createTempDirectory("shell-long-containment").toFile()
        try {
            val prefix=": #"+"a".repeat(40000)+"\n"
            val result=ShellContainmentPreflight.analyze(prefix+"printf x > /outside/target",root,root)
            assertTrue(result.externalDirectories.isNotEmpty())
            assertTrue(ShellContainmentPreflight.analyze(prefix+"eval \"\$DYNAMIC\"",root,root).requiresShellEscape)
        } finally {root.deleteRecursively()}
    }
    @Test fun actualSupervisorCancellationDoesNotRetry() = runBlocking {
        var cleanup=0
        val budget=LocalShellAttemptPlan.MemoryBudget()
        val command="exec sleep 20 #"+"a".repeat(40000)
        val p=LocalShellAttemptPlan.prepare(listOf(node("/bin/sh"),node("-c"),node(command)),limits,command.length.toLong(),budget=budget)
        val root=Files.createTempDirectory("shell-large-cancel").toFile()
        try {
            val job=launch {
                p.use { LocalProcessSupervisor.capture(it.render(),root,cleanup={cleanup++}) }
            }
            delay(200);withTimeout(5000){job.cancelAndJoin()}
            assertEquals(1,cleanup);assertEquals(0,budget.heldBytes())
        } finally {p.close();root.deleteRecursively()}
    }
}
