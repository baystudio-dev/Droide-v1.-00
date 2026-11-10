package com.baystudio.droide.core
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class LspDiagnosticEvidenceTest {
 @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
 private fun report(message:String)=LspDiagnosticsProtocol.format("main.kt",listOf(LspDiagnostic(line=1,column=1,endLine=1,endColumn=2,message=message,severity=1,source="fixture")))
 @Test fun diagnosticMessageIsNotRuntimeAvailability(){
  for(message in listOf("Package not installed","Symbol unavailable","command not found in this expression","manager unavailable is a string constant","ERROR: missing symbol")){
   val evidence=AgentToolResultSemantics.classify("diagnose",report(message))
   assertEquals(message,AgentToolEvidenceState.SUCCEEDED,evidence.state)
   assertTrue(evidence.operationStarted)
  }
 }
 @Test fun actualDiagnosticPagesDoNotBecomeUnavailable()=runBlocking{
  LspDeliveryFixture().use{f->
   f.text=report("Package not installed. "+"context ".repeat(1000))
   val c=f.context()
   var output=AgentTools.execute("diagnose",lspDeliveryArgs(),c);var pages=0
   while(true){
    assertTrue(output.take(100),output.startsWith("LSP_RESULT_PAGE\n"))
    assertEquals(AgentToolEvidenceState.SUCCEEDED,AgentToolResultSemantics.classify("diagnose",output).state)
    val meta=lspDeliveryMetadata(output);pages++
    if(!meta["more_output"]!!.toString().toBoolean())break
    output=AgentTools.execute("diagnose",lspDeliveryArgs(meta),c)
   }
   assertTrue(pages>1);assertEquals(1,f.calls)
  }
 }
 @Test fun realUnavailableAndExplicitFailuresRemainFailures()=runBlocking{
  for(text in listOf("Language server unavailable for this project","ERROR: failed to query","DENIED: blocked")){
   LspDeliveryFixture().use{f->
    f.text=text+"\n"+"details ".repeat(1000)
    val output=AgentTools.execute("diagnose",lspDeliveryArgs(),f.context())
    assertTrue(AgentToolResultSemantics.classify("diagnose",output).isFailure)
    assertFalse(output.startsWith("LSP_RESULT_PAGE\n"))
   }
  }
 }

 @Test fun diagnosticHeaderIsAnchoredAndOtherToolsKeepTheirSemantics(){
  for(text in listOf("ERROR: Diagnostics for main.kt:\nunavailable","DENIED: Diagnostics for main.kt:\nunavailable","Language server unavailable\nDiagnostics for main.kt:")){
   assertTrue(AgentToolResultSemantics.classify("diagnose",text).isFailure)
  }
  assertEquals(AgentToolEvidenceState.UNAVAILABLE,AgentToolResultSemantics.classify("run_command",report("not installed")).state)
  assertEquals(AgentToolEvidenceState.UNAVAILABLE,AgentToolResultSemantics.classify("diagnose","Diagnostics for main.kt without colon\nunavailable").state)
 }
 @Test fun emptyDiagnosticFormatterMeansRetrievedNotCleanWorkspace(){
  val e=AgentToolResultSemantics.classify("diagnose",LspDiagnosticsProtocol.format("unavailable.kt",emptyList()))
  assertEquals(AgentToolEvidenceState.SUCCEEDED,e.state);assertTrue(e.detail.contains("clean workspace"))
 }
 @Test fun malformedPageCannotMaskAvailabilityFailure(){
  for(text in listOf("LSP_RESULT_PAGE\ninvalid\nLSP_PAGE_TEXT\nunavailable","LSP_RESULT_PAGE\n{}\nLSP_PAGE_TEXT\nunavailable")){
   assertEquals(AgentToolEvidenceState.UNAVAILABLE,AgentToolResultSemantics.classify("lsp",text).state)
  }
 }
}
