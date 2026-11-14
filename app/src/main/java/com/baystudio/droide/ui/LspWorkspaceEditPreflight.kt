package com.baystudio.droide.ui

import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.LspWorkspaceEdit
import com.baystudio.droide.core.TextEditApplier

 
internal object LspWorkspaceEditPreflight {
    fun requireCurrent(edit: LspWorkspaceEdit) {
        check(edit.applicationGuard?.invoke() != false) {
            "Language-server analysis changed after the request. Re-run the action."
        }
    }

    suspend fun prepare(edit: LspWorkspaceEdit, state: EditorWorkspaceState, files: FileRepository): Map<String, String> {
        requireCurrent(edit)
        require(edit.edits.isNotEmpty()) { "Language server returned no edits" }
        val updates = linkedMapOf<String, String>()
        val revisions = linkedMapOf<String, Int>()
        val owners = linkedMapOf<String, EditorDocument>()
        for ((path, edits) in edit.edits.groupBy { it.path }) {
            val doc = state.document(path)
            owners[path] = doc
            doc.ensureLoaded(files)
            check(doc.agentEditable) { "Workspace edit targets a large/performance-mode or non-text document: $path" }
            val expected = edit.expectedContent[path]
            require(expected != null && doc.content == expected) {
                "Document changed since the language-server request: $path. Re-run the action."
            }
            revisions[path] = doc.changeVersion
            updates[path] = TextEditApplier.apply(doc.content, edits.map { it.range })
        }
        
        for ((path, revision) in revisions) {
            val doc = state.peek(path)
            check(doc != null && doc === owners[path] && doc.agentEditable &&
                doc.changeVersion == revision && doc.content == edit.expectedContent[path]) {
                "Document changed or became unavailable/read-only while preparing language-server edits: $path. Re-run the action."
            }
        }
        requireCurrent(edit)
        return updates
    }
}
