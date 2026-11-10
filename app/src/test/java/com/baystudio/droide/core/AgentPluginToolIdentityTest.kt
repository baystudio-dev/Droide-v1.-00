package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginToolIdentityTest {
    private fun fixture(block:(File)->Unit) {
        val root=Files.createTempDirectory("plugin-tool-identity").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun plugin(root:File,id:String,names:List<String>):AgentPluginContributionRoot {
        val dir=File(root,id).apply { mkdirs() }
        File(dir,"tools.json").writeText(buildJsonObject { putJsonArray("tools") {
            names.forEach { name -> add(buildJsonObject {
                put("name",name);putJsonArray("command") { add("inspect");add(name) }
            }) }
        } }.toString())
        return AgentPluginContributionRoot(id,"Fixture","1","",AgentPluginFormat.DROIDE,dir,"abcdef0123456789")
    }
    private fun manager(root:File,plugins:List<AgentPluginContributionRoot>)=AgentPluginToolManager(root,AgentPluginSource { plugins })
    @Test fun sameLongToolPrefixDoesNotHideSecondTool()=fixture { root ->
        val names=listOf("a".repeat(26)+"_first","a".repeat(26)+"_second")
        val tools=manager(root,listOf(plugin(root,"fixture",names))).discover()
        assertEquals(names.toSet(),tools.map { it.name }.toSet());assertEquals(2,tools.map { it.functionName }.distinct().size)
        assertTrue(tools.all { it.functionName.length<=64 && it.functionName.matches(Regex("[a-zA-Z0-9_-]+")) })
    }
    @Test fun sameLongPluginPrefixDoesNotHideSecondTool()=fixture { root ->
        val ids=listOf("p".repeat(20)+"first","p".repeat(20)+"second")
        val tools=manager(root,ids.map { plugin(root,it,listOf("read")) }).discover()
        assertEquals(ids.toSet(),tools.map { it.pluginId }.toSet());assertEquals(2,tools.map { it.functionName }.distinct().size)
    }
    @Test fun untruncatedWireNameStaysCompatible()=fixture { root ->
        val tool=manager(root,listOf(plugin(root,"fixture",listOf("read")))).discover().single()
        assertEquals("plugin_fixture_read_abcdef01",tool.functionName)
    }
    @Test fun identitiesDoNotDependOnCatalogOrder()=fixture { root ->
        val names=listOf("a".repeat(26)+"_first","a".repeat(26)+"_second")
        val original=plugin(root,"fixture",names)
        val before=manager(root,listOf(original)).discover().associate { it.name to it.functionName }
        plugin(root,"fixture",names.reversed())
        val after=manager(root,listOf(original)).discover().associate { it.name to it.functionName }
        assertEquals(before,after);assertEquals(2,after.size)
    }
    private class RecordingTerminal:ITerminalSession {
        override val output=MutableStateFlow("")
        val calls=mutableListOf<List<String>>()
        override fun start() {}
        override fun send(cmd:String) {}
        override suspend fun execOnce(cmd:String,timeoutMs:Long):ExecResult=error("unexpected")
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult { calls+=argv;return ExecResult(0,"ok",false) }
        override fun interrupt() {}
        override fun clear() {}
        override fun destroy() {}
    }
    @Test fun bothLongToolsDispatchTheirOwnCommands()=fixture { root -> runBlocking {
        val names=listOf("a".repeat(26)+"_first","a".repeat(26)+"_second")
        val m=manager(root,listOf(plugin(root,"fixture",names)));val terminal=RecordingTerminal()
        val tools=m.discover();assertEquals(2,tools.size)
        for (tool in tools) {
            assertTrue(m.permissionResource(tool,buildJsonObject {}).contains(tool.name))
            m.execute(tool,buildJsonObject {},terminal)
        }
        assertEquals(names,terminal.calls.map { it[1] })
    } }
    @Test fun changedCommandStillInvalidatesApprovedDefinition()=fixture { root -> runBlocking {
        val name="a".repeat(26)+"_first";val p=plugin(root,"fixture",listOf(name));val m=manager(root,listOf(p));val old=m.discover().single()
        File(p.root,"tools.json").writeText("""{"tools":[{"name":"$name","command":["changed"]}]}""")
        val terminal=RecordingTerminal()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { m.execute(old,buildJsonObject {},terminal) } }
        assertTrue(terminal.calls.isEmpty())
    } }
}
