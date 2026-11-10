package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake kernel lease tests verify wiring/ownership only. Real seals are tested separately on host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ImmutableHookTransportTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout=20000) fun completeLargeInputIsDeliveredAndReceiptKeepsBlockingDecision() = env { e ->
        val args=buildJsonObject { put("path","a.txt");put("content","quote \" slash \\ 🌏\n".repeat(130000)) }.toString()
        e.terminal.reply=ExecResult(2,"{\"decision\":\"block\",\"reason\":\"reviewed full input\"}",false)
        val result=e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input(args)))
        assertTrue(result.blocked);assertEquals(e.terminal.reply.output,result.reason)
        assertEquals(args,Json.parseToJsonElement(e.factory.bytes.toString("UTF-8")).jsonObject.getValue("toolInput").jsonPrimitive.content)
        assertTrue(e.factory.lease!!.closed);assertEquals(1,e.terminal.calls)
        assertFalse(e.root.resolve(".droide/.runtime-hooks").exists())
        assertTrue(e.terminal.command.contains("/proc/self/fd/9"))
    }

    @Test(timeout=10000) fun missingCapabilityReceiptFailsClosedAndClosesLease() = env { e ->
        e.terminal.receipt=false
        val error=runCatching { e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input("{}"))) }.exceptionOrNull()
        assertTrue(error is SourceBackedHookTransportUnavailableException);assertTrue(e.factory.lease!!.closed)
    }

    @Test(timeout=10000) fun cancellationAtIoReturnClosesLeaseBeforeAnyProcess() = env { e -> coroutineScope {
        lateinit var work:Deferred<HookDispatchResult>
        e.factory.afterWrite={work.cancel(CancellationException("IO-return cancellation"))}
        work=async(start=CoroutineStart.LAZY) { e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input("{}"))) }
        work.start();assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
        assertTrue(e.factory.lease!!.closed);assertEquals(0,e.terminal.calls)
    } }

    @Test(timeout=10000) fun hookPermissionDenialDoesNotRunProcessAndReleasesLease() = env(ask=true) { e -> coroutineScope {
        val work=async { e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input("{}"))) }
        val approval=checkNotNull(e.approvals.pending.first { it!=null })
        assertTrue(approval.detail.contains("/proc/self/fd/9"));assertFalse(approval.detail.contains("/proc/1234"))
        e.approvals.resolve(approval.id,ApprovalResolution.DENY);work.await()
        assertEquals(0,e.terminal.calls);assertTrue(e.factory.lease!!.closed)
    } }

    @Test(timeout=10000) fun ownerRevokedDuringPermissionCannotDispatch() = env(ask=true) { e -> coroutineScope {
        val work=async { e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input("{}")),isStillCurrent=e.current::get) }
        val approval=checkNotNull(e.approvals.pending.first { it!=null });e.current.set(false)
        e.approvals.resolve(approval.id,ApprovalResolution.ALLOW_ONCE)
        val failure=runCatching { work.await() }.exceptionOrNull()
        assertTrue(failure == null || failure is CancellationException) // Approval may deny stale owners before dispatch.
        assertEquals(0,e.terminal.calls);assertTrue(e.factory.lease!!.closed)
    } }

    @Test(timeout=10000) fun cancellationDuringProcessKeepsLeaseUntilProcessUnwinds() = env { e -> coroutineScope {
        val started=CompletableDeferred<Unit>();val unwound=AtomicBoolean(false)
        e.terminal.block={try { started.complete(Unit);awaitCancellation() } finally { assertFalse(e.factory.lease!!.closed);unwound.set(true) }}
        val work=async { e.manager.dispatch(HookLifecycleEvent.PRE_TOOL_USE,HookLifecycleContext(toolName="write_file",streamedToolInput=e.input("{}"))) }
        started.await();work.cancel();assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
        assertTrue(unwound.get());assertTrue(e.factory.lease!!.closed)
    } }

    @Test @Config(sdk=[29]) fun api29FailsBeforeAllocationOrSyscall() {
        var touched=false
        assertTrue(runCatching { AndroidSealedHookEvent.create({touched=true},{touched=true},{}) }.exceptionOrNull() is SourceBackedHookTransportUnavailableException)
        assertFalse(touched)
    }

    @Test fun memoryAdmissionIsSharedBoundedAndReleased() {
        val available=HookKernelMemory.availableBytes;val maximum=HookKernelMemory.maximumProcessShare
        HookKernelMemory.availableBytes={1024L*1024*1024};HookKernelMemory.maximumProcessShare={4L*1024*1024}
        val a=HookKernelMemory.reserve();val b=HookKernelMemory.reserve()
        try {
            a.grow(3L*1024*1024)
            assertTrue(runCatching { b.grow(2L*1024*1024) }.exceptionOrNull() is SourceBackedHookTransportUnavailableException)
            a.close();b.grow(2L*1024*1024)
            HookKernelMemory.availableBytes={16L*1024*1024}
            assertTrue(runCatching { b.grow(3L*1024*1024) }.exceptionOrNull() is SourceBackedHookTransportUnavailableException)
        } finally {a.close();b.close();HookKernelMemory.availableBytes=available;HookKernelMemory.maximumProcessShare=maximum}
    }

    private fun env(ask:Boolean=false,body:suspend(Env)->Unit) {
        val e=Env(ask)
        try {runBlocking {withTimeout(15000) {body(e)}}} finally {e.approvals.resolve(ApprovalResolution.DENY);PathSecurity.deleteTreeNoFollow(e.root)}
    }
    private class Env(ask:Boolean) {
        val root=Files.createTempDirectory("immutable-hook-").toFile()
        val current=AtomicBoolean(true);val approvals=ApprovalManager();val factory=FakeFactory();val terminal=FakeTerminal()
        val manager=HookManager(root,terminal,PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))+
            if(ask)listOf(PermRule("hook","*",PermEffect.ASK))else emptyList()),approvals)
        init {
            root.resolve(".droide").mkdirs();root.resolve(".droide/hooks.json").writeText("""{"enabled":true,"events":{"PreToolUse":[{"command":"fixture-hook {event_file}"}]}}""")
            manager.immutableEventFactory=factory
        }
        fun input(text:String):HookToolInputSource {
            val store=OwnedResponseContentStore(root.resolve("owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32)
            val session=store.authorize(OwnedResponseContentStore.Owner("session","run","call"))
            val completed=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {it.append(text);it.sealCompleted { stream -> StreamedJsonValidator.requireObject(stream) }.content}
            return HookToolInputSource.admit(session,completed.handle) {check(current.get())}
        }
    }
    private class FakeLease:ImmutableHookEvent {
        var closed=false;var sealed=false
        override val parentDescriptorPath="/proc/1234/fd/42"
        override fun requireSealed() {check(sealed && !closed)}
        override fun close() {closed=true}
    }
    private class FakeFactory:ImmutableHookEventFactory {
        val bytes=ByteArrayOutputStream();var lease:FakeLease?=null;var afterWrite:()->Unit={}
        override fun create(write:(java.io.OutputStream)->Unit,takeOwnership:(ImmutableHookEvent)->Unit,checkCurrent:()->Unit):ImmutableHookEvent {
            val result=FakeLease();lease=result;takeOwnership(result);checkCurrent();write(bytes);result.sealed=true;afterWrite();return result
        }
    }
    private class FakeTerminal:ITerminalSession {
        override val output=MutableStateFlow("");var calls=0;var command="";var receipt=true;var reply=ExecResult(0,"{}",false)
        var block:(suspend()->Unit)?=null
        override suspend fun execOnce(cmd:String,timeoutMs:Long):ExecResult {
            calls++;command=cmd;block?.invoke()
            val marker=checkNotNull(Regex("__DROIDE_SEALED_HOOK_[a-f0-9]{32}:").find(cmd)).value
            return if(receipt)reply.copy(output=reply.output+"\n"+marker+reply.exitCode+"\n") else reply
        }
        override fun start()=error("unsupported")
        override fun send(cmd:String)=error("unsupported")
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult=error("unsupported")
        override fun interrupt()=Unit
        override fun clear()=Unit
        override fun destroy()=Unit
    }
}
