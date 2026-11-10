package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Pure metadata/admission tests. These do not start a CLI, provider, process, or network. */
class AcpSessionCommandsTest {
    private fun update(vararg names: String): JsonObject = buildJsonObject {
        put("sessionUpdate", "available_commands_update")
        put("availableCommands", buildJsonArray { names.forEach { add(buildJsonObject { put("name", it) }) } })
    }
    private fun params(id: String, update: JsonObject) = buildJsonObject { put("sessionId", id); put("update", update) }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun initializeMetadataIsInertUntilSessionRequestCaptureStarts() {
        val commands = AcpSessionCommands()
        commands.observe(params("selected", update("premature")))
        commands.beginCapture()
        commands.bind("selected")
        assertTrue(commands.snapshot("selected").isEmpty())
    }

    @Test fun newSessionSelectsOnlyExactFinalIdAndNeverSharesWrongSessionNames() {
        val commands = AcpSessionCommands()
        commands.beginCapture()
        commands.observe(params("other", update("wrong")))
        commands.observe(params("selected", update("compact", "help")))
        assertTrue(commands.snapshot("selected").isEmpty())
        commands.bind("selected")
        assertEquals(listOf("compact", "help"), commands.snapshot("selected"))
        assertTrue(commands.snapshot("other").isEmpty())
        commands.observe(params("other", update("injected")))
        assertEquals(listOf("compact", "help"), commands.snapshot("selected"))
    }

    @Test fun emptySnapshotReplacesCommandsBeforeAndAfterBindWithoutListenerInstallation() {
        val commands = AcpSessionCommands()
        commands.beginCapture()
        commands.observe(params("selected", update("compact")))
        commands.observe(params("selected", update()))
        commands.bind("selected")
        assertTrue(commands.snapshot("selected").isEmpty())
        commands.observe(params("selected", update("help")))
        assertEquals(listOf("help"), commands.snapshot("selected"))
        commands.observe(params("selected", update()))
        assertTrue(commands.snapshot("selected").isEmpty())
    }

    @Test fun loadCaptureAcceptsOnlyRequestedIdAndRejectsFinalIdChange() {
        val commands = AcpSessionCommands()
        commands.beginCapture("restored")
        repeat(10) { commands.observe(params("other-$it", update("wrong"))) }
        commands.observe(params("restored", update("compact")))
        assertTrue(runCatching { commands.bind("changed") }.isFailure)
        commands.bind("restored")
        assertEquals(listOf("compact"), commands.snapshot("restored"))
    }

    @Test fun earlyCandidateIdsAreBoundedAndOverflowFailsClosedWithoutEvictingKnownIds() {
        val commands = AcpSessionCommands()
        commands.beginCapture()
        repeat(AcpSessionCommands.MAX_EARLY_SESSIONS) { commands.observe(params("candidate-$it", update("help-$it"))) }
        commands.observe(params("overflow", update("compact")))
        commands.bind("overflow")
        assertTrue(commands.snapshot("overflow").isEmpty())
        // Bound updates can still supply the exact final session after early capacity exhaustion.
        commands.observe(params("overflow", update("compact")))
        assertEquals(listOf("compact"), commands.snapshot("overflow"))
    }

    @Test fun collectorIgnoresTranscriptToolPermissionAndNonStringSessionIdentity() {
        val commands = AcpSessionCommands()
        commands.beginCapture()
        for (kind in listOf("agent_message_chunk", "agent_thought_chunk", "tool_call", "request_permission", "config_option_update")) {
            commands.observe(params("selected", buildJsonObject { put("sessionUpdate", kind); put("availableCommands", update("wrong")["availableCommands"]!!) }))
        }
        commands.observe(buildJsonObject { put("sessionId", 42); put("update", update("wrong")) })
        commands.bind("selected")
        assertTrue(commands.snapshot("selected").isEmpty())
    }

    @Test fun missingMalformedAndOversizeSnapshotsRevokeInsteadOfRetainingOrTruncatingNames() {
        val bad = listOf(
            json("""{"sessionUpdate":"available_commands_update"}"""),
            json("""{"sessionUpdate":"available_commands_update","availableCommands":false}"""),
            json("""{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"compact"},{"name":42}]}"""),
            update("compact", "bad name"), update("compact", "/already-prefixed"), update("compact", "bad\u0000name"),
            update("compact", "x".repeat(AcpSessionCommands.MAX_COMMAND_NAME_CHARS + 1)),
            update(*(0..AcpSessionCommands.MAX_COMMANDS).map { "command-$it" }.toTypedArray()),
        )
        val commands = AcpSessionCommands()
        commands.bind("selected")
        for (malformed in bad) {
            commands.observe(params("selected", update("compact")))
            commands.observe(params("selected", malformed))
            assertTrue(malformed.toString(), commands.snapshot("selected").isEmpty())
        }
    }

    @Test fun acceptedNamesAreExactDeduplicatedBoundedAndSnapshotsCannotBeMutated() {
        val names = AcpSessionCommands.parseSnapshot(update("compact", "Compact", "git:status", "folder/action", "compact"))!!
        assertEquals(listOf("compact", "Compact", "git:status", "folder/action"), names)
        assertTrue(runCatching { (names as MutableList<String>).add("injected") }.isFailure)
        assertEquals(AcpSessionCommands.MAX_COMMANDS,
            AcpSessionCommands.parseSnapshot(update(*(0 until AcpSessionCommands.MAX_COMMANDS).map { "command-$it" }.toTypedArray()))!!.size)
        assertEquals(listOf("x".repeat(AcpSessionCommands.MAX_COMMAND_NAME_CHARS)),
            AcpSessionCommands.parseSnapshot(update("x".repeat(AcpSessionCommands.MAX_COMMAND_NAME_CHARS))))
    }

    @Test fun bindingCannotBeReusedForAnotherSessionAndOldSnapshotsStayUnchanged() {
        val commands = AcpSessionCommands()
        commands.bind("selected")
        commands.observe(params("selected", update("compact")))
        val old = commands.snapshot("selected")
        commands.observe(params("selected", update("help")))
        assertEquals(listOf("compact"), old)
        assertTrue(runCatching { commands.bind("other") }.isFailure)
        assertTrue(runCatching { commands.beginCapture() }.isFailure)
    }

    @Test fun advertisedNativeCommandIsOneUnwrappedTextBlockWithExactArguments() {
        val raw = "/compact  preserve \"quotes\" and spacing\nsecond argument line\t"
        val content = AcpSessionCommands.nativePrompt(raw, false, listOf("compact"))!!
        assertEquals(1, content.size)
        assertEquals(setOf("type", "text"), content[0].jsonObject.keys)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(raw, content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun unknownPrefixCaseAndOrdinaryTextCannotBecomeNativeCommands() {
        for (raw in listOf("hello", "/unknown", "/compactness", "/Compact", "/workspace/file.kt", " /compact", "//compact", "/compact; other")) {
            assertNull(raw, AcpSessionCommands.nativePrompt(raw, false, listOf("compact")))
        }
        assertNull(AcpSessionCommands.nativePrompt("/compact", false, emptyList()))
        assertNotNull(AcpSessionCommands.nativePrompt("/git:status --short", false, listOf("git:status")))
    }

    @Test fun advertisedCommandWithAnyMediaFailsInsteadOfDroppingItOrWrappingTheCommand() {
        val error = runCatching { AcpSessionCommands.nativePrompt("/compact", true, listOf("compact")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("attachments"))
        assertNull(AcpSessionCommands.nativePrompt("normal message", true, listOf("compact")))
    }

    @Test fun configurationUpdatesReplaceNamesWhileModelAndReasoningMergesPreserveThem() {
        val original = WorkspaceAgentSessionConfiguration(currentModelId = "model-a")
        val named = WorkspaceAgentSessionConfigurations.update(update("compact"), original)
        assertEquals(listOf("compact"), named.availableCommands)
        assertEquals("model-a", named.currentModelId)
        assertEquals(named.availableCommands, WorkspaceAgentSessionConfigurations.merge(json("{}"), named).availableCommands)
        assertEquals(named.availableCommands,
            WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"current_model_update","currentModelId":"model-b"}"""), named).availableCommands)
        assertTrue(WorkspaceAgentSessionConfigurations.update(update(), named).availableCommands.isEmpty())
        assertTrue(WorkspaceAgentSessionConfigurations.update(json("""{"sessionUpdate":"available_commands_update"}"""), named).availableCommands.isEmpty())
    }
}
