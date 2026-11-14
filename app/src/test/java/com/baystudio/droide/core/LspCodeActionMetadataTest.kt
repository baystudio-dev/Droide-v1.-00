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
class LspCodeActionMetadataTest {
    private fun allocate(type: Class<*>): Any {
        val unsafeType=Class.forName("sun.misc.Unsafe")
        val field=unsafeType.getDeclaredField("theUnsafe").also { it.isAccessible=true }
        return unsafeType.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),type)
    }
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(target,value)
    }


    private fun parse(items:List<JsonObject>):List<LspCodeAction> {
        val root=createTempDirectory("droide-action-identity").toFile()
        try {
            val manager=allocate(ProfessionalLspManager::class.java);field(manager,"root",root.canonicalFile)
            val type=ProfessionalLspManager::class.java.declaredClasses.single {it.simpleName=="ServerState"}
            val state=allocate(type)
            field(state,"spec",LanguageServerSpec("fixture-generic",emptySet(),emptySet(),emptyList(),source="fixture"))
            val method=ProfessionalLspManager::class.java.declaredMethods.single {it.name=="parseCodeActions"}.also {it.isAccessible=true}
            return try { @Suppress("UNCHECKED_CAST")
                method.invoke(manager,JsonArray(items),state,emptyMap<String,String>(),emptyMap<String,Int?>(),null) as List<LspCodeAction>
            } catch(e:InvocationTargetException) {throw e.targetException}
        } finally {root.deleteRecursively()}
    }
    private fun action(disabled:JsonElement?=null, command:JsonElement?=null)=buildJsonObject {
        put("title","Fix")
        if(disabled!=null) put("disabled",disabled)
        if(command!=null) put("command",command)
    }
    @Test fun malformedDisabledReasonCannotAbortValidSibling() {
        val malformed=action(disabled=buildJsonObject {put("reason",JsonObject(emptyMap()))})
        assertEquals(1,parse(listOf(malformed,action())).size)
    }
    @Test fun malformedCommandTitleCannotAbortValidSibling() {
        val malformed=action(command=buildJsonObject {put("title",JsonObject(emptyMap()));put("command","fix")})
        assertEquals(1,parse(listOf(malformed,action())).size)
    }
    @Test fun malformedDisabledShapeDoesNotBecomeEnabled() {
        assertEquals(1,parse(listOf(action(disabled=JsonPrimitive(true)),action())).size)
    }
    @Test fun validDisabledReasonRetained() {
        val result=parse(listOf(action(disabled=buildJsonObject {put("reason","Unavailable")}))).single()
        assertEquals("Unavailable",result.disabledReason)
    }
    @Test fun validCommandTitleRetained() {
        val result=parse(listOf(action(command=buildJsonObject {put("title","Apply");put("command","fix")}))).single()
        assertEquals("Apply",result.commandTitle)
    }
}
