package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryDependencyDiscoveryBaselineTest {
    private val authored = setOf(
        "src/main.js",
        "vendor/custom.js",
        "build/custom.js",
        "node_modules_notes/readme.js",
        "Node_Modules/case.js",
        "packages/app/source.js",
    )
    private val dependencies = setOf(
        "node_modules/pkg/root.js",
        "packages/app/node_modules/pkg/nested.js",
    )

    @Test fun defaultQuickOpenAndGlobExcludeRootAndNestedDependencyTrees() = runBlocking {
        val root = fixture()
        try {
            val files = FileRepository(root)
            val actual = listOf(files.findFilesPage("").paths.toSet(), files.globPage("**/*.js").paths.toSet())
            assertEquals("Default discovery must exclude only exact node_modules directory segments", listOf(authored, authored), actual)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun defaultTextSearchExcludesDependenciesWhileExplicitReadsAndListingsRemainAvailable() = runBlocking {
        val root = fixture()
        try {
            val files = FileRepository(root)
            assertTrue(files.listFiles().any { it.path == "node_modules" && it.isDir })
            assertEquals(listOf("node_modules/pkg/root.js"), files.listFiles("node_modules/pkg").map { it.path })
            assertEquals(listOf("packages/app/node_modules/pkg/nested.js"),
                files.listFilesPage("packages/app/node_modules/pkg").entries.map { it.path })
            dependencies.forEach { assertEquals("needle\n", files.readText(it)) }
            assertEquals("Default text discovery must retain authored and ordinary-name paths", authored,
                files.searchPage("needle").hits.map { it.path }.toSet())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun fixture(): File = Files.createTempDirectory("droide-dependency-baseline-").toFile().also { root ->
        (authored + dependencies).forEach { path ->
            root.resolve(path).apply { parentFile.mkdirs(); writeText("needle\n") }
        }
    }
}
