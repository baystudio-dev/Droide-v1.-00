
package com.baystudio.droide.core
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugEmptyThreadInspectionTest {
 @Test fun lastThreadExitClearsInspectionWithoutEvaluatingDeadFrame() = scenario()

 private fun scenario() = runBlocking {
  val root = Files.createTempDirectory("debug-thread-exit-").toFile()
  root.resolve("main.py").writeText("print(1)")
  val failures = AtomicReference<Throwable?>()
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures.compareAndSet(null, e) })
  val host = DebugProtocolFixture(root)
  val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
  val threads = AtomicReference(listOf(7))
  val inspectedScopes = java.util.concurrent.CopyOnWriteArrayList<Int>()
  val stackRequests = Channel<Int>(Channel.UNLIMITED)
  val threadReplies = Channel<Unit>(Channel.UNLIMITED)
  val evaluations = java.util.concurrent.CopyOnWriteArrayList<Int?>()
  suspend fun event(name: String, body: JsonObject) = host.send(buildJsonObject {
   put("seq",100);put("type","event");put("event",name);put("body",body)
  })
  val adapter = scope.launch(Dispatchers.IO) {
   for (r in host.requests) when (r["command"]!!.jsonPrimitive.content) {
    "launch" -> { host.reply(r);event("initialized",buildJsonObject {}) }
    "threads" -> {
     host.reply(r,buildJsonObject {
      putJsonArray("threads") { threads.get().forEach { id -> add(buildJsonObject { put("id",id);put("name","thread-$id") }) } }
     })
     threadReplies.send(Unit)
    }
    "stackTrace" -> {
     val id=r["arguments"]!!.jsonObject["threadId"]!!.jsonPrimitive.int
     stackRequests.send(id)
     host.reply(r,buildJsonObject { putJsonArray("stackFrames") { add(buildJsonObject { put("id",id);put("name","frame-$id");put("line",1) }) } })
    }
    "scopes" -> {
     inspectedScopes.add(r["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int)
     host.reply(r,buildJsonObject { putJsonArray("scopes") { add(buildJsonObject { put("name","Local");put("variablesReference",7) }) } })
    }
    "variables" -> host.reply(r,buildJsonObject { putJsonArray("variables") { add(buildJsonObject { put("name","local");put("value","42");put("variablesReference",0) }) } })
    "evaluate" -> {
     val id=r["arguments"]!!.jsonObject["frameId"]?.jsonPrimitive?.int
     evaluations.add(id)
     host.reply(r,buildJsonObject { put("result","watch-$id");put("variablesReference",0) })
    }
    "disconnect" -> { host.reply(r);break }
    else -> host.reply(r)
   }
  }
  suspend fun inspectionIdle() {
   // Join the actual owned inspection job; avoid a sleep-dependent event overlap.
   val field=DebugManager::class.java.getDeclaredField("inspectionJob").apply { isAccessible=true }
   (field.get(manager) as? Job)?.join()
  }
  try {
   manager.addWatch("value")
   manager.start(DebugConfiguration("fixture","launch",listOf("fixture-dap"),buildJsonObject {},"test"))
   event("stopped",buildJsonObject { put("reason","breakpoint");put("threadId",7) })
   assertEquals(7,withTimeout(5000) { stackRequests.receive() })
   withTimeout(5000) { inspectionIdle() }
   withTimeout(5000) { threadReplies.receive() }
   assertEquals(7,manager.selectedFrame.value)
   assertEquals("watch-7",manager.watches.value.single().value)
   assertTrue(manager.variables.value.any { it.name == "local" && it.value == "42" })
   val evaluatedBefore = evaluations.toList()
   threads.set(emptyList())
   event("thread",buildJsonObject { put("reason","exited");put("threadId",7) })
   withTimeout(5000) { threadReplies.receive(); inspectionIdle() }
   assertNull(manager.selectedThread.value)
   assertNull("No thread must mean no selected dead frame",manager.selectedFrame.value)
   assertTrue(manager.frames.value.isEmpty())
   assertTrue(manager.variables.value.isEmpty())
   assertEquals("",manager.watches.value.single().value)
   assertEquals("value",manager.watches.value.single().expression)
   assertEquals(evaluatedBefore,evaluations.toList())
   assertTrue(stackRequests.tryReceive().isFailure)
   threads.set(listOf(8))
   event("thread",buildJsonObject { put("reason","started");put("threadId",8) })
   withTimeout(5000) { threadReplies.receive(); inspectionIdle() }
   assertEquals(8,withTimeout(5000) { stackRequests.receive() })
   assertEquals(8,manager.selectedThread.value)
   assertEquals(8,manager.selectedFrame.value)
   assertEquals("watch-8",manager.watches.value.single().value)
   assertEquals("value",manager.watches.value.single().expression)
   assertEquals(DebugState.STOPPED,manager.state.value)
   assertFalse(host.closed.get())
  } finally {
   adapter.cancelAndJoin();manager.close();host.close();scope.cancel();stackRequests.close();threadReplies.close();PathSecurity.deleteTreeNoFollow(root)
  }
  assertNull(failures.get())
 }
}
