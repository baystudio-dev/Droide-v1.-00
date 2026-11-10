package com.baystudio.droide.core

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SafProjectIdentityTest {
    private val tree = Uri.parse("content://example.documents/tree/primary%3AProjects%2FDroide")
    private fun project(id: String, uri: String = tree.toString()) =
        DroideProject(id, "Same display name", "/private/projects/$id", uri)

    @Test fun equivalentTreeUriEncodingReusesExistingProjectAndItsOriginalUri() {
        val original = project("kept")
        val selected = Uri.parse("content://example.documents/tree/primary%3aProjects%2fDroide/document/primary%3AProjects%2FDroide")
        assertSame(original, SafProjectIdentity.existing(listOf(original), selected, null))
        assertEquals(tree.toString(), original.treeUri)
    }

    @Test fun opaqueIdentityAndProviderCannotBeReplacedByDisplayNameOrPrefix() {
        val original = project("kept")
        for (different in listOf(
            "content://another.documents/tree/primary%3AProjects%2FDroide",
            "content://example.documents/tree/primary%3AProjects%2Fdroide",
            "content://example.documents/tree/primary%3AProjects",
            "content://10@example.documents/tree/primary%3AProjects%2FDroide",
        )) assertNull(SafProjectIdentity.existing(listOf(original), Uri.parse(different), null))
    }

    @Test fun alreadyActiveDuplicateKeepsItsOwnWorkspaceAndPendingEvidence() {
        val a = project("first")
        val b = project("second")
        assertSame(b, SafProjectIdentity.existing(listOf(a, b), tree, "second"))
        assertNotEquals(a.rootPath, b.rootPath)
    }

    @Test fun ambiguousInactiveDuplicatesAreNotSilentlyMergedOrChosen() {
        val projects = listOf(project("first"), project("second"))
        try { SafProjectIdentity.existing(projects, tree, "unrelated"); fail("Ambiguous mirrors were selected") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("multiple registered projects")) }
        assertEquals(2, projects.size)
    }

    @Test fun malformedOrNonTreeRequestsCannotCreateNewProject() {
        for (uri in listOf("file:///sdcard/Projects", "content://example.documents/document/root", "https://example/tree/root")) {
            try { SafProjectIdentity.existing(emptyList(), Uri.parse(uri), null); fail(uri) }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unrelatedLegacyMalformedRecordDoesNotPreventValidLookup() {
        val original = project("valid")
        assertSame(original, SafProjectIdentity.existing(
            listOf(project("legacy", "file:///external"), original, DroideProject("local", "Local", "/private/projects/local")),
            tree, null))
    }
}
