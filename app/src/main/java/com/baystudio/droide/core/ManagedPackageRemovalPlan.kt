package com.baystudio.droide.core

// The caller's UI snapshot selects an identity; the durable receipt selects the files and state.
internal object ManagedPackageRemovalPlan {
    data class Plan(val current: ManagedPackageRecord, val remaining: List<ManagedPackageRecord>)

    fun resolve(records: List<ManagedPackageRecord>, familyId: String, version: String): Plan {
        val current = records.singleOrNull { it.familyId == familyId && it.version == version }
            ?: error("Managed package receipt is missing or duplicated")
        val key = "$familyId@$version"
        val remaining = records.filterNot { it.familyId == familyId && it.version == version }
        val dependents = remaining.filter { key in it.dependencies }
        check(dependents.isEmpty()) {
            "Package is required by: " + dependents.joinToString { "${it.familyId}@${it.version}" }
        }
        if (!current.active) return Plan(current, remaining)
        val fallback = remaining.filter { it.familyId == familyId }.maxByOrNull { it.installedAtEpochMs }
        return Plan(current, remaining.map { candidate ->
            if (candidate.familyId == familyId) candidate.copy(active = candidate.version == fallback?.version) else candidate
        })
    }

    suspend fun requireHealthyFallback(plan: Plan, verify: suspend (ManagedPackageRecord) -> Boolean) {
        if (!plan.current.active) return
        val fallback = plan.remaining.firstOrNull { it.familyId == plan.current.familyId && it.active } ?: return
        check(verify(fallback)) {
            "Fallback ${fallback.familyId}@${fallback.version} failed verification; repair it before removing the active package"
        }
    }
}
