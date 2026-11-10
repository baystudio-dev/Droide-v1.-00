package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object LocalUbuntuTypeScriptServerPolicy {
    const val VERSION = "6.0.3"
    const val INTEGRITY = "sha512-y2TvuxSZPDyQakkFRPZHKFm+KKVqIisdg9/CZwm9ftvKXLP8NRWj38/ODjNbr43SsoXqNuAisEf1GdCxqWcdBw=="

    fun requireCompatibleMetadata(metadata: JsonObject): String {
        require(metadata["name"]?.jsonPrimitive?.content == "typescript" &&
            metadata["version"]?.jsonPrimitive?.content?.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) == true) { "TypeScript server dependency identity changed" }
        require(metadata["bin"]?.jsonObject?.get("tsserver")?.jsonPrimitive?.content in setOf("bin/tsserver", "./bin/tsserver")) {
            "TypeScript dependency does not provide tsserver"
        }
        val dist = metadata["dist"]?.jsonObject ?: error("TypeScript server artifact metadata is missing")
        LocalUbuntuNpmPolicy.requireIntegrity(dist["integrity"]?.jsonPrimitive?.content ?: error("TypeScript integrity is missing"))
        LocalUbuntuNpmPolicy.requireRegistryTarball(dist["tarball"]?.jsonPrimitive?.content ?: error("TypeScript server artifact URL is missing"))
        return requireNotNull(metadata["version"]?.jsonPrimitive?.content)
    }
}
