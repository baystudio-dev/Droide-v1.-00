package com.baystudio.droide.core
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspCallHierarchyRoundTripTest {
    private fun allocate(type: Class<*>): Any {
        val unsafeType=Class.forName("sun.misc.Unsafe")
        val field=unsafeType.getDeclaredField("theUnsafe").also { it.isAccessible=true }
        return unsafeType.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),type)
    }
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(target,value)
    }

    private fun check(data:String) {
        val root=createTempDirectory("droide-call-roundtrip").toFile()
        try {
            val manager=allocate(ProfessionalLspManager::class.java);field(manager,"root",root.canonicalFile)
            val type=ProfessionalLspManager::class.java.declaredClasses.single {it.simpleName=="ServerState"}
            val state=allocate(type)
            field(state,"spec",LanguageServerSpec("fixture-generic",emptySet(),emptySet(),emptyList(),source="fixture"))
            val input=buildJsonObject {
                put("name","test");put("kind",12);put("uri",File(root,"one.txt").toURI().toString())
                put("range",Json.parseToJsonElement("""{"start":{"line":0,"character":0},"end":{"line":0,"character":4}}"""))
                put("selectionRange",Json.parseToJsonElement("""{"start":{"line":0,"character":0},"end":{"line":0,"character":4}}"""));put("data",buildJsonObject {put("opaque",data)})
            }
            val method=ProfessionalLspManager::class.java.declaredMethods.single {it.name=="parseCallHierarchyItem"}.also {it.isAccessible=true}
            val result=try {method.invoke(manager,input,state) as LspCallHierarchyItem}
                catch(e:InvocationTargetException) {throw e.targetException}
            assertEquals("one.txt",result.path);assertEquals(1,result.line)

            val outgoing=Json.parseToJsonElement(result.rawJson)
            assertEquals(input,outgoing)
        } finally {root.deleteRecursively()}
    }
    @Test fun smallOpaqueDataRoundTrips()=check("small")
    @Test fun largeOpaqueDataRoundTrips()=check("x".repeat(210_000))
    @Test fun escapedAndUnicodeDataRoundTrips()=check(("🙂"+34.toChar()+10.toChar()+92.toChar()).repeat(30_000))
}
