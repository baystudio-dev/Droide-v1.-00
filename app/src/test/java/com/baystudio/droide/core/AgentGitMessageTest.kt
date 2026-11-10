package com.baystudio.droide.core
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
class AgentGitMessageTest {
 @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
 private suspend fun fixture(block:suspend(java.io.File,AgentTools.Ctx)->Unit){
  val root=Files.createTempDirectory("agent-git-message-").toFile()
  try{
   val c=agentWorkflowContext(root)
   c.git.init();c.git.configureIdentity("Test Developer","test@example.com")
   root.resolve("sample.txt").writeText("initial")
   assertTrue(c.git.commitAll("initial").startsWith("Committed"))
   root.resolve("sample.txt").writeText("changed")
   block(root,c)
  }finally{PathSecurity.deleteTreeNoFollow(root)}
 }
 private suspend fun run(c:AgentTools.Ctx,message:String)=AgentTools.execute("git_commit",buildJsonObject{put("message",message)},c)
 @Test fun overlongMessageCannotSilentlyCommitPrefix()=runBlocking{fixture{root,c->
  val message="Subject\n\n"+"body ".repeat(110)+"\nSigned-off-by: Test Developer <test@example.com>"
  val result=run(c,message)
  assertTrue(result,result.contains("Invalid commit message"))
  Git.open(root).use{g->assertEquals(1,g.log().call().count());assertEquals("initial",g.log().call().first().fullMessage);assertTrue(g.status().call().modified.contains("sample.txt"))}
 }}
 @Test fun splitSurrogateBoundaryCannotCreateCorruptedCommit()=runBlocking{fixture{root,c->
  val result=run(c,"x".repeat(499)+"😀")
  assertTrue(result,result.contains("Invalid commit message"))
  Git.open(root).use{g->assertEquals(1,g.log().call().count())}
 }}
 @Test fun validExactBoundaryAndBodyArePreserved()=runBlocking{fixture{root,c->
  val message="Subject\n\n"+"x".repeat(491)
  assertEquals(500,message.length)
  assertTrue(run(c,message).startsWith("Committed"))
  Git.open(root).use{g->assertEquals(message,g.log().call().first().fullMessage)}
 }}
 @Test fun whitespaceUsesExistingManagerNormalization()=runBlocking{fixture{root,c->
  val message="\n "+"x".repeat(499)+" \n"
  assertTrue(run(c,message).startsWith("Committed"))
  Git.open(root).use{g->assertEquals("x".repeat(499),g.log().call().first().fullMessage)}
 }}
}
