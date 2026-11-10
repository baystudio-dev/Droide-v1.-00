package com.baystudio.droide.core

import android.net.Uri
import android.provider.DocumentsContract

/** Provider authority + opaque tree document ID, not display name or URI encoding. */
internal data class SafProjectIdentity(val authority: String, val documentId: String) {
    companion object {
        fun from(uri: Uri): SafProjectIdentity {
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
                "Choose a folder through Android's folder picker"
            }
            val authority = requireNotNull(uri.authority?.takeIf { it.isNotBlank() }) {
                "External folder provider is missing"
            }
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            require(documentId.isNotBlank()) { "External folder identity is missing" }
            return SafProjectIdentity(authority, documentId)
        }

        fun existing(projects: List<DroideProject>, uri: Uri, activeId: String?): DroideProject? {
            val requested = from(uri)
            val matches = projects.filter { project ->
                project.treeUri?.let { value ->
                    runCatching { from(Uri.parse(value)) == requested }.getOrDefault(false)
                } == true
            }

            matches.firstOrNull { it.id == activeId }?.let { return it }
            require(matches.size <= 1) {
                "This external folder has multiple registered projects. Open the intended project from Projects; existing files were preserved."
            }
            return matches.singleOrNull()
        }
    }
}
