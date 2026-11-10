package com.baystudio.droide.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*

/** App/environment-scoped durable build exclusion. No workspace close or timeout releases a key. */
class CMakeBuildOwnerLeases private constructor(private val directory: File) {
    class Lease internal constructor(
        val id: String,
        val nonce: String,
        val request: CMakeOwnerRequest,
        internal val file: File,
        internal var phase: String = "RESERVED",
        internal var ready: CMakeReadyProof? = null,
    )
    private val lock = Any()
    private val live = mutableMapOf<String, Lease>()

    fun reserve(request: CMakeOwnerRequest): Lease = synchronized(lock) {
        val selection = request.selection
        val configured = selection.configuredBuildDirectoryIdentity
        val records = records()
        check(records.size < 64 && (configured == null || records.count { it["local"]?.jsonPrimitive?.content?.isNotEmpty() == true } < 32)) { "CMake configured-build lease capacity exhausted" }
        if (configured != null) {
            check(records.none { record -> conflicts(record, selection.environmentIdentity,
                requireNotNull(selection.configuredBuildLocalCanonical), requireNotNull(selection.configuredBuildGuestAbsolute), configured) }) {
                "Selected CMake build is waiting for an owned process group or is quarantined"
            }
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        val lease = Lease(id, UUID.randomUUID().toString().replace("-", ""), request, File(directory, "$id.json"))
        // Builtin mode still keeps a process-owner tombstone, but does not occupy a configured key.
        persist(lease)
        live[id] = lease
        lease
    }
    fun starting(lease: Lease) = synchronized(lock) {
        requireLive(lease); check(lease.phase == "RESERVED")
        // RESERVED was durably recorded before entering the protected factory. An app restart without
        // READY remains quarantined regardless of this in-memory phase. No disk I/O here.
        lease.phase = "STARTING"
    }
    fun ready(lease: Lease, proof: CMakeReadyProof) = synchronized(lock) {
        requireLive(lease); check(lease.phase == "STARTING")
        check(matches(lease, proof))
        lease.ready = proof; lease.phase = "READY"; persist(lease)
    }
    fun quarantine(lease: Lease) = synchronized(lock) {
        requireLive(lease); lease.phase = "QUARANTINED"; persist(lease)
    }
    fun releaseNeverStarted(lease: Lease) = synchronized(lock) {
        requireLive(lease); check(lease.phase == "RESERVED") { "Started owner needs composite group proof" }
        remove(lease)
    }
    fun release(lease: Lease, receipt: CMakeTeardownReceipt) = synchronized(lock) {
        requireLive(lease)
        check(receipt.releasesBuild && receipt.leaseId == lease.id && receipt.supervisorNonce == lease.nonce &&
            receipt.ready == lease.ready && receipt.ownerGeneration == lease.request.ownerGeneration &&
            receipt.environmentIdentity == lease.request.selection.environmentIdentity) { "CMake composite release proof is incomplete" }
        remove(lease)
    }
    private fun remove(lease: Lease) {
        check(lease.file.delete()) { "CMake durable lease removal failed" }
        CMakePrivateState.syncDirectory(directory)
        live.remove(lease.id); lease.phase = "RELEASED"
    }
    private fun requireLive(lease: Lease) { check(live[lease.id] === lease && lease.phase != "RELEASED") }
    private fun matches(lease: Lease, proof: CMakeReadyProof) = proof.leaseId == lease.id && proof.nonce == lease.nonce &&
        proof.ownerGeneration == lease.request.ownerGeneration && proof.environmentIdentity == lease.request.selection.environmentIdentity
    private fun persist(lease: Lease) {
        val s = lease.request.selection
        val value = buildJsonObject {
            put("schema", 1); put("id", lease.id); put("nonce", lease.nonce); put("phase", lease.phase)
            put("environment", s.environmentIdentity); put("ownerGeneration", lease.request.ownerGeneration)
            put("local", s.configuredBuildLocalCanonical ?: ""); put("guest", s.configuredBuildGuestAbsolute ?: "")
            s.configuredBuildDirectoryIdentity?.let { put("device", it.device); put("inode", it.inode); put("uid", it.ownerUid); put("mode", it.mode) }
            lease.ready?.let { put("ready", CMakeControlRecords.readyJson(it)) }
        }
        CMakePrivateState.durableWrite(lease.file, value.toString())
    }
    private fun records(): List<JsonObject> {
        val files = directory.listFiles() ?: error("CMake lease registry is unavailable")
        require(files.size <= 128) { "CMake lease registry capacity exceeded" }
        return files.filter { it.name.endsWith(".json") }.map { file ->
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..16_384)
            val value = CMakeControlRecords.strictObject(CMakeSelectionInspector.readCache(file, 16_384))
            validateRecord(file.name, value)
            value
        }
    }
    private fun validateRecord(filename: String, value: JsonObject) {
        fun text(key: String): String {
            val primitive = value[key] as? JsonPrimitive ?: error("Invalid CMake lease field: $key")
            require(primitive.isString)
            return primitive.content.also { require(it.length <= 4096 && it.none(Char::isISOControl)) }
        }
        fun number(key: String): Long {
            val primitive = value[key] as? JsonPrimitive ?: error("Invalid CMake lease number: $key")
            require(!primitive.isString)
            return requireNotNull(primitive.longOrNull)
        }
        val id = text("id"); val nonce = text("nonce")
        require(id.matches(Regex("[a-f0-9]{32}")) && nonce.matches(Regex("[a-f0-9]{32}")) && filename == "$id.json") {
            "Malformed CMake lease name/identity; retained for review"
        }
        require(number("schema") == 1L && number("ownerGeneration") >= 0)
        require(text("phase") in setOf("RESERVED", "STARTING", "READY", "QUARANTINED"))
        require(text("environment").isNotEmpty())
        val local = text("local"); val guest = text("guest")
        val identityFields = setOf("device", "inode", "uid", "mode")
        val baseFields = setOf("schema", "id", "nonce", "phase", "environment", "ownerGeneration", "local", "guest")
        require(value.keys.all { it in baseFields || it in identityFields || it == "ready" })
        if (local.isEmpty()) {
            require(guest.isEmpty() && value.keys.none { it in identityFields })
        } else {
            require(File(local).isAbsolute && File(local).toPath().normalize().toString() == local)
            require(CMakeAnalysisSelections.normalizeGuestPath(guest) == guest)
            require(number("device") >= 0 && number("inode") > 0 && number("uid") >= 0)
            require(number("mode") in 0..65535 && android.system.OsConstants.S_ISDIR(number("mode").toInt()))
        }
        value["ready"]?.let {
            val proof = CMakeControlRecords.parseReady(it.jsonObject)
            require(proof.leaseId == id && proof.nonce == nonce && proof.environmentIdentity == text("environment") &&
                proof.ownerGeneration == number("ownerGeneration"))
        }
        require(text("phase") != "READY" || value["ready"] != null)
    }
    private fun conflicts(record: JsonObject, environment: String, local: String, guest: String, identity: CMakeDirectoryIdentity): Boolean {
        if (record["environment"]?.jsonPrimitive?.content != environment) return false
        return record["local"]?.jsonPrimitive?.content == local || record["guest"]?.jsonPrimitive?.content == guest ||
            (record["device"]?.jsonPrimitive?.longOrNull == identity.device && record["inode"]?.jsonPrimitive?.longOrNull == identity.inode)
    }

    /** Read-only kernel observation followed by durable key release. Never guesses numeric signal authority. */
    fun recover(): Int = synchronized(lock) {
        var recovered = 0
        records().forEach { record ->
            val id = record["id"]?.jsonPrimitive?.content ?: return@forEach
            if (live.containsKey(id)) return@forEach
            val proof = runCatching { CMakeControlRecords.parseReady(record.getValue("ready").jsonObject) }.getOrNull() ?: return@forEach
            if (proof.leaseId != id || proof.nonce != record["nonce"]?.jsonPrimitive?.content ||
                proof.environmentIdentity != record["environment"]?.jsonPrimitive?.content ||
                proof.ownerGeneration != record["ownerGeneration"]?.jsonPrimitive?.longOrNull) return@forEach
            if (!CMakeControlRecords.sameKernel(proof)) return@forEach
            if (!originalAbsent(proof.outer) || !originalAbsent(proof.supervisor)) return@forEach
            val groupGone = try { Os.kill(-proof.leader.pid, 0); false }
                catch (failure: ErrnoException) { failure.errno == OsConstants.ESRCH }
            if (!groupGone || !originalAbsent(proof.outer) || !originalAbsent(proof.supervisor)) return@forEach
            val file = File(directory, "$id.json")

            if (file.length() <= 16_384 && CMakeControlRecords.read(file) == record && file.delete()) {
                CMakePrivateState.syncDirectory(directory); recovered++
            }
        }
        recovered
    }
    private fun originalAbsent(birth: CMakeProcessBirth): Boolean {
        val statPath = "/proc/${birth.pid}/stat"
        fun absentNow(): Boolean = try { Os.lstat(statPath); false }
            catch (failure: ErrnoException) { failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ESRCH }
            catch (_: Exception) { false }
        try { Os.lstat(statPath) }
        catch (failure: ErrnoException) { return failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ESRCH }
        catch (_: Exception) { return false }
        return try {
            // FileNotFoundException also means EACCES on Android. Only an exact native absence observation,
            // or a successfully read different birth, permits recovery.
            CMakeControlRecords.procStartTicks(birth.pid) != birth.startTicks
        } catch (_: Exception) { absentNow() }
    }

    companion object {
        private val instances = mutableMapOf<String, CMakeBuildOwnerLeases>()
        fun forApp(appRoot: File): CMakeBuildOwnerLeases = synchronized(instances) {
            val root = appRoot.canonicalPath
            instances.getOrPut(root) {
                val directory = File(appRoot, "run/cmake-build-leases")
                CMakeSelectionInspector.requireCanonicalNoSymlinks(directory)
                check(!Files.isSymbolicLink(directory.toPath()) && (directory.isDirectory || directory.mkdirs()))
                Os.chmod(directory.absolutePath, 448)
                CMakeBuildOwnerLeases(directory)
            }
        }
    }
}
