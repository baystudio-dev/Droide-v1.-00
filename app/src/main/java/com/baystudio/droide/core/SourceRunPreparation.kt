package com.baystudio.droide.core

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Built-in file runners use user-installed compilers, with disposable generated files. */
internal object SourceRunPreparation {
    private const val OWNER_FILE = ".droide-owned-run"
    private const val OWNER_CONTENT = "droide-source-run-v1\n"
    private const val SCOPES_FILE = ".droide-package-scopes.json"
    private class RunGate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val runGates = mutableMapOf<String, RunGate>()

    internal suspend fun <T> withRunDirectory(workDir: File, directory: String, action: suspend () -> T): T {
        val key = workDir.canonicalPath + "/" + directory
        val gate = synchronized(runGates) { runGates.getOrPut(key) { RunGate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock { action() }
        } finally {
            synchronized(runGates) { if (--gate.users == 0) runGates.remove(key) }
        }
    }

    fun typeScriptPlan(path: String): RunPlan {
        val source = checkedSourcePath(path)
        val directory = runDirectory("typescript", source)
        val config = buildJsonObject {
            put("compilerOptions", buildJsonObject {
                put("target", "ES2022")
                put("module", "NodeNext")
                put("moduleResolution", "NodeNext")
                put("rootDir", "../../../..")
                put("outDir", ".")
                put("noEmitOnError", true)
                put("rewriteRelativeImportExtensions", true)
            })
            put("files", buildJsonArray { add(JsonPrimitive("../../../../$source")) })
        }.toString()
        val emitted = source.substringBeforeLast('.') + when (source.substringAfterLast('.').lowercase()) {
            "mts" -> ".mjs"
            "cts" -> ".cjs"
            else -> ".js"
        }
        return RunPlan(
            steps = listOf(listOf("tsc", "--project", "$directory/tsconfig.json"), listOf("node", "$directory/$emitted")),
            display = "tsc $source && node $directory/$emitted",
            preparation = RunPreparation(directory, mapOf("tsconfig.json" to config), mirrorTypeScriptPackageScopes = true),
        )
    }

    fun cSharpPlan(path: String): RunPlan {
        val source = checkedSourcePath(path)
        val directory = runDirectory("csharp", source)
        val include = xmlEscape(msBuildEscape("../../../../$source"))
        val project = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <OutputType>Exe</OutputType>
                <TargetFramework>net8.0</TargetFramework>
                <ImplicitUsings>enable</ImplicitUsings>
                <Nullable>enable</Nullable>
                <EnableDefaultCompileItems>false</EnableDefaultCompileItems>
                <ImportDirectoryBuildProps>false</ImportDirectoryBuildProps>
                <ImportDirectoryBuildTargets>false</ImportDirectoryBuildTargets>
              </PropertyGroup>
              <ItemGroup><Compile Include="$include" /></ItemGroup>
            </Project>
        """.trimIndent() + "\n"
        return RunPlan(
            steps = listOf(
                listOf("dotnet", "build", "$directory/DroideRun.csproj", "--configuration", "Debug", "--disable-build-servers",
                    "--property:UseSharedCompilation=false", "--property:RestoreIgnoreFailedSources=true",
                    "--property:ImportDirectoryBuildProps=false", "--property:ImportDirectoryBuildTargets=false"),
                listOf("dotnet", "$directory/bin/Debug/net8.0/DroideRun.dll"),
            ),
            display = "dotnet build $source && dotnet $directory/bin/Debug/net8.0/DroideRun.dll",
            preparation = RunPreparation(directory, mapOf("DroideRun.csproj" to project)),
        )
    }

    fun nativePlan(path: String): RunPlan {
        val source = checkedSourcePath(path)
        val extension = source.substringAfterLast('.').lowercase()
        require(extension in setOf("c", "cpp", "cc", "cxx", "c++", "s", "asm")) { "Headers cannot be run; build the source translation unit or project" }
        val directory = runDirectory("native", source)
        val cpp = source.endsWith(".C") || extension in setOf("cpp", "cc", "cxx", "c++")
        val compiler = if (cpp) "g++" else "gcc"
        val flags = if (extension in setOf("s", "asm")) listOf("-x", if (source.endsWith(".S")) "assembler-with-cpp" else "assembler") else
            listOf(if (cpp) "-std=c++17" else "-std=c17", "-Wall", "-Wextra")
        val output = "$directory/program"
        return RunPlan(listOf(listOf(compiler, "-g", "-O0") + flags + listOf("./$source", "-o", output), listOf("./$output")),
            "$compiler $source && ./$output", RunPreparation(directory, mapOf("source-path.txt" to "$source\n")))
    }

    fun prepare(workDir: File, preparation: RunPreparation): Map<String, String> =
        ExportWriterAdmission.withWriter(workDir, "Generated run preparation") {
        require(preparation.files.size in 1..16) { "Invalid run preparation" }
        require(preparation.files.values.all { it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }) { "Run preparation is too large" }
        require(preparation.directory.matches(Regex("\\.droide/run/(typescript|csharp|native)/[0-9a-f]{64}"))) {
            "Run preparation must use an isolated generated directory"
        }
        require(preparation.files.keys.none { it == OWNER_FILE || it == SCOPES_FILE }) { "Reserved generated run file" }
        val directory = checkedGeneratedPath(workDir, preparation.directory)
        if (directory.exists()) {
            require(directory.isDirectory) { "Run preparation path is not a directory" }
            val marker = checkedGeneratedPath(directory, OWNER_FILE)
            require(marker.isFile && marker.length() == OWNER_CONTENT.length.toLong() && marker.readText() == OWNER_CONTENT) {
                "Run directory contains unowned files; move it aside before running this source"
            }
        } else {
            check(directory.mkdirs()) { "Could not create generated run directory" }
            writeGenerated(directory, OWNER_FILE, OWNER_CONTENT)
        }
        val files = linkedMapOf<String, String>()
        if (preparation.mirrorTypeScriptPackageScopes) files.putAll(mirrorPackageScopes(workDir.canonicalFile, directory))
        preparation.files.forEach { (relative, content) -> writeGenerated(directory, relative, content) }
        files.putAll(preparation.files)
        files
    }

    private fun mirrorPackageScopes(workDir: File, output: File): Map<String, String> {
        val previous = checkedGeneratedPath(output, SCOPES_FILE)
        if (previous.isFile) {
            require(previous.length() <= 1024 * 1024) { "Generated package-scope manifest is too large" }
            val scopes = Json.parseToJsonElement(previous.readText()) as? kotlinx.serialization.json.JsonArray
                ?: error("Invalid generated package-scope manifest")
            for (scope in scopes) {
                val relative = (scope as? JsonPrimitive)?.contentOrNull ?: error("Invalid generated package scope")
                require(relative == "package.json" || relative.endsWith("/package.json")) { "Invalid generated package scope" }
                val marker = checkedGeneratedPath(output, relative)
                if (marker.exists()) require(marker.isFile && marker.delete()) { "Could not update generated package scope" }
            }
        }
        val markers = linkedMapOf("package.json" to "{\"type\":\"commonjs\"}\n")
        var directories = 0
        Files.walkFileTree(workDir.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != workDir.toPath() && dir.fileName.toString() in setOf(".droide", ".git", "node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                require(++directories <= 50_000) { "Workspace is too large for single-file TypeScript execution; use the project build command" }
                val manifest = dir.resolve("package.json")
                if (!Files.exists(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE
                require(!Files.isSymbolicLink(manifest) && Files.isRegularFile(manifest)) { "TypeScript package scope is not a regular file" }
                require(Files.size(manifest) <= 1024 * 1024) { "TypeScript package scope is too large" }
                val json = Json.parseToJsonElement(manifest.toFile().readText()) as? JsonObject
                    ?: error("Invalid TypeScript package.json: ${workDir.toPath().relativize(manifest)}")
                val type = (json["type"] as? JsonPrimitive)?.contentOrNull
                val relative = workDir.toPath().relativize(manifest).toString().replace(File.separatorChar, '/')
                // NodeNext classifies source files by their original package scope. The emitted tree
                // needs the same classification, without copying scripts or other package metadata.
                markers[relative] = if (type == "module") "{\"type\":\"module\"}\n" else "{\"type\":\"commonjs\"}\n"
                require(markers.size <= 4_096) { "Too many TypeScript package scopes for single-file execution" }
                return FileVisitResult.CONTINUE
            }
        })
        markers.forEach { (relative, content) -> writeGenerated(output, relative, content) }
        writeGenerated(output, SCOPES_FILE, buildJsonArray { markers.keys.forEach { add(JsonPrimitive(it)) } }.toString())
        return markers
    }

    // Workspace sync deliberately excludes .droide. Materialize only these generated files in the
    // selected Device terminal, using argv for contents and refusing symlinks or unowned directories.
    internal fun remotePreparationCommands(preparation: RunPreparation, files: Map<String, String>): List<List<String>> {
        require(preparation.directory.matches(Regex("\\.droide/run/(typescript|csharp|native)/[0-9a-f]{64}")))
        files.keys.forEach { checkedRelativeParts(it) }
        val setup = """
            set -eu
            dir=${'$'}1
            for part in .droide .droide/run .droide/run/${preparation.directory.split('/')[2]} "${'$'}dir"; do
              [ ! -L "${'$'}part" ] || { printf '%s\n' 'Generated run path contains a symbolic link' >&2; exit 1; }
              [ ! -e "${'$'}part" ] || [ -d "${'$'}part" ] || exit 1
            done
            if [ -e "${'$'}dir" ]; then
              [ ! -L "${'$'}dir/$OWNER_FILE" ] && [ -f "${'$'}dir/$OWNER_FILE" ] &&
                [ "${'$'}(cat "${'$'}dir/$OWNER_FILE")" = 'droide-source-run-v1' ] || {
                  printf '%s\n' 'Generated run directory contains unowned files' >&2; exit 1;
                }
              rm -rf -- "${'$'}dir"
            fi
            umask 077
            mkdir -p -- "${'$'}dir"
            printf '%s\n' 'droide-source-run-v1' > "${'$'}dir/$OWNER_FILE"
        """.trimIndent()
        val write = """
            set -eu
            dir=${'$'}1
            shift
            [ ! -L "${'$'}dir" ] && [ -d "${'$'}dir" ] && [ ! -L "${'$'}dir/$OWNER_FILE" ] &&
              [ "${'$'}(cat "${'$'}dir/$OWNER_FILE")" = 'droide-source-run-v1' ] || exit 1
            while [ "${'$'}#" -gt 0 ]; do
              name=${'$'}1
              content=${'$'}2
              shift 2
              target="${'$'}dir/${'$'}name"
              parent=${'$'}{target%/*}
              mkdir -p -- "${'$'}parent"
              [ ! -L "${'$'}target" ] || exit 1
              printf '%s' "${'$'}content" > "${'$'}target"
            done
        """.trimIndent()
        val commands = mutableListOf(listOf("sh", "-c", setup, "droide-run-prepare", preparation.directory))
        var batch = mutableListOf("sh", "-c", write, "droide-run-write", preparation.directory)
        var bytes = 0
        for ((relative, content) in files) {
            val entryBytes = (relative.toByteArray(Charsets.UTF_8).size + content.toByteArray(Charsets.UTF_8).size) * 4
            if (bytes > 0 && bytes + entryBytes > 48 * 1024) {
                commands += batch
                batch = mutableListOf("sh", "-c", write, "droide-run-write", preparation.directory)
                bytes = 0
            }
            batch += relative
            batch += content
            bytes += entryBytes
        }
        if (bytes > 0) commands += batch
        return commands
    }

    internal fun checkedGeneratedPath(root: File, relative: String): File {
        val parts = checkedRelativeParts(relative)
        var current = root.canonicalFile
        for (part in parts) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "Generated run path contains a symbolic link" }
        }
        return PathSecurity.resolveWithin(root, relative)
    }

    private fun checkedRelativeParts(relative: String): List<String> {
        val parts = relative.split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it }) {
            "Invalid generated run path"
        }
        return parts
    }

    private fun writeGenerated(root: File, relative: String, content: String) {
        val target = checkedGeneratedPath(root, relative)
        require(!target.exists() || target.isFile) { "Generated run file is not a regular file" }
        val parent = requireNotNull(target.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Could not prepare generated file directory" }
        val temporary = Files.createTempFile(parent.toPath(), ".droide-run-", ".tmp")
        try {
            Files.write(temporary, content.toByteArray(Charsets.UTF_8))
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun checkedSourcePath(path: String): String {
        require(path.isNotBlank() && path.none { it.code < 32 || it.code == 127 } && '\\' !in path) { "Invalid source path" }
        val normalized = java.nio.file.Paths.get(path).normalize()
        require(!normalized.isAbsolute && normalized.nameCount > 0 && normalized.first().toString() != "..") { "Source path escapes workspace" }
        return normalized.toString().replace(File.separatorChar, '/')
    }

    private fun runDirectory(language: String, path: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ".droide/run/$language/$hash"
    }

    private fun msBuildEscape(value: String): String = buildString {
        for (c in value) if (c in "%$@();?*'") append("%" + c.code.toString(16).uppercase().padStart(2, '0')) else append(c)
    }
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
