package com.baystudio.droide.core
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class CustomToolVerifiedDefinitionTest {
 @Test fun executionDoesNotRediscoverAfterFingerprintVerification(){
  val root=Files.createTempDirectory("verified-custom").toFile()
  try{
   val script=root.resolve(".droide/tools/original.sh");script.parentFile.mkdirs();script.writeText("# desc: approved")
   val other=root.resolve(".droide/other.sh");other.writeText("# different content")
   val expected=CustomToolManager(root).permissionFingerprint("original")
   var reads=0
   val manager=CustomToolManager(root){file->
    reads++
    if(reads==2){Files.delete(file.toPath());Files.createSymbolicLink(file.toPath(),other.toPath())}
    ByteArrayInputStream("# desc: approved\n".toByteArray())
   }
   val calls=mutableListOf<List<*>>()
   val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,method,args->
    assertEquals("execArgv",method.name);calls.add(args!![0] as List<*>);ExecResult(0,"ok",false)
   } as ITerminalSession
   runBlocking{manager.execute("original","payload",terminal,expected)}
   assertEquals(listOf("sh",".droide/tools/original.sh","payload"),calls.single())
   assertEquals("Execution should only discover once",1,reads)
  }finally{PathSecurity.deleteTreeNoFollow(root)}
 }

 @Test fun publicArgvAndExecuteRemainEquivalentForAllInterpreters(){
  for(ext in listOf("sh","py","js")){
   val root=Files.createTempDirectory("verified-parity").toFile()
   try{
    val script=root.resolve(".droide/tools/example.$ext");script.parentFile.mkdirs();script.writeText("# fixture")
    val manager=CustomToolManager(root);val input="😀".repeat(5000)+"\nexact"
    val expected=manager.argv("example",input)
    val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,method,args->
     assertEquals("execArgv",method.name);assertEquals(expected,args!![0]);ExecResult(0,"ok",false)
    } as ITerminalSession
    assertEquals("exit=0\nok",runBlocking{manager.execute("example",input,terminal,manager.permissionFingerprint("example"))})
   }finally{PathSecurity.deleteTreeNoFollow(root)}
  }
 }
}
