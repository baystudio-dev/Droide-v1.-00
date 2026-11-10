package com.baystudio.droide.core
import java.nio.file.Files
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class CustomToolInputPreservationTest {
 private fun fixture(ext:String,action:(CustomToolManager,java.io.File)->Unit) {
  val root=Files.createTempDirectory("custom-input").toFile()
  try {root.resolve(".droide/tools").mkdirs();root.resolve(".droide/tools/fixture."+ext).writeText("# fixture, never executed\n");action(CustomToolManager(root),root)}
  finally {PathSecurity.deleteTreeNoFollow(root)}
 }
 private fun exact(ext:String,input:String) = fixture(ext){manager,_ ->
  val argv=manager.argv("fixture",input)!!
  assertEquals(3,argv.size);assertEquals(mapOf("sh" to "sh","py" to "python3","js" to "node")[ext],argv[0])
  assertEquals(".droide/tools/fixture."+ext,argv[1]);assertTrue("script input was truncated",argv[2]==input)
 }
 @Test fun shellGetsCompleteJsonInput() { exact("sh","{\"value\":\""+ "x".repeat(20000)+"\"}") }
 @Test fun pythonGetsCompleteJsonInput() { exact("py","{\"value\":\""+ "x".repeat(20000)+"\"}") }
 @Test fun nodeGetsCompleteJsonInput() { exact("js","{\"value\":\""+ "x".repeat(20000)+"\"}") }
 @Test fun unicodeQuotesAndNewlinesArePreservedNotShellInterpolated() { exact("sh","😀".repeat(4500)+"'\"\n$"+"(not-executed)") }
 @Test fun existingShortAndExactBoundaryInputsStaySame() { for(size in listOf(0,1,7999,8000))exact("sh","x".repeat(size)) }
 @Test fun actualExecutionBoundaryReceivesFullArgument() = fixture("py"){manager,_ ->runBlocking {
  val input="x".repeat(20000)+"END";var count=0
  val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,method,args ->
   assertEquals("execArgv",method.name);count++;assertTrue((args!![0] as List<*>).last()==input);ExecResult(0,"complete",false)
  } as ITerminalSession
  assertEquals("exit=0\ncomplete",manager.execute("fixture",input,terminal,manager.permissionFingerprint("fixture")))
  assertEquals(1,count)
 }}
 @Test fun staleScriptStillCannotExecute() = fixture("sh"){manager,root ->runBlocking {
  val fingerprint=manager.permissionFingerprint("fixture");root.resolve(".droide/tools/fixture.sh").appendText("changed")
  val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,_,_->throw AssertionError("Must not execute")} as ITerminalSession
  assertThrows(IllegalArgumentException::class.java){runBlocking{manager.execute("fixture","x".repeat(20000),terminal,fingerprint)}}
 }}
 @Test fun backendLaunchFailureIsExplicitAndNotRetriedWithTruncatedInput() = fixture("js"){manager,_ ->runBlocking {
  val input="x".repeat(200000);var count=0
  val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,method,args ->
   assertEquals("execArgv",method.name);count++;assertTrue((args!![0] as List<*>).last()==input);ExecResult(-1,"Argument list too long",false)
  } as ITerminalSession
  assertEquals("exit=-1\nArgument list too long",manager.execute("fixture",input,terminal))
  assertEquals(1,count)
 }}
}
