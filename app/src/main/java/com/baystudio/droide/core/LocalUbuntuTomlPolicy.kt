package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

internal object LocalUbuntuTomlPolicy {
    const val FAMILY = "lsp.taplo-wasm"
    const val FAMILY_ID = FAMILY
    const val VERSION = "0.8.0"
    const val PACKAGE = "@taplo/lsp"
    const val COMMAND = "droide-taplo-lsp"
    const val CORE_VERSION = "0.2.0"
    const val POLICY = "taplo-original-wasm-v1"
    const val PROVENANCE = "https://github.com/tamasfe/taplo/tree/48e4c083e6a98891e3d6b2b65450970a3779e8cb"
    const val SOURCE_COMMIT = "48e4c083e6a98891e3d6b2b65450970a3779e8cb"
    const val SOURCE_BINDING_SHA256 = "6da5394e8494d0dd6a1d99d48d2f9e676d723ed0abeb364b18b9cd8ac0f7f62d"
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val PREREQUISITE_MESSAGE = "Install the exact independent runtime.taplo-node 22.23.3 prerequisite explicitly first; terminal Node, npm and another server receipt cannot substitute."
    const val MAX_ARCHIVE_BYTES = 67_108_864L
    const val MAX_ARCHIVES_BYTES = 68_157_440L
    const val MAX_ARCHIVE_UNPACKED = 83_886_080L
    const val MAX_TREE_BYTES = 83_886_080L
    const val MAX_FILES = 128
    const val MAX_ENTRIES = 256
    val artifacts: Map<String,JsonObject> by lazy { strictObject("""{"@taplo/lsp@0.8.0":{"name":"@taplo/lsp","version":"0.8.0","license":"MIT","gitHead":"48e4c083e6a98891e3d6b2b65450970a3779e8cb","main":"dist/index.js","types":"dist/index.d.ts","dependencies":{"@taplo/core":"^0.2.0"},"optionalDependencies":null,"peerDependencies":null,"bundledDependencies":null,"scripts":{"build":"yarn rollup --silent -c rollup.config.mjs","prepublish":"RELEASE=true yarn build"},"engines":null,"bin":null,"dist":{"integrity":"sha512-ddXw9MB8vSEmF0HrzyZESAlGpxKqARlthNqIFnBDplnFyiSX9I5jkWtPulFORfW4J+mmttdPJPiG5UihP6T7+w==","shasum":"03e7083d3715470c0554c5b949f0e7444ceb74d2","tarball":"https://registry.npmjs.org/@taplo/lsp/-/lsp-0.8.0.tgz","fileCount":4,"unpackedSize":35643040,"signatures":[{"keyid":"SHA256:jl3bwswu80PjjokCgh0o2w5c2U4LhQAE57gj9cz1kzA","sig":"MEYCIQDFoKhixR+Od+jFa8XQo9D0wEPSl8ac7tbJNl2fsHNS6wIhAImKIzMMM7DKNn4OLxXLte6Z36P21B6rSTni38mSCXvt"}]},"sha512":"75d5f0f4c07cbd21261741ebcf2644480946a712aa01196d84da88167043a659c5ca2497f48e63916b4fba514e45f5b827e9a6b6d74f24f886e548a13fa4fbfb"},"@taplo/core@0.2.0":{"name":"@taplo/core","version":"0.2.0","license":"MIT","gitHead":"48e4c083e6a98891e3d6b2b65450970a3779e8cb","main":"dist/index.js","types":"dist/index.d.ts","dependencies":null,"optionalDependencies":null,"peerDependencies":null,"bundledDependencies":null,"scripts":{"build":"yarn rollup --silent -c rollup.config.mjs","prepack":"yarn build"},"engines":null,"bin":null,"dist":{"integrity":"sha512-r8bl54Zj1In3QLkiW/ex694bVzpPJ9EhwqT9xkcUVODnVUGirdB1JTsmiIv0o1uwqZiwhi8xNnTOQBRQCpizrQ==","shasum":"a256aef743307c853de08e2199e295fa3266122f","tarball":"https://registry.npmjs.org/@taplo/core/-/core-0.2.0.tgz","fileCount":8,"unpackedSize":25518,"signatures":[{"keyid":"SHA256:jl3bwswu80PjjokCgh0o2w5c2U4LhQAE57gj9cz1kzA","sig":"MEUCIE7v/TG2OOVt7XqSev1NFU41RFRWLZiRQXhrEurdS/oZAiEAsnXoq+M98UxxEE3qGfD+yxDQeED4UOQ92TuD8dpuO5c="}]},"sha512":"afc6e5e78663d489f740b9225bf7b1ebde1b573a4f27d121c2a4fdc6471454e0e75541a2add075253b26888bf4a35bb0a998b0862f313674ce4014500a98b3ad"}}""",65536).mapValues { it.value.jsonObject } }
    val placements = mapOf("node_modules/@taplo/lsp" to "@taplo/lsp@0.8.0", "node_modules/@taplo/core" to "@taplo/core@0.2.0")
    val integrity get() = artifacts.getValue("$PACKAGE@$VERSION")["dist"]!!.jsonObject["integrity"]!!.jsonPrimitive.content
    fun isFamily(family:String) = family == FAMILY || family == LocalUbuntuTomlNodePolicy.FAMILY
    fun version(family:String) = when(family) { FAMILY -> VERSION; LocalUbuntuTomlNodePolicy.FAMILY -> LocalUbuntuTomlNodePolicy.VERSION; else -> error("Not a Taplo family") }
    fun kind(family:String) = when(family) { FAMILY -> LocalManagedPackageMetadata.KIND_UBUNTU_NPM; LocalUbuntuTomlNodePolicy.FAMILY -> LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE; else -> error("Not a Taplo family") }
    fun command(family:String) = if(family == FAMILY) COMMAND else { require(family == LocalUbuntuTomlNodePolicy.FAMILY); LocalUbuntuTomlNodePolicy.COMMAND }
    fun root(family:String) = if(family == FAMILY) ROOT else { require(family == LocalUbuntuTomlNodePolicy.FAMILY); LocalUbuntuTomlNodePolicy.ROOT }
    fun recipe() = WorkstationInstallRecipe(FAMILY,VERSION,PACKAGE,VERSION,COMMAND,22,PROVENANCE,registryIntegrity=integrity,additionalDependencies=mapOf("@taplo/core" to CORE_VERSION))
    fun supports(recipe:WorkstationInstallRecipe) = recipe == this.recipe()
    fun requirePackage(identity:String,value:JsonObject) {
        val expected=artifacts.getValue(identity)
        for(key in listOf("name","version","license","main","types","dependencies","optionalDependencies","peerDependencies","bundledDependencies","engines","bin","scripts")) {
            val actual=value[key]?:JsonNull
            require(actual==(expected[key]?:JsonNull)) { "Taplo original manifest field changed: $identity/$key" }
        }
        require(value["type"] == null || value["type"] == JsonPrimitive("commonjs"))
        require(value["exports"] == null) { "Unexpected Taplo export map" }
    }
    fun sha256(bytes:ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun strictObject(text:String,maxBytes:Int=4_194_304) = LocalUbuntuPowerShellRuntimePolicy.strictObject(text,maxBytes)
    fun read(file:File,maxBytes:Int=4_194_304,active:()->Unit={}) = NimRuntimeClosure.readText(file,maxBytes,active)
    fun safeRelative(value:String) = NimRuntimeClosure.requireRelative(value)
}
