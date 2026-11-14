package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentResultPathsTest {
    @Test fun structuredGuestDiffsAppearInSessionResultsWithoutReadingWorkspaceFiles() {
        val root = Files.createTempDirectory("external-paths-").toFile()
        try {
            val mapper = WorkspacePathMapper(root, "/guest/workspace")
            for (path in listOf("src/app.kt", "/guest/workspace/src/app.kt", "${root.path}/src/app.kt", "file:///guest/workspace/src/app.kt")) {
                val patch = ExternalAgentToolUpdate("write", kind = "edit", status = "completed",
                    changes = listOf(AgentFileChanges.preview(path, null, "new"))).withWorkspacePaths(mapper)
                val part = patch.applyTo(AgentStreamPart("activity", 0, AgentStreamPartKind.TOOL, AgentStreamPartState.PENDING))
                val page = AgentSessionResults.page(listOf(AgentMessage("assistant", "", part)))
                assertEquals(path, "src/app.kt", (page.entries.single() as AgentSessionResult.File).path)
            }
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun foreignAndMalformedLocationsCannotBecomeExportableWorkspaceResults() {
        val root = Files.createTempDirectory("external-paths-").toFile()
        try {
            val mapper = WorkspacePathMapper(root, "/guest/workspace")
            for (path in listOf("/guest/workspace-other/private", "/guest/workspace/../private",
                "file://foreign/guest/workspace/private", "file:///guest/workspace/file?query=1", "file:///guest/workspace/%2e%2e/private")) {
                val patch = ExternalAgentToolUpdate("write", status = "completed",
                    changes = listOf(AgentFileChanges.preview(path, null, "new"))).withWorkspacePaths(mapper)
                val part = patch.applyTo(AgentStreamPart("activity", 0, AgentStreamPartKind.TOOL, AgentStreamPartState.PENDING))
                assertTrue(path, AgentSessionResults.page(listOf(AgentMessage("assistant", "", part))).entries.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
