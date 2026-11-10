package com.baystudio.droide.core
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class PluginAgentDescriptionTest {
 private fun check(header:String, expected:String, crlf:Boolean=false) {
  val root=Files.createTempDirectory("profile-description").toFile()
  try {
   val dir=root.resolve("plugin");dir.resolve("agents").mkdirs()
   var text="---\nname: worker\n"+header+"\ntools: [read]\n---\nInstructions"
   if(crlf)text=text.replace("\n","\r\n")
   dir.resolve("agents/worker.md").writeText(text)
   val source=AgentPluginSource { listOf(AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,dir,"fixture-sha")) }
   val manager=AgentProfileManager(root,source)
   val profile=manager.get("fixture.worker")!!
   assertEquals(expected,profile.description)
   assertEquals(expected,manager.subagentSummaries().single{it.name==profile.name}.description)
   assertEquals("Instructions",profile.systemInstructions)
   assertEquals(setOf("read_file"),profile.tools)
   assertEquals(AgentMode.BUILD,profile.mode);assertTrue(profile.subagent);assertFalse(profile.mainAgent)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
 @Test fun foldedDescription() { check("description: >\n  Analyze code\n  and report findings","Analyze code and report findings") }
 @Test fun literalDescription() { check("description: |\n  Analyze code\n  Report findings","Analyze code\nReport findings") }
 @Test fun explicitIndentAndChompCrlf() { check("description: >2-\n  Analyze code\n  Report findings","Analyze code Report findings",true) }
 @Test fun nestedDescriptionDoesNotOverrideRootBlock() { check("metadata:\n  description: Nested\ndescription: >\n  Root text","Root text") }
 @Test fun ordinaryQuotedAndMissingStayUnchanged() {
  check("description: \"Ordinary summary\"","Ordinary summary")
  check("description: '>'",">");check("description: >text",">text");check("","Fixture: worker")
 }
 @Test fun duplicateFirstDefinitionAndInvalidFallbackStayUnchanged() {
  check("description: \"First\"\ndescription: |\n  Later","First")
  check("description: |9\n  Wrong indent","|9")
 }
 @Test fun displayBoundUnchanged() { check("description: >-\n  "+"a".repeat(700),"a".repeat(500)) }
}
