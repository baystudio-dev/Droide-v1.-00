package com.baystudio.droide.core
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class TerminalReadinessInvalidationTest {
    private class Session:ITerminalSession,TerminalCommandActivitySession {
        override val output=MutableStateFlow("")
        override val commandActivity=MutableStateFlow(TerminalCommandActivity(TerminalCommandPhase.READY))
        override fun noteCommandInput() {}
        override fun start() {}; override fun send(cmd:String) {}; override fun interrupt() {}; override fun clear() {}; override fun destroy() {}
        override suspend fun execOnce(cmd:String,timeoutMs:Long)=error("No command execution allowed")
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long)=error("No command execution allowed")
        fun ready(generation:Long,exit:Int=0) {commandActivity.value=TerminalCommandActivity(TerminalCommandPhase.READY,exit,generation)}
    }
    private fun snapshot(vararg sessions:Session)=TerminalManager.Snapshot(sessions.mapIndexed { i,s -> TerminalManager.Tab("$i",s,"test",TerminalManager.Profile.LOCAL_LINUX_ARM64)},null)
    @Test fun lateDebounceCollectorDoesNotDropFirstRealHint()=runTest {
        val delegate=StandardTestDispatcher(testScheduler)
        var held:Pair<kotlin.coroutines.CoroutineContext,Runnable>?=null
        var first=true
        val delayed=object:CoroutineDispatcher(),Delay {
            override fun scheduleResumeAfterDelay(timeMillis:Long,continuation:CancellableContinuation<Unit>) =
                (delegate as Delay).scheduleResumeAfterDelay(timeMillis,continuation)
            override fun invokeOnTimeout(timeMillis:Long,block:Runnable,context:kotlin.coroutines.CoroutineContext):DisposableHandle =
                (delegate as Delay).invokeOnTimeout(timeMillis,block,context)
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){
                if(first){first=false;held=context to block}else delegate.dispatch(context,block)
            }
        }
        val a=Session()
        val observer=TerminalReadinessInvalidation(CoroutineScope(coroutineContext+delayed),MutableStateFlow(snapshot(a)))
        runCurrent()
        val pending=held!!;delegate.dispatch(pending.first,pending.second)
        runCurrent();advanceTimeBy(400);runCurrent()
        assertEquals(1L,observer.revision.value)
        observer.close()
    }
    @Test fun readyGenerationsCoalesceAndOutputPulsesDoNotTriggerProbes()=runTest {
        val a=Session();val state=MutableStateFlow(snapshot(a));val observer=TerminalReadinessInvalidation(this,state)
        runCurrent();advanceTimeBy(400);runCurrent();val initial=observer.revision.value
        repeat(20) {a.ready(it+1L,if(it%2==0)0 else 1);runCurrent()}
        advanceTimeBy(400);runCurrent();assertEquals(initial+1,observer.revision.value)
        repeat(10) {a.commandActivity.value=a.commandActivity.value.copy(lastOutputAtMs=it.toLong());a.output.value="ignored $it";runCurrent()}
        advanceTimeBy(400);runCurrent();assertEquals(initial+1,observer.revision.value)
        a.ready(21,1);runCurrent();advanceTimeBy(400);runCurrent();assertEquals(initial+2,observer.revision.value)
        observer.close()
    }
    @Test fun replacementAndClosedSessionsCannotPublishStaleHints()=runTest {
        val a=Session();val state=MutableStateFlow(snapshot(a));val observer=TerminalReadinessInvalidation(this,state)
        runCurrent();advanceTimeBy(400);runCurrent()
        val b=Session();state.value=snapshot(b);runCurrent();advanceTimeBy(400);runCurrent();val replaced=observer.revision.value
        a.ready(1);runCurrent();advanceTimeBy(400);runCurrent();assertEquals(replaced,observer.revision.value)
        b.commandActivity.value=TerminalCommandActivity(TerminalCommandPhase.CLOSED);runCurrent();advanceTimeBy(400);runCurrent();val closed=observer.revision.value
        b.ready(4);runCurrent();advanceTimeBy(400);runCurrent();assertEquals(closed,observer.revision.value)
        observer.close();state.value=snapshot(Session());runCurrent();advanceTimeBy(400);runCurrent();assertEquals(closed,observer.revision.value)
    }
    @Test fun sessionsCoalesceButSeparateWorkspacesDoNotInvalidateEachOther()=runTest {
        val a=Session();val b=Session();val c=Session()
        val first=TerminalReadinessInvalidation(this,MutableStateFlow(snapshot(a,b)))
        val second=TerminalReadinessInvalidation(this,MutableStateFlow(snapshot(c)))
        runCurrent();advanceTimeBy(400);runCurrent();val before=first.revision.value;val other=second.revision.value
        a.ready(1);b.ready(1,2);runCurrent();advanceTimeBy(400);runCurrent()
        assertEquals(before+1,first.revision.value);assertEquals(other,second.revision.value)
        first.close();second.close()
    }
    @Test fun parentCancellationDropsPendingDebouncedHint()=runTest {
        val parent=SupervisorJob(coroutineContext[Job]);val scope=CoroutineScope(coroutineContext+parent)
        val a=Session();val observer=TerminalReadinessInvalidation(scope,MutableStateFlow(snapshot(a)))
        runCurrent();advanceTimeBy(400);runCurrent();val initial=observer.revision.value
        a.ready(1);runCurrent();parent.cancel();advanceTimeBy(400);runCurrent()
        assertEquals(initial,observer.revision.value);observer.close()
    }
}
