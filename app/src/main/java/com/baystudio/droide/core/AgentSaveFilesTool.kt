package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal fun AgentTools.Ctx.requireSavePermission(resource: String, tool: String, action: String = "edit") {
    check(perms.toolAllowed(tool) && AgentModePolicy.check(mode, tool) == null &&
        ApprovalPolicyGuard.remainsPermitted(perms, action, resource, permissionScope)) {
        "Edit permission changed before Save"
    }
}

internal object AgentSaveFilesTool {
    suspend fun execute(
        args: JsonObject, c: AgentTools.Ctx,
        approve: suspend (path: String, content: String) -> String?,
    ): String {
        val authority = c.documentAuthority ?: return "ERROR: Editor save authority is unavailable"
        val items = args["files"] as? JsonArray ?: error("files must be an array of explicit document versions")
        require(items.size in 1..32) { "save_files requires 1..32 explicit paths" }
        val mutation = AgentWorkspaceMutation(c.files, authority)
        val requests = mutableListOf<WorkspaceDocumentSaveRequest>()
        val previews = mutableListOf<String>()
        for (item in items) {
            val value = item as? JsonObject ?: error("Each file must include a path and document version")
            val path = (value["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("path must be a string")
            val target = mutation.inspect(path) as? AgentWorkspaceMutation.Target.Live
                ?: return "ERROR: Save target is no longer open: $path. Read the file again."
            val expected = mutation.expectedVersion(value) ?: return mutation.versionRequired(target)
            if (target.snapshot.version != expected) return mutation.versionConflict(target.path, target.snapshot)
            requests += WorkspaceDocumentSaveRequest(target.path, expected)
            previews += target.content
        }
        require(requests.map { it.path }.distinct().size == requests.size) { "Duplicate Save targets are not allowed" }
        for ((index, request) in requests.withIndex()) approve(request.path, previews[index])?.let { return it }
        return authority.saveDocuments(requests, {
            requests.forEach { c.requireSavePermission(it.path, "save_files") }
        }).summary()
    }
}
