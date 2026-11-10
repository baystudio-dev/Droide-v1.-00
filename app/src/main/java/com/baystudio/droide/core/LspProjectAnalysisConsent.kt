package com.baystudio.droide.core

enum class LspProjectAnalysisPurpose { PROJECT_ANALYSIS, ELP_METADATA_INSPECTION }

/** Metadata-observed identity only. No version probe is required to ask permission to probe. */
data class ElpMetadataInspectionDisclosure(val pythonPath: String, val pythonFingerprint: String, val tomlPath: String, val tomlDigest: String) {
    val detail: String get() = "Selected Python: $pythonPath\nEntry fingerprint: $pythonFingerprint\nOrigin: preinstalled, unverified; version is not yet validated.\nConfiguration: $tomlPath\nSHA-256: $tomlDigest\n\nExecute this selected Python with the fixed read-only -I -S stdlib tomllib helper on the captured bytes only. CPython 3.12.x and its stdlib origins must pass validation. Interpreter limit: 5 seconds; complete one-attempt limit including cleanup: 20 seconds. No project imports, child launches or network. This executes selected Python only. It does not enable ELP, OTP, Rebar, project code, dependency/network work or installation. No process remains while waiting for separate project-analysis approval."
}

data class LspProjectAnalysisRequest(
    val serverId: String,
    val workspacePath: String,
    val projectPath: String,
    val commands: List<List<String>>,
    val explanation: String,
    val installationDisclosure: String = "Enabling analysis does not install packages or authorize database migrations or deployment.",

    val executionDisclosure: String? = null,
    val purpose: LspProjectAnalysisPurpose = LspProjectAnalysisPurpose.PROJECT_ANALYSIS,
    val metadataDisclosure: ElpMetadataInspectionDisclosure? = null,
) {
    init { require((purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) == (metadataDisclosure != null)) }
    val summary: String get() = if (purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) "Inspect ELP configuration with selected Python?" else "Enable $serverId project analysis for this workspace session?"
    val detail: String get() = if (purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) requireNotNull(metadataDisclosure).detail else "Workspace: $workspacePath\nProject: $projectPath\nServer: $serverId\n" +
        "Commands: ${commands.joinToString("; ") { it.joinToString(" ") }}\n\n$explanation\n\n" +
        (executionDisclosure?.let { "$it " } ?: "Project code and configuration can execute with the local Ubuntu user's file and network access. ") +
        "Only enable workspaces you trust. This is not a sandbox. The grant stays in memory until you revoke it " +
        "or close/switch this workspace. Revoke project analysis from the editor's Code actions menu. " +
        installationDisclosure
}

enum class LspProjectAnalysisReadiness { ON_DEMAND, PREPARING, READY, FAILED }

data class LspProjectAnalysisStatus(
    val serverId: String,
    val enabled: Boolean,
    val projectPath: String,
    val capabilityNote: String? = null,
    val canRevoke: Boolean = enabled,
    val readiness: LspProjectAnalysisReadiness = LspProjectAnalysisReadiness.ON_DEMAND,
    val readinessDetail: String? = null,
)

/** Message is a bounded client-authored summary, never raw server/tool output. */
class LspProjectAnalysisNotReadyException(
    val readiness: LspProjectAnalysisReadiness,
    message: String,
) : IllegalStateException(message.take(320)) {
    init {
        require(readiness == LspProjectAnalysisReadiness.PREPARING || readiness == LspProjectAnalysisReadiness.FAILED)
    }
}

class LspProjectAnalysisRequiredException(message: String) : IllegalStateException(message)

internal data class LspProjectAnalysisIdentity(
    val workspacePath: String,
    val serverId: String,
    val projectPath: String,
    val configuration: String,
) {
    val group: List<String> get() = listOf(workspacePath, serverId, projectPath)
}

/** Pure, finite in-memory authority. No Agent permission grants or persisted settings. */
internal class LspProjectAnalysisConsent {
    class Ticket internal constructor(internal val runtime: Any, internal val identity: LspProjectAnalysisIdentity, internal val epoch: Long)
    class Permit internal constructor(internal val identity: LspProjectAnalysisIdentity)
    private val lock = Any()
    private val runtime = Any()
    private var closed = false
    private val epochs = mutableMapOf<List<String>, Long>()
    private val configurations = mutableMapOf<List<String>, String>()
    private val grants = mutableMapOf<LspProjectAnalysisIdentity, Permit>()

    /** Only callers observing the current registry configuration may update this identity. */
    fun observe(identity: LspProjectAnalysisIdentity): Permit? = synchronized(lock) {
        if (closed) return@synchronized null
        val group = identity.group
        val previous = configurations.put(group, identity.configuration)
        if (previous != null && previous != identity.configuration) {
            epochs[group] = (epochs[group] ?: 0L) + 1L
            grants.keys.removeAll { it.group == group }
        }
        grants[identity]
    }

    fun begin(identity: LspProjectAnalysisIdentity): Ticket? = synchronized(lock) {
        if (closed) return@synchronized null
        observe(identity)
        Ticket(runtime, identity, epochs[identity.group] ?: 0L)
    }

    fun isCurrent(ticket: Ticket): Boolean = synchronized(lock) {
        !closed && ticket.runtime === runtime && (epochs[ticket.identity.group] ?: 0L) == ticket.epoch &&
            configurations[ticket.identity.group] == ticket.identity.configuration
    }

    fun approve(ticket: Ticket): Permit? = synchronized(lock) {
        if (!isCurrent(ticket)) return@synchronized null
        grants[ticket.identity] ?: Permit(ticket.identity).also { grants[ticket.identity] = it }
    }

    fun isCurrent(permit: Permit): Boolean = synchronized(lock) {
        !closed && grants[permit.identity] === permit &&
            configurations[permit.identity.group] == permit.identity.configuration
    }

    fun revoke(identity: LspProjectAnalysisIdentity) = synchronized(lock) {
        val group = identity.group
        epochs[group] = (epochs[group] ?: 0L) + 1L
        grants.keys.removeAll { it.group == group }
    }

    fun close() = synchronized(lock) {
        closed = true
        grants.clear()
        configurations.clear()
        epochs.clear()
    }
}
