package com.baystudio.droide.core

/** Read-result ownership shared by the native Agent and workspace-bound external clients. */
internal data class AgentReadContext(
    val files: FileRepository,
    val documentAuthority: WorkspaceDocumentAuthority?,
    val lsp: LspManager,
    val sessionId: String?,
    val permissionScope: String,
) {
    constructor(context: AgentTools.Ctx) : this(context.files, context.documentAuthority,
        context.lsp, context.sessionId, context.permissionScope)
}
