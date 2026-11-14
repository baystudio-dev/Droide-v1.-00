package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.*

/** A durable observation of exact old owners, NOT evidence that they stopped.
 * A later boot on the same local Android device/proof source can discharge those
 * owners, including legacy rows with unknown birth boots. Production boot identity
 * attests that this mirror is in app-private local storage, never a remote mount.
 * Called only under the authority's SQLite write transaction. Proof survives a
 * failed launch transaction; no runtime/PID/liveness guess grants admission.
 */
internal class MirrorProcessRebootBarrier(private val directory: File, private val binding: String,
    private val checkpoint: (String) -> Unit = {},
) {
    private data class Observation(val boot: WorkspaceProcessBootIdentity.Identity, val owners: List<MirrorProcessCustodyLedger.Owner>)

    fun observe(owners: List<MirrorProcessCustodyLedger.Owner>, current: WorkspaceProcessBootIdentity.Identity?, runtimeId: String): Boolean =
        AnchoredWorkspaceFiles(directory).use { authority ->
            if (authority.kind(FOLDER) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                if (current == null || owners.none { it.runtimeId != runtimeId && (it.boot == null || it.boot.device == current.device) }) return@use false
                check(authority.createDirectory(FOLDER).durable) { "CLI reboot observation directory durability is unconfirmed" }
            }
            authority.openDirectory(FOLDER).use { files ->
                val names = names(files)
                // Retire only against the committed snapshot, never removals that
                // this transaction has not committed. UUID reuse is not permitted.
                for (id in names - owners.mapTo(mutableSetOf()) { it.id }) {
                    read(authority, files, id)
                    val key = files.openRead(id + SUFFIX).use { checkNotNull(it.observe().attributes.fileKey()) }
                    files.deleteRedundantMetadata(id + SUFFIX, key)
                }
                if (current == null) return@use false
                val foreign = owners.filter { it.runtimeId != runtimeId }
                var allObserved = foreign.isNotEmpty()
                for (owner in foreign) {
                    if (owner.boot != null && owner.boot.device != current.device) { allObserved = false; continue }
                    val existing = read(authority, files, owner.id)
                    if (existing != null) {
                        check(existing.owners == listOf(owner)) { "CLI reboot observation owner changed" }
                        if (existing.boot.device != current.device) allObserved = false
                    } else {
                        check(names(files).size < MirrorProcessCustodyLedger.MAX_OWNERS) { "CLI reboot observation capacity is full; work was preserved" }
                        val observation = Observation(current, listOf(owner))
                        // An interrupted private staging file never authorizes
                        // cleanup. Publish only complete forced bytes under the
                        // final owner name, so a crash cannot poison that name.
                        val stage = ".process-reboot-stage-${UUID.randomUUID()}"
                        authority.createNew(stage).use { output -> output.write(encode(authority, observation)); output.force(384) }
                        checkpoint("writer-reboot-observation-before-publish")
                        check(authority.moveNoReplaceDurable(stage, files, owner.id + SUFFIX).durable) {
                            "CLI reboot observation publication durability is unconfirmed"
                        }
                        check(read(authority, files, owner.id) == observation) { "CLI reboot recovery observation changed" }
                    }
                }
                allObserved
            }
        }

    fun remaining(owners: List<MirrorProcessCustodyLedger.Owner>, current: WorkspaceProcessBootIdentity.Identity?, runtimeId: String): List<MirrorProcessCustodyLedger.Owner> =
        AnchoredWorkspaceFiles(directory).use { authority ->
            if (authority.kind(FOLDER) == AnchoredWorkspaceFiles.Kind.ABSENT) return@use owners
            authority.openDirectory(FOLDER).use { files ->
                names(files)
                owners.filter { owner ->
                    val observation = read(authority, files, owner.id)
                    if (observation == null) true else {
                        check(observation.owners == listOf(owner)) { "CLI reboot observation owner changed" }
                        val eligible = owner.runtimeId != runtimeId && current != null && current.device == observation.boot.device && current.boot != observation.boot.boot
                        if (eligible) {
                            files.openRead(owner.id + SUFFIX).use { it.forceContents() }; files.forceDirectory()
                            check(read(authority, files, owner.id) == observation) { "CLI reboot observation changed before reconciliation" }
                        }
                        !eligible
                    }
                }
            }
        }

    private fun names(files: AnchoredWorkspaceFiles): Set<String> = buildSet {
        files.forEachChild { name ->
            check(size < MirrorProcessCustodyLedger.MAX_OWNERS && name.endsWith(SUFFIX)) { "Unknown or excessive CLI reboot recovery evidence" }
            val id = name.removeSuffix(SUFFIX)
            check(UUID.fromString(id).toString() == id && add(id) && files.kind(name) == AnchoredWorkspaceFiles.Kind.FILE) { "CLI reboot observation is unsafe" }
        }
    }

    private fun read(authority: AnchoredWorkspaceFiles, files: AnchoredWorkspaceFiles, id: String): Observation? {
        val name = id + SUFFIX
        if (files.kind(name) == AnchoredWorkspaceFiles.Kind.ABSENT) return null
        check(files.kind(name) == AnchoredWorkspaceFiles.Kind.FILE) { "CLI reboot recovery proof is unsafe; work was preserved" }
        val text = readStable(files, name, MAX_BYTES).toString(Charsets.UTF_8)
        val json = Json.parseToJsonElement(text).jsonObject
        check(json.keys == setOf("format", "binding", "authority", "device", "boot", "owners")) { "Unknown CLI reboot recovery proof" }
        check(json.getValue("format").jsonPrimitive.content == FORMAT && json.getValue("binding").jsonPrimitive.content == binding &&
            json.getValue("authority").jsonPrimitive.content == fingerprint(authority)) { "CLI reboot recovery proof belongs to another authority" }
        val boot = WorkspaceProcessBootIdentity.Identity(json.getValue("device").jsonPrimitive.content, json.getValue("boot").jsonPrimitive.content)
        val owners = json.getValue("owners").jsonArray.map { value ->
            val row = value.jsonArray
            check(row.size == 4 && row.all { it is JsonPrimitive && it.isString })
            val values = row.map { it.jsonPrimitive.content }
            check(UUID.fromString(values[0]).toString() == values[0] && UUID.fromString(values[1]).toString() == values[1])
            val recorded = if (values[2].isEmpty() && values[3].isEmpty()) null else WorkspaceProcessBootIdentity.Identity(values[2], values[3])
            MirrorProcessCustodyLedger.Owner(values[0], values[1], recorded)
        }
        check(owners.size == 1 && owners.single().id == id)
        val result = Observation(boot, owners)
        check(text.toByteArray(Charsets.UTF_8).contentEquals(encode(authority, result))) { "CLI reboot recovery proof is malformed; work was preserved" }
        return result
    }

    private fun encode(files: AnchoredWorkspaceFiles, observation: Observation): ByteArray = buildJsonObject {
        put("format", FORMAT); put("binding", binding); put("authority", fingerprint(files))
        put("device", observation.boot.device); put("boot", observation.boot.boot)
        put("owners", JsonArray(observation.owners.map { owner -> JsonArray(listOf(owner.id, owner.runtimeId,
            owner.boot?.device.orEmpty(), owner.boot?.boot.orEmpty()).map(::JsonPrimitive)) }))
    }.toString().toByteArray(Charsets.UTF_8).also { check(it.size <= MAX_BYTES) }

    private fun fingerprint(files: AnchoredWorkspaceFiles): String = files.directoryKey().toString() + ":" +
        MessageDigest.getInstance("SHA-256").digest(readStable(files, "identity", 4096)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun readStable(files: AnchoredWorkspaceFiles, name: String, limit: Int): ByteArray = files.openRead(name).use { input ->
        val before = input.observe()
        check(before.attributes.size() in 1L..limit.toLong()) { "CLI recovery proof is incomplete or too large" }
        val bytes = ByteArray(before.attributes.size().toInt()); var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0) { "CLI recovery proof is truncated" }; offset += count
        }
        val after = input.observe()
        check(input.read() == -1 && before.attributes.fileKey() == after.attributes.fileKey() && before.changed == after.changed &&
            before.attributes.size() == after.attributes.size() && before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime()) {
            "CLI recovery proof changed during inspection"
        }
        files.openRead(name).use { check(it.observe().attributes.fileKey() == before.attributes.fileKey()) { "CLI recovery proof was replaced" } }
        bytes
    }

    companion object {
        private const val FOLDER = "process-reboot-observations"
        private const val SUFFIX = ".boot"
        private const val FORMAT = "DROIDE-PROCESS-REBOOT-OBSERVATION-1"
        private const val MAX_BYTES = 65_536
    }
}
