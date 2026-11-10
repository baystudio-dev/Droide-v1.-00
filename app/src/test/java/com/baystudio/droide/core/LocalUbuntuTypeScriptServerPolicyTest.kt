package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuTypeScriptServerPolicyTest {
    private fun metadata(version: String = "6.0.3", server: String? = "bin/tsserver", integrity: String = LocalUbuntuTypeScriptServerPolicy.INTEGRITY,
        tarball: String = "https://registry.npmjs.org/typescript/-/typescript-6.0.3.tgz") = buildJsonObject {
        put("name", "typescript"); put("version", version)
        put("bin", buildJsonObject { put("tsc", "bin/tsc"); server?.let { put("tsserver", it) } })
        put("dist", buildJsonObject { put("integrity", integrity); put("tarball", tarball) })
    }
    @Test fun newerVersionsThatStillProvideTsserverAreAcceptedByCapability() {
        assertEquals("8.1.2", LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata("8.1.2", "bin/tsserver")))
    }
    @Test fun reviewedTypeScriptCompanionProvidesTheLegacyServer() {
        assertEquals("6.0.3", LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata()))
        assertEquals("6.0.3", LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata(server = "./bin/tsserver")))
    }
    @Test fun compilerOnlyTypeScriptSevenCannotBecomeTheServerDependency() {
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata("7.0.2", null)) }
    }
    @Test fun missingServerOrChangedPublisherBytesFailBeforeInstallation() {
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata(server = null)) }
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata(integrity = "different")) }
    }
    @Test fun companionArtifactCannotEscapeTheOfficialRegistry() {
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(metadata(tarball = "https://foreign.example/typescript.tgz")) }
    }
}
