package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class PluginAgentProfileIdentityTest {
    private fun fixture(id:String="p".repeat(40), names:List<String>, block:(AgentProfileManager,File,List<String>)->Unit) {
        val root=Files.createTempDirectory("profile-identity").toFile()
        try {
            val dir=File(root,"plugin").apply { mkdirs() };File(dir,"agents").mkdirs()
            for((index,name) in names.withIndex()) File(dir,"agents/$index.md").writeText("---\nname: $name\ndescription: Profile $name\nmode: explore\ntools: [read, grep]\n---\nInstructions $name")
            val contribution=AgentPluginContributionRoot(id,"Fixture","1","",AgentPluginFormat.DROIDE,dir,"abcdef0123456789")
            block(AgentProfileManager(root,AgentPluginSource { listOf(contribution) }),dir,names)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun profiles(m:AgentProfileManager)=m.discover().filter { it.source==AgentProfileSource.PLUGIN }
    @Test fun longSiblingNamesWithSamePrefixRemainDiscoverable()=fixture(names=listOf("a".repeat(22)+"first","a".repeat(22)+"second")) { m,_,names ->
        val values=profiles(m)
        assertEquals(2,values.size);assertEquals(2,values.map { it.name }.distinct().size)
        assertEquals(names.map { "Instructions $it" }.toSet(),values.map { it.systemInstructions }.toSet())
        assertTrue(values.all { it.name.length<=64 && it.name.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) })
    }
    @Test fun getSelectsEachDistinctInstructionsAndTools()=fixture(names=listOf("a".repeat(22)+"first","a".repeat(22)+"second")) { m,_,_ ->
        val values=profiles(m);assertEquals(2,values.size)
        for(p in values) {
            assertEquals(p,m.get(p.name));assertEquals(AgentMode.EXPLORE,p.mode)
            assertEquals(setOf("read_file","search_files"),p.tools)
            assertTrue(p.subagent);assertFalse(p.mainAgent)
        }
        assertEquals(2,m.subagentSummaries().count { summary -> values.any { it.name==summary.name } })
    }
    @Test fun untruncatedNameKeepsLegacyIdentity()=fixture("fixture",listOf("worker")) { m,_,_ ->
        assertEquals("fixture.worker",profiles(m).single().name)
    }
    @Test fun exact64CharacterIdentityIsNotChanged()=fixture("p".repeat(40),listOf("a".repeat(23))) { m,_,_ ->
        assertEquals("p".repeat(40)+"."+"a".repeat(23),profiles(m).single().name)
    }
    @Test fun identityDoesNotDependOnFileEnumerationOrder()=fixture(names=listOf("a".repeat(22)+"first","a".repeat(22)+"second")) { m,dir,_ ->
        val before=profiles(m).associate { it.systemInstructions to it.name }
        val a=File(dir,"agents/0.md");val b=File(dir,"agents/1.md");val old=a.readText()
        a.writeText(b.readText());b.writeText(old)
        val after=profiles(m).associate { it.systemInstructions to it.name }
        assertEquals(2,after.size);assertEquals(before,after)
    }
    @Test fun changedInstructionsStillRejectStoredRevision()=fixture(names=listOf("a".repeat(22)+"first")) { m,dir,_ ->
        val before=profiles(m).single();assertTrue(before.acceptsStoredRevision(before.revision))
        val file=File(dir,"agents/0.md");file.writeText(file.readText()+"\nChanged")
        val after=profiles(m).single()
        assertEquals(before.name,after.name);assertFalse(after.acceptsStoredRevision(before.revision))
        assertFalse(after.acceptsStoredRevision(""))
    }
}
