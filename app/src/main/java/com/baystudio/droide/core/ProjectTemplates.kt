package com.baystudio.droide.core

import java.io.File
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val files: Map<String, String>,
    val primaryFile: String?,
)

object ProjectTemplateRegistry {
    val all: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "empty",
            name = "Empty Project",
            description = "Blank workspace. Add any language or project files yourself.",
            files = emptyMap(),
            primaryFile = null,
        ),
        ProjectTemplate(
            id = "python",
            name = "Python",
            description = "Minimal Python project with pyproject.toml and main.py.",
            files = linkedMapOf(
                "pyproject.toml" to "[project]\nname = \"droide-project\"\nversion = \"0.1.0\"\nrequires-python = \">=3.12\"\n",
                "main.py" to "print('Hello from Droide')\n",
            ),
            primaryFile = "main.py",
        ),
        ProjectTemplate(
            id = "java",
            name = "Java",
            description = "Minimal Java source project without forcing Gradle or Maven.",
            files = mapOf("Main.java" to "public class Main { public static void main(String[] args) { System.out.println(\"Hello from Droide\"); } }\n"),
            primaryFile = "Main.java",
        ),
        ProjectTemplate(
            id = "kotlin",
            name = "Kotlin",
            description = "Minimal Kotlin source project without forcing a build system.",
            files = mapOf("Main.kt" to "fun main() { println(\"Hello from Droide\") }\n"),
            primaryFile = "Main.kt",
        ),
        ProjectTemplate(
            id = "javascript",
            name = "JavaScript / Node.js",
            description = "Minimal Node.js project with package.json and index.js.",
            files = linkedMapOf(
                "package.json" to "{\n  \"name\": \"droide-project\",\n  \"version\": \"0.1.0\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n",
                "index.js" to "console.log('Hello from Droide');\n",
            ),
            primaryFile = "index.js",
        ),
        ProjectTemplate(
            id = "typescript",
            name = "TypeScript",
            description = "Minimal TypeScript project metadata and src/index.ts. Runtime/compiler packages remain user-selected.",
            files = linkedMapOf(
                "package.json" to "{\n  \"name\": \"droide-project\",\n  \"version\": \"0.1.0\",\n  \"private\": true\n}\n",
                "tsconfig.json" to "{\n  \"compilerOptions\": { \"target\": \"ES2022\", \"module\": \"ESNext\", \"strict\": true, \"outDir\": \"dist\" },\n  \"include\": [\"src/**/*.ts\"]\n}\n",
                "src/index.ts" to "console.log('Hello from Droide');\n",
            ),
            primaryFile = "src/index.ts",
        ),
        ProjectTemplate(
            id = "go",
            name = "Go",
            description = "Minimal Go module.",
            files = linkedMapOf(
                "go.mod" to "module example.com/droide/project\n\ngo 1.27\n",
                "main.go" to "package main\n\nimport \"fmt\"\n\nfunc main() { fmt.Println(\"Hello from Droide\") }\n",
            ),
            primaryFile = "main.go",
        ),
        ProjectTemplate(
            id = "rust",
            name = "Rust",
            description = "Minimal Cargo binary project.",
            files = linkedMapOf(
                "Cargo.toml" to "[package]\nname = \"droide_project\"\nversion = \"0.1.0\"\nedition = \"2024\"\n",
                "src/main.rs" to "fn main() { println!(\"Hello from Droide\"); }\n",
            ),
            primaryFile = "src/main.rs",
        ),
        ProjectTemplate(
            id = "c",
            name = "C",
            description = "Single-file C project. Compiler installation remains independent.",
            files = mapOf("main.c" to "#include <stdio.h>\n\nint main(void) { puts(\"Hello from Droide\"); return 0; }\n"),
            primaryFile = "main.c",
        ),
        ProjectTemplate(
            id = "cpp-cmake",
            name = "C++ / CMake",
            description = "Minimal CMake C++ project. CMake/compiler installation remains independent.",
            files = linkedMapOf(
                "CMakeLists.txt" to "cmake_minimum_required(VERSION 3.20)\nproject(DroideProject LANGUAGES CXX)\nset(CMAKE_CXX_STANDARD 17)\nadd_executable(droide_app src/main.cpp)\n",
                "src/main.cpp" to "#include <iostream>\n\nint main() { std::cout << \"Hello from Droide\\n\"; return 0; }\n",
            ),
            primaryFile = "src/main.cpp",
        ),
    )

    fun byId(id: String): ProjectTemplate? = all.firstOrNull { it.id == id }
}

/** Narrow capability seam. Production uses only existing anchored publication operations. */
internal interface TemplateDirectory : Closeable {
    fun kind(name: String): AnchoredWorkspaceFiles.Kind
    fun directoryKey(): String
    fun openDirectory(name: String): TemplateDirectory
    fun createDirectory(name: String): AnchoredWorkspaceFiles.MoveReceipt
    fun createNew(name: String): TemplateOutput
    fun fileKey(name: String): Any
    fun publish(staged: String, target: String): AnchoredWorkspaceFiles.MoveReceipt
}

internal interface TemplateOutput : Closeable {
    fun key(): Any
    fun write(bytes: ByteArray)
    fun force()
}

internal class AnchoredTemplateDirectory(private val files: AnchoredWorkspaceFiles) : TemplateDirectory {
    override fun kind(name: String) = files.kind(name)
    override fun directoryKey() = files.directoryKey()
    override fun openDirectory(name: String): TemplateDirectory = AnchoredTemplateDirectory(files.openDirectory(name))
    override fun createDirectory(name: String) = files.createDirectory(name)
    override fun createNew(name: String): TemplateOutput {
        val output = files.createNew(name)
        return object : TemplateOutput {
            override fun key(): Any = checkNotNull(output.observe().attributes.fileKey())
            override fun write(bytes: ByteArray) = output.write(bytes)
            override fun force() = output.force(384)
            override fun close() = output.close()
        }
    }
    override fun fileKey(name: String): Any = files.openRead(name).use {
        checkNotNull(it.observe().attributes.fileKey())
    }
    override fun publish(staged: String, target: String) = files.moveNoReplaceDurable(staged, files, target)
    override fun close() = files.close()
}

object ProjectTemplateWriter {

    fun write(root: File, template: ProjectTemplate) = write(root, template) {
        AnchoredTemplateDirectory(AnchoredWorkspaceFiles(it))
    }

    internal fun write(root: File, template: ProjectTemplate, open: (File) -> TemplateDirectory) {
        // Open even empty templates: a missing or unsafe registered root must not be recreated.
        open(root).use { directory ->
            directory.directoryKey()
            val parentKeys = mutableMapOf<String, String>()
            template.files.forEach { (relative, content) ->
                require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative &&
                    '\u0000' !in relative && relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "Expected a confined template path: $relative"
                }
                try {
                    writeEntry(directory, relative.split('/'), relative, content, parentKeys, "")
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: ExistingTemplateTarget) {

                    throw IllegalArgumentException(failure.message).also { error ->
                        failure.suppressed.forEach(error::addSuppressed)
                    }
                } catch (failure: Exception) {
                    throw IOException("Template publication failed at ${root.absolutePath}/$relative; " +
                        "partial files and staging evidence were retained", failure)
                }
            }
        }
    }

    private class ExistingTemplateTarget(relative: String) :
        IllegalArgumentException("Template target already exists: $relative")

    private fun writeEntry(directory: TemplateDirectory, parts: List<String>, relative: String, content: String,
                           parentKeys: MutableMap<String, String>, prefix: String) {
        if (parts.size > 1) {
            val parent = parts.first()
            val parentPath = "$prefix$parent/"
            when (directory.kind(parent)) {
                AnchoredWorkspaceFiles.Kind.ABSENT -> {
                    check(parentPath !in parentKeys) {
                        "Template parent is unavailable: $parentPath; no replacement was created"
                    }
                    val receipt = directory.createDirectory(parent)
                    check(receipt.durable) {
                        "Template parent creation durability unconfirmed (published=${receipt.published}, errno=${receipt.error})"
                    }
                }
                AnchoredWorkspaceFiles.Kind.DIRECTORY -> Unit
                else -> error("Template parent is not an ordinary directory: $parent")
            }
            directory.openDirectory(parent).use { child ->
                val key = child.directoryKey()
                val previous = parentKeys.putIfAbsent(parentPath, key)
                check(previous == null || previous == key) { "Template parent identity changed: $parentPath" }
                writeEntry(child, parts.drop(1), relative, content, parentKeys, parentPath)
                child.directoryKey()
            }
            directory.directoryKey()
            return
        }
        val target = parts.single()
        if (directory.kind(target) != AnchoredWorkspaceFiles.Kind.ABSENT) throw ExistingTemplateTarget(relative)
        val staged = ".${target}.template-${UUID.randomUUID()}"
        val key = directory.createNew(staged).use { output ->
            val key = output.key()
            output.write(content.toByteArray(Charsets.UTF_8))
            output.force()
            key
        } // A close failure prevents publication; the staged file is deliberately not removed.
        check(directory.fileKey(staged) == key) { "Template staging identity changed; evidence was retained" }
        val receipt = directory.publish(staged, target)
        check(receipt.durable) {
            "Template publication durability unconfirmed (published=${receipt.published}, errno=${receipt.error})"
        }
        check(directory.fileKey(target) == key) { "Published template identity changed; evidence was retained" }
        directory.directoryKey()
    }
}
