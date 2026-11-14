package com.baystudio.droide.core
import android.content.Context
import android.net.LocalServerSocket
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exact production DroideCliServer, explicit fake Android I/O, real temporary endpoint files. */
class CliServerExitReceiptHostTest {
 @Test(timeout=10_000) fun failedEndpointWithdrawalRemainsOwnedAndBlocksExitUntilRetried() = runBlocking<Unit> {
  val root = Files.createTempDirectory("r64-cli-endpoint-").toFile()
  val authority = UnifiedPackageAuthority()
  var endpoint: File? = null
  try {
   ApplicationWorkAdmission.isOpen = true
   DroideCliServer.start(Context(root), authority, "workspace")
   val session = active()
   endpoint = File(root, "cli/workspace/endpoint-v1")
   await { endpoint.isFile }
   assertTrue(endpoint.delete())
   assertTrue(endpoint.mkdir())
   File(endpoint,"keep").writeText("force DirectoryNotEmptyException")
   ApplicationWorkAdmission.isOpen = false
   DroideCliServer.stop(authority)
   withTimeout(3_000) { sessionJob(session).join() }
   assertTrue("Failed endpoint owner must remain in Exit receipts", owned().contains(session))
   val failed = runCatching { DroideCliServer.stopAndAwaitForExit(authority) }.exceptionOrNull()
   assertNotNull("Exit must not report success while endpoint withdrawal fails", failed)
   assertTrue(owned().contains(session))
   assertTrue(File(endpoint,"keep").delete()); assertTrue(endpoint.delete())
   DroideCliServer.stopAndAwaitForExit(authority)
   assertFalse(owned().contains(session))
  } finally {
   endpoint?.let { File(it,"keep").delete(); it.delete() }
   ApplicationWorkAdmission.isOpen = false
   runCatching { DroideCliServer.stopAndAwaitForExit() }
   root.deleteRecursively()
  }
 }
 @Test(timeout=10_000) fun listenerCreatedAfterCancelRetainsFailedCloseReceipt() = runBlocking<Unit> {
  val root = Files.createTempDirectory("r64-cli-late-listener-").toFile()
  val entered = CountDownLatch(1); val release = CountDownLatch(1)
  try {
   LocalServerSocket.constructorEntered = entered; LocalServerSocket.constructorRelease = release
   LocalServerSocket.nextFailClose = true
   ApplicationWorkAdmission.isOpen = true
   DroideCliServer.start(Context(root), UnifiedPackageAuthority(), "late")
   val session = active()
   assertTrue(entered.await(3, TimeUnit.SECONDS))
   ApplicationWorkAdmission.isOpen = false
   DroideCliServer.stop()
   release.countDown()
   withTimeout(3_000) { sessionJob(session).join() }
   assertTrue("Late listener's failed finally close must remain owned", owned().contains(session))
   assertNotNull(runCatching { DroideCliServer.stopAndAwaitForExit() }.exceptionOrNull())
   assertTrue(owned().contains(session))
   LocalServerSocket.newest!!.failClose = false
   DroideCliServer.stopAndAwaitForExit()
   assertTrue(LocalServerSocket.newest!!.closed.get())
   assertFalse(owned().contains(session))
  } finally {
   release.countDown(); LocalServerSocket.constructorEntered=null; LocalServerSocket.constructorRelease=null
   LocalServerSocket.newest?.failClose=false
   ApplicationWorkAdmission.isOpen=false
   runCatching { DroideCliServer.stopAndAwaitForExit() }
   root.deleteRecursively()
  }
 }
 @Test(timeout=10_000) fun normalExitWithdrawsExactEndpointAndDrainsSession() = runBlocking<Unit> {
  val root=Files.createTempDirectory("r64-cli-normal-").toFile()
  try {
   ApplicationWorkAdmission.isOpen=true
   DroideCliServer.start(Context(root),UnifiedPackageAuthority(),"normal")
   val session=active(); val endpoint=File(root,"cli/normal/endpoint-v1")
   await { endpoint.isFile }
   ApplicationWorkAdmission.isOpen=false
   DroideCliServer.stopAndAwaitForExit()
   assertFalse(endpoint.exists()); assertTrue(sessionJob(session).isCompleted)
   assertFalse(owned().contains(session)); assertTrue(LocalServerSocket.newest!!.closed.get())
  } finally { ApplicationWorkAdmission.isOpen=false; runCatching { DroideCliServer.stopAndAwaitForExit() }; root.deleteRecursively() }
 }
 private fun active(): Any = field(DroideCliServer,"active")!!
 @Suppress("UNCHECKED_CAST") private fun owned()=field(DroideCliServer,"ownedSessions") as Set<Any>
 private fun sessionJob(session:Any)=(field(session,"scope") as CoroutineScope).coroutineContext[Job]!!
 private fun field(owner:Any,name:String)=owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner)
 private suspend fun await(test:()->Boolean) { withTimeout(3_000) { while(!test()) delay(5) } }
}
