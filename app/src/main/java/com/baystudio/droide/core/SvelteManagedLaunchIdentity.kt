package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Exact selected app-owned generation. This value grants no authority until rechecked at creation. */
data class SvelteManagedLaunchIdentity(
    val familyId: String,
    val version: String,
    val contractSha256: String,
    val receiptSha256: String,
    val nodeReceiptSha256: String,
    val sealSha256: String,
    val physicalProofSha256: String,
    val manifestSha256: String,
    val launchPolicyRevision: String,
    val nodeCommand: String,
    val nodeVersion: String,
    val selectedPackageIdentity: String,
    val discoveredExecutable: String,
    val launchExecutable: String,
)

/** Called only under the established package gates. No shell, discovery probe, or health recursion. */
internal object SvelteManagedLaunchAuthority {
    suspend fun inspect(appRoot: String, authority: LocalManagedPackageAuthority, records: List<ManagedPackageRecord>,
        selections: Map<String,String>, discovered: String): SvelteManagedLaunchIdentity? = try {
        currentCoroutineContext().ensureActive()
        require(records.size <= 512)
        val record = records.single { it.familyId == LocalUbuntuSveltePolicy.FAMILY_ID && it.version == LocalUbuntuSveltePolicy.VERSION }
        require(authority.ownedReceipt(record.familyId,record.version) == record)
        val selected = LocalLinuxPackageEnvironment.selectedRecords(records,selections,appRoot)
        require(selected.singleOrNull { it.familyId == record.familyId } == record &&
            (selections[record.familyId] == null || selections[record.familyId] == record.version))
        val dependency = record.dependencies.single()
        val node = records.single { "${it.familyId}@${it.version}" == dependency }
        require(selected.singleOrNull { it.familyId == "runtime.node" } == node &&
            (selections["runtime.node"] == null || selections["runtime.node"] == node.version))
        require(record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.metadata[PackageBackendContract.METADATA_KEY] == PackageBackendId.LOCAL_APP.name)
        val root = File(record.installRoot)
        LocalUbuntuSveltePolicy.requireRealDirectory(root)
        require(root.canonicalPath == root.absolutePath && records.none { it != record && it.installRoot == record.installRoot })
        require(authority.verifySveltePackageWithoutHealth(record))
        val guest = requireNotNull(LocalLinuxPackageEnvironment.guestPath(root.absolutePath,"$appRoot/packages/"))
        val wrapper = "$guest/payload/bin/svelteserver"
        require(discovered == wrapper || discovered == "/opt/droide/bin/svelteserver")
        var projectionHash = "version-specific"
        if (discovered == "/opt/droide/bin/svelteserver") {
            val projection = File(appRoot,"managed/ubuntu-bin/svelteserver")
            val bytes = LocalUbuntuSveltePolicy.readRegular(projection,65536)
            require(bytes == "#!/bin/sh\nexec '${wrapper.replace("'", "'\\''")}' \"\$@\"\n" && projection.canExecute())
            projectionHash = LocalUbuntuSveltePolicy.hash(bytes)
        }
        val reviewText = LocalUbuntuSveltePolicy.readRegular(File(root,"RECIPE_LOCK.json"),64000)
        val review = LocalUbuntuSveltePolicy.strictObject(reviewText)
        val profile = LocalUbuntuSvelteLaunchPolicy.validateReview(review,dependency)
        val activation = record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)
        currentCoroutineContext().ensureActive()
        SvelteManagedLaunchIdentity(record.familyId,record.version,LocalUbuntuSveltePolicy.CONTRACT_SHA256,
            receiptHash(record),receiptHash(node),record.metadata.getValue(LocalManagedPackageMetadata.SEAL_SHA256_KEY),
            LocalUbuntuNpmPolicy.sha256(File(root,LocalUbuntuSveltePolicy.PROOF_FILE)),LocalUbuntuSveltePolicy.manifestSha256,
            LocalUbuntuSvelteLaunchPolicy.REVISION,profile.getValue("nodeCommand").jsonPrimitive.content,node.version,
            "${record.familyId}@${record.version}:$activation:${selections[record.familyId] ?: "active"}:${selections["runtime.node"] ?: "active"}:$projectionHash",
            discovered,wrapper)
    } catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { null }

    private fun receiptHash(record: ManagedPackageRecord): String {
        fun strings(values:List<String>)=JsonArray(values.map(::JsonPrimitive))
        fun mapping(values:Map<String,String>)=JsonObject(values.mapValues { JsonPrimitive(it.value) })
        return LocalUbuntuSveltePolicy.hash(LocalUbuntuSveltePolicy.canonical(buildJsonObject {
            put("family",record.familyId);put("version",record.version);put("scope",record.scope);put("root",record.installRoot)
            put("installedAt",record.installedAtEpochMs);put("active",record.active);put("paths",strings(record.pathEntries))
            put("environment",mapping(record.environment));put("commands",mapping(record.commands));put("dependencies",strings(record.dependencies))
            put("metadata",mapping(record.metadata));put("artifact",record.artifactSha256?.let(::JsonPrimitive) ?: JsonNull)
            put("checksum",record.checksumFile);put("abi",record.abi?.let(::JsonPrimitive) ?: JsonNull)
            put("health",JsonArray(record.healthChecks.map { buildJsonObject { put("executable",it.executable);put("args",strings(it.args)) } }))
        }))
    }
}
