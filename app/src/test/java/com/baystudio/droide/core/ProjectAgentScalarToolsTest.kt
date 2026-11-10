package com.baystudio.droide.core
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class ProjectAgentScalarToolsTest {
 private fun check(header:String,expected:Set<String>?,present:Boolean=true) {
  val root=Files.createTempDirectory("project-tools").toFile()
  try {
   val dir=root.resolve(".droide/agents");dir.mkdirs()
   dir.resolve("worker.md").writeText("---\nname: worker\ndescription: Worker\n"+header+"\n---\nInstructions")
   val manager=AgentProfileManager(root)
   val profile=manager.get("worker")
   if(!present){assertNull(profile);return}
   assertNotNull(profile);profile!!
   assertEquals(expected,profile.tools);assertEquals("Instructions",profile.systemInstructions)
   assertEquals(AgentMode.BUILD,profile.mode);assertTrue(profile.subagent);assertFalse(profile.mainAgent)
   assertEquals(AgentProfileSource.PROJECT,profile.source)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
 @Test fun singleToolIsRestrictionNotInheritance() { check("tools: read_file",setOf("read_file")) }
 @Test fun csvToolsAreRecognized() { check("tools: read_file, search_files",setOf("read_file","search_files")) }
 @Test fun quotedCsvUsesSameNames() { check("tools: \"read_file, search_files\"",setOf("read_file","search_files")) }
 @Test fun duplicateAndPatternNormalizationStayExisting() { check("tools: read_file, read_file, mcp_*",setOf("read_file","mcp_*")) }
 @Test fun invalidScalarDoesNotSilentlyInherit() { check("tools: invalid tool",null,false) }
 @Test fun missingAndEmptyKeepDistinctMeaning() { check("",null);check("tools: []",emptySet());check("tools: \"\"",emptySet());check("tools:",emptySet()) }
 @Test fun listFormsRemainUnchanged() { check("tools: [read_file, search_files]",setOf("read_file","search_files"));check("tools:\n  - read_file",setOf("read_file")) }
 @Test fun scalarStillUsesExistingToolCountLimit() { check("tools: "+(1..97).joinToString(","){"tool_"+it},null,false) }
}
