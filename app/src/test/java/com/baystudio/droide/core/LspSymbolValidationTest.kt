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
class LspSymbolValidationTest {
    private fun allocate(type: Class<*>): Any {
        val unsafeType=Class.forName("sun.misc.Unsafe")
        val field=unsafeType.getDeclaredField("theUnsafe").also { it.isAccessible=true }
        return unsafeType.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),type)
    }
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).also { it.isAccessible=true }.set(target,value)
    }

    private fun run(range: String, link: Boolean=false, uriMode:Int=0, nesting:Int=0): List<LspSymbol> {
        val root=createTempDirectory("droide-location").toFile()
        try {
            val manager=allocate(ProfessionalLspManager::class.java);field(manager,"root",root.canonicalFile)
            val type=ProfessionalLspManager::class.java.declaredClasses.single {it.simpleName=="ServerState"}
            val state=allocate(type)
            field(state,"spec",LanguageServerSpec("fixture-generic",emptySet(),emptySet(),emptyList(),source="fixture"))
            val method=ProfessionalLspManager::class.java.declaredMethods.single {it.name=="parseSymbols"}.also {it.isAccessible=true}
            fun location(r:String, mode:Int=0)=buildJsonObject {
                put("name","test"); put("kind",12)
                if(mode==0) put(if(link) "selectionRange" else "range",Json.parseToJsonElement(r))
                else put("location",buildJsonObject {
                    put("uri",if(mode==1) JsonObject(emptyMap()) else JsonPrimitive(File(root,"one.txt").toURI().toString()))
                    put("range",Json.parseToJsonElement(r))
                })
            }
            val valid=location("""{"start":{"line":2,"character":3},"end":{"line":2,"character":4}}""")
            var nested=location(range,uriMode)
            repeat(nesting) {
                nested=JsonObject(location(range).toMutableMap().also {it["children"]=JsonArray(listOf(nested))})
            }
            return try { @Suppress("UNCHECKED_CAST")
                (method.invoke(manager,JsonArray(listOf(nested,valid)),"one.txt",state,null) as List<LspSymbol>)
            } catch(e:InvocationTargetException) {throw e.targetException}
        } finally {root.deleteRecursively()}
    }
    private fun omitted(range:String) {
        val result=run(range)
        assertEquals(1,result.size);assertEquals(3,result.single().line);assertEquals(4,result.single().column)
    }
    @Test fun missingRangeOmitted()=omitted("null")
    @Test fun missingStartOmitted()=omitted("""{"end":{"line":0,"character":0}}""")
    @Test fun negativePositionOmitted()=omitted("""{"start":{"line":-1,"character":0},"end":{"line":0,"character":0}}""")
    @Test fun overflowPositionOmitted()=omitted("""{"start":{"line":2147483647,"character":0},"end":{"line":2147483647,"character":0}}""")
    @Test fun objectPositionDoesNotCrashSiblings()=omitted("""{"start":{"line":{},"character":0},"end":{"line":0,"character":0}}""")
    @Test fun quotedCoordinateOmitted()=omitted("""{"start":{"line":"0","character":0},"end":{"line":0,"character":0}}""")
    @Test fun reversedRangeOmitted()=omitted("""{"start":{"line":1,"character":3},"end":{"line":0,"character":0}}""")
    @Test fun absentEndOmitted()=omitted("""{"start":{"line":0,"character":0}}""")
    @Test fun validDocumentSymbolRetainsOneBasedCoordinates() {
        val result=run("""{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}""")
        assertEquals(2,result.size);assertEquals(1,result.first().line);assertEquals(1,result.first().column)
    }
    @Test fun validDocumentSelectionRange() {
        val result=run("""{"start":{"line":1,"character":7},"end":{"line":3,"character":0}}""",true)
        assertEquals(2,result.size);assertEquals(2,result.first().line);assertEquals(8,result.first().column)
    }
    @Test fun malformedWorkspaceUriDoesNotCrashSiblings() {
        assertEquals(1,run("""{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}""",uriMode=1).size)
    }
    @Test fun validWorkspaceSymbolPreserved() {
        val result=run("""{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}""",uriMode=2)
        assertEquals(2,result.size);assertEquals("one.txt",result.first().path)
    }

    @Test fun deepSymbolsRetainExistingBoundAndDisplayDepth() {
        val result=run("""{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}""",nesting=600)
        assertEquals(500,result.size);assertEquals(0,result.first().depth);assertEquals(16,result.last().depth)
    }

}
