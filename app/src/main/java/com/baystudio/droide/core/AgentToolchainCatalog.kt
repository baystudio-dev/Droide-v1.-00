package com.baystudio.droide.core

/** Search is catalog discovery; list alone only describes existing receipts. */
internal object AgentToolchainCatalog {
    suspend fun query(authority: UnifiedPackageAuthority, query: String?): String {
        val matches = authority.search(query.orEmpty().trim())
        val installed = authority.list().groupBy { it.familyId }
        return buildString {
            appendLine("UNIFIED_PACKAGE_AUTHORITY_CATALOG")
            matches.forEach { item ->
                append(item.familyId).append(" · ").append(item.name)
                append(" · versions=").append(item.versions.joinToString(","))
                append(" · installed=")
                append(installed[item.familyId]?.joinToString(",") { "${it.version} (${it.state})" } ?: "none")
                appendLine()
            }
            if (matches.isEmpty()) appendLine("No matching catalog entries found.")
            appendLine("Catalog metadata does not prove a runtime is installed or installable. Use toolchain status for availability and dependencies; install through the reviewed provider when available.")
        }
    }
}
