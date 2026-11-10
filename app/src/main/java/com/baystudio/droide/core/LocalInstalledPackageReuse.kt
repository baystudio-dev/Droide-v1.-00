package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Reuse requires exact reviewed identity and a fresh supplied proof. Private prerequisites supply an independently owned health-free full byte/closure proof. */
internal object LocalInstalledPackageReuse {
    suspend fun artifact(
        record: ManagedPackageRecord?,
        familyId: String,
        version: String,
        sha256: String,
        owns: (ManagedPackageRecord) -> Boolean,
        verify: suspend (ManagedPackageRecord) -> Boolean,
        identity: (ManagedPackageRecord) -> Boolean = { true },
    ): ManagedPackageRecord? {
        currentCoroutineContext().ensureActive()
        if (record == null || record.familyId != familyId || record.version != version ||
            !sha256.matches(Regex("[0-9a-f]{64}")) || record.artifactSha256 != sha256 || !owns(record) || !identity(record)) return null
        return record.takeIf { verify(it) }
    }

    fun npmIdentityMatches(
        record: ManagedPackageRecord,
        recipe: WorkstationInstallRecipe,
        review: JsonObject,
        nodeDependency: ManagedPackageRecord?,
        nodeCommand: String,
        nodeMajor: Int,
        nodeVersion: String? = null,
        extraDependencies: List<String> = emptyList(),
    ): Boolean {
        if (recipe.familyId == LocalUbuntuGraphqlPolicy.FAMILY) return false // Independent GraphQL proof, never generic Node reuse.
        if (recipe.familyId == LocalUbuntuTomlPolicy.FAMILY) return false // Independent Taplo proof, never generic Node reuse.
        if (recipe.familyId == LocalUbuntuVuePolicy.FAMILY) return false // Vue uses its exact multiarchive receipt/content proof, never generic selected Node identity.
        val integrity = recipe.registryIntegrity ?: return false
        if (recipe.familyId == LocalUbuntuMusePolicy.FAMILY) {
            if (extraDependencies.size != 1 || !LocalUbuntuMusePolicy.isRuntimeKey(extraDependencies.single()) ||
                review["museRuntime"] != JsonPrimitive(extraDependencies.single())) return false
        } else if (extraDependencies.isNotEmpty()) return false
        val expectedDependencies = nodeDependency?.let { listOf("${it.familyId}@${it.version}") }.orEmpty() + extraDependencies
        fun text(key: String): String? = (review[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return record.familyId == recipe.familyId && record.version == recipe.version &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM &&
            record.dependencies == expectedDependencies &&
            (review["schema"] as? JsonPrimitive)?.intOrNull == 1 &&
            text("familyId") == recipe.familyId && text("version") == recipe.version &&
            text("packageName") == recipe.packageName && text("packageVersion") == recipe.packageVersion &&
            text("registryIntegrity") == integrity && text("nodeCommand") == nodeCommand &&
            (review["nodeMajor"] as? JsonPrimitive)?.intOrNull == nodeMajor &&
            review["additionalDependencies"] == buildJsonObject {
                recipe.additionalDependencies.forEach { (name, version) -> put(name, version) }
            } &&
            (review["allowLifecycleScripts"] as? JsonPrimitive)?.booleanOrNull == recipe.allowLifecycleScripts &&
            text("lifecycleScriptReview") == recipe.lifecycleScriptReview &&
            (recipe.familyId != LocalUbuntuSveltePolicy.FAMILY_ID || (nodeVersion != null && expectedDependencies.size == 1 &&
                LocalUbuntuSvelteLaunchPolicy.profileMatches(review, nodeCommand, nodeVersion, expectedDependencies.single()))) &&
            (recipe.familyId != LocalUbuntuPrismaPolicy.FAMILY_ID || (nodeVersion != null &&
                LocalUbuntuPrismaLaunchPolicy.profileMatches(review, nodeCommand, nodeVersion, expectedDependencies.singleOrNull())))
    }
}
