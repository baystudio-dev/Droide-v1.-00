package com.baystudio.droide.core

object LocalLinuxPackageEnvironment {
    private val supportedKinds = setOf(
        LocalUbuntuElpPolicy.KIND,
        LocalUbuntuCSharpPolicy.KIND,
        LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
        LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
        LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
        LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS,
        LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
        LocalManagedPackageMetadata.KIND_UBUNTU_RUBY,
        LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE,
        LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION,
        LocalManagedPackageMetadata.KIND_UBUNTU_APT,
        LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT,
    )

    fun supportsWorkspaceSelection(record: ManagedPackageRecord, appRoot: String): Boolean =
        record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] in supportedKinds &&
            guestPath(record.installRoot, "$appRoot/packages/") != null &&
            (record.familyId != LocalUbuntuElpPolicy.FAMILY || record.active && record.metadata["elp.health"] == LocalUbuntuElpLspHealth.LABEL)

    fun selectedRecords(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): List<ManagedPackageRecord> {
        val eligible = records.filter { supportsWorkspaceSelection(it, appRoot) }
        return eligible.groupBy { it.familyId }
            .toSortedMap()
            .values
            .mapNotNull { familyRecords ->
                val family = familyRecords.first().familyId
                val selected = workspaceSelections[family]
                familyRecords.firstOrNull { selected != null && it.version == selected }
                    ?: familyRecords.filter { it.active }.singleOrNull()
            }
            .take(128)
    }

    fun guestBinPaths(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): List<String> = selectedRecords(records, workspaceSelections, appRoot)
        // Private health-only commands do not add an original Node/compiler directory to terminal PATH.
        .filter { it.commands.isNotEmpty() && it.familyId !in setOf("runtime.graphql-node",LocalUbuntuTomlNodePolicy.FAMILY,LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuVueHelperPolicy.FAMILY,LocalUbuntuCSharpSdkPolicy.FAMILY,LocalUbuntuCSharpHelperPolicy.FAMILY) }
        .flatMap { record ->
            val wrapper = guestPath(record.installRoot, "$appRoot/packages/")?.let { "$it/payload/bin" }
            val home = record.environment["JAVA_HOME"]?.takeIf { it.startsWith("${record.installRoot}/") }
                ?.let { guestPath(it, "$appRoot/packages/") }
            listOfNotNull(home?.takeIf { record.familyId == LocalUbuntuJdkPolicy.FAMILY }?.let { "$it/bin" }, wrapper)
        }
        .distinct()
        .take(64)

    fun guestEnvironment(
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        appRoot: String,
    ): Map<String, String> {
        val packageRoot = "$appRoot/packages/"
        val out = linkedMapOf<String, String>()
        selectedRecords(records, workspaceSelections, appRoot).forEach { record ->
            record.environment.toSortedMap().forEach environment@{ (key, value) ->
                if (key == "PATH") return@environment
                if (!value.startsWith("${record.installRoot}/")) return@environment
                val guest = guestPath(value, packageRoot) ?: return@environment
                out[key] = guest
            }
        }
        return out.toMap()
    }

    internal fun guestPath(hostPath: String, packageRoot: String): String? {
        if (!hostPath.startsWith(packageRoot)) return null
        val relative = hostPath.removePrefix(packageRoot)
        if (relative.isBlank() || relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return "/opt/droide/packages/$relative"
    }
}
