package com.baystudio.droide.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Existing {"current":"name"} format, isolated from palette/rendering and custom-theme parsing. */
internal class ThemeSelectionPersistence(
    private val workspace: File,
    private val publish: (Path, Path) -> Unit = { temporary, target ->
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
    },
) {
    private fun file(): File {
        val raw = File(workspace, ".droide/theme.json")
        require(!Files.isSymbolicLink(raw.toPath())) { "Theme selection cannot be a symbolic link" }
        return PathSecurity.resolveWithin(workspace, ".droide/theme.json")
    }

    @Synchronized fun read(): String? {
        val target = file()
        if (!target.exists()) return null
        require(target.isFile && target.length() in 1..16_000) { "Invalid theme selection file" }
        val root = Json.parseToJsonElement(target.readText()) as? JsonObject ?: error("Invalid theme selection")
        val name = root["current"] as? JsonPrimitive ?: error("Missing theme selection")
        require(name.isString && name.content.isNotBlank() && name.content.length <= 80) { "Invalid theme name" }
        return name.content
    }

    @Synchronized fun write(name: String): Unit =
        ExportWriterAdmission.withWriter(workspace, "Theme selection persistence") {
        require(name.isNotBlank() && name.length <= 80) { "Invalid theme name" }
        val target = file()
        val parent = target.parentFile ?: error("Theme directory is unavailable")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create theme directory" }
        val temporary = Files.createTempFile(parent.toPath(), ".theme.json.", ".tmp")
        try {
            Files.write(temporary, buildJsonObject { put("current", name) }.toString().toByteArray(Charsets.UTF_8))
            check(file().toPath() == target.toPath()) { "Theme destination changed during save" }
            publish(temporary, target.toPath())
        } finally { Files.deleteIfExists(temporary) }
        Unit
    }
}
