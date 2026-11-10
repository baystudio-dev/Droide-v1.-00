package com.baystudio.droide.core
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class ProjectAgentDescriptionTest {
 private fun profile(header:String):AgentProfile? {
  val root=Files.createTempDirectory("project-description").toFile()
  try{root.resolve(".droide/agents").mkdirs();root.resolve(".droide/agents/custom.md").writeText("---\nname: custom\n"+header+"\n---\nExact instructions")
   return AgentProfileManager(root).get("custom")
  }finally{root.deleteRecursively()}
 }
 @Test fun foldedDescriptionKeepsProjectProfileAvailable(){
  val p=profile("description: >-\n  Review code\n  carefully.\ntools: [read_file]");assertNotNull(p)
  assertEquals("Review code carefully.",p!!.description);assertEquals(setOf("read_file"),p.tools);assertEquals("Exact instructions",p.systemInstructions)
 }
 @Test fun literalDescriptionKeepsNewline(){val p=profile("description: |-\n  Review code\n  carefully.");assertNotNull(p);assertEquals("Review code\ncarefully.",p!!.description)}
 @Test fun ordinaryDescriptionControl(){assertEquals("Simple summary",profile("description: Simple summary")!!.description)}

 @Test fun fieldLookingContentStaysDescription(){val p=profile("description: |-\n  tools: *\n  mode: build\ntools: [read_file]\nmode: review")!!;assertEquals("tools: *\nmode: build",p.description);assertEquals(setOf("read_file"),p.tools);assertEquals(AgentMode.REVIEW,p.mode)}
 @Test fun unknownKeyAfterBlockStillRejected(){assertNull(profile("description: >-\n  summary\nunknown: value"))}
 @Test fun toolValidationAfterBlockStillRuns(){assertNull(profile("description: >-\n  summary\ntools: [bad/tool]"))}
 @Test fun duplicatesRetainSourceOrderLastWins(){
  assertEquals("last",profile("description: first\ndescription: >-\n  last")!!.description)
  assertEquals("last",profile("description: >-\n  first\ndescription: last")!!.description)
  assertEquals("last",profile("description: >-\n  first\ndescription: |-\n  last")!!.description)
 }
 @Test fun indentationAndChomp(){assertEquals("A\n\nB",profile("description: |2+\n  A\n\n  B\n\n")!!.description)}
 @Test fun crlfAndComments(){assertEquals("A B",profile("description: >- # explanation\r\n  A\r\n  B\r\n# end\r\ntools: []")!!.description)}
 @Test fun malformedIndentationStillRejected(){assertNull(profile("description: |9\n  not indented enough"))}
 @Test fun quotedMarkerAndPlainControls(){assertEquals("|",profile("description: \"|\"")!!.description);assertEquals("last",profile("description: first\ndescription: last")!!.description)}
 @Test fun descriptionLimitStillEnforced(){assertNull(profile("description: |-\n  "+"x".repeat(501)))}
}
