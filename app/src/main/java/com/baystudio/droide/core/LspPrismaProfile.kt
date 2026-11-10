package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.*

/** Bounded legacy-schema client policy. A project root and a managed prelude are not a sandbox. */
internal object LspPrismaProfile {
    const val ID = "prisma-language-server"
    const val VERSION = "31.13.0"
    const val IDENTITY = "prisma-language-server-31.13.0-legacy-schema-v1"
    const val LAUNCH_POLICY_REVISION = "prisma-managed-public-root-launch-v1"
    const val ROOT_POLICY = "config-first-two-pass-real-js-ts-c12-stems-unavailable-barrier-then-package-vcs-parent-v1"
    const val TRANSPORT_POLICY = "existing-bounded-content-length-jsonrpc-no-stdout-authentication-v1"
    val extensions = setOf("prisma")

    enum class Generation { LEGACY, NEXT_OR_8 }

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').endsWith(".prisma")

    fun classify(text: String): Generation {
        var index = 0
        while (index < text.length && javascriptWhitespace(text[index])) index++
        if (!text.startsWith("//", index)) return Generation.LEGACY
        index += 2
        while (text.getOrNull(index) == ' ') index++
        if (!text.startsWith("use", index)) return Generation.LEGACY
        index += 3
        if (text.getOrNull(index) != ' ') return Generation.LEGACY
        while (text.getOrNull(index) == ' ') index++
        if (!text.startsWith("prisma-", index)) return Generation.LEGACY
        index += 7
        index += when {
            text.startsWith("next", index) -> 4
            text.getOrNull(index) == '8' -> 1
            else -> return Generation.LEGACY
        }

        return if (index == text.length || javascriptWhitespace(text[index])) Generation.NEXT_OR_8
            else Generation.LEGACY
    }

    private fun javascriptWhitespace(character: Char): Boolean = character.code.let {
        it in 0x09..0x0d || it == 0x20 || it == 0xa0 || it == 0x1680 ||
            it in 0x2000..0x200a || it == 0x2028 || it == 0x2029 ||
            it == 0x202f || it == 0x205f || it == 0x3000 || it == 0xfeff
    }

    private enum class Kind { ABSENT, FILE, DIRECTORY, INVALID }
    private enum class ConfigState { NONE, SUPPORTED, UNAVAILABLE }
    private data class ConfigBoundary(val state: ConfigState, val markers: Map<String, Kind>, val selected: String?)
    private val supportedSuffixes = listOf("js", "ts", "mjs", "cjs", "mts", "cts")
    // c12's additional extension and bare/directory-index forms are barriers, not accepted Prisma
    // configurations. This is a finite picker, not c12.
    private val unsupportedSuffixes = listOf("json", "jsonc", "json5", "yaml", "yml", "toml")
    private val vcs = listOf(".git", ".hg", ".svn", ".jj")

    private fun kind(file: File): Kind = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attributes.isSymbolicLink || !Files.isReadable(file.toPath()) -> Kind.INVALID
            attributes.isRegularFile -> Kind.FILE
            attributes.isDirectory -> Kind.DIRECTORY
            else -> Kind.INVALID
        }
    } catch (_: NoSuchFileException) {
        Kind.ABSENT
    } catch (_: Exception) {
        // Denied/unreadable metadata is not evidence of absence.
        Kind.INVALID
    }

    private fun configBoundary(root: File): ConfigBoundary {
        val markers = linkedMapOf<String, Kind>()
        val supported = mutableListOf<String>()
        var invalid = false
        fun inspect(stem: String) {
            val bare = kind(File(root, stem))
            markers[stem] = bare
            if (bare != Kind.ABSENT) invalid = true
            for (suffix in supportedSuffixes + unsupportedSuffixes) {
                val name = "$stem.$suffix"
                val value = kind(File(root, name))
                markers[name] = value
                if (value == Kind.ABSENT) continue
                if (suffix in supportedSuffixes && value == Kind.FILE) supported += name
                else invalid = true
            }
        }
        inspect("prisma.config")
        val config = kind(File(root, ".config"))
        markers[".config"] = config
        when (config) {
            Kind.ABSENT -> Unit
            Kind.DIRECTORY -> { inspect(".config/prisma"); inspect(".config/prisma.config") }
            else -> invalid = true // Never look through a symlink or non-directory container.
        }
        return ConfigBoundary(when {
            invalid -> ConfigState.UNAVAILABLE
            supported.isNotEmpty() -> ConfigState.SUPPORTED
            else -> ConfigState.NONE
        }, markers, supported.firstOrNull())
    }

    /** Metadata only. A farther config takes precedence over a nearer package/VCS marker. */
    fun projectRoot(workspace: File, document: File): File {
        val boundary = workspace.canonicalFile
        val file = document.canonicalFile
        require(PathSecurity.contains(boundary, file)) { "Language-server document escaped the workspace" }
        val directory = file.parentFile ?: boundary
        // Preserve this exact unavailable root for status/revoke; never borrow an ancestor grant.
        if (kind(directory) != Kind.DIRECTORY) return directory
        val ancestors = mutableListOf<File>()
        var candidate = directory
        while (PathSecurity.contains(boundary, candidate)) {
            ancestors += candidate
            if (kind(candidate) != Kind.DIRECTORY || configBoundary(candidate).state != ConfigState.NONE) return candidate
            if (candidate == boundary) break
            candidate = candidate.parentFile ?: break
        }
        return ancestors.firstOrNull { root ->
            kind(File(root, "package.json")) == Kind.FILE || vcs.any { kind(File(root, it)) == Kind.DIRECTORY }
        } ?: directory
    }

    fun projectAvailable(project: File): Boolean =
        kind(project) == Kind.DIRECTORY && configBoundary(project).state != ConfigState.UNAVAILABLE

    /** Binds marker selection/types, not executable configuration contents or editor generation. */
    fun selectionIdentity(project: File): JsonObject {
        val selection = configBoundary(project)
        return buildJsonObject {
            put("root", project.absoluteFile.normalize().path)
            put("directory", kind(project).name)
            put("state", selection.state.name)
            selection.selected?.let { put("selected", it) }
            put("markers", buildJsonObject { selection.markers.forEach { (name, value) -> put(name, value.name) } })
        }
    }

    fun clientCapabilities(base: JsonObject): JsonObject {
        val workspace = JsonObject(base.getValue("workspace").jsonObject + ("configuration" to JsonPrimitive(false)))
        val documents = base.getValue("textDocument").jsonObject
        val definition = (documents["definition"] as? JsonObject).orEmpty() + ("linkSupport" to JsonPrimitive(true))
        return JsonObject(base + ("workspace" to workspace) +
            ("textDocument" to JsonObject(documents + ("definition" to JsonObject(definition)))))
    }

    const val INSTALLATION_DISCLOSURE = "Enabling analysis does not install packages or authorize Prisma generate, migrations or database commands. Managed installation and zero-document health are separate from project-analysis consent."
    const val APPROVAL_EXPLANATION = "Prisma legacy-schema analysis repeatedly imports executable JavaScript/TypeScript project configuration and reads schema files. Configuration can execute code using the selected Ubuntu user's environment, HOME, configuration and credentials, read or write files outside this folder and access the network. Recursive schema reads can follow symlinks outside this root; config.schema, the config loader and PRoot are not a sandbox. Known IDE changes can restart analysis and repeat config imports. A config import failure may continue with defaults instead of the intended configuration. Discovered executable/package/runtime/prelude provenance is unverified; a command name alone does not establish the managed pin or debug redirection."
    const val FEATURE_NOTE = "Legacy Prisma schemas only. Prisma Next/ORM 8 requires a separate project-local adapter; Prisma 6 and mixed-generation disk schemas are unverified. Original managed 31.13.0 uses a sealed public-entry debug-to-stderr prelude and zero-document transport health; a discovered same-name command does not inherit those guarantees. Editor transport retains existing bounded framing, without arbitrary project stdout isolation or full JSON-RPC conformance guarantees. Diagnostics are unversioned; empty reports or silence do not certify current validity. Config import failure can fall back to defaults. Known IDE writes retire analysis; external disk/config changes require restart/reopen. Server commands, generate/migrate/database actions, TypeScript restart integration and completion-resolve UI are not integrated."
}
