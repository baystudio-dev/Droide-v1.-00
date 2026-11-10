package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real document authority with a recording synchronous native binding, not device Sora rendering. */
class WorkspaceSourceTextReplacementTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("live-source-").toFile()
        val files = FileRepository(root)
        val state = EditorWorkspaceState().apply { completeRecovery() }
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val store = OwnedResponseContentStore(root.resolve("private").toPath(),
            OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32)
        val session = store.authorize(OwnedResponseContentStore.Owner("session","run","call"))
        val document = state.document("a").also {
            root.resolve("a").writeText("saved")
            runBlocking { it.ensureLoaded(files) }
            it.capture("unsaved draft")
        }
        var nativeText = document.content
        var nativeWrites = 0
        fun bind(prepare: () -> Unit = {}) {
            document.nativeMutationBinding = EditorNativeMutationBinding(Any(), { true }, prepare,
                { before, after, publish ->
                    assertEquals(nativeText,before);nativeText=after;nativeWrites++;publish()
                })
        }
        fun source(text: String, kind: OwnedResponseContentStore.Kind = OwnedResponseContentStore.Kind.TOOL_CONTENT): StreamedFileArgumentExtractor.Source {
            val content=session.begin(kind).use { writer ->
                writer.append(text)
                writer.sealCompleted { input -> val b=ByteArray(4096);while(input.read(b)>=0){} }.content
            }
            return StreamedFileArgumentExtractor.Source(content.handle,content.byteLength,content.utf16Length,content.sha256,kind)
        }
        suspend fun prepare(text: String) = WorkspaceSourceTextReplacement.prepare(session,source(text)) {}
        suspend fun snapshot() = requireNotNull(authority.snapshot("a"))
        override fun close() { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun overTwoMillionLiveCharactersPreserveUnsavedUndoAndNeverWriteDisk() = runBlocking {
        Fixture().use { f ->
            f.bind()
            val before=f.snapshot();val text="x".repeat(2_100_000);val prepared=f.prepare(text)
            val history=EditHistory(f.files,f.authority)
            val pending=history.prepareLive("a",before.content)
            var observed:WorkspaceSourceTextReplacement.Publication?=null
            val result=f.authority.replaceSourceText("a",before.version,prepared,"Agent review",{}, { observed=it })
            val applied=result as WorkspaceDocumentMutationResult.Applied
            assertSame(prepared.publication,observed)
            assertSame(prepared,observed!!.replacement)
            assertEquals("LIVE_BUFFER",observed!!.effect)
            assertEquals(before.version,observed!!.before)
            assertEquals(applied.after.version,observed!!.after)
            assertEquals(prepared.sha256,observed!!.sha256)
            assertEquals(text,f.document.content);assertEquals(text,f.nativeText)
            assertTrue(f.document.dirty);assertEquals("saved",f.root.resolve("a").readText())
            withContext(Dispatchers.IO) { history.commitLive(pending,EditHistory.LiveTransition.from(applied)) }
            assertEquals(1,history.depth())
            assertTrue(history.undo().startsWith("Undone:"))
            assertEquals("unsaved draft",f.document.content);assertEquals("saved",f.root.resolve("a").readText())
            assertEquals(2,f.nativeWrites)
        }
    }

    @Test fun nativeTypingIsCapturedBeforeExactVersionCheck() = runBlocking {
        Fixture().use { f ->
            val before=f.snapshot();val prepared=f.prepare("replacement")
            f.bind { f.nativeText="user typed";f.document.captureNativeText(f.nativeText) }
            val result=f.authority.replaceSourceText("a",before.version,prepared,"Agent",{}, { error("must not publish") })
            assertTrue(result is WorkspaceDocumentMutationResult.Conflict)
            assertEquals("user typed",f.document.content);assertEquals(0,f.nativeWrites)
            assertNull(prepared.publication)
            assertTrue(runCatching { f.authority.replaceSourceText("a",f.snapshot().version,prepared,"Agent",{}, {}) }.isFailure)
        }
    }

    @Test fun bridgeRebindingDuringNativePreparationCannotPublish() = runBlocking {
        Fixture().use { f ->
            val bridge=WorkspaceDocumentAuthorityBridge();bridge.bind(f.authority)
            val before=f.snapshot();val prepared=f.prepare("replacement")
            f.bind { bridge.bind(EditorWorkspaceDocumentAuthority(EditorWorkspaceState(),Dispatchers.Unconfined)) }
            assertTrue(runCatching { bridge.replaceSourceText("a",before.version,prepared,"Agent",{}, {}) }.isFailure)
            assertEquals(0,f.nativeWrites);assertEquals(before.content,f.document.content);assertNull(prepared.publication)
        }
    }

    @Test fun authorityRevocationAfterNativePreparationCannotPublish() = runBlocking {
        Fixture().use { f ->
            var current=true;val before=f.snapshot();val prepared=f.prepare("replacement")
            f.bind { current=false }
            assertTrue(runCatching { f.authority.replaceSourceText("a",before.version,prepared,"Agent",{ check(current) },{}) }.isFailure)
            assertEquals(0,f.nativeWrites);assertNull(prepared.publication)
        }
    }

    @Test fun stopAfterPublicationKeepsReceiptAndUndoDespitePromptCancellation() = runBlocking {
        Fixture().use { f ->
            f.bind();val before=f.snapshot();val prepared=f.prepare("new ".repeat(40_000))
            val history=EditHistory(f.files,f.authority);val pending=history.prepareLive("a",before.content)
            val dispatcher=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val authority=EditorWorkspaceDocumentAuthority(f.state,dispatcher)
                val job=launch {
                    val owner=currentCoroutineContext().job
                    authority.replaceSourceText("a",before.version,prepared,"Agent",{ owner.ensureActive() },{ owner.cancel() })
                    fail("prompt cancellation must not report normal return")
                }
                job.join();assertTrue(job.isCancelled)
                val proof=requireNotNull(prepared.publication)
                assertEquals(1,f.nativeWrites);assertEquals("saved",f.root.resolve("a").readText())
                withContext(Dispatchers.IO) { history.commitLive(pending,EditHistory.LiveTransition(proof.before,proof.after)) }
                assertTrue(history.undo().startsWith("Undone:"));assertEquals(before.content,f.document.content)
            } finally { dispatcher.close() }
        }
    }

    @Test fun readOnlyAndUnsupportedAuthorityNeverFallbackToDisk() = runBlocking {
        Fixture().use { f ->
            val before=f.snapshot();val prepared=f.prepare("replacement");f.document.setReviewMode(true)
            f.bind { error("read-only must not prepare native mutation") }
            assertTrue(f.authority.replaceSourceText("a",before.version,prepared,"Agent",{}, {}) is WorkspaceDocumentMutationResult.ReadOnly)
            assertNull(prepared.publication);assertEquals("saved",f.root.resolve("a").readText())
            val unsupported=object:WorkspaceDocumentAuthority {
                override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
                override suspend fun snapshots()=emptyList<WorkspaceDocumentSnapshot>()
            }
            assertTrue(runCatching { unsupported.replaceSourceText("a",before.version,f.prepare("x"),"Agent",{}, {}) }.exceptionOrNull() is WorkspaceRecoveryEditorUnavailableException)
        }
    }

    @Test fun capacityKindDescriptorAndCancellationAreCheckedBeforeMutation() = runBlocking {
        Fixture().use { f ->
            val source=f.source("small")
            val oversized=StreamedFileArgumentExtractor.Source(source.handle,EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES+1,source.utf16Units,source.sha256,source.kind)
            assertTrue(runCatching { WorkspaceSourceTextReplacement.prepare(f.session,oversized) {} }.isFailure)
            assertTrue(runCatching { f.prepare("\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES+1)) }.isFailure)
            val wrong=f.source("small",OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            assertTrue(runCatching { WorkspaceSourceTextReplacement.prepare(f.session,wrong) {} }.isFailure)
            val forged=StreamedFileArgumentExtractor.Source(source.handle,source.bytes,source.utf16Units,"0".repeat(64),source.kind)
            assertTrue(runCatching { WorkspaceSourceTextReplacement.prepare(f.session,forged) {} }.isFailure)
            assertTrue(runCatching { WorkspaceSourceTextReplacement.prepare(f.session,source) { throw CancellationException("Stop") } }.isFailure)
            assertEquals("unsaved draft",f.document.content);assertEquals("saved",f.root.resolve("a").readText())
        }
    }
}
