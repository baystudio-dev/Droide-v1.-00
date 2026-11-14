package com.baystudio.droide.core

/** One live native session's reversible Plan excursion. IDs are opaque and case-sensitive. */
internal class WorkspaceAgentModeSelection(
    private val planIds: List<String>,
    private val safeEditIds: List<String>,
    private val collaborationPlanIds: List<String> = emptyList(),
    private val collaborationEditIds: List<String> = emptyList(),
) {
    private var previousEditId: String? = null
    private val supportsCollaborationMode = collaborationPlanIds.isNotEmpty() && collaborationEditIds.isNotEmpty()

    data class Selection(
        val id: String,
        val needsRequest: Boolean,
        val enteringPlanFrom: String? = null,
        val usedSafeFallback: Boolean = false,
        val collaborationMode: Boolean = false,
        val configId: String? = null,
        val approvalModeId: String? = null,
        val approvalModeConfigId: String? = null,
        val nativeDefault: Boolean = false,
    )

    /** A confirmed profile at one observed configuration revision, not a vendor transaction. */
    data class Admission(val id: String, val configurationRevision: Long,
                         val collaborationMode: Boolean = false, val configId: String? = null,
                         val approvalModeId: String? = null, val approvalModeConfigId: String? = null,
                         val nativeDefault: Boolean = false)

    fun resolve(mode: WorkspaceAgentMode, configuration: WorkspaceAgentSessionConfiguration): Selection {
        if (supportsCollaborationMode && configuration.collaborationModeObserved) {
            val ids = if (mode == WorkspaceAgentMode.PLAN) collaborationPlanIds else collaborationEditIds
            val current = configuration.currentCollaborationModeId
            val id = current?.takeIf { it in ids && it in configuration.collaborationModes }
                ?: ids.firstOrNull { it in configuration.collaborationModes }
                ?: error("CLI collaboration mode cannot be confirmed. No prompt was sent.")
            val configId = requireNotNull(configuration.collaborationModeConfigId) {
                "CLI collaboration configuration is unavailable. No prompt was sent."
            }
            check(configuration.currentModeId?.let { it in configuration.modes }
                ?: (configuration.modes.isEmpty() && configuration.modeConfigId == null)) {
                "CLI approval profile cannot be confirmed. No prompt was sent."
            }
            return Selection(id, current != id, collaborationMode = true, configId = configId,
                approvalModeId = configuration.currentModeId, approvalModeConfigId = configuration.modeConfigId)
        }
        // ACP mode selection is optional. A mode-less adapter can still execute an
        // ordinary edit turn using its own native defaults. Never claim Plan or
        // issue a guessed set-mode, and never downgrade observed mode metadata.
        if (mode == WorkspaceAgentMode.EDIT && hasNativeDefaultOnly(configuration)) {
            return Selection("", false, nativeDefault = true)
        }
        val advertised = configuration.modes
        val current = configuration.currentModeId?.takeIf { it in advertised }
        val wanted = when (mode) {
            WorkspaceAgentMode.PLAN -> {
                // Do not switch between two already-active Plan aliases or normalize vendor IDs.
                val id = current?.takeIf { it in planIds }
                    ?: planIds.firstOrNull { it in advertised }
                    ?: error("This CLI has not advertised a supported Plan mode. No prompt was sent.")
                Selection(id, current != id, current?.takeUnless { it in planIds })
            }
            WorkspaceAgentMode.EDIT -> {
                // Keep an existing native approval profile. Edit is not permission to raise it.
                if (current != null && current !in planIds) Selection(current, false)
                else {
                    val retained = previousEditId?.takeIf { it in advertised && it !in planIds }
                    val id = retained ?: safeEditIds.firstOrNull { it in advertised && it !in planIds }
                        ?: error("Build cannot be confirmed: the previous CLI edit profile is unavailable and this adapter has not advertised a verified safe fallback. No prompt was sent.")
                    Selection(id, current != id, usedSafeFallback = retained == null)
                }
            }
        }
        return wanted
    }

    /** Call only after an accepted response, or after verifying an already-active native mode. */
    fun accepted(selection: Selection, configuration: WorkspaceAgentSessionConfiguration) {
        check(if (selection.nativeDefault) hasNativeDefaultOnly(configuration) else matches(selection.id, selection.collaborationMode, selection.configId, selection.approvalModeId, selection.approvalModeConfigId, configuration)) {
            "CLI mode was not confirmed. No prompt was sent."
        }
        selection.enteringPlanFrom?.let { previousEditId = it }
    }

    fun admission(selection: Selection, configuration: WorkspaceAgentSessionConfiguration, revision: Long): Admission {
        accepted(selection, configuration)
        return Admission(selection.id, revision, selection.collaborationMode, selection.configId, selection.approvalModeId, selection.approvalModeConfigId, selection.nativeDefault)
    }

    fun requireCurrent(admission: Admission, configuration: WorkspaceAgentSessionConfiguration, revision: Long) {
        check(admission.configurationRevision == revision &&
            (if (admission.nativeDefault) hasNativeDefaultOnly(configuration) else matches(admission.id, admission.collaborationMode, admission.configId, admission.approvalModeId, admission.approvalModeConfigId, configuration))) {
            "CLI configuration changed before prompt admission. No prompt was sent."
        }
    }
    private fun hasNativeDefaultOnly(configuration: WorkspaceAgentSessionConfiguration): Boolean =
        !configuration.modeCapabilityObserved && configuration.modes.isEmpty() &&
            configuration.currentModeId == null && configuration.modeConfigId == null &&
            !configuration.collaborationModeObserved && configuration.collaborationModes.isEmpty() &&
            configuration.currentCollaborationModeId == null && configuration.collaborationModeConfigId == null

    private fun matches(id: String, collaborationMode: Boolean, configId: String?, approvalModeId: String?, approvalModeConfigId: String?,
                        configuration: WorkspaceAgentSessionConfiguration): Boolean =
        if (collaborationMode) configuration.currentCollaborationModeId == id && id in configuration.collaborationModes &&
            configuration.collaborationModeConfigId == configId && configuration.currentModeId == approvalModeId &&
            configuration.modeConfigId == approvalModeConfigId &&
            (approvalModeId?.let { it in configuration.modes } ?: configuration.modes.isEmpty())
        else (!supportsCollaborationMode || !configuration.collaborationModeObserved) && configuration.currentModeId == id && id in configuration.modes
}
