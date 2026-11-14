package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Connects Droide's explicit CMake build output to clangd without running a compiler. */
internal object LspClangdLaunchPolicy {
    private const val GENERATED_DIRECTORY = ".droide/build/cmake"

    fun command(spec: LanguageServerSpec, argv: List<String>, root: File, mapper: WorkspacePathMapper): List<String> {
        if (spec.id != "clangd" || spec.source != "built-in" || argv.isEmpty()) return argv
        if (argv.any { it == "--" || it.matches(Regex("--?compile-commands-dir(?:=.*)?")) }) return argv
        // Any root entry is intentional project configuration, including an invalid
        // or dangling link: do not silently replace it with generated build state.
        val rootDatabase = File(root, "compile_commands.json").toPath()
        if (Files.exists(rootDatabase, LinkOption.NOFOLLOW_LINKS)) return argv
        val directory = runCatching { SourceRunPreparation.checkedGeneratedPath(root, GENERATED_DIRECTORY) }.getOrNull() ?: return argv
        val database = runCatching { SourceRunPreparation.checkedGeneratedPath(root, "$GENERATED_DIRECTORY/compile_commands.json") }.getOrNull() ?: return argv
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(database.toPath(), LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(database.toPath())) return argv
        val mapped = runCatching { mapper.localToRemote(directory) }.getOrNull() ?: return argv
        return argv + "--compile-commands-dir=$mapped"
    }
}
