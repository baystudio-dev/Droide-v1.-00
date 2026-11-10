package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExternalIdeMediaTest {
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()
    @Test fun terminalCliAndMcpExposeSameMediaToolsAndNativeDocxRead() = runBlocking {
        ExternalIdeFixture(includeSkills = true, includeMedia = true).use { f ->
            f.root.resolve("input.docx").writeBytes(mediaFixture("sample.docx"))
            val list = f.rpc("tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
            assertTrue(list.any { it.jsonObject["name"] == JsonPrimitive("droide_document") })
            assertTrue(list.any { it.jsonObject["name"] == JsonPrimitive("droide_audio") })
            val arguments = buildJsonObject { put("operation", "read"); put("path", "input.docx") }
            val result = f.rpc("tools/call", buildJsonObject { put("name", "droide_document"); put("arguments", arguments) })["result"]!!.jsonObject
            assertEquals(result.toString(), JsonPrimitive(false), result["isError"]); assertTrue(result.toString().contains("Hello world"))
            val cli = DroideCliTool(unusedIdePackages(), f.binding.workspaceId).executeResult(listOf("ide", "call", "droide_document", arguments.toString()))
            assertEquals(0, cli.exitCode); assertTrue(cli.stdout.contains("Hello world"))
            assertTrue(f.call("droide_skill", buildJsonObject { put("operation", "load"); put("name", "documents") }).contains("Original" , ignoreCase = true))
        }
    }
    @Test fun externalWritesRespectPermissionAndCannotReadOtherConversationBlobs() = runBlocking {
        ExternalIdeFixture(PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "*", PermEffect.DENY))), includeMedia = true).use { f ->
            val write = f.rpc("tools/call", buildJsonObject { put("name", "droide_document"); put("arguments", buildJsonObject {
                put("operation", "create_docx"); put("destination", "out.docx"); put("content", "Hello")
            }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), write["isError"]); assertFalse(f.root.resolve("out.docx").exists())
            val read = f.rpc("tools/call", buildJsonObject { put("name", "droide_document"); put("arguments", buildJsonObject {
                put("operation", "read"); put("path", ".droide/chat-media/blobs/other/original.docx")
            }) })["result"]!!.jsonObject
            assertEquals(JsonPrimitive(true), read["isError"])
        }
    }
}
