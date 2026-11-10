package com.baystudio.droide.core

/** A capability receipt owns its marker and transaction cache, never shared distro packages. */
internal object FoundryUbuntuLayerReleasePolicy {
    fun command(layerId: String): List<String> {
        require(layerId.matches(Regex("[0-9a-f]{24}"))) { "Invalid Ubuntu capability layer identity" }
        return listOf("sh", "-lc", "set -eu; " +
            "rm -f '/var/lib/droide-foundry/layers/$layerId.lock'; " +
            "rm -rf '/var/cache/droide-foundry/$layerId'")
    }
}
