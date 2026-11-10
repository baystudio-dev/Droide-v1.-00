package com.baystudio.droide.core

/** Shared distro commands may have multiple integrations; unrelated command owners still conflict. */
internal object LocalCommandProjectionPolicy {
    fun project(records: List<ManagedPackageRecord>, appRoot: String): List<ManagedPackageRecord> {
        val active = LocalUbuntuBundleAliasPolicy.projectCommands(records, appRoot).sortedBy { it.familyId }
        val owners = mutableMapOf<String, ManagedPackageRecord>()
        return active.map { record ->
            record.copy(commands = record.commands.filter { (name, _) ->
                val previous = owners[name]
                if (previous == null) { owners[name] = record; true }
                else {
                    require(equivalentApt(previous, record, name, appRoot)) { "Local command collision for $name" }
                    false
                }
            })
        }
    }
    private fun equivalentApt(first: ManagedPackageRecord, second: ManagedPackageRecord, command: String, root: String): Boolean {
        fun recipe(record: ManagedPackageRecord): UbuntuAptRecipe? {
            val recipe = UbuntuAptCatalog.find(record.familyId) ?: return null
            val expected = "$root/packages/${record.familyId}/${recipe.version}/arm64-v8a"
            return recipe.takeIf {
                record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_APT &&
                    record.version == recipe.version && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
                    record.installRoot == expected && record.commands[command] == "$expected/payload/host-bin/$command"
            }
        }
        val left = recipe(first) ?: return false
        val right = recipe(second) ?: return false
        return left.commands[command] == right.commands[command] && left.launchArgs(command) == right.launchArgs(command)
    }
}
