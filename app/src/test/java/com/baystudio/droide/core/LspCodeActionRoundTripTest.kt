package com.baystudio.droide.core
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Invokes the actual manager parser, with a non-live generic state fixture.
 * Constructor bypass deliberately avoids unrelated process/native-manager lifecycle.
 */
class LspCodeActionRoundTripTest {
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
                put("title","Refactor test");put("kind","refactor.rewrite")
                put("data",buildJsonObject {put("opaque",data)})
            }
            val method=ProfessionalLspManager::class.java.declaredMethods.single {it.name=="parseCodeAction"}.also {it.isAccessible=true}
            val result=try {method.invoke(manager,input,state,emptyMap<String,String>(),emptyMap<String,Int?>(),null) as LspCodeAction}
                catch(e:InvocationTargetException) {throw e.targetException}
            assertEquals("Refactor test",result.title);assertNull(result.edit)
            // This is the same parsing operation used by resolveCodeAction before dispatch.
            val outgoing=Json.parseToJsonElement(requireNotNull(result.rawJson))
            assertEquals(input,outgoing)
        } finally {root.deleteRecursively()}
    }
    @Test fun smallOpaqueDataRoundTrips()=check("small")
    @Test fun largeOpaqueDataRoundTrips()=check("x".repeat(210_000))
    @Test fun escapedAndUnicodeDataRoundTrips()=check(("🙂"+34.toChar()+10.toChar()+92.toChar()).repeat(30_000))
}
