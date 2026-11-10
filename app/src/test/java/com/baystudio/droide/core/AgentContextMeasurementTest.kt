package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.assertTrue

/** Reproducible request-component measurements, not provider billing/tokenizer claims. */
class AgentContextMeasurementTest {
    @Test fun measureRequestComponents() {
        val root = Files.createTempDirectory("context-measure").toFile()
        try {
            val definitions = AgentTools.definitionsWithCustom(
                SkillManager(root).withBundled(), CustomToolManager(root),
                ideAvailable = true, lspEditAvailable = true, toolchainAvailable = true,
                capabilityAvailable = true, browserAvailable = true, commandSessionsAvailable = true,
                visionAvailable = true, mediaAvailable = true, generationAvailable = true,
                handbookAvailable = true, authoringAvailable = true, localArchiveAvailable = true,
            )
            val system = AgentRuntimePrompt.compose(AgentMode.BUILD, AgentRequestScope.modeInstruction(AgentMode.BUILD), "", "", false, definitions)
            println("CONTEXT_MEASURE " + buildJsonObject {
                put("scenario", "full_available_builtin_empty_workspace")
                put("systemChars", system.length)
                put("schemaChars", definitions.toString().length)
                put("tools", definitions.size)
                put("estimatedTokens", ContextBudget.estimate(system, emptyList(), definitions, "", "").approximateTokens)
                put("schemas", buildJsonArray {
                    definitions.forEach { element -> add(buildJsonObject {
                        put("name", element.jsonObject.getValue("function").jsonObject.getValue("name"))
                        put("chars", element.toString().length)
                    }) }
                })
            })
            val selection = AgentToolSelection()
            fun measure(name: String, tools: JsonArray): Int {
                val prompt = AgentRuntimePrompt.compose(AgentMode.BUILD, AgentRequestScope.modeInstruction(AgentMode.BUILD), "", "", false, tools)
                val tokens = ContextBudget.estimate(prompt, emptyList(), tools, "", "").approximateTokens
                println("CONTEXT_MEASURE " + buildJsonObject {
                    put("scenario", name); put("systemChars", prompt.length); put("schemaChars", tools.toString().length)
                    put("tools", tools.size); put("estimatedTokens", tokens)
                })
                return tokens
            }
            assertTrue(measure("initial_coding_projected", selection.project(definitions)) < 12_000)
            selection.search(buildJsonObject { put("query", "document"); put("limit", 1) })
            measure("document_loaded", selection.project(definitions))
            val extras = buildJsonArray {
                repeat(80) { n -> add(buildJsonObject {
                    put("type", "function"); put("function", buildJsonObject {
                        put("name", "plugin_$n"); put("description", "Plugin task $n " + "metadata ".repeat(300))
                        put("parameters", buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) })
                    })
                }) }
            }
            val expanded = JsonArray(definitions + extras)
            val many = AgentToolSelection()
            measure("80_plugins_eager_comparison", expanded)
            assertTrue(measure("80_plugins_projected", many.project(expanded)) < 12_000)
            val greeting = AgentRuntimePrompt.compose(AgentMode.BUILD, AgentRequestScope.modeInstruction(AgentMode.BUILD), "", "", false, null, greetingOnly = true)
            println("CONTEXT_MEASURE " + buildJsonObject {
                put("scenario", "initial_greeting_empty_workspace")
                put("systemChars", greeting.length)
                put("estimatedTokens", ContextBudget.estimate(greeting, emptyList(), null, "", "").approximateTokens)
            })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
