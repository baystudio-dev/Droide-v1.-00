package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class AgentPluginSchemaEnvelopeTest {
    private fun fixture(schema:JsonElement?,block:(AgentPluginToolManager,File)->Unit) {
        val root=Files.createTempDirectory("plugin-schema-envelope").toFile()
        try {
            val dir=File(root,"plugin").apply { mkdirs() }
            val file=File(dir,"tools.json")
            fun tool(name:String,s:JsonElement?)=buildJsonObject {
                put("name",name);putJsonArray("command") { add("inspect") }
                if(s!=null) put("inputSchema",s)
            }
            file.writeText(buildJsonObject { putJsonArray("tools") { add(tool("target",schema));add(tool("sibling",buildJsonObject {})) } }.toString())
            val plugin=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,dir,"fixture-sha")
            block(AgentPluginToolManager(root,AgentPluginSource { listOf(plugin) }),file)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    private fun rejects(schema:JsonElement)=fixture(schema) { m,_ -> assertEquals(listOf("sibling"),m.discover().map { it.name }) }
    @Test fun arraySchemaIsNotReplacedWithPermissiveObject() { rejects(JsonArray(emptyList())) }
    @Test fun stringSchemaIsNotReplacedWithPermissiveObject() { rejects(JsonPrimitive("object")) }
    @Test fun nullSchemaIsNotReplacedWithPermissiveObject() { rejects(JsonNull) }
    @Test fun numericSchemaIsNotReplacedWithPermissiveObject() { rejects(JsonPrimitive(7)) }
    @Test fun unsupportedBooleanSchemaIsNotReplacedWithPermissiveObject() { rejects(JsonPrimitive(false));rejects(JsonPrimitive(true)) }
    @Test fun absentSchemaKeepsExistingObjectDefault()=fixture(null) { m,_ ->
        val def=m.discover().first { it.name=="target" }
        assertEquals(buildJsonObject { put("type","object") },def.inputSchema)
        m.permissionResource(def,buildJsonObject { put("anything",42) })
    }
    @Test fun explicitEmptyObjectSchemaRemainsSupported()=fixture(buildJsonObject {}) { m,_ ->
        val def=m.discover().first { it.name=="target" }
        assertEquals(buildJsonObject {},def.inputSchema)
        m.permissionResource(def,buildJsonObject { put("anything",42) })
    }
    @Test fun validObjectSchemaIsPreservedAndEnforced() {
        val schema=Json.parseToJsonElement("""{"type":"object","required":["count"],"properties":{"count":{"type":"integer"}},"additionalProperties":false}""")
        fixture(schema) { m,_ ->
            val def=m.discover().first { it.name=="target" };assertEquals(schema,def.inputSchema)
            m.permissionResource(def,buildJsonObject { put("count",1) })
            assertThrows(IllegalArgumentException::class.java) { m.permissionResource(def,buildJsonObject { put("other",1) }) }
        }
    }
    private class RecordingTerminal:ITerminalSession {
        override val output=MutableStateFlow("")
        var calls=0
        override fun start() {}
        override fun send(cmd:String) {}
        override suspend fun execOnce(cmd:String,timeoutMs:Long):ExecResult=error("unexpected")
        override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult { calls++;return ExecResult(0,"ok",false) }
        override fun interrupt() {}
        override fun clear() {}
        override fun destroy() {}
    }
    @Test fun malformedReplacementCannotReuseApprovedDefaultSchema()=fixture(null) { m,file ->
        val def=m.discover().first { it.name=="target" };val terminal=RecordingTerminal()
        file.writeText("""{"tools":[{"name":"target","command":["inspect"],"inputSchema":null}]}""")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { m.execute(def,buildJsonObject {},terminal) } }
        assertEquals(0,terminal.calls)
    }
}
