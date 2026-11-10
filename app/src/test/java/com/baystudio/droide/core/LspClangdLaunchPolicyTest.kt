package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LspClangdLaunchPolicyTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "clangd" }
    private val original = listOf("/managed/clangd", "--background-index")

    @Test fun mapsKnownGeneratedCmakeDatabaseToTheBoundWorkspaceAsOneLiteralArgument() = inWorkspace { root ->
        root.resolve(".droide/build/cmake").mkdirs()
        root.resolve(".droide/build/cmake/compile_commands.json").writeText("[]")
        val next = LspClangdLaunchPolicy.command(spec, original, root, WorkspacePathMapper(root, "/workspace/project with spaces"))
        assertEquals(original + "--compile-commands-dir=/workspace/project with spaces/.droide/build/cmake", next)
        assertFalse(next.any { it.startsWith("--query-driver") })
    }

    @Test fun preservesProjectRootDatabaseAndUserProvidedCommandDirectory() = inWorkspace { root ->
        root.resolve(".droide/build/cmake").mkdirs()
        root.resolve(".droide/build/cmake/compile_commands.json").writeText("[]")
        val mapper = WorkspacePathMapper(root, "/workspace")
        val user = original + listOf("--compile-commands-dir", "/workspace/custom build")
        assertEquals(user, LspClangdLaunchPolicy.command(spec, user, root, mapper))
        val llvmStyle = original + "-compile-commands-dir=/workspace/custom"
        assertEquals(llvmStyle, LspClangdLaunchPolicy.command(spec, llvmStyle, root, mapper))
        root.resolve("compile_commands.json").writeText("[]")
        assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, mapper))
    }

    @Test fun missingOrUnownedBuildTreesDoNotChangeTheLaunch() = inWorkspace { root ->
        val mapper = WorkspacePathMapper(root, "/workspace")
        assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, mapper))
        root.resolve("build").mkdirs()
        root.resolve("build/compile_commands.json").writeText("[]")
        root.resolve(".droide/build/custom").mkdirs()
        root.resolve(".droide/build/custom/compile_commands.json").writeText("[]")
        assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, mapper))
    }

    @Test fun symlinkedDirectoryOrDatabaseIsNeverAutomaticallyAdmitted() = inWorkspace { root ->
        val outside = Files.createTempDirectory("droide-clangd-outside-").toFile()
        try {
            outside.resolve("compile_commands.json").writeText("[]")
            root.resolve(".droide/build").mkdirs()
            Files.createSymbolicLink(root.resolve(".droide/build/cmake").toPath(), outside.toPath())
            val mapper = WorkspacePathMapper(root, "/workspace")
            assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, mapper))
            Files.delete(root.resolve(".droide/build/cmake").toPath())
            root.resolve(".droide/build/cmake").mkdir()
            Files.createSymbolicLink(root.resolve(".droide/build/cmake/compile_commands.json").toPath(), outside.resolve("compile_commands.json").toPath())
            assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, mapper))
        } finally { PathSecurity.deleteTreeNoFollow(outside) }
    }

    @Test fun preservesContributedSpecsAndRejectsMismatchedWorkspaceMapping() = inWorkspace { root ->
        root.resolve(".droide/build/cmake").mkdirs()
        root.resolve(".droide/build/cmake/compile_commands.json").writeText("[]")
        val contributed = spec.copy(source = "user-extension")
        assertEquals(original, LspClangdLaunchPolicy.command(contributed, original, root, WorkspacePathMapper(root, "/workspace")))
        val other = Files.createTempDirectory("droide-clangd-other-").toFile()
        try {
            assertEquals(original, LspClangdLaunchPolicy.command(spec, original, root, WorkspacePathMapper(other, "/workspace")))
        } finally { PathSecurity.deleteTreeNoFollow(other) }
    }

    private fun inWorkspace(request: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("droide clangd workspace ").toFile()
        try { request(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
