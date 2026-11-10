package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// A sibling intent survives the tree rename without changing a sealed package payload.
internal object LocalPackageRemovalJournal {
    data class Intent(val familyId: String, val version: String, val installRoot: String, val activationId: String?)

    fun entryName(record: ManagedPackageRecord): String = DevicePackageRemovalJournal.entryName(record)
    fun metadata(trash: File): File = File(trash.parentFile, trash.name + ".receipt")

    fun prepare(trash: File, record: ManagedPackageRecord) {
        LocalPackageInstallJournal.requireSafeParents(trash)
        val intent = Intent(record.familyId, record.version, record.installRoot,
            record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
        check(intent.activationId == null || runCatching {
            java.util.UUID.fromString(intent.activationId).toString() == intent.activationId
        }.getOrDefault(false)) { "Invalid local removal generation" }
        check(entryName(record) == trash.name) { "Local removal journal identity is inconsistent" }
        val destination = metadata(trash)
        check(!destination.exists() && !PathSecurity.isSymbolicLink(destination)) { "Interrupted local removal intent needs recovery before retry" }
        val body = JsonObject(mapOf(
            "familyId" to JsonPrimitive(intent.familyId),
            "version" to JsonPrimitive(intent.version),
            "installRoot" to JsonPrimitive(intent.installRoot),
            "activationId" to (intent.activationId?.let(::JsonPrimitive) ?: JsonNull),
        )).toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(destination).use { output -> output.write(body); output.fd.sync() }
    }

    fun read(trash: File): Intent? {
        LocalPackageInstallJournal.requireSafeParents(trash)
        val file = metadata(trash)
        if (!file.exists() && !PathSecurity.isSymbolicLink(file)) return null
        check(file.isFile && !PathSecurity.isSymbolicLink(file) && file.length() in 1..8192) { "Unsafe local removal intent was preserved" }
        val objectValue = Json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Invalid local removal intent was preserved")
        fun value(key: String): String = (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid local removal intent was preserved")
        val activation = objectValue["activationId"]
        check(activation == JsonNull || activation is JsonPrimitive && activation.isString) { "Invalid local removal generation was preserved" }
        val intent = Intent(value("familyId"), value("version"), value("installRoot"),
            (activation as? JsonPrimitive)?.contentOrNull)
        val identity = ManagedPackageRecord(intent.familyId, intent.version, "", intent.installRoot, 0)
        check(entryName(identity) == trash.name) { "Local removal intent identity does not match its journal" }
        intent.activationId?.let { id ->
            check(runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid local removal generation was preserved" }
        }
        return intent
    }

    fun belongsTo(intent: Intent, record: ManagedPackageRecord): Boolean =
        intent.familyId == record.familyId && intent.version == record.version && intent.installRoot == record.installRoot

    fun matchesTree(record: ManagedPackageRecord, tree: File): Boolean {
        if (!tree.isDirectory || PathSecurity.isSymbolicLink(tree)) return false
        val id = record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] ?: return false
        return runCatching { LocalPackageInstallJournal.matchesActivation(tree, id) }.getOrDefault(false)
    }

    fun matchesIntent(intent: Intent, tree: File): Boolean {
        if (!tree.isDirectory || PathSecurity.isSymbolicLink(tree)) return false
        val id = intent.activationId ?: return true
        return runCatching { LocalPackageInstallJournal.matchesActivation(tree, id) }.getOrDefault(false)
    }

    fun restore(source: File, trash: File, conflictRoot: File) {
        LocalPackageInstallJournal.requireSafeParents(source)
        LocalPackageInstallJournal.requireSafeParents(conflictRoot)
        val intent = read(trash)
        check(intent == null || matchesIntent(intent, trash)) { "Conflicting local removal generation was preserved" }
        check(trash.isDirectory && !PathSecurity.isSymbolicLink(trash)) { "Unsafe local removal backup was preserved" }
        val sourcePresent = source.exists() || PathSecurity.isSymbolicLink(source)
        var conflict: File? = null
        if (sourcePresent) {
            check(!PathSecurity.isSymbolicLink(source) && source.isDirectory) { "Unsafe local package source was preserved" }
            check(conflictRoot.mkdirs() || conflictRoot.isDirectory) { "Cannot preserve conflicting local package copy" }
            check(!PathSecurity.isSymbolicLink(conflictRoot)) { "Unsafe local removal conflict directory" }
            conflict = File(conflictRoot, java.util.UUID.randomUUID().toString())
            check(source.renameTo(conflict)) { "Cannot preserve conflicting local package copy" }
        }
        if (!trash.renameTo(source)) {
            if (conflict != null) check(conflict.renameTo(source)) { "Cannot restore original local package after recovery failure" }
            error("Cannot restore interrupted local package removal")
        }
        check(PathSecurity.deleteTreeNoFollow(metadata(trash))) { "Cannot clean recovered local removal intent" }
    }

    fun discard(trash: File): Boolean {
        val intent = read(trash)
        if (trash.exists() || PathSecurity.isSymbolicLink(trash)) {
            check(intent == null || matchesIntent(intent, trash)) { "Conflicting local removal generation was preserved" }
        }
        return PathSecurity.deleteTreeNoFollow(trash) && PathSecurity.deleteTreeNoFollow(metadata(trash))
    }
}
