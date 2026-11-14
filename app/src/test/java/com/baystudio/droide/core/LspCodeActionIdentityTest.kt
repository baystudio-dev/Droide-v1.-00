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
class LspCodeActionIdentityTest {
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
    private fun action(id:Int)=buildJsonObject {
        put("title","Fix import");put("kind","quickfix")
        put("data",buildJsonObject {put("id",id)})
    }
    @Test fun samePresentationDifferentOpaqueDataRetained() {
        val result=parse(listOf(action(1),action(2)))
        assertEquals(2,result.size)
        assertEquals(listOf(action(1),action(2)),result.map {Json.parseToJsonElement(requireNotNull(it.rawJson))})
    }
    @Test fun identicalActionStillDeduplicated() {
        assertEquals(1,parse(listOf(action(1),action(1))).size)
    }
    @Test fun samePresentationDifferentCommandsRetained() {
        fun command(id:String)=buildJsonObject {
            put("title","Fix import");put("kind","quickfix")
            put("command",buildJsonObject {put("title","Apply fix");put("command",id)})
        }
        assertEquals(2,parse(listOf(command("fix.a"),command("fix.b"))).size)
    }
}
