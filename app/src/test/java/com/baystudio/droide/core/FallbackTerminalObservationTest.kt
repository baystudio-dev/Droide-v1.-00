package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class FallbackTerminalObservationTest {
    @get:org.junit.Rule val admission:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
    private fun fixture(action:suspend(TerminalSession,TerminalManager)->Unit)=runBlocking {
        val root=Files.createTempDirectory("fallback-observer").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val session=TerminalSession(root,scope)
        // Keep real owner observation while preventing OS launch in this registry fixture.
        val published=object:ITerminalSession by session,TerminalOwnershipObservationSource {
            override fun start()=Unit
            override suspend fun observeTerminalOwnership()=session.observeTerminalOwnership()
        }
        val manager=TerminalManager(scope,root){_,_,_->published}
        try{action(session,manager)}finally{manager.dispose(false);session.destroy();scope.cancel();root.deleteRecursively()}
    }
    @Test fun unstartedSessionObservationDoesNotStartProcess()=fixture{session,_->
        val observed=session.observeTerminalOwnership();observed.validateMetadata();assertTrue(observed.receipts.isEmpty());assertTrue(observed.retainedOwners.isEmpty());assertFalse(observed.disposed)
    }
    @Test fun actualManagerRoutesFallbackProducerAndKeepsUnknowns()=fixture{_,manager->
        manager.createLocal()
        val observed=CatalogTerminalObservation.capture(manager,null)
        assertFalse(observed.evidence().unknownCoverage.any{it.contains("unsupported")})
        assertTrue(observed.evidence().unknownCoverage.isNotEmpty());assertTrue(observed.evidence().unknownDescriptors.isNotEmpty())
        assertTrue(observed.evidence().liveOwners.isEmpty())
    }
    @Test fun destructionInvalidatesCapturedOwnership()=fixture{session,_->
        val observed=session.observeTerminalOwnership();session.destroy();assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertTrue(session.observeTerminalOwnership().disposed)
    }
    @Test fun failedExecutionRegistrationAndRemovalInvalidates()=fixture{session,_->
        val observed=session.observeTerminalOwnership();session.execArgv(listOf("unused"),-1)
        assertTrue(runCatching{observed.validateMetadata()}.isFailure);assertTrue(session.observeTerminalOwnership().retainedOwners.isEmpty())
    }
    @Test fun lateStickyFailurePublicationInvalidatesWithoutInvokingCleanup()=fixture{session,_->
        val observed=session.observeTerminalOwnership()
        // Exercise the exact owner-local callback publication helper; no live capture is launched.
        val publish=TerminalSession::class.java.getDeclaredMethod("recordCleanupFailure",Throwable::class.java).apply{isAccessible=true}
        publish.invoke(session,IllegalStateException("fixture retained failure"))
        assertTrue(runCatching{observed.validateMetadata()}.isFailure)
        val failed=session.observeTerminalOwnership();assertTrue(failed.retainedOwners.any{it.contains("cleanup failure retained")})
        publish.invoke(session,IllegalStateException("second fixture failure"));assertTrue(runCatching{failed.validateMetadata()}.isFailure)
    }
    @Test fun cancellationDoesNotChangeSessionOwnership()=fixture{session,_->
        val before=session.observeTerminalOwnership();val cancelled=Job().apply{cancel()}
        assertTrue(runCatching{withContext(cancelled){session.observeTerminalOwnership()}}.exceptionOrNull() is CancellationException)
        before.validateMetadata();assertFalse(session.observeTerminalOwnership().disposed)
    }
}
