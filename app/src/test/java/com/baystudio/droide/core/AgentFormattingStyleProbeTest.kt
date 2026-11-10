package com.baystudio.droide.core
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.Assert.*
class AgentFormattingStyleProbeTest {
 @Test fun explicitProjectStyleReachesAgentFormatting() = check("root = true\n[*]\nindent_style = space\nindent_size = 2\n", "text\n", LspFormattingOptions(2,true))
 @Test fun detectedTabsReachAgentFormatting() = check(null,"first\n\tsecond\n\tthird\n",LspFormattingOptions(4,false))
 @Test fun unchangedDefaultRemainsFourSpaces() = check(null,"text\n",LspFormattingOptions(4,true))
 @Test fun latestUserDefaultsAreFetchedOncePerRequest() = runBlocking {
  fixture { root, files, lsp, captured ->
   var calls=0
   var defaults=CodeStyleDefaults(detectIndentation=false,indentStyle=IndentStyle.TABS,indentSize=3)
   val actions=AgentLspEditActions(files,lsp,EditHistory(files),null) {calls++;defaults}
   runCatching {actions.prepareFormat("a.kt")};assertEquals(LspFormattingOptions(3,false),captured.last())
   defaults=CodeStyleDefaults(detectIndentation=false,indentSize=8)
   runCatching {actions.prepareFormat("a.kt")};assertEquals(LspFormattingOptions(8,true),captured.last());assertEquals(2,calls)
  }
 }
 @Test fun explicitConfigOverridesUserDefaults() = runBlocking {
  fixture {root,files,lsp,captured ->
   root.resolve(".editorconfig").writeText("root=true\n[*]\nindent_style=space\nindent_size=2\n")
   runCatching {AgentLspEditActions(files,lsp,EditHistory(files),null) {CodeStyleDefaults(false,IndentStyle.TABS,8,8)}.prepareFormat("a.kt")}
   assertEquals(LspFormattingOptions(2,true),captured.single())
  }
 }
 @Test fun liveUnsavedBufferWinsOverDiskForDetection() = runBlocking {
  fixture {_,files,lsp,captured ->
   val live=WorkspaceDocumentSnapshot("a.kt","first\n\tsecond\n",true,WorkspaceDocumentKind.TEXT,true,true,1,1,0,0,5)
   val docs=java.lang.reflect.Proxy.newProxyInstance(WorkspaceDocumentAuthority::class.java.classLoader,arrayOf(WorkspaceDocumentAuthority::class.java)) {_,m,_ ->
    if(m.name=="snapshot") live else error("Unexpected document call: ${m.name}")
   } as WorkspaceDocumentAuthority
   runCatching {AgentLspEditActions(files,lsp,EditHistory(files),docs).prepareFormat("a.kt")}
   assertEquals(LspFormattingOptions(4,false),captured.single())
  }
 }
 @Test fun preferenceCancellationNeverDispatchesFormatting() = runBlocking {
  fixture {_,files,lsp,captured ->
   val cancellation=kotlinx.coroutines.CancellationException("preferences cancelled")
   val failure=runCatching {AgentLspEditActions(files,lsp,EditHistory(files),null) {throw cancellation}.prepareFormat("a.kt")}.exceptionOrNull()
   assertSame(cancellation,failure);assertTrue(captured.isEmpty())
  }
 }
 @Test fun missingDocumentNeverDispatchesFormatting() = runBlocking {
  fixture {root,files,lsp,captured ->
   root.resolve("a.kt").delete()
   assertTrue(runCatching {AgentLspEditActions(files,lsp,EditHistory(files),null).prepareFormat("a.kt")}.isFailure)
   assertTrue(captured.isEmpty())
  }
 }
 private suspend fun fixture(block:suspend (java.io.File,FileRepository,LspManager,MutableList<LspFormattingOptions>)->Unit) {
  val root=Files.createTempDirectory("agent-style-controls").toFile()
  try {
   root.resolve("a.kt").writeText("text\n");val captured=mutableListOf<LspFormattingOptions>()
   val lsp=object:LspManager {
    override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
    override suspend fun diagnose(relPath:String)=""
    override suspend fun lsp(op:String,path:String,line:Int,col:Int)=""
    override suspend fun formatDocument(path:String,options:LspFormattingOptions):LspWorkspaceEdit? {captured+=options;return null}
   }
   block(root,FileRepository(root),lsp,captured)
  } finally {root.deleteRecursively()}
 }
 private fun check(config:String?,content:String,expected:LspFormattingOptions) = runBlocking {
  val root=Files.createTempDirectory("agent-style").toFile()
  try {
   root.resolve("a.kt").writeText(content);config?.let {root.resolve(".editorconfig").writeText(it)}
   var captured:LspFormattingOptions?=null
   val lsp=object:LspManager {
    override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
    override suspend fun diagnose(relPath:String)=""
    override suspend fun lsp(op:String,path:String,line:Int,col:Int)=""
    override suspend fun formatDocument(path:String):LspWorkspaceEdit? {captured=LspFormattingOptions(4,true);return null}
    override suspend fun formatDocument(path:String,options:LspFormattingOptions):LspWorkspaceEdit? {captured=options;return null}
   }
   val files=FileRepository(root)
   runCatching {AgentLspEditActions(files,lsp,EditHistory(files),null).prepareFormat("a.kt")}
   assertEquals(expected,captured);assertEquals(content,root.resolve("a.kt").readText())
  } finally {root.deleteRecursively()}
 }
}
