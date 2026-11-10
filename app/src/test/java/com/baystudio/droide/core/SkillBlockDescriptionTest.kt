package com.baystudio.droide.core
import java.nio.file.Files
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Test
class SkillBlockDescriptionTest {
    private fun skill(header:String, expected:String, crlf:Boolean=false) {
        val root=Files.createTempDirectory("skill-block").toFile()
        try {
            val file=root.resolve(".droide/skills/example/SKILL.md");file.parentFile.mkdirs()
            var text="---\nname: example\n"+header+"\n---\nBody"
            if(crlf) text=text.replace("\n","\r\n")
            file.writeText(text)
            val m=SkillManager(root)
            assertEquals(expected,m.catalog().single().description)
            assertEquals(expected,m.load("example")!!.description)
            assertEquals("Body",m.load("example")!!.body)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun foldedCatalogAndLoadPreserveMeaning() { skill("description: >\n  Use this skill\n  for testing","Use this skill for testing") }
    @Test fun literalCatalogAndLoadPreserveNewlines() { skill("description: |\n  Use this skill\n  for testing","Use this skill\nfor testing") }
    @Test fun foldedBlankAndMoreIndentedLinesRemainMeaningful() {
        skill("description: >-\n  First line\n  continues\n\n  New paragraph\n    code line\n  Last line","First line continues\nNew paragraph\n  code line\nLast line")
    }
    @Test fun explicitIndentAndInlineCommentAreParsed() {
        skill("description: |2- # summary\n  First\n    Indented","First\n  Indented")
        skill("description: >-2\n  First\n  Second","First Second")
    }
    @Test fun crlfBlockDescriptionWorks() { skill("description: >+\n  First\n  Second","First Second",true) }
    @Test fun quotedMarkersAndPlainDescriptionsStayCompatible() {
        skill("description: '>'",">");skill("description: \"|\"","|")
        skill("description: Normal description","Normal description")
        skill("description: >text",">text")
    }
    @Test fun nestedMetadataDoesNotReplaceRootDescription() {
        skill("metadata:\n  description: nested\n  choices: [one, two]\ndescription: >\n  Root\n  description","Root description")
    }
    @Test fun duplicateRootDescriptionKeepsFirstDefinitionCompatibility() {
        skill("description: >\n  First\n  value\ndescription: second","First value")
    }
    @Test fun quotedRootWithNestedOrDuplicateBlockKeepsLegacyFallback() {
        skill("description: \"Normal summary\"\nmetadata:\n  description: |\n    nested","Normal summary")
        skill("description: 'First summary'\ndescription: |\n  duplicate","First summary")
    }
    @Test fun aliasOrCollectionFirstDescriptionKeepsLegacyFallback() {
        skill("anchor: &a root\ndescription: *a\ndescription: |\n  duplicate","*a")
        skill("description: [one]\ndescription: |\n  duplicate","[one]")
    }
    @Test fun malformedBlockFallsBackWithoutLosingSkillOrBody() { skill("description: |9\n  Bad indentation","|9") }
    @Test fun scalarParserPreservesChompingBeforeUiTrimming() {
        assertEquals("line\n",SkillYamlDescription.blockValue("description: |\n  line\n\n"))
        assertEquals("line",SkillYamlDescription.blockValue("description: |-\n  line\n\n"))
        assertEquals("line\n\n",SkillYamlDescription.blockValue("description: |+\n  line\n\n"))
        assertEquals("line next\n",SkillYamlDescription.blockValue("description: >\n  line\n  next\n"))
    }
    @Test fun noAliasExpansionOrObjectConstruction() {
        assertNull(SkillYamlDescription.blockValue("description: !!java/object:java.lang.ProcessBuilder |\n  never\n"))
        assertNull(SkillYamlDescription.blockValue("other: &a x\ndescription: *a\n"))
        assertEquals("Summary\n",SkillYamlDescription.blockValue("other: &a [*a, *a]\ndescription: |\n  Summary\n"))
    }
    @Test fun onlyRootBlockScalarIsSelected() {
        assertNull(SkillYamlDescription.blockValue("metadata:\n  description: |\n    nested\n"))
        assertNull(SkillYamlDescription.blockValue("- description: |\n    list\n"))
        assertNull(SkillYamlDescription.blockValue("description: [one, two]\n"))
    }
    @Test fun catalogStillReadsHeaderOnlyAndBoundsDescription() {
        val root=Files.createTempDirectory("skill-block-io").toFile()
        try {
            val file=root.resolve(".droide/skills/example/SKILL.md");file.parentFile.mkdirs()
            file.writeText("---\nname: example\ndescription: >-\n  A useful\n  skill\n---\n"+"x".repeat(100000))
            var bytes=0
            val m=SkillManager(root,AgentPluginSource.EMPTY) { f -> object:FilterInputStream(f.inputStream()) {
                override fun read(buffer:ByteArray,offset:Int,length:Int)=super.read(buffer,offset,length).also { if(it>0)bytes+=it }
            } }
            assertEquals("A useful skill",m.catalog().single().description);assertTrue(bytes<=512)
            file.writeText("---\nname: example\ndescription: |\n  "+"a".repeat(2000)+"\n---\nBody")
            assertEquals(1024,m.catalog().single().description.length)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
