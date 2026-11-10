package com.baystudio.droide.core
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubagentReportProjectionTest {
 @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
 private suspend fun fixture(result:String="",error:String="",messages:List<AgentMessage> = emptyList(), block:suspend(SubagentManager)->Unit){
  val root=Files.createTempDirectory("subagent-projection-").toFile()
  val c=agentWorkflowContext(root)
  val manager=SubagentManager(c.files,c.terminal,c.git,c.approvals,c.lsp,c.questions,c.perms,root,0,1,onBackgroundCompletion={})
  try{
   SessionManager(root).save(DroideSession("child-report","Child",messages=messages))
   SubagentTaskStore(root).create(SubagentTaskRecord(id="task-report",parentSessionId="parent-report",childSessionId="child-report",
    profile="explore",description="Review source",background=false,delivery=SubagentDeliveryState.DELIVERED,state=SubagentTaskState.IDLE,result=result,error=error))
   block(manager)
  }finally{manager.closeAndJoin();PathSecurity.deleteTreeNoFollow(root)}
 }
 @Test fun publicStatusMarksTruncatedReport()=runBlocking{
  fixture(result="result ".repeat(800)+"FINAL QUALIFICATION"){m->
   val text=m.control("parent-report","status","task-report")
   assertTrue(text,text.contains(SubagentTextProjection.NOTICE))
   assertTrue(text.contains("state=idle"))
   assertFalse(text.contains("FINAL QUALIFICATION"))
  }
 }
 @Test fun publicTranscriptMarksTruncatedMessage()=runBlocking{
  fixture(messages=listOf(AgentMessage("assistant","body ".repeat(700)+"FINAL QUALIFICATION"))){m->
   val text=m.control("parent-report","transcript","task-report")
   assertTrue(text,text.contains(SubagentTextProjection.NOTICE))
   assertTrue(text.startsWith("SUBAGENT_TRANSCRIPT task_id=task-report"))
  }
 }
 @Test fun publicTranscriptMarksTotalWindowCut()=runBlocking{
  fixture(messages=(1..20).map{AgentMessage("assistant","message-"+it+" "+"x".repeat(1100))}){m->
   val text=m.control("parent-report","transcript","task-report")
   assertTrue(text,text.contains(SubagentTextProjection.NOTICE))
   assertTrue(text.length<16500)
  }
 }
 @Test fun publicTranscriptMarksMessageWindow()=runBlocking{
  fixture(messages=(1..70).map{AgentMessage("assistant","message-"+it)}){m->
   val text=m.control("parent-report","transcript","task-report")
   assertTrue(text,text.contains("older messages omitted; showing last 60 of 70 messages"))
   assertFalse(text.contains("ASSISTANT: message-1\n"))
   assertTrue(text.contains("ASSISTANT: message-70"))
  }
 }
 @Test fun publicStatusMarksLongError()=runBlocking{
  fixture(error="problem ".repeat(300)){m->
   assertTrue(m.control("parent-report","status","task-report").contains("\nerror="+SubagentTextProjection.NOTICE))
  }
 }
 @Test fun combinedWindowAndBodyCutKeepBothNotices()=runBlocking{
  fixture(messages=(1..70).map{AgentMessage("assistant","message-"+it+" "+"x".repeat(1100))}){m->
   val text=m.control("parent-report","transcript","task-report")
   assertTrue(text.contains(SubagentTextProjection.NOTICE))
   assertTrue(text.contains("older messages omitted; showing last 60 of 70 messages"))
  }
 }
 @Test fun publicTranscriptMarksStreamDetail()=runBlocking{
  val part=AgentStreamPart("part",1,AgentStreamPartKind.TOOL,AgentStreamPartState.COMPLETED,detail="details ".repeat(250))
  fixture(messages=listOf(AgentMessage("assistant","",streamPart=part))){m->
   val text=m.control("parent-report","transcript","task-report")
   assertTrue(text,text.contains(SubagentTextProjection.NOTICE))
   assertTrue(text.contains("[tool:completed]"))
  }
 }
 @Test fun shortReportAndTranscriptRemainIdentical()=runBlocking{
  fixture(result="Short report",messages=listOf(AgentMessage("assistant","Short report"))){m->
   assertTrue(m.control("parent-report","status","task-report").endsWith("\nresult=Short report"))
   assertEquals("SUBAGENT_TRANSCRIPT task_id=task-report child_session_id=child-report messages=1\nASSISTANT: Short report",m.control("parent-report","transcript","task-report"))
  }
 }
 @Test fun exactBoundaryNoNoticeAndShortIdentity(){
  for(limit in listOf(1200,2400,4000,16000)){
   val text="x".repeat(limit)
   assertSame(text,SubagentTextProjection.bounded(text,limit))
   assertTrue(SubagentTextProjection.bounded(text+"x",limit).startsWith(SubagentTextProjection.NOTICE))
   assertEquals(limit,SubagentTextProjection.bounded(text+"x",limit).length)
  }
 }
 @Test fun truncationDoesNotSplitValidSurrogates(){
  for(limit in listOf(1200,2400,4000,16000)){
   val end=limit-SubagentTextProjection.NOTICE.length
   val value="x".repeat(end-1)+"😀"+"x".repeat(limit)
   val result=SubagentTextProjection.bounded(value,limit)
   assertEquals(limit-1,result.length)
   assertFalse(result.last().isHighSurrogate())
  }
 }
 @Test fun storedProjectionNoticeSurvivesSecondaryViews()=runBlocking{
  val projected=SubagentTextProjection.bounded("x".repeat(17000),16000)
  fixture(result=projected,messages=listOf(AgentMessage("assistant",projected))){m->
   assertTrue(m.control("parent-report","status","task-report").contains(SubagentTextProjection.NOTICE))
   assertTrue(m.control("parent-report","transcript","task-report").contains(SubagentTextProjection.NOTICE))
  }
 }
}
