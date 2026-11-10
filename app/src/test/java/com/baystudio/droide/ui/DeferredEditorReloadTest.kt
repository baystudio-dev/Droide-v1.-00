package com.baystudio.droide.ui
import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class DeferredEditorReloadTest {
 @Test fun failedDiscardReloadMustKeepRecoveryReference() = runBlocking {
  val root=Files.createTempDirectory("deferred-reload-failure-").toFile()
  try {
   root.resolve("main.txt").mkdir()
   val ref=EditorRecoveryBuffer("main.txt","fixture.bin",null,Any()) { "only recovered edits" }
   val doc=EditorDocument("main.txt");doc.restoreDeferred(ref)
   assertTrue(doc.dirty)
   doc.reload(FileRepository(root),discardUnsaved=true)
   assertNotNull(doc.loadError)
   assertSame("Failed disk reload must not orphan deferred unsaved edits",ref,doc.pendingRecoveryBuffer)
   assertTrue(doc.dirty)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }

 @Test fun successfulDiscardReloadPublishesDiskAndReleasesRecovery() = runBlocking {
  val root=Files.createTempDirectory("deferred-reload-success-").toFile()
  try {
   root.resolve("main.txt").writeText("disk text")
   var reads=0
   val ref=EditorRecoveryBuffer("main.txt","fixture.bin",null,Any()) { reads++; "unsaved text" }
   val doc=EditorDocument("main.txt");doc.restoreDeferred(ref)
   doc.reload(FileRepository(root),discardUnsaved=true)
   assertEquals("disk text",doc.content);assertNull(doc.pendingRecoveryBuffer)
   assertFalse(doc.dirty);assertTrue(doc.loaded);assertEquals(0,reads)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
 @Test fun ordinaryReloadKeepsDeferredEditsAndFailedDiscardCanRecoverLater() = runBlocking {
  val root=Files.createTempDirectory("deferred-reload-retry-").toFile()
  try {
   root.resolve("main.txt").mkdir()
   val ref=EditorRecoveryBuffer("main.txt","fixture.bin",null,Any()) { "unsaved text" }
   val doc=EditorDocument("main.txt");doc.restoreDeferred(ref)
   doc.reload(FileRepository(root))
   assertSame(ref,doc.pendingRecoveryBuffer);assertTrue(doc.dirty)
   doc.reload(FileRepository(root),discardUnsaved=true)
   assertSame(ref,doc.pendingRecoveryBuffer);assertTrue(doc.dirty)
   assertTrue(root.resolve("main.txt").delete());root.resolve("main.txt").writeText("disk text")
   doc.ensureLoaded(FileRepository(root))
   assertEquals("unsaved text",doc.content);assertEquals("disk text",doc.savedContent)
   assertTrue(doc.dirty);assertNull(doc.pendingRecoveryBuffer)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }

 @Test fun cancelledDiscardReloadRetainsDeferredEdits() = runBlocking {
  val root=Files.createTempDirectory("deferred-reload-cancel-").toFile()
  try {
   root.resolve("main.txt").writeText("disk text")
   val ref=EditorRecoveryBuffer("main.txt","fixture.bin",null,Any()) { "unsaved text" }
   val doc=EditorDocument("main.txt");doc.restoreDeferred(ref)
   var cancelled=false
   launch {
    cancel()
    try { doc.reload(FileRepository(root),discardUnsaved=true) }
    catch (expected: CancellationException) { cancelled=true }
   }.join()
   assertTrue(cancelled);assertSame(ref,doc.pendingRecoveryBuffer);assertTrue(doc.dirty)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
}
