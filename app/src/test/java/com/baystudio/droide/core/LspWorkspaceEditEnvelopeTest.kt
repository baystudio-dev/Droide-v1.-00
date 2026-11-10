package com.baystudio.droide.core
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspWorkspaceEditEnvelopeTest {
    private fun allocate(type: Class<*>): Any {
        val unsafeType=Class.forName("sun.misc.Unsafe")
        val field=unsafeType.getDeclaredField("theUnsafe").also { it.isAccessible=true }
        return unsafeType.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),type)
    }
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(target,value)
    }
    private fun run(payload: (String,String)->JsonObject): LspWorkspaceEdit? {
        val root=createTempDirectory("droide-workspace-envelope").toFile()
        try {
            val manager=allocate(ProfessionalLspManager::class.java)
            field(manager,"root",root.canonicalFile)
            val stateType=ProfessionalLspManager::class.java.declaredClasses.single { it.simpleName=="ServerState" }
            val state=allocate(stateType)
            field(state,"spec",LanguageServerSpec("fixture-generic",emptySet(),emptySet(),emptyList(),source="fixture"))
            val method=ProfessionalLspManager::class.java.declaredMethods.single { it.name=="parseWorkspaceEdit" }.also { it.isAccessible=true }
            return try {
                method.invoke(manager,payload(File(root,"one.txt").toURI().toString(),File(root,"two.txt").toURI().toString()),
                    state,mapOf("one.txt" to "abc","two.txt" to "def"),mapOf("one.txt" to 2,"two.txt" to 2),null) as LspWorkspaceEdit?
            } catch (e: InvocationTargetException) { throw e.targetException }
        } finally { root.deleteRecursively() }
    }
    private fun edits()=Json.parseToJsonElement("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}},"newText":"new"}]""")
    private fun doc(uri: String, version: JsonElement = JsonNull)=buildJsonObject {
        put("textDocument",buildJsonObject {put("uri",uri);put("version",version)})
        put("edits",edits())
    }
    private fun rejected(payload:(String,String)->JsonObject) {
        assertThrows(IllegalArgumentException::class.java) { run(payload) }
    }
    @Test fun changesCannotSkipMalformedSibling() = rejected { a,b ->
        buildJsonObject {put("changes",buildJsonObject {put(a,edits());put(b,JsonNull)})}
    }
    @Test fun malformedChangesContainerCannotBeIgnoredBesideValidDocuments() = rejected { a,_ ->
        buildJsonObject {put("changes",JsonArray(emptyList()));put("documentChanges",buildJsonArray {add(doc(a))})}
    }
    @Test fun malformedDocumentChangesContainerCannotBeIgnoredBesideValidChanges() = rejected { a,_ ->
        buildJsonObject {put("changes",buildJsonObject {put(a,edits())});put("documentChanges",JsonObject(emptyMap()))}
    }
    @Test fun nonObjectDocumentCannotBeSkipped() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a));add(JsonNull)})}
    }
    @Test fun missingTextDocumentCannotBeSkipped() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a));add(buildJsonObject {put("edits",edits())})})}
    }
    @Test fun missingUriCannotBeSkipped() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a));add(buildJsonObject {put("textDocument",JsonObject(emptyMap()));put("edits",edits())})})}
    }
    @Test fun missingEditsCannotBeSkipped() = rejected { a,b ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a));add(buildJsonObject {put("textDocument",buildJsonObject {put("uri",b)})})})}
    }
    @Test fun malformedVersionCannotLoseVersionGuard() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a,JsonPrimitive("bad")))})}
    }
    @Test fun numericStringVersionIsNotAnInteger() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a,JsonPrimitive("2")))})}
    }
    @Test fun validMultiFileChangesRemainComplete() {
        val result=run { a,b ->buildJsonObject {put("changes",buildJsonObject {put(a,edits());put(b,edits())})}}!!
        assertEquals(setOf("one.txt","two.txt"),result.edits.map {it.path}.toSet())
    }
    @Test fun validNullVersionRemainsSupported() {
        assertEquals(1,run { a,_ ->buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a))})}}!!.edits.size)
    }
    @Test fun matchingIntegerVersionRemainsGuarded() {
        val result=run { a,_ ->buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a,JsonPrimitive(2)))})}}!!
        assertEquals(2,result.documentVersions["one.txt"])
    }
    @Test fun absentVersionCompatibilityRemainsSupported() {
        val result=run { a,_ ->buildJsonObject {put("documentChanges",buildJsonArray {
            add(buildJsonObject {put("textDocument",buildJsonObject {put("uri",a)});put("edits",edits())})
        })}}!!
        assertEquals(1,result.edits.size);assertTrue(result.documentVersions.isEmpty())
    }
    @Test fun mismatchedIntegerVersionStillRejected() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a,JsonPrimitive(3)))})}
    }
    @Test fun resourceOperationRemainsRejected() = rejected { a,_ ->
        buildJsonObject {put("documentChanges",buildJsonArray {add(doc(a));add(buildJsonObject {put("kind","delete");put("uri",a)})})}
    }
}
