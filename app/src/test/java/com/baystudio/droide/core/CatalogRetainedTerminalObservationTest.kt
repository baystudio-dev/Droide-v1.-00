package com.baystudio.droide.core

import android.content.Context
import android.app.ApplicationExitInfo
import com.termux.terminal.TerminalSession as TermuxSession
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the production retention/restore host; fake handles never launch native Android PTYs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogRetainedTerminalObservationTest {
    @Test fun hostObservationIncludesOtherProjectOwnersWithoutNativeGetters()=runBlocking<Unit>{fixture().use{f->
        f.projects+=listOf("first","second")
        val a=f.host.create("first",f.root,"A",f.root,true);val b=f.host.create("second",f.root,"B",f.root,true)
        val observed=f.host.observeOwnership();assertEquals(2,observed.sessions.size)
        assertTrue(observed.sessions.any{it===a.session});assertTrue(observed.sessions.any{it===b.session});observed.validate()
        assertEquals(0,f.created.sumOf{it.destroyed})
    }}
    @Test fun unsupportedRetainedSourcesAreExplicitUnknowns()=runBlocking<Unit>{fixture().use{f->
        f.projects+="other";f.host.create("other",f.root,"Other",f.root,true)
        val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Unconfined)
        val manager=TerminalManager(scope,f.root){_,_,_->error("Observation must not create")}
        try{
            val observation=CatalogTerminalObservation.capture(manager,f.host);observation.validate()
            assertTrue(observation.evidence().unknownCoverage.any{it.contains("unsupported")})
            assertTrue(observation.evidence().retainedOwners.any{it.contains("Application-retained")})
            assertEquals(0,f.created.single().destroyed)
        }finally{manager.dispose(false);scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}
    }}
    @Test fun hostRemoveAndRecreateInvalidatesToken()=runBlocking<Unit>{fixture().use{f->
        f.projects+="owner";val first=f.host.create("owner",f.root,"First",f.root,true)
        val observed=f.host.observeOwnership();f.host.removeAfterVerifiedExit("owner",f.root,first.id,first.session)
        f.host.create("owner",f.root,"Second",f.root,true)
        assertTrue(runCatching{observed.validate()}.isFailure);assertEquals(0,(first.session as FakeHandle).destroyed)
    }}
    private suspend fun fixture(recoveryPolicy: StartupRecoveryPolicy? = null) = Fixture(
        RuntimeEnvironment.getApplication(),
        requireNotNull(currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher),
        recoveryPolicy,
    )

    private class Fixture(val context: Context, mainDispatcher: CoroutineDispatcher, recoveryPolicy: StartupRecoveryPolicy?) : AutoCloseable {
        val root = File(context.filesDir, "host-test-${UUID.randomUUID()}").apply { mkdirs() }
        val created = mutableListOf<FakeHandle>()
        val projects = mutableSetOf<String>()
        var failNextStart = false
        var failOnFactoryCall = -1
        // Preserve this test's actual main thread through runBlocking's event loop without changing
        // global state.
        val host = PersistentLocalTerminalHost.forTesting(context, mainDispatcher, recoveryPolicy) { _, _ ->
            FakeHandle(failNextStart || created.size + 1 == failOnFactoryCall).also {
                failNextStart = false
                created += it
            }
        }
        override fun close() {
            projects.forEach { key -> host.destroyProject(key, root) }
            runBlocking { projects.forEach { key -> host.flush(key, root) } }
            root.deleteRecursively()
        }
    }

    private class FakeHandle(private val failStart: Boolean) : NativePtySessionHandle {
        override val output = MutableStateFlow("")
        override val terminalTitle = MutableStateFlow<String?>(null)
        override val workingDirectory = MutableStateFlow<String?>(null)
        override val nativePtySession: TermuxSession get() = error("test must not instantiate native PTY")
        override val displayedPtySession: StateFlow<TermuxSession> get() = error("test must not instantiate native PTY")
        var started = 0
        var destroyed = 0
        override fun start() { started++; if (failStart) throw IOException("partial native startup fixture") }
        override fun destroy() { destroyed++ }
        override fun send(cmd: String) = Unit
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun writeRaw(text: String) = Unit
        override fun setScreenUpdateListener(listener: (() -> Unit)?) = Unit
        override fun setBellListener(listener: (() -> Unit)?) = Unit
        override fun captureTranscript() = ""
        override suspend fun execOnce(cmd: String, timeoutMs: Long) = ExecResult(0, "", false)
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = ExecResult(0, "", false)
    }
}
