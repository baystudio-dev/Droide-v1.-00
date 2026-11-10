package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalAgentWorkspaceAliasTest {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private class Fixture(val root: File, val alias: File, val real: File, val source: File,
                          val scope: CoroutineScope, val local: LocalAgentWorkspaceProcessHost, val remote: File) {
        val io get() = local.workspaceIo
        fun path(name: String) = File(remote, name).absolutePath
        fun file(name: String, value: String = "proposal"): File = File(remote, name).apply { parentFile.mkdirs(); writeText(value) }
    }
    private fun fixture(aliasRoot: Boolean = true, body: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(30_000) {
            val root = Files.createTempDirectory("cli-alias-").toFile().canonicalFile
            val real = File(root,"real").apply { mkdirs() }
            val alias = File(root,"alias"); Files.createSymbolicLink(alias.toPath(),real.toPath())
            val use = File(if(aliasRoot) alias else real,"app/files").apply { mkdirs() }
            val source = File(use,"project").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val local = LocalAgentWorkspaceProcessHost(source,File(use,"cache"),scope,File(use,"persistent"))
            try {
                local.workspaceIo.refresh()
                val remote = File(local.pathMapper().remoteRoot)
                body(Fixture(root,alias,real,source,scope,local,remote))
            } finally { scope.coroutineContext.job.cancelAndJoin(); PathSecurity.deleteTreeNoFollow(root) }
        }
    }
    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (t: Throwable) { if(t is CancellationException) throw t; return t }
        throw AssertionError("Expected fail-closed refusal")
    }
    @Test fun aliasPreviewAndReadUseMapperIdentity() = fixture { f ->
        f.file("nested/index.html","hello")
        assertEquals("hello",f.io.reviewText(f.path("nested/index.html"),hash("hello")))
        assertEquals("hello",f.io.readExecutionText(f.path("nested/index.html")))
    }
    @Test fun aliasPullUsesOriginalPinnedRoot() = fixture { f ->
        f.file("index.html","hello")
        val out=File(f.root,"out"); f.io.pull(f.path("index.html"),out)
        assertEquals("hello",out.readText())
    }
    @Test fun canonicalRootStillWorks() = fixture(false) { f ->
        f.file("index.html","hello")
        val out=File(f.root,"out"); f.io.pull(f.path("index.html"),out)
        assertEquals("hello",out.readText()); assertEquals("hello",f.io.reviewText(f.path("index.html"),hash("hello")))
    }
    @Test fun aliasMediaStreamsExactBytes() = fixture { f ->
        f.file("image.bin","binary-data")
        assertEquals("binary-data",f.io.openMedia(f.path("image.bin")).use { it.readBytes().toString(Charsets.UTF_8) })
    }
    @Test fun aliasNewAttachmentPreservesExistingFile() = fixture { f ->
        val staged=File(f.root,"staged").apply { writeText("asset") }
        f.io.pushNew(staged,f.path("nested/asset.bin"))
        assertEquals("asset",File(f.remote,"nested/asset.bin").readText())
        assertNotNull(failure { f.io.pushNew(staged,f.path("nested/asset.bin")) })
        assertEquals("asset",File(f.remote,"nested/asset.bin").readText())
    }
    @Test fun aliasAbsentCheckSeesCreation() = fixture { f ->
        f.io.requireExecutionAbsent(f.path("new.txt")); f.file("new.txt")
        assertNotNull(failure { f.io.requireExecutionAbsent(f.path("new.txt")) })
    }
    @Test fun traversalSiblingAndForeignAliasRemainRejected() = fixture { f ->
        val out=File(f.root,"out")
        for(path in listOf(f.path("../outside"),f.remote.absolutePath+"-sibling/file",File(f.root,"outside").absolutePath))
            assertNotNull(failure { f.io.pull(path,out) })
        assertFalse(out.exists())
    }
    @Test fun childSymlinkOutsideRemainsRejected() = fixture { f ->
        val outside=File(f.root,"outside").apply { writeText("secret") }
        Files.createSymbolicLink(File(f.remote,"link").toPath(),outside.toPath())
        val out=File(f.root,"out")
        assertNotNull(failure { f.io.pull(f.path("link"),out) }); assertFalse(out.exists())
        assertNotNull(failure { f.io.reviewText(f.path("link"),hash("secret")) })
    }
    @Test fun childSymlinkInsideRemainsRejectedByAnchoredReader() = fixture { f ->
        val target=f.file("target","inside")
        Files.createSymbolicLink(File(f.remote,"link").toPath(),target.toPath())
        assertNotNull(failure { f.io.pull(f.path("link"),File(f.root,"out")) })
        assertNotNull(failure { f.io.openMedia(f.path("link")).close() })
    }
    @Test fun intermediateSymlinkRemainsRejected() = fixture { f ->
        f.file("dir/target","inside")
        Files.createSymbolicLink(File(f.remote,"linked-dir").toPath(),File(f.remote,"dir").toPath())
        assertNotNull(failure { f.io.pull(f.path("linked-dir/target"),File(f.root,"out")) })
    }
    @Test fun wrongReviewHashRetainsProposal() = fixture { f ->
        f.file("index.html","hello")
        assertNotNull(failure { f.io.reviewText(f.path("index.html"),hash("old")) })
        assertEquals("hello",File(f.remote,"index.html").readText())
    }
    @Test fun aliasParentRetargetingFailsIdentityGuard() = fixture { f ->
        f.file("index.html","hello")
        Files.delete(f.alias.toPath())
        val other=File(f.root,"other").apply { mkdirs() }
        Files.createSymbolicLink(f.alias.toPath(),other.toPath())
        assertNotNull(failure { f.io.pull(f.path("index.html"),File(f.root,"out")) })
        assertFalse(File(f.root,"out").exists())
    }
    @Test fun replacedMirrorRootFailsIdentityGuard() = fixture { f ->
        f.file("index.html","hello")
        val saved=File(f.remote.parentFile,"saved"); Files.move(f.remote.toPath(),saved.toPath())
        f.remote.mkdirs(); File(f.remote,"index.html").writeText("replacement")
        assertNotNull(failure { f.io.pull(f.path("index.html"),File(f.root,"out")) })
        assertEquals("hello",File(saved,"index.html").readText())
    }
    @Test fun recreatedHostPreservesPendingAliasedBytes() = fixture { f ->
        f.file("index.html","hello")
        val next=LocalAgentWorkspaceProcessHost(f.source,File(f.source.parentFile,"cache"),f.scope,File(f.source.parentFile,"persistent"))
        next.workspaceIo.refresh()
        assertEquals(f.remote.canonicalPath,next.pathMapper().remoteRoot)
        assertEquals("hello",next.workspaceIo.reviewText(f.path("index.html"),hash("hello")))
    }
    @Test fun realReviewAllowPublishesNewFileWithAliasRoot() = runBlocking {
        val f=AliasReviewFixture.open(existing=false,nativePublisher=true)
        try { f.seedPending(); val result=f.reviewApproved()
            assertEquals(listOf("file.txt"),result.changedFiles); assertTrue(result.pendingFiles.isEmpty())
            assertEquals("CLI proposal",File(f.source,"file.txt").readText())
            assertEquals(WorkspaceAgentSyncPhase.SYNCED,f.host.workspaceSyncState.value.phase)
            assertEquals(1,f.promptCount)
        } finally { f.close() }
    }
    @Test fun realReviewAllowPublishesExistingFileWithAliasRoot() = runBlocking {
        val f=AliasReviewFixture.open()
        try { f.seedPending(); val result=f.reviewApproved()
            assertEquals(listOf("file.txt"),result.changedFiles); assertTrue(result.pendingFiles.isEmpty())
            assertEquals("CLI proposal",File(f.source,"file.txt").readText())
            assertEquals(WorkspaceAgentSyncPhase.SYNCED,f.host.workspaceSyncState.value.phase)
            assertEquals(1,f.promptCount)
        } finally { f.close() }
    }
    @Test fun allThreeWebsiteFilesFromVideoPublishAfterOneReview() = runBlocking {
        val f=AliasReviewFixture.open(existing=false,nativePublisher=true)
        val proposals=mapOf("index.html" to "<h1>Hello</h1>","script.js" to "console.log('hello')", "styles.css" to "body{color:blue}")
        try { f.seedPending(proposals); val result=f.reviewApproved()
            assertEquals(proposals.keys.sorted(),result.changedFiles.sorted()); assertTrue(result.pendingFiles.isEmpty())
            proposals.forEach { (name,value) -> assertEquals(value,File(f.source,name).readText()) }
            assertEquals(WorkspaceAgentSyncPhase.SYNCED,f.host.workspaceSyncState.value.phase)
            assertEquals(1,f.promptCount)
        } finally { f.close() }
    }

}
