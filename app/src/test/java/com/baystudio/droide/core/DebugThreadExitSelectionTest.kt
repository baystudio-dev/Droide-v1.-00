
package com.baystudio.droide.core
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugThreadExitSelectionTest {
 @Test fun exitedSelectedThreadCannotOverrideRefreshedLiveSelection() = scenario(listOf(8), 8)
 @Test fun unrelatedThreadEventPreservesStillLiveSelection() = scenario(listOf(8, 7), 7)

 @Test fun explicitStoppedThreadRemainsSelectedOverFirstThreadInList() = scenario(listOf(8, 7), 7, listOf(8, 7))

 private fun scenario(liveThreads: List<Int>, expected: Int, initialThreads: List<Int> = listOf(7)) = runBlocking {
  val root = Files.createTempDirectory("debug-thread-exit-").toFile()
  root.resolve("main.py").writeText("print(1)")
  val failures = AtomicReference<Throwable?>()
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> failures.compareAndSet(null, e) })
  val host = DebugProtocolFixture(root)
  val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
  val threads = AtomicReference(initialThreads)
  val inspectedScopes = java.util.concurrent.CopyOnWriteArrayList<Int>()
  val stackRequests = Channel<Int>(Channel.UNLIMITED)
  suspend fun event(name: String, body: JsonObject) = host.send(buildJsonObject {
   put("seq",100);put("type","event");put("event",name);put("body",body)
  })
  val adapter = scope.launch(Dispatchers.IO) {
   for (r in host.requests) when (r["command"]!!.jsonPrimitive.content) {
    "launch" -> { host.reply(r);event("initialized",buildJsonObject {}) }
    "threads" -> host.reply(r,buildJsonObject {
     putJsonArray("threads") { threads.get().forEach { id -> add(buildJsonObject { put("id",id);put("name","thread-$id") }) } }
    })
    "stackTrace" -> {
     val id=r["arguments"]!!.jsonObject["threadId"]!!.jsonPrimitive.int
     stackRequests.send(id)
     host.reply(r,buildJsonObject { putJsonArray("stackFrames") { add(buildJsonObject { put("id",id);put("name","frame-$id");put("line",1) }) } })
    }
    "scopes" -> {
     inspectedScopes.add(r["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int)
     host.reply(r)
    }
    "evaluate" -> {
     val id=r["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int
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
   threads.set(liveThreads)
   event("thread",buildJsonObject { put("reason",if (7 in liveThreads) "started" else "exited");put("threadId",if (7 in liveThreads) 8 else 7) })
   val requested = withTimeout(5000) { stackRequests.receive() }
   withTimeout(5000) { inspectionIdle() }
   assertEquals("Stack inspection must use a thread still present in the refreshed list",expected,requested)
   assertEquals(expected,manager.selectedThread.value)
   assertEquals(expected,manager.selectedFrame.value)
   assertEquals(expected,inspectedScopes.last())
   assertEquals("watch-$expected",manager.watches.value.single().value)
   assertEquals(DebugState.STOPPED,manager.state.value)
   assertFalse(host.closed.get())
  } finally {
   adapter.cancelAndJoin();manager.close();host.close();scope.cancel();stackRequests.close();PathSecurity.deleteTreeNoFollow(root)
  }
  assertNull(failures.get())
 }
}
