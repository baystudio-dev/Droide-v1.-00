package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real application host and captured Codex metadata; fake process and workspace only. */
class WorkspaceAcpCodexCollaborationTest {
    private class Peer : AutoCloseable {
        private val alive = AtomicBoolean(true)
        private val serverOutput = PipedOutputStream()
        private val clientOutput = PipedInputStream(serverOutput, 16_384)
        private val captured = ByteArrayOutputStream()
        val methods = CopyOnWriteArrayList<String>()
        val requestedModes = CopyOnWriteArrayList<String>()
        val modeRequested = CompletableDeferred<JsonObject>()
        var holdMode = false
        var rejectMode = false
        var reportWrongMode = false
        var modeResultOverride: JsonElement? = null
        var current = "agent"
        var collaboration = "plan"
        var omitCollaboration = false
        var responseApprovalOverride: String? = null
        var responseApprovalConfigId: String? = null
        var responseRemoveApprovalChoice = false
        private var applyResponseChanges = false
        val requestedConfigs = CopyOnWriteArrayList<String>()
        private val capturedConfiguration = requireNotNull(javaClass.getResourceAsStream("/agent-acp/codex-session.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        var initializeParams: JsonObject? = null
        private fun configuration(): JsonObject = JsonObject(capturedConfiguration.toMutableMap().apply {
            put("sessionId", JsonPrimitive("mode_fixture"))
            put("configOptions", JsonArray(capturedConfiguration.getValue("configOptions").jsonArray.mapNotNull { raw ->
                val item = raw.jsonObject
                val id = item.getValue("id").jsonPrimitive.content
                if (id == "collaboration_mode" && omitCollaboration) null
                else JsonObject(item.toMutableMap().apply {
                    when (id) {
                        "mode" -> {
                            put("currentValue", JsonPrimitive(current))
                            if (applyResponseChanges) {
                                responseApprovalConfigId?.let { put("id", JsonPrimitive(it)) }
                                if (responseRemoveApprovalChoice) put("options", JsonArray(
                                    item.getValue("options").jsonArray.filter {
                                        it.jsonObject.getValue("value").jsonPrimitive.content != current
                                    }))
                            }
                        }
                        "collaboration_mode" -> put("currentValue", JsonPrimitive(collaboration))
                    }
                })
            }))
        })
        private val clientInput = object : OutputStream() {
            @Synchronized override fun write(value: Int) { captured.write(value) }
            @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { captured.write(bytes, offset, length) }
            @Synchronized override fun flush() {
                val frames = captured.toString("UTF-8")
                if (frames.isEmpty()) return
                check(frames.endsWith('\n')); captured.reset()
                frames.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    when (method) {
                        "initialize" -> {
                            initializeParams = request.getValue("params").jsonObject
                            respond(request, buildJsonObject { put("protocolVersion", 1); put("agentCapabilities", buildJsonObject { put("loadSession", true) }) })
                        }
                        "session/new", "session/load" -> respond(request, configuration())
                        "session/set_config_option", "session/set_mode" -> {
                            val params = request.getValue("params").jsonObject
                            requestedConfigs += (params["configId"]?.jsonPrimitive?.content ?: "legacy")
                            requestedModes += params.getValue(if (method == "session/set_mode") "modeId" else "value").jsonPrimitive.content
                            modeRequested.complete(request)
                            if (!holdMode) finishMode(request)
                        }
                        "session/prompt" -> respond(request, buildJsonObject { put("stopReason", "end_turn") })
                        "session/cancel" -> Unit
                        else -> error("Unexpected fake request: $method")
                    }
                }
            }
        }
        val process = object : HostedStdioProcess {
            override val stdin = clientInput
            override val stdout = clientOutput
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() { this@Peer.close() }
        }
        fun finishMode(request: JsonObject) {
            if (modeResultOverride != null) emit(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", modeResultOverride!!)
            }) else if (rejectMode) emit(buildJsonObject {
                put("jsonrpc", "2.0"); put("id", request.getValue("id"))
                put("error", buildJsonObject { put("code", -32602); put("message", "Mode rejected") })
            }) else {
                if (!reportWrongMode) {
                    val params = request.getValue("params").jsonObject
                    val value = (params["modeId"] ?: params.getValue("value")).jsonPrimitive.content
                    if (params["configId"]?.jsonPrimitive?.content == "collaboration_mode") collaboration = value
                    else current = value
                }
                applyResponseChanges = true
                responseApprovalOverride?.let { current = it }
                respond(request, configuration())
            }
        }
        private fun respond(request: JsonObject, result: JsonObject) = emit(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result)
        })
        @Synchronized private fun emit(value: JsonObject) {
            serverOutput.write((value.toString() + "\n").toByteArray()); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                runCatching { serverOutput.close() }; runCatching { clientOutput.close() }
            }
        }
    }

    private fun fixture(block: suspend CoroutineScope.(WorkspaceAgentPluginHost, Peer) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-mode-boundary-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer()
        val processes = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                check(argv == listOf("/fixture/codex-acp"))
                currentCoroutineContext()[SessionHostedProcessOwner]?.retain(peer.process)
                return peer.process
            }
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "fixture-checksum"
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> = emptyMap()
            override fun requirePath(path: String) { check(path == root.canonicalPath) }
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
                check(command.contains("fixture-checksum"))
                return BridgeShellResult(0, "", "")
            }
            override suspend fun pull(path: String, output: File) = error("No transfer permitted")
            override suspend fun push(input: File, path: String) = error("No transfer permitted")
        }
        val host = WorkspaceAgentPluginHost(root, File(root, "cache"), FileRepository(root), processes,
            ApprovalManager(), PermissionEngine(), scope, io)
        try { withTimeout(7_000) { block(host, peer) } }
        finally {
            host.closeAndAwait(); peer.close()
            withTimeout(2_000) { scope.coroutineContext.job.cancelAndJoin() }
            root.deleteRecursively()
            assertTrue("Uncaught host/transport failure: $uncaught", uncaught.isEmpty())
        }
    }

    private suspend fun WorkspaceAgentPluginHost.turn(mode: WorkspaceAgentMode, resume: String? = null) =
        runMediaTurn("codex", "Audit the fixture", emptyList(), null, mode, resume, {})

    @Test(timeout = 12_000) fun loadedNativePlanMustLeaveCollaborationPlanBeforeBuild() = fixture { host, peer ->
        host.turn(WorkspaceAgentMode.EDIT, "mode_fixture")
        assertEquals(listOf("collaboration_mode"), peer.requestedConfigs)
        assertEquals(listOf("default"), peer.requestedModes)
        assertEquals("agent", peer.current)
        assertEquals("default", peer.collaboration)
        assertEquals(1, peer.methods.count { it == "session/load" })
        assertEquals(1, peer.methods.count { it == "session/prompt" })
    }

    @Test(timeout = 12_000) fun planThenBuildOnlyChangesCollaborationAndKeepsAdvertisedTools() = fixture { host, peer ->
        peer.collaboration = "default"
        host.turn(WorkspaceAgentMode.PLAN)
        host.turn(WorkspaceAgentMode.EDIT)
        assertEquals(listOf("collaboration_mode", "collaboration_mode"), peer.requestedConfigs)
        assertEquals(listOf("plan", "default"), peer.requestedModes)
        assertEquals("agent", peer.current)
        assertEquals(1, peer.methods.count { it == "session/new" })
        assertEquals(2, peer.methods.count { it == "session/prompt" })
        val caps = peer.initializeParams!!.getValue("clientCapabilities").jsonObject
        assertTrue(caps.getValue("terminal").jsonPrimitive.boolean)
        assertTrue(caps.getValue("fs").jsonObject.getValue("readTextFile").jsonPrimitive.boolean)
        assertTrue(caps.getValue("fs").jsonObject.getValue("writeTextFile").jsonPrimitive.boolean)
    }

    @Test(timeout = 12_000) fun buildAuditPreservesProviderReadOnlyApprovalAndExitsOnlyPlan() = fixture { host, peer ->
        peer.current = "read-only"
        host.turn(WorkspaceAgentMode.EDIT)
        assertEquals("read-only", peer.current)
        assertEquals("default", peer.collaboration)
        assertEquals(listOf("collaboration_mode"), peer.requestedConfigs)
        assertEquals(1, peer.methods.count { it == "session/prompt" })
    }

    @Test(timeout = 12_000) fun alreadyDefaultBuildAuditSendsNoModeRequest() = fixture { host, peer ->
        peer.collaboration = "default"
        host.turn(WorkspaceAgentMode.EDIT)
        assertTrue(peer.requestedConfigs.isEmpty())
        assertEquals("agent", peer.current)
        assertEquals("default", peer.collaboration)
    }

    @Test(timeout = 12_000) fun contradictoryCollaborationResponseRetiresWithoutPrompt() = fixture { host, peer ->
        peer.reportWrongMode = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun missingCollaborationResponseRetiresWithoutPrompt() = fixture { host, peer ->
        peer.modeResultOverride = buildJsonObject { put("configOptions", JsonArray(emptyList())) }
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun rejectedCollaborationRequestRetiresWithoutPrompt() = fixture { host, peer ->
        peer.rejectMode = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun stopDuringCollaborationTransitionCannotAdmitBuild() = fixture { host, peer ->
        peer.holdMode = true
        val turn = async { runCatching { host.turn(WorkspaceAgentMode.EDIT) } }
        val request = peer.modeRequested.await()
        host.cancel("codex", "mode_fixture")
        peer.finishMode(request)
        assertTrue(turn.await().isFailure)
        assertFalse("session/prompt" in peer.methods)
    }

    @Test fun capturedCollaborationChangeIsNotLostByConfigurationProjection() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/agent-acp/codex-session.json"))
            .bufferedReader().use { it.readText() }
        val normal = WorkspaceAgentSessionConfigurations.merge(Json.parseToJsonElement(raw).jsonObject)
        val planning = WorkspaceAgentSessionConfigurations.merge(Json.parseToJsonElement(
            raw.replace("\"currentValue\": \"default\"", "\"currentValue\": \"plan\"")).jsonObject)
        assertNotEquals(normal, planning)
        assertEquals(normal.currentModeId, planning.currentModeId)
    }

    @Test(timeout = 12_000) fun unexpectedApprovalChangeCannotConfirmCollaborationTransition() = fixture { host, peer ->
        peer.responseApprovalOverride = "agent-full-access"
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    private fun capturedConfiguration(): WorkspaceAgentSessionConfiguration = requireNotNull(
        javaClass.getResourceAsStream("/agent-acp/codex-session.json"))
        .bufferedReader().use { WorkspaceAgentSessionConfigurations.merge(Json.parseToJsonElement(it.readText()).jsonObject) }

    private fun selector() = WorkspaceAgentPluginCatalog.entries.single { it.id == "codex" }.let {
        WorkspaceAgentModeSelection(it.planModeCandidates, it.editModeCandidates,
            it.collaborationPlanModeCandidates, it.collaborationEditModeCandidates)
    }

    @Test fun collaborationUsesAdvertisedOpaqueConfigIdAndPreservesOtherConfiguration() {
        val old = capturedConfiguration().copy(collaborationModeConfigId = "Vendor/Collaboration:MiXeD")
        val selected = selector().resolve(WorkspaceAgentMode.PLAN, old)
        val request = WorkspaceAgentSessionConfigurations.modeRequest("same_session", old, selected.id, selected.collaborationMode)
        assertEquals("session/set_config_option", request.first)
        assertEquals("Vendor/Collaboration:MiXeD", request.second.getValue("configId").jsonPrimitive.content)
        assertEquals("plan", request.second.getValue("value").jsonPrimitive.content)
        assertEquals("same_session", request.second.getValue("sessionId").jsonPrimitive.content)
        assertEquals(old, WorkspaceAgentSessionConfigurations.merge(JsonObject(emptyMap()), old))
    }

    @Test fun disappearingCollaborationAxisCannotFallBackToChangingApproval() {
        val old = capturedConfiguration()
        val removed = WorkspaceAgentSessionConfigurations.merge(buildJsonObject {
            put("configOptions", buildJsonArray { add(buildJsonObject {
                put("id", "mode"); put("type", "select"); put("currentValue", "read-only")
                put("options", buildJsonArray { listOf("read-only", "workspace-write").forEach {
                    add(buildJsonObject { put("value", it) })
                } })
            }) })
        }, old)
        assertTrue(removed.collaborationModeObserved)
        assertNull(removed.collaborationModeConfigId)
        assertTrue(runCatching { selector().resolve(WorkspaceAgentMode.EDIT, removed) }.isFailure)
    }

    @Test fun unsupportedCollaborationValuesFailAndUnmappedAdaptersKeepNativeSemantics() {
        val current = capturedConfiguration()
        assertTrue(runCatching { selector().resolve(WorkspaceAgentMode.PLAN,
            current.copy(collaborationModes = listOf("PLAN", "default"))) }.isFailure)
        val native = current.copy(modes = listOf("default", "plan"), currentModeId = "default")
        val provider = WorkspaceAgentModeSelection(listOf("plan"), listOf("default"))
        val edit = provider.resolve(WorkspaceAgentMode.EDIT, native)
        assertEquals("default", edit.id)
        assertFalse(edit.collaborationMode)
        assertFalse(edit.needsRequest)
        provider.accepted(edit, native)
        val plan = provider.resolve(WorkspaceAgentMode.PLAN, native)
        assertEquals("plan", plan.id)
        assertFalse(plan.collaborationMode)
        assertEquals("mode", WorkspaceAgentSessionConfigurations.modeRequest("kept", native, plan.id,
            plan.collaborationMode).second.getValue("configId").jsonPrimitive.content)
    }

    @Test fun changedCollaborationConfigOrApprovalCannotReuseAdmission() {
        val current = capturedConfiguration()
        val modes = selector()
        val admission = modes.admission(modes.resolve(WorkspaceAgentMode.EDIT, current), current, 10L)
        modes.requireCurrent(admission, current.copy(availableCommands = listOf("help")), 10L)
        assertTrue(runCatching { modes.requireCurrent(admission,
            current.copy(currentCollaborationModeId = "plan"), 10L) }.isFailure)
        assertTrue(runCatching { modes.requireCurrent(admission,
            current.copy(collaborationModeConfigId = "changed"), 10L) }.isFailure)
        assertTrue(runCatching { modes.requireCurrent(admission,
            current.copy(currentModeId = "agent-full-access"), 10L) }.isFailure)
        assertTrue(runCatching { modes.requireCurrent(admission, current, 12L) }.isFailure)
    }

    @Test(timeout = 12_000) fun actualHostRevisionTracksCollaborationAwayAndBack() = fixture { host, peer ->
        peer.collaboration = "default"
        host.prepareSession("codex", null, {})
        // Inspect the real private revision owner, not a duplicated test counter.
        val sessions = WorkspaceAgentPluginHost::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            .get(host) as Map<*, *>
        val live = checkNotNull(sessions["codex"])
        val revision = live.javaClass.getDeclaredField("configurationRevision").apply { isAccessible = true }
        val configuration = live.javaClass.getDeclaredField("configuration").apply { isAccessible = true }
        val replace = live.javaClass.getDeclaredMethod("replaceConfiguration", WorkspaceAgentSessionConfiguration::class.java)
            .apply { isAccessible = true }
        synchronized(live) {
            val original = configuration.get(live) as WorkspaceAgentSessionConfiguration
            val start = revision.getLong(live)
            val modes = selector()
            val admission = modes.admission(modes.resolve(WorkspaceAgentMode.EDIT, original), original, start)
            replace.invoke(live, original.copy(currentCollaborationModeId = "plan"))
            replace.invoke(live, original)
            assertEquals(start + 2L, revision.getLong(live))
            assertTrue(runCatching { modes.requireCurrent(admission, original, revision.getLong(live)) }.isFailure)
        }
    }

    @Test fun malformedConfigurationUpdatesInvalidatePreviouslyConfirmedModes() {
        val current = capturedConfiguration()
        for (bad in listOf(JsonNull, JsonPrimitive(false), JsonObject(emptyMap()))) {
            val changed = WorkspaceAgentSessionConfigurations.update(buildJsonObject {
                put("sessionUpdate", "config_option_update"); put("configOptions", bad)
            }, current)
            assertTrue(changed.collaborationModeObserved)
            assertNull(changed.currentCollaborationModeId)
            assertNull(changed.currentModeId)
            assertTrue(runCatching { selector().resolve(WorkspaceAgentMode.EDIT, changed) }.isFailure)
        }
    }

    @Test(timeout = 12_000) fun replacedApprovalConfigCannotConfirmCollaborationTransition() = fixture { host, peer ->
        peer.responseApprovalConfigId = "another-approval-profile"
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertEquals(listOf("collaboration_mode"), peer.requestedConfigs)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test(timeout = 12_000) fun removedCurrentApprovalChoiceCannotConfirmCollaborationTransition() = fixture { host, peer ->
        peer.responseRemoveApprovalChoice = true
        assertTrue(runCatching { host.turn(WorkspaceAgentMode.EDIT) }.isFailure)
        assertEquals(listOf("collaboration_mode"), peer.requestedConfigs)
        assertFalse("session/prompt" in peer.methods)
        assertFalse(peer.process.isAlive)
    }

    @Test fun collaborationOnlyProviderDoesNotNeedInventedApprovalMetadata() {
        val current = capturedConfiguration().copy(currentModeId = null, modeConfigId = null, modes = emptyList())
        val modes = selector()
        val selected = modes.resolve(WorkspaceAgentMode.EDIT, current)
        assertTrue(selected.collaborationMode)
        assertFalse(selected.needsRequest)
        modes.accepted(selected, current)
        assertNull(selected.approvalModeId)
        assertNull(selected.approvalModeConfigId)
    }

    @Test fun missingConfigurationUpdateCannotKeepStaleModesButEmptyOrdinaryResponseCan() {
        val current = capturedConfiguration()
        assertEquals(current, WorkspaceAgentSessionConfigurations.merge(JsonObject(emptyMap()), current))
        val malformedUpdate = buildJsonObject { put("sessionUpdate", "config_option_update") }
        val changed = WorkspaceAgentSessionConfigurations.update(malformedUpdate, current)
        assertNull(changed.currentCollaborationModeId)
        assertNull(changed.currentModeId)
        assertTrue(changed.collaborationModeObserved)
        assertTrue(runCatching { selector().resolve(WorkspaceAgentMode.EDIT, changed) }.isFailure)
    }
}
