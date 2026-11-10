package com.baystudio.droide.core
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class CustomToolIdentityTest {
 private fun fixture(names:List<String>,action:(CustomToolManager)->Unit){
  val root=Files.createTempDirectory("custom-identity").toFile()
  try{root.resolve(".droide/tools").mkdirs();names.forEach{root.resolve(".droide/tools/$it").writeText("# fixture $it")}
   action(CustomToolManager(root))
  }finally{root.deleteRecursively()}
 }
 private fun distinct(names:List<String>)=fixture(names){manager->
  val defs=manager.discover();assertEquals(names.size,defs.size);assertEquals(names.size,defs.map{it.name}.toSet().size)
  for(d in defs){assertTrue(d.name.length<=56);assertTrue(d.name.matches(Regex("[A-Za-z0-9_-]+")));assertEquals(".droide/tools/"+d.script.name,manager.argv(d.name,"exact")!![1]);assertNotNull(manager.permissionFingerprint(d.name))}
 }
 @Test fun extensionSiblingsRemainIndividuallyAddressable(){distinct(listOf("build.sh","build.py","build.js"))}
 @Test fun sanitizationSiblingsRemainIndividuallyAddressable(){distinct(listOf("a b.sh","a_b.sh","a.b.sh"))}
 @Test fun longPrefixSiblingsRemainIndividuallyAddressable(){distinct(listOf("a".repeat(56)+"1.sh","a".repeat(56)+"2.sh"))}
 @Test fun ordinaryNamesStayStable(){fixture(listOf("ordinary.sh")){assertEquals("ordinary",it.discover().single().name)}}

 @Test fun generatedNameCannotShadowLiteralLegacyName(){
  var generated=""
  fixture(listOf("build.sh","build.py")){m->generated=m.discover().first{it.script.extension=="sh"}.name}
  fixture(listOf("build.sh","build.py","$generated.sh")){m->
   val defs=m.discover();assertEquals(3,defs.size);assertEquals(3,defs.map{it.name}.toSet().size)
   assertEquals("$generated.sh",defs.first{it.name==generated}.script.name)
   for(d in defs)assertEquals(".droide/tools/"+d.script.name,m.argv(d.name,"")!![1])
  }
 }
 @Test fun reorderKeepsSameFilenameIdentities(){
  val names=listOf("a b.sh","a_b.sh","a.b.py");var first=emptyMap<String,String>()
  fixture(names){m->first=m.discover().associate{it.script.name to it.name}}
  fixture(names.reversed()){m->assertEquals(first,m.discover().associate{it.script.name to it.name})}
 }
 @Test fun addingAndRemovingSiblingRequiresCatalogRefresh(){
  val root=Files.createTempDirectory("identity-change").toFile()
  try{
   val dir=root.resolve(".droide/tools");dir.mkdirs();dir.resolve("build.sh").writeText("# sh")
   val m=CustomToolManager(root);assertEquals("build",m.discover().single().name)
   dir.resolve("build.py").writeText("# py")
   assertEquals(2,m.discover().size);assertNull(m.argv("build",""))
   dir.resolve("build.sh").delete()
   assertEquals(".droide/tools/build.py",m.argv("build","")!![1])
  }finally{root.deleteRecursively()}
 }
 @Test fun staleFingerprintCannotRunSurvivorAfterCollisionChanges(){
  val root=Files.createTempDirectory("identity-permission").toFile()
  try{
   val dir=root.resolve(".droide/tools");dir.mkdirs();dir.resolve("build.sh").writeText("# sh")
   val m=CustomToolManager(root);val original=m.permissionFingerprint("build")
   dir.resolve("build.py").writeText("# py");dir.resolve("build.sh").delete()
   val terminal=java.lang.reflect.Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,_,_->throw AssertionError("Must not execute")} as ITerminalSession
   assertThrows(IllegalArgumentException::class.java){kotlinx.coroutines.runBlocking{m.execute("build","",terminal,original)}}
  }finally{root.deleteRecursively()}
 }
}
