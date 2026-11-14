package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Native Agent consumer of the repository's local-delete authority, never mirror recovery. */
internal object AgentLocalDirectoryRecovery {
    suspend fun execute(
        tool: String,
        args: JsonObject,
        c: AgentTools.Ctx,
        readGate: suspend (String) -> String?,
        editGate: suspend (String, String, String) -> String?,
    ): String {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        require(c.files.root.canonicalFile == c.workDir.canonicalFile) { "Recovery context belongs to another workspace" }
        if (tool == "local_deleted_folders") {
            val action = string(args, "action")
            require(action in setOf("list", "inspect")) { "action must be list or inspect" }
            val token = if (action == "inspect") token(args) else null
            val destination = optionalString(args, "destination")
            if (action == "list") require(args["operation"] == null && args["prepared_sha256"] == null && destination == null) {
                "List accepts only a cursor, not an operation token or destination"
            } else require(args["cursor"] == null) { "Inspect does not accept a list cursor" }
            val resource = token?.let(::retainedPath) ?: LocalDirectoryDeleteCustody.HOME
            readGate(resource)?.let { return it }
            val archiveResource = if (destination != null) c.files.localDirectoryRecovery.archiveReadPath(requireNotNull(token), destination) else null
            archiveResource?.let { readGate(it)?.let { denied -> return denied } }
            caller.ensureActive()
            val recovery = c.files.localDirectoryRecovery
            val result = if (action == "list") {
                val page = recovery.list(optionalString(args, "cursor"))
                buildJsonObject {
                    put("success", true); put("action", action)
                    put("items", JsonArray(page.items.map(::inspection)))
                    put("next_cursor", page.nextCursor?.let(::JsonPrimitive) ?: JsonNull)
                    put("complete", page.complete)
                    put("note", "This is a custody observation. Follow next_cursor until complete; originals may remain mutable and no storage was released.")
                }
            } else buildJsonObject {
                put("success", true); put("action", action)
                put("item", inspection(recovery.inspect(requireNotNull(token))))
                if (destination != null) {
                    val archive = recovery.inspectArchive(token, destination)
                    put("archive", buildJsonObject {
                        put("status", archive.status); put("archive_id", archive.archiveId)
                        put("destination", archive.destination); put("receipt_path", archive.receiptPath)
                        put("bytes", archive.bytes); put("sha256", archive.sha256)
                        put("current_file_reverified", archive.currentFileReverified)
                        put("receipt_durability_reconfirmed", false)
                        put("synchronization_status", "UNKNOWN_NOT_REPLAYED")
                        put("detail", archive.detail + " This read-only inspection performs no new file or metadata durability checks.")
                    })
                }
            }
            caller.ensureActive()
            check(listOfNotNull(resource, archiveResource).all {
                ApprovalPolicyGuard.remainsPermitted(c.perms, "read", it, c.permissionScope)
            }) {
                "Read permission changed during recovery inspection; no result was disclosed"
            }
            return result.toString()
        }

        require(tool == "archive_deleted_folder") { "Unsupported local recovery tool" }
        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): folder archive recovery requires BUILD mode."
        val owner = c.localRecoveryOwner ?: return "ERROR: local folder archive recovery is unavailable in this Agent context"
        val cache = c.localRecoveryCache ?: return "ERROR: local folder archive staging is unavailable in this Agent context"
        require(owner.mode == c.mode && owner.provenance == c.provenance && owner.permissionScope == c.permissionScope) {
            "Recovery request authority belongs to another Agent context"
        }
        val token = token(args)
        val destination = string(args, "destination")
        val sourceResource = retainedPath(token)
        val readResources = linkedSetOf(sourceResource)
        val approvedReads = hashSetOf<String>()
        var custodyTreeApproved = false
        var acceptedPolicy: Pair<PermissionPolicyDocument, List<PermRule>>? = null
        fun requireCurrent() {
            caller.ensureActive()
            owner.requireCurrent()
            acceptedPolicy?.let { expected ->
                check((c.perms.policySnapshot() to c.perms.snapshot()) == expected) {
                    "Recovery permission policy changed. Inspect the exact token and destination receipt before retrying; publication may already have occurred."
                }
            }
            check(c.perms.toolAllowed(tool) && readResources.all {
                ApprovalPolicyGuard.remainsPermitted(c.perms, "read", it, c.permissionScope)
            } && ApprovalPolicyGuard.remainsPermitted(c.perms, "edit", destination, c.permissionScope)) {
                "Recovery permission changed; no further work is authorized. Inspect the exact token and destination receipt before retrying; publication may already have occurred."
            }
        }
        caller.ensureActive()
        owner.requireCurrent()
        if (c.perms.decide("edit", destination, c.permissionScope) == PermEffect.DENY)
            return "DENIED (policy): edit $destination"
        val sourceAsked = c.perms.decide("read", sourceResource, c.permissionScope) == PermEffect.ASK
        readGate(sourceResource)?.let { return it }
        if (sourceAsked) approvedReads += sourceResource
        requireCurrent()
        val recovery = c.files.localDirectoryRecovery
        val selected = recovery.inspect(token)
        selected.source?.let { original ->
            readResources += original
            val asked = c.perms.decide("read", original, c.permissionScope) == PermEffect.ASK
            readGate(original)?.let { return it }
            if (asked) approvedReads += original
            requireCurrent()
        }
        val review = recovery.prepareArchive(token, destination)
        requireCurrent()
        editGate(review.destination, "Recover deleted folder to ${review.destination}",
            "Original project path: ${review.inspection.source ?: "unknown"}\n" +
                "Retained folder: ${review.inspection.retainedPath}\n" +
                "Deletion operation: ${token.operation}\nPrepared SHA-256: ${token.preparedSha256}\n" +
                "Archive destination: ${review.destination}\n" +
                "Create a TAR observation without overwriting any project file. Retained originals and deletion evidence stay intact. " +
                "Late writes may not be included. A recorded earlier publication is reported historically, not repeated.")?.let { return it }
        requireCurrent()
        fun resources(relative: String): List<String> = listOf(
            review.inspection.retainedPath,
            requireNotNull(review.inspection.source) { "Original source path is unavailable" },
        ).map { base -> if (relative.isEmpty()) base else "$base/$relative" }.distinct()
        try {
            recovery.authorizeReadEntries(review) { relative ->
                requireCurrent()
                for ((index, resource) in resources(relative).withIndex()) {
                    when (c.perms.decide("read", resource, c.permissionScope)) {
                        PermEffect.ALLOW -> Unit
                        PermEffect.DENY -> throw ReadDenied("DENIED (policy): read $resource")
                        PermEffect.ASK -> if (index == 0 && relative.isNotEmpty()) {
                            if (!custodyTreeApproved) {
                                // Root read ALLOW or a prior diagnostic read is not recursive consent.
                                // This explicit selected-tree approval is local to this one request.
                                val decision = c.approvals.requestApproval(
                                    "local_deleted_folder_tree", "Read retained folder for ${review.destination}",
                                    "Read this retained folder and all descendants, including hidden and sensitive files, to create the requested archive. " +
                                        "Original-path permissions still apply separately to every entry; any DENY remains enforced.\n" +
                                        "Retained tree: $sourceResource\nOriginal project path: ${review.inspection.source}\n" +
                                        "Deletion operation: ${token.operation}\nPrepared SHA-256: ${token.preparedSha256}\n" +
                                        "Archive destination: ${review.destination}\nThis grant applies only to this reviewed recovery call; no session wildcard permission is saved.",
                                    action = "read", resource = "$sourceResource/**", permissionScope = c.permissionScope,
                                    provenance = c.provenance, approvalSessionId = "", sessionGrantAction = "", sessionGrantResource = "",
                                    isStillCurrent = { caller[kotlinx.coroutines.Job]?.isActive != false && owner.isStillCurrent() },
                                    ownerAdmission = owner.approvalAdmission,
                                )
                                requireCurrent()
                                if (decision is ApprovalDecision.Denied) throw ReadDenied("DENIED: recursive retained-folder read was not approved")
                                custodyTreeApproved = true
                            }
                        } else if (resource !in approvedReads) {
                            check(approvedReads.size < MAX_EXACT_READ_APPROVALS) {
                                "Folder recovery requires too many distinct read approvals; no archive was published. Review the source read policy before another attempt."
                            }
                            readGate(resource)?.let { throw ReadDenied(it) }
                            approvedReads += resource
                            requireCurrent()
                        }
                    }
                }
            }
        } catch (denied: ReadDenied) { return denied.denial }
        requireCurrent()
        acceptedPolicy = c.perms.policySnapshot() to c.perms.snapshot()
        fun checkReadEntry(relative: String) {
            requireCurrent()
            resources(relative).forEachIndexed { index, resource ->
                val decision = c.perms.decide("read", resource, c.permissionScope)
                val approved = resource in approvedReads || index == 0 && relative.isNotEmpty() && custodyTreeApproved
                check(decision == PermEffect.ALLOW || decision == PermEffect.ASK && approved) {
                    "Recovery entry requires fresh read approval: $resource. No unapproved entry was read; inspect and retry explicitly."
                }
            }
        }
        val progress = AgentLocalDirectoryRecoveryProgress(c.onProgress)
        val result = recovery.archive(review, cache, ::requireCurrent, ::checkReadEntry,
            onProgress = progress::report,
            beforePublication = {
                requireCurrent()
                check(c.documentAuthority?.snapshot(review.destination)?.dirty != true) {
                    "Archive destination has unsaved editor changes; choose another destination"
                }
                requireCurrent()
            })
        // The core records committed evidence even if ownership closes after publication.
        // Do not imply rollback or replay when publication succeeded but this call is canceled.
        // The core returned across Dispatchers.IO; recheck full policy before disclosure.
        requireCurrent()
        return buildJsonObject {
            put("success", true); put("committed", result.committed)
            put("historical", result.historical); put("current_file_reverified", result.currentFileReverified)
            put("receipt_durability_reconfirmed", !result.historical)
            put("destination", result.destination); put("bytes", result.bytes); put("sha256", result.sha256)
            put("archive_id", result.archiveId); put("receipt_path", result.receiptPath)
            put("retained_path", result.retainedPath); put("synchronization_pending", result.syncPending)
            put("synchronization_status", result.synchronizationStatus)
            put("detail", result.detail + if (result.historical) " The historical receipt was observed without new file or metadata durability checks." else "")
        }.toString()
    }

    private const val MAX_EXACT_READ_APPROVALS = 256

    private class ReadDenied(val denial: String) : IllegalStateException(denial)

    private fun inspection(value: LocalDirectoryDeleteCustody.Inspection) = buildJsonObject {
        put("operation", value.operation)
        put("source", value.source?.let(::JsonPrimitive) ?: JsonNull)
        put("retained_path", value.retainedPath)
        put("prepared_sha256", value.token?.preparedSha256?.let(::JsonPrimitive) ?: JsonNull)
        put("state", value.state.name); put("source_state", value.sourceState.name)
        put("observer", value.observer.name); put("detail", value.detail)
    }

    private fun token(args: JsonObject): LocalDirectoryDeleteCustody.Token {
        val operation = string(args, "operation")
        val digest = string(args, "prepared_sha256")
        require(ProjectCopyEvidence.UUID_PATTERN.matches(operation) && ProjectCopyEvidence.SHA256_PATTERN.matches(digest)) {
            "Use an exact operation and prepared_sha256 from local_deleted_folders"
        }
        return LocalDirectoryDeleteCustody.Token(operation, digest)
    }

    private fun retainedPath(token: LocalDirectoryDeleteCustody.Token) =
        "${LocalDirectoryDeleteCustody.HOME}/${token.operation}/original"

    private fun string(args: JsonObject, key: String): String =
        requireNotNull(optionalString(args, key)) { "Missing arg: $key" }

    private fun optionalString(args: JsonObject, key: String): String? = args[key]?.let {
        require(it is JsonPrimitive && it.isString) { "$key must be a string" }
        it.content.also { value -> require(value.isNotBlank() && value.length <= 16_384) { "$key is empty or too long" } }
    }
}
