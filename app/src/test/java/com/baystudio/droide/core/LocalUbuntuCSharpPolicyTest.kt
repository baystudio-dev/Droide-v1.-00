package com.baystudio.droide.core

import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCSharpPolicyTest {
    @Test fun exactThreeIndependentArtifactsAndOneManagedEntry() {
        assertEquals(3,LocalUbuntuCSharpPolicy.families.size)
        val tool=LocalUbuntuCSharpPolicy.requested("lsp.csharp-ls","0.28.0")
        assertEquals(CSharpArtifactRole.MANAGED_NUPKG,tool.role)
        assertEquals(CSharpArtifactDigest.Algorithm.SHA512,tool.publisherDigest.algorithm)
        assertTrue(tool.descriptor() is TrustedSha512ArtifactSpec)
        assertTrue(LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpHelperPolicy.FAMILY).descriptor() is TrustedArtifactSpec)
        assertEquals(221,LocalUbuntuCSharpPolicy.catalogMembers.size)
        assertEquals(59_497_796L,LocalUbuntuCSharpPolicy.catalogMembers.values.sum())
        assertEquals(12985L,LocalUbuntuCSharpPolicy.catalogMembers[".signature.p7s"])
        assertTrue(LocalUbuntuCSharpPolicy.SIGNATURE_POLICY.contains("not cryptographically verified"))
    }
    @Test fun wrongVersionAndEveryIdentitySubstitutionFail() {
        for(version in listOf(null,"latest","0.28.1","0.27.0","custom"))reject{LocalUbuntuCSharpPolicy.requested(LocalUbuntuCSharpPolicy.FAMILY,version)}
        val r=LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpPolicy.FAMILY)
        for(value in listOf(r.copy(url="https://example.com/server.nupkg"),r.copy(entryPoint="tools/net10.0/any/fake.exe"),r.copy(role=CSharpArtifactRole.ORIGINAL_SDK),r.copy(expectedBytes=1),r.copy(maxFiles=222),r.copy(command="dotnet")))reject{value.validate()}
        reject{LocalUbuntuCSharpPolicy.recipe("runtime.vue-helper")}
    }
    @Test fun typedDigestAuthenticatesBytesAndKeepsLocalShaSeparate() {
        val file=Files.createTempFile("csharp-hash-fixture",".data").toFile()
        try {
            val bytes="authored inert fixture".toByteArray();file.writeBytes(bytes)
            fun hash(a:String)=MessageDigest.getInstance(a).digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}
            val digest=CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA512,hash("SHA-512"))
            assertEquals(hash("SHA-256"),digest.verify(file,1024,bytes.size.toLong()))
            reject{digest.verify(file,1024,1)}
            file.appendText("changed");reject{digest.verify(file,1024,null)}
            reject{CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA256,"0".repeat(128)).validate()}
            reject{CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA512,"0".repeat(128),LocalUbuntuCSharpPolicy.SHA512_BASE64).validate()}
        } finally {file.delete()}
    }
    private fun reject(block:()->Unit){assertTrue(runCatching(block).isFailure)}
}
