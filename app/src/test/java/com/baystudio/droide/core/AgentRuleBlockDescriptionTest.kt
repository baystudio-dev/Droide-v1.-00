package com.baystudio.droide.core
import java.nio.file.Files
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Test
class AgentRuleBlockDescriptionTest {
    private fun check(header:String,expected:String,crlf:Boolean=false,body:String="Body") {
        val root=Files.createTempDirectory("rule-block").toFile()
        try {
            val dir=root.resolve("plugin").apply { mkdirs() }
            val file=dir.resolve("rules/example.md");file.parentFile.mkdirs()
            var text="---\nname: example\n"+header+"\ntrigger: glob\nglobs: ['*.kt']\n---\n"+body
            if(crlf) text=text.replace("\n","\r\n")
            file.writeText(text)
            val plugin=AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,dir,"abcdef0123456789")
            var readBytes=0
            val m=AgentRuleManager(root,AgentPluginSource { listOf(plugin) }) { f -> object:FilterInputStream(f.inputStream()) {
                override fun read(buffer:ByteArray,offset:Int,length:Int)=super.read(buffer,offset,length).also { if(it>0)readBytes+=it }
            } }
            assertEquals(expected,m.catalog().single().description)
            if(body.length>1000) assertTrue("metadata bytes=$readBytes",readBytes<=512)
            val value=m.load("fixture:example")!!
            assertEquals(expected,value.description);assertEquals(body,value.body)
            assertEquals(AgentPluginRuleActivation.GLOB,value.activation)
            assertEquals(listOf("*.kt"),value.globs)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun foldedRuleDescriptionKeepsMeaning() { check("description: >\n  Use for\n  Kotlin changes","Use for Kotlin changes") }
    @Test fun literalRuleDescriptionKeepsNewlines() { check("description: |\n  First\n  Second","First\nSecond") }
    @Test fun indentChompAndParagraphsUseSharedParser() {
        check("description: >2- # summary\n  First\n  continuation\n\n  Paragraph\n    Code","First continuation\nParagraph\n  Code")
        check("description: |+\n  First\n  Second","First\nSecond")
    }
    @Test fun crlfAndRootMappingDescriptionWork() {
        check("metadata:\n  description: nested\ndescription: >\n  Root\n  summary","Root summary",true)
    }
    @Test fun singleLineQuotedAndDuplicateFallbacksStayCompatible() {
        check("description: 'Ordinary'","Ordinary")
        check("description: '|'\nmetadata:\n  description: |\n    nested","|")
        check("description: [first]\ndescription: |\n  duplicate","[first]")
    }
    @Test fun malformedBlockFallsBackWithoutChangingActivationOrBody() { check("description: |9\n  Wrong indent","|9") }
    @Test fun metadataOnlyReadDoesNotReadLongBody() { check("description: >-\n  Metadata\n  summary","Metadata summary",body="x".repeat(10000)) }
    @Test fun existingDescriptionDisplayLimitIsPreserved() { check("description: |\n  "+"a".repeat(900),"a".repeat(500)) }
}
