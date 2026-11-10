package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
class LspTomlSnapshotAuthorityTest {
    @Test fun exactDirtySchemaAndRootConfigPrecedence() {
        val root=Files.createTempDirectory("toml-snapshot-").toFile()
        File(root,"taplo.toml").writeText("[formatting]\n")
        val schema=File(root,"schema.json");schema.writeText("{\"type\":\"number\"}")
        val overlay=LspTomlSnapshotAuthority.Overlay("{\"type\":\"string\"}",1)
        val snapshot=LspTomlSnapshotAuthority.capture(root,setOf(schema.path),mapOf(schema.path to overlay))
        assertEquals(File(root,"taplo.toml").path,snapshot.selectedConfigPath)
        assertEquals(overlay.text,String(snapshot.entries.getValue(schema.path).bytes()!!))
        assertTrue(snapshot.entries.getValue(File(root,".taplo.toml").path).optionalConfig)
        val mutable=snapshot.entries.getValue(schema.path).bytes()!!;mutable[0]=0
        assertEquals('{'.code.toByte(),snapshot.entries.getValue(schema.path).bytes()!![0])
        File(root,".taplo.toml").writeText("")
        val next=LspTomlSnapshotAuthority.capture(root,setOf(schema.path),mapOf(schema.path to overlay))
        assertNotEquals(snapshot.generation,next.generation);assertEquals(snapshot.rootIdentity,next.rootIdentity)
        assertEquals(File(root,".taplo.toml").path,next.selectedConfigPath)
        assertTrue(runCatching{LspTomlSnapshotAuthority.capture(root,setOf(File(root,"missing.json").path),emptyMap())}.isFailure)
    }
    @Test fun schemaWalkerPreservesDraftAndSchemaPositionMeaning() {
        for (draft in listOf("04","06","07")) LspTomlSnapshotAuthority.admitSchema("""{"${'$'}schema":"http://json-schema.org/draft-$draft/schema#","definitions":{"A":{"type":"string"}},"properties":{"${'$'}id":{"type":"string"},"field":{"${'$'}ref":"#/definitions/A"}},"enum":[{"${'$'}ref":"https://example.invalid/ignored"}]}""")
        LspTomlSnapshotAuthority.admitSchema("""{"definitions":{"a/b~c":{"type":"string"}},"${'$'}ref":"#/definitions/a~1b~0c"}""")
        LspTomlSnapshotAuthority.admitSchema("""{"${'$'}defs":{"A":{"type":"string"}},"${'$'}ref":"#/${'$'}defs/A"}""")
        for (schema in listOf("""{"${'$'}ref":"#"}""", """{"${'$'}ref":"#/missing"}""", """{"${'$'}ref":"https://example.invalid/a.json"}""", """{"${'$'}ref":"#/a%20b"}""", """{"${'$'}id":"file:///tmp/schema.json"}""", """{"definitions":{"A":{"${'$'}ref":"#/definitions/A"}}}""", """{"properties":{"x":{"${'$'}schema":"http://json-schema.org/draft-07/schema#"}}}""", """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema"}"""))
            assertTrue(schema,runCatching{LspTomlSnapshotAuthority.admitSchema(schema)}.isFailure)
    }
    @Test fun boundedInputsAndSymlinksFailWithoutFallback() {
        val root=Files.createTempDirectory("toml-snapshot-bounds-").toFile()
        val link=File(root,"link.json");Files.createSymbolicLink(link.toPath(),File(root,"missing.json").toPath())
        assertTrue(runCatching{LspTomlSnapshotAuthority.capture(root,setOf(link.path),emptyMap())}.isFailure)
        val tooLarge=LspTomlSnapshotAuthority.Overlay("x".repeat(LspTomlSnapshotAuthority.MAX_FILE_BYTES+1),2)
        assertTrue(runCatching{LspTomlSnapshotAuthority.capture(root,emptySet(),mapOf(File(root,"taplo.toml").path to tooLarge))}.isFailure)
        assertTrue(runCatching{LspTomlSnapshotAuthority.path(root.path,"../escape.json")}.isFailure)
        assertTrue(runCatching{LspTomlSnapshotAuthority.validateReplay((0..32).associate { File(root,"$it.toml").path to LspTomlSnapshotAuthority.Overlay("",it.toLong()) },root.path)}.isFailure)
        val sub=File(root,"sub").apply{mkdir()};val schema=File(sub,"s.json").apply{writeText("true")}
        assertEquals(LspTomlSnapshotAuthority.capture(root,emptySet(),emptyMap()).rootIdentity,LspTomlSnapshotAuthority.capture(root,setOf(schema.path),emptyMap()).rootIdentity)
    }
    @Test fun requiredReplayParentMustExistEvenForUnsavedDocument() {
        val root=Files.createTempDirectory("toml-replay-parent-").toFile()
        val doc=File(root,"missing/a.toml").path
        val overlay=mapOf(doc to LspTomlSnapshotAuthority.Overlay("a=1",1))
        assertTrue(runCatching{LspTomlSnapshotAuthority.capture(root,emptySet(),overlay,setOf(doc))}.isFailure)
        File(root,"missing").mkdir()
        val captured=LspTomlSnapshotAuthority.capture(root,emptySet(),overlay,setOf(doc))
        assertTrue(captured.parents.containsKey(File(root,"missing").path))
        assertEquals(setOf(doc),captured.documentPaths)
    }

}
