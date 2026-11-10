package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryDependencyDiscoveryTest {
    @Test fun policyMatchesOnlyExactDirectorySegments() {
        listOf("node_modules", "packages/app/node_modules", "node_modules/pkg").forEach {
            assertTrue(DependencyDiscoveryPolicy.isExcluded(it, true, false))
            assertFalse(DependencyDiscoveryPolicy.isExcluded(it, true, true))
        }
        listOf("vendor", "build", "Node_Modules", "node_modules_notes").forEach {
            assertFalse(DependencyDiscoveryPolicy.isExcluded(it, true, false))
        }
        assertFalse(DependencyDiscoveryPolicy.isExcluded("src/node_modules", false, false))
        assertTrue(DependencyDiscoveryPolicy.isExcluded("src/node_modules/pkg/index.js", false, false))
    }

    @Test fun explicitDependencyOptInReachesEveryDiscoveryEntryPoint() = runBlocking {
        val paths = setOf("src/main.js", "node_modules/pkg/root.js", "packages/app/node_modules/pkg/nested.js")
        val root = fixture(*paths.toTypedArray())
        try {
            val files = FileRepository(root)
            assertEquals(paths, files.findFilesPage("", includeDependencies = true).paths.toSet())
            assertEquals(paths, files.findFiles("", includeDependencies = true).toSet())
            assertEquals(paths, files.globPage("**/*.js", includeDependencies = true).paths.toSet())
            assertEquals(paths, files.glob("**/*.js", includeDependencies = true).lineSequence().filter { it.isNotBlank() }.toSet())
            assertEquals(paths, files.searchPage("needle", includeDependencies = true).hits.map { it.path }.toSet())
            val rendered = files.search("needle", includeDependencies = true)
            paths.forEach { assertTrue(rendered.contains("$it:1:")) }
            assertFalse(files.isSearchVisible("node_modules/pkg/root.js"))
            assertTrue(files.isSearchVisible("node_modules/pkg/root.js", includeDependencies = true))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun dirtyDependencyOverlayUsesTheSamePolicyAndNeverDuplicatesDisk() = runBlocking {
        val path = "node_modules/pkg/active.js"
        val root = fixture(path)
        try {
            val files = FileRepository(root)
            val snapshot = WorkspaceDocumentSnapshot(path, "needle live\n", true, WorkspaceDocumentKind.TEXT,
                true, true, 1, 1, 0, 0, 0)
            val documents = object : WorkspaceDocumentAuthority {
                override suspend fun snapshot(path: String) = snapshot.takeIf { it.path == path }
                override suspend fun snapshots() = listOf(snapshot)
                override suspend fun captureSearchSnapshots(request: WorkspaceSearchCaptureRequest) =
                    request.captureFiniteSnapshots(listOf(snapshot))
            }
            assertTrue(files.searchPage("needle", documents = documents).hits.isEmpty())
            val optedIn = files.searchPage("needle", documents = documents, includeDependencies = true)
            assertEquals(1, optedIn.hits.size)
            assertEquals(path, optedIn.hits.single().path)
            assertEquals("needle live", optedIn.hits.single().excerpt)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun optInDoesNotBypassRootIgnoreSensitiveOrInternalRules() = runBlocking {
        val allowed = "node_modules/allowed.js"
        val ignored = "node_modules/ignored.js"
        val root = fixture(allowed, ignored, "node_modules/.env", "node_modules/secret.pem",
            "node_modules/.git/private.js", ".droide/private.js")
        try {
            root.resolve(".gitignore").writeText("node_modules/ignored.js\n")
            val files = FileRepository(root)
            assertEquals(setOf(allowed), files.findFilesPage(".js", includeDependencies = true).paths.toSet())
            assertEquals(setOf(allowed), files.globPage("**/*.js", includeDependencies = true).paths.toSet())
            assertEquals(setOf(allowed), files.searchPage("needle", includeDependencies = true).hits.map { it.path }.toSet())
            assertFalse(files.isSearchVisible(ignored, includeDependencies = true))
            assertFalse(files.isSearchVisible("node_modules/.env", includeDependencies = true))
            root.resolve(".gitignore").writeText("node_modules/\n!node_modules/allowed.js\n")
            assertTrue(files.globPage("**/*.js", includeDependencies = true).paths.isEmpty())
            assertTrue(files.searchPage("needle", includeDependencies = true).hits.isEmpty())
            assertEquals("needle\n", files.readText(allowed))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun explicitDirectoriesAndOrdinaryNodeModulesFilenameRemainAccessible() = runBlocking {
        val root = fixture("node_modules/pkg/index.js", "node_modules/pkg/node_modules/child.js", "src/node_modules")
        try {
            val files = FileRepository(root)
            assertTrue(files.listFiles().any { it.path == "node_modules" })
            assertFalse(files.listFiles(includeDependencies = false).any { it.path == "node_modules" })
            assertEquals(listOf("node_modules/pkg/index.js"),
                files.listFiles("node_modules/pkg", includeDependencies = false).map { it.path })
            assertTrue(files.listFiles("node_modules/pkg", includeDependencies = true).any { it.path.endsWith("/node_modules") })
            assertEquals(listOf("src/node_modules"), files.listFiles("src", includeDependencies = false).map { it.path })
            assertTrue("src/node_modules" in files.findFiles("node_modules"))
            assertTrue(files.isSearchVisible("src/node_modules"))
            assertTrue(files.listFilesPage(options = DirectoryListingOptions.EXPLORER).entries.any { it.path == "node_modules" })
            assertEquals("needle\n", files.readText("node_modules/pkg/index.js"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun targetedGlobRequiresOptInAndDoesNotChangeContainment() = runBlocking {
        val path = "node_modules/pkg/index.js"
        val root = fixture(path)
        try {
            val files = FileRepository(root)
            val options = TextSearchOptions(includeGlob = "node_modules/**/*.js")
            assertTrue(files.globPage("node_modules/**/*.js").paths.isEmpty())
            assertTrue(files.searchPage("needle", options = options).hits.isEmpty())
            assertEquals(listOf(path), files.globPage("node_modules/**/*.js", includeDependencies = true).paths)
            assertEquals(listOf(path), files.searchPage("needle", options = options, includeDependencies = true).hits.map { it.path })
            assertTrue(runCatching { files.listFiles("../", includeDependencies = true) }.isFailure)
            assertTrue(runCatching { files.isSearchVisible("../outside.js", includeDependencies = true) }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun fixture(vararg paths: String): File = Files.createTempDirectory("droide-dependency-policy-").toFile().also { root ->
        paths.forEach { root.resolve(it).apply { parentFile.mkdirs(); writeText("needle\n") } }
    }
}
