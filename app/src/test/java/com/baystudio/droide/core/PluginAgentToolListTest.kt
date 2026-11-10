package com.baystudio.droide.core
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class PluginAgentToolListTest {
    private fun check(header:String,expected:Set<String>?,crlf:Boolean=false) {
        val root=Files.createTempDirectory("profile-tools").toFile()
        try {
            val dir=root.resolve("plugin");dir.resolve("agents").mkdirs()
            var text="---\nname: worker\ndescription: Worker\n"+header+"\n---\nInstructions"
            if(crlf)text=text.replace("\n","\r\n")
            dir.resolve("agents/worker.md").writeText(text)
            val source=AgentPluginSource { listOf(AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,dir,"fixture-sha")) }
            val profile=AgentProfileManager(root,source).get("fixture.worker")!!
            assertEquals(expected,profile.tools);assertEquals("Instructions",profile.systemInstructions)
            assertEquals(AgentMode.BUILD,profile.mode);assertTrue(profile.subagent);assertFalse(profile.mainAgent)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun blockSequenceRetainsDeclaredTools() { check("tools:\n  - read\n  - grep",setOf("read_file","search_files")) }
    @Test fun commentsQuotesAndCrlfAreParsedAsYaml() { check("tools: # allowed\n  - 'read' # read only\n  - \"grep\"",setOf("read_file","search_files"),true) }
    @Test fun nestedMetadataIsNotSelectedOverRootSequence() { check("metadata:\n  tools: [bash]\ntools:\n  - read",setOf("read_file")) }
    @Test fun firstRootToolsDefinitionRemainsAuthoritative() {
        check("tools:\n  - read\ntools: [bash]",setOf("read_file"))
        check("tools: [read]\ntools:\n  - bash",setOf("read_file"))
    }
    @Test fun existingInlineCommaAndAbsentSemanticsStayUnchanged() {
        check("tools: [read, grep]",setOf("read_file","search_files"))
        check("tools: read, grep",setOf("read_file","search_files"))
        check("tools: []",emptySet());check("",null)
    }
    @Test fun unrelatedBlockDoesNotReinterpretLegacyInlineOrAliasValue() {
        check("tools: [\"read,grep\"]\nmetadata:\n  tools:\n    - bash",setOf("read_file","search_files"))
        check("anchor: &a read\ntools: *a\ntools:\n  - bash",setOf("*a"))
    }
    @Test fun invalidPatternsRemainFilteredByExistingPolicy() { check("tools:\n  - read\n  - 'invalid tool'\n  - grep",setOf("read_file","search_files")) }
    @Test fun aliasesAndNestedSequencesDoNotExpandTools() {
        check("anchor: &a bash\ntools:\n  - *a",emptySet())
        check("tools:\n  - [bash]",emptySet())
    }
    @Test fun malformedBlockUsesExistingRestrictiveFallback() { check("tools:\n  - read\n broken: [",emptySet()) }
}
