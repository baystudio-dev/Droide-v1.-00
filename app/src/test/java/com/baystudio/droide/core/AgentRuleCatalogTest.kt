package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRuleCatalogTest {
    @Test fun ordinarySchemaKeepsNamesActivationAndDescriptionsWithoutBodies() = fixture { root, records ->
        rule(records, 0, "---\nname: alpha\ndescription: Description\ntrigger: manual\n---\nPRIVATE_BODY")
        val fn = definition(root, AgentRuleManager(root, source(records)))
        assertEquals(listOf("p0:alpha"), names(fn))
        assertEquals("Load an Agent rule on demand. Available: p0:alpha: [manual] Description", fn.getValue("description").jsonPrimitive.content)
        assertFalse(fn.toString().contains("PRIVATE_BODY"))
    }

    @Test fun catalogDoesNotReadTheRestOfALongInstructionBody() = fixture { root, records ->
        val body = "PRIVATE_START\n" + "x".repeat(11_000) + "\nPRIVATE_END"
        rule(records, 0, "---\nname: one\ndescription: Description\n---\n$body")
        val reads = linkedMapOf<String, Int>()
        val closed = mutableListOf<String>()
        val manager = observed(root, records, reads, closed)
        assertEquals(listOf("p0:one"), names(definition(root, manager)))
        assertTrue("Metadata reads: $reads", reads.values.all { it <= 512 })
        assertEquals(reads.keys.toList(), closed)
        assertEquals(body, manager.load("p0:one")!!.body)
    }

    @Test fun lookaheadReadsOnlySixtyFiveMetadataPrefixes() = fixture { root, records ->
        repeat(70) { n -> rule(records, n, "---\nname: r$n\n---\n" + "x".repeat(11_000)) }
        val reads = linkedMapOf<String, Int>()
        val fn = definition(root, observed(root, records, reads, mutableListOf()))
        assertTrue(fn.getValue("description").jsonPrimitive.content.contains("Additional entries exist"))
        assertNull(fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"])
        assertEquals(65, reads.size)
        assertTrue("Metadata reads: $reads", reads.values.all { it <= 512 })
    }

    @Test fun unicodeWhitespaceAcrossByteBoundaryDoesNotInventAValidRule() = fixture { root, records ->
        val header = "---\nname: blank\n---\n"
        val blank = header + " ".repeat(511 - header.length) + "\u3000".repeat(10)
        rule(records, 0, blank)
        rule(records, 1, "---\nname: valid\n---\nBody")
        val manager = AgentRuleManager(root, source(records))
        assertEquals(listOf("p0:valid"), manager.catalog().map { it.name })
        assertDiagnosticCatalog(definition(root, manager), listOf("p0:valid"))
    }

    @Test fun singleByteCrlfReadsKeepFrontmatterAndExcludeBlankBodies() = fixture { root, records ->
        rule(records, 0, "---\r\nname: renamed\r\ndescription: 日本語🙂\r\ntrigger: glob\r\n---\r\nBody")
        rule(records, 1, "---\r\nname: blank\r\n---\r\n \t\u3000")
        val manager = AgentRuleManager(root, source(records)) { file ->
            object : FilterInputStream(file.inputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
            }
        }
        val fn = definition(root, manager)
        assertEquals(listOf("p0:renamed"), manager.catalog().map { it.name })
        assertDiagnosticCatalog(fn, listOf("p0:renamed"))
        assertTrue(fn.getValue("description").jsonPrimitive.content.contains("[glob] 日本語🙂"))
    }

    @Test fun plainAndMalformedFrontmatterKeepTheLegacyCatalogMeaning() = fixture { root, records ->
        rule(records, 0, "Plain instructions")
        rule(records, 1, "---\nname: ignored\nNo closing delimiter")
        rule(records, 2, "---\n---\nBody")
        rule(records, 3, "   \u3000\t")
        val manager = AgentRuleManager(root, source(records))
        val names = manager.catalog().map { it.name }
        assertEquals(manager.modelVisible().map { it.name }.sorted(), names)
        assertEquals(listOf("p0:r00", "p0:r01", "p0:r02"), names)
        assertDiagnosticCatalog(definition(root, manager), names)
    }

    @Test fun emptyAndDuplicateRulesKeepTheirPriorityBeforeActivationFiltering() = fixture { root, records ->
        rule(records, 0, "---\nname: same\n---\n   ")
        rule(records, 1, "---\nname: same\ntrigger: always\n---\nAlways")
        rule(records, 2, "---\nname: same\ntrigger: model\n---\nDuplicate")
        rule(records, 3, "---\nname: other\n---\nOther")
        val manager = AgentRuleManager(root, source(records))
        assertEquals(listOf("p0:other"), manager.catalog().map { it.name })
        val fn = definition(root, manager)
        assertDiagnosticCatalog(fn, listOf("p0:other"))
        assertTrue(fn.getValue("description").jsonPrimitive.content.contains("duplicate_name"))
    }

    @Test fun catalogReflectsMetadataEditsDeletionAndPluginRevocation() = fixture { root, records ->
        val file = rule(records, 0, "---\nname: before\n---\nBody")
        var enabled = true
        val manager = AgentRuleManager(root, AgentPluginSource { if (enabled) records else emptyList() })
        assertEquals(listOf("p0:before"), names(definition(root, manager)))
        file.writeText("---\nname: after\n---\nNew body")
        assertEquals(listOf("p0:after"), names(definition(root, manager)))
        assertNull(manager.load("p0:before")); assertEquals("New body", manager.load("p0:after")!!.body)
        check(file.delete()); assertTrue(names(definition(root, manager)).isEmpty())
        file.writeText("---\nname: restored\n---\nBody")
        assertEquals(listOf("p0:restored"), names(definition(root, manager)))
        enabled = false; assertTrue(names(definition(root, manager)).isEmpty()); assertNull(manager.load("p0:restored"))
    }

    @Test fun legacyFullBodyApisRemainCompleteAfterCatalogDiscovery() = fixture { root, records ->
        val body = "START\n" + "x".repeat(10_000) + "\nTAIL🙂"
        rule(records, 0, "---\nname: manual\ntrigger: manual\n---\n$body")
        rule(records, 1, "---\nname: always\ntrigger: always\n---\n$body")
        val manager = AgentRuleManager(root, source(records))
        definition(root, manager)
        assertEquals(body, manager.modelVisible().single().body)
        assertEquals(body, manager.alwaysOn().single().body)
        assertTrue(manager.discover().all { it.body == body })
    }

    @Test fun longHeadersAreFullyReadBeforePublishingMetadata() = fixture { root, records ->
        rule(records, 0, "---\ndescription: " + " ".repeat(1_100) + "Long header\nname: declared\n---\nBody")
        val reads = linkedMapOf<String, Int>()
        val fn = definition(root, observed(root, records, reads, mutableListOf()))
        assertEquals(listOf("p0:declared"), names(fn))
        assertTrue(fn.getValue("description").jsonPrimitive.content.contains("Long header"))
        assertTrue(reads.values.single() > 512)
    }

    private fun assertDiagnosticCatalog(fn: JsonObject, names: List<String>) {
        val description = fn.getValue("description").jsonPrimitive.content
        assertTrue(description.contains("Rule discovery incomplete"))
        assertTrue(description.contains("invalid_rule"))
        names.forEach { assertTrue(description.contains("$it:")) }
        assertNull(fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"])
    }

    private fun observed(root: File, records: List<AgentPluginContributionRoot>, reads: MutableMap<String, Int>, closed: MutableList<String>) =
        AgentRuleManager(root, source(records)) { file -> object : FilterInputStream(file.inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                if (it > 0) reads[file.path] = (reads[file.path] ?: 0) + it
            }
            override fun close() { super.close(); closed += file.path }
        } }
    private fun names(fn: JsonObject) = fn.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject["enum"]
        ?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun definition(root: File, manager: AgentRuleManager) = AgentTools.definitionsWithCustom(SkillManager(root), CustomToolManager(root), rules = manager)
        .map { it.jsonObject.getValue("function").jsonObject }.single { it["name"] == JsonPrimitive("rule") }
    private fun source(records: List<AgentPluginContributionRoot>) = AgentPluginSource { records }
    private fun rule(records: List<AgentPluginContributionRoot>, n: Int, text: String) = records[n / 32].root.resolve("rules/r%02d.md".format(n % 32)).apply { writeText(text) }
    private fun fixture(block: (File, List<AgentPluginContributionRoot>) -> Unit) {
        val root = Files.createTempDirectory("droide-rule-catalog-").toFile()
        try {
            val records = (0..2).map { n ->
                val dir = root.resolve("plugin$n").apply { mkdir(); resolve("rules").mkdir() }
                AgentPluginContributionRoot("p$n", "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))
            }
            block(root, records)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
