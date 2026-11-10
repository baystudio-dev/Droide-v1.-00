package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

data class ElpObservedFile(val guestPath: String, val kind: String, val identity: ElpFileIdentity? = null,
    val bytes: ByteArray? = null, val directoryIdentity: String? = null)
data class ElpGuestAncestor(val guestPath: String, val directoryIdentity: String, val markers: Map<String, ElpObservedFile>)
data class ElpSelectionInspection(val workspaceRoot: String, val rootPath: String, val guestPath: String,
    val ancestors: List<ElpGuestAncestor>, val tools: ElpToolSelection?, val python: ElpSelectedToolEntry?,
    val observationIdentity: String, val unavailableReasons: List<String> = emptyList(),
    val guestWorkspaceRoot: String = workspaceRoot)

data class ElpMetadataInspectionRequest(val inspection: ElpSelectionInspection, val python: ElpSelectedToolEntry,
    val tomlGuestPath: String, val tomlBytes: ByteArray, val inputSha256: String, val attemptIdentity: String) {
    val identity get() = LspElpLaunchPolicy.sha(listOf(inspection.observationIdentity, python.toString(),
        tomlGuestPath, inputSha256, attemptIdentity, LspElpLaunchPolicy.PROFILE).joinToString("\n").toByteArray())
}
/** A separately disclosed Python-only attempt. It never grants OTP/Rebar or project analysis. */
class ElpMetadataInspectionPermit(val identity: String, val requestIdentity: String, private val current: () -> Boolean) {
    private val claimed = AtomicBoolean(false)
    private val revoked = AtomicBoolean(false)
    val isCurrent get() = !revoked.get() && current()
    fun claim(request: ElpMetadataInspectionRequest) { requireCurrent(request); check(claimed.compareAndSet(false, true)) }
    fun requireCurrent(request: ElpMetadataInspectionRequest) { check(isCurrent && requestIdentity == request.identity) { "ELP metadata inspection authority changed" } }
    fun invalidate() { revoked.set(true) }
}
data class ElpMetadataInspectionResult(val inputSha256: String, val pythonIdentity: String,
    val data: JsonObject, val cleanup: ElpTeardownReceipt)

/** Client supplies this only after its exact final project grant; no version probe is needed to issue it. */
class ElpProjectAnalysisPermit(val identity: String, val observationIdentity: String,
    val selectionIdentity: String, val managedIdentity: String, val needsRebar: Boolean,
    val rebarProfile: String = "test", private val current: () -> Boolean) {
    private val revoked = AtomicBoolean(false)
    val isCurrent get() = !revoked.get() && current()
    fun invalidate() { revoked.set(true) }
    fun requireCurrent(inspection: ElpSelectionInspection, managed: ElpManagedLaunchIdentity) {
        check(isCurrent && observationIdentity == inspection.observationIdentity &&
            selectionIdentity == inspection.tools?.consentIdentity && managedIdentity == managed.consentIdentity)
        require(rebarProfile.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        if (needsRebar) require(inspection.tools?.rebar != null) { "Selected Rebar is unavailable" }
    }
}

internal object ElpMetadataInspection {
    val markerNames = listOf(".elp.toml", ".elp_lint.toml", "rebar.config", "rebar.config.script", "rebar.lock",
        "build_info.json", "src", "include", "test", ".buckconfig", "BUCK", "TARGETS")
    fun inspect(files: ElpGuestFilesystem, workspaceRoot: String, input: String, deadline: ElpDeadline): ElpSelectionInspection {
        require(File(workspaceRoot).canonicalPath == workspaceRoot && File(input).canonicalPath == input)
        require(input == workspaceRoot || input.startsWith("$workspaceRoot/"))
        val reasons = mutableListOf<String>()
        fun selected(role: String): ElpSelectedToolEntry? = try { files.candidate(role, deadline) }
            catch (failure: Exception) { reasons += "$role: ${failure.message?.take(256)}"; null }
        val erl = selected("erl"); val escript = selected("escript"); val rebar = selected("rebar3"); val python = selected("python")
        val tools = if (erl != null && escript != null && erl.installationRoot == escript.installationRoot)
            ElpToolSelection(erl, escript, rebar) else null
        if (tools == null) reasons += "A compatible captured OTP pair is unavailable; no tool was executed"
        val guestInput = files.guestWorkspaceRoot + input.removePrefix(workspaceRoot)
        var cursor = if (files.isDirectory(guestInput)) guestInput else guestInput.substringBeforeLast('/').ifEmpty { "/" }
        val ancestors = mutableListOf<ElpGuestAncestor>(); var total = 0L
        while (true) {
            deadline.check(); require(ancestors.size < 64)
            val observed = markerNames.associateWith { name ->
                files.observe(if (cursor == "/") "/$name" else "$cursor/$name", deadline).also {
                    total += it.bytes?.size ?: 0; require(total <= 8_388_608)
                }
            }
            ancestors += ElpGuestAncestor(cursor, files.directory(cursor).toString(), observed)
            if (cursor == "/") break
            cursor = cursor.substringBeforeLast('/').ifEmpty { "/" }
        }
        val signature = listOf(workspaceRoot, input, guestInput, files.mappingIdentity, tools?.consentIdentity.orEmpty(), python.toString()) +
            ancestors.flatMap { a -> listOf(a.guestPath, a.directoryIdentity) + a.markers.toSortedMap().map { (name, value) ->
                "$name:${value.kind}:${value.identity}:${value.directoryIdentity}"
            } }
        return ElpSelectionInspection(workspaceRoot, input, guestInput, ancestors.toList(), tools, python,
            LspElpLaunchPolicy.sha(signature.joinToString("\n").toByteArray()), reasons.toList(), files.guestWorkspaceRoot)
    }
    fun verifyObservation(files: ElpGuestFilesystem, captured: ElpSelectionInspection, deadline: ElpDeadline) {
        require(inspect(files, captured.workspaceRoot, captured.rootPath, deadline).observationIdentity == captured.observationIdentity) {
            "ELP guest ancestor/configuration/selection observation changed"
        }
    }
    fun validateObserver(request: ElpMetadataInspectionRequest, output: ByteArray, files: ElpGuestFilesystem,
        deadline: ElpDeadline): Pair<JsonObject, String> {
        require(output.size in 1..4_194_304)
        val response = GraphqlWire.strict(output)
        require(response.keys == setOf("contractVersion", "inputSha256", "python", "data"))
        require(response["contractVersion"] == JsonPrimitive(1) && response["inputSha256"] == JsonPrimitive(request.inputSha256))
        val python = response["python"]?.jsonObject ?: error("Missing Python observation")
        require(python.keys == setOf("executable", "version", "stdlibOrigin", "tomllibOrigin"))
        val executable = python.getValue("executable").jsonPrimitive.content
        require(executable in setOf(request.python.file.guestPath, request.python.file.canonicalGuestPath))
        require(python.getValue("version").jsonPrimitive.content.matches(Regex("3\\.12\\.(0|[1-9][0-9]*)")))
        val stdlib = python.getValue("stdlibOrigin").jsonPrimitive.content
        val tomllib = python.getValue("tomllibOrigin").jsonPrimitive.content
        require(stdlib == "${request.python.installationRoot}/lib/python3.12" && tomllib == "$stdlib/tomllib/__init__.py")
        val origins = listOf(files.directory(stdlib).toString(), files.capture(tomllib, 1_048_576, deadline).first.toString(),
            files.capture("$stdlib/tomllib/_parser.py", 1_048_576, deadline).first.toString())
        return (response["data"] as? JsonObject ?: error("TOML root is not a JSON object")) to
            LspElpLaunchPolicy.sha((request.python.toString() + python + origins).toByteArray())
    }
}
