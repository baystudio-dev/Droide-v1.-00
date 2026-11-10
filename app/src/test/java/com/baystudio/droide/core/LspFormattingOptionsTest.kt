package com.baystudio.droide.core
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspFormattingOptionsTest {
    private open class Stub:LspManager {
        override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath:String)=""
        override suspend fun lsp(op:String,path:String,line:Int,col:Int)=""
        var oldCalls=0
        override suspend fun formatDocument(path:String):LspWorkspaceEdit? {oldCalls++;return null}
    }
    @Test fun mappingUsesIndentSizeNotVisualTabWidth() {
        val style=CodeStyleProfile(IndentStyle.SPACES,8,2,4,"test")
        assertEquals(LspFormattingOptions(2,true),LspFormattingOptions.fromCodeStyle(style))
        assertEquals(LspFormattingOptions(2,false),LspFormattingOptions.fromCodeStyle(style.copy(indentStyle=IndentStyle.TABS)))
    }
    @Test fun valuesAreWireNumbersAndBooleans() {
        assertEquals(Json.parseToJsonElement("""{"tabSize":3,"insertSpaces":false}"""),LspFormattingOptions(3,false).toJson())
    }
    @Test fun invalidWidthRejected() {
        assertTrue(runCatching{LspFormattingOptions(0,true)}.isFailure)
        assertTrue(runCatching{LspFormattingOptions(-1,true)}.isFailure)
    }
    @Test fun compatibilityOverloadReachesLegacyManager()=runBlocking {
        val legacy=Stub()
        legacy.formatDocument("a.unknown",LspFormattingOptions(2,true))
        assertEquals(1,legacy.oldCalls)
    }
    @Test fun provisioningPreservesOptionsAndLegacyCalls()=runBlocking {
        var observed:LspFormattingOptions?=null
        val delegate=object:Stub() {
            override suspend fun formatDocument(path:String,options:LspFormattingOptions):LspWorkspaceEdit? {observed=options;return null}
        }
        val wrapper=ProvisioningLspManager(delegate,{error("No discovery for unknown file")})
        val options=LspFormattingOptions(3,false)
        wrapper.formatDocument("a.unknown",options);assertSame(options,observed);assertEquals(0,delegate.oldCalls)
        wrapper.formatDocument("a.unknown");assertEquals(1,delegate.oldCalls)
    }
    @Test fun concurrentCallsKeepTheirOwnImmutableOptions()=runBlocking {
        val seen=java.util.concurrent.CopyOnWriteArrayList<LspFormattingOptions>()
        val barrier=CompletableDeferred<Unit>()
        val delegate=object:Stub() {
            override suspend fun formatDocument(path:String,options:LspFormattingOptions):LspWorkspaceEdit? {
                seen+=options;if(seen.size==2)barrier.complete(Unit);barrier.await();assertTrue(options in seen);return null
            }
        }
        val wrapper=ProvisioningLspManager(delegate,{true})
        val a=LspFormattingOptions(2,true);val b=LspFormattingOptions(3,false)
        withTimeout(2000){awaitAll(async{wrapper.formatDocument("a.unknown",a)},async{wrapper.formatDocument("b.unknown",b)})}
        assertEquals(setOf(a,b),seen.toSet())
    }
}
