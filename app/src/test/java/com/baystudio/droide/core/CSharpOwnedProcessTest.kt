package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

class CSharpOwnedProcessTest {
    @Test fun controlParserRejectsDuplicateKeysAndTrailingData() {
        assertTrue(runCatching { CSharpControlRecords.strict("""{"schema":1,"schema":1}""".toByteArray()) }.isFailure)
        assertTrue(runCatching { CSharpControlRecords.strict("{} {}".toByteArray()) }.isFailure)
    }
    @Test fun directoryIdentityRequiresPrivateMode() {
        val root=Files.createTempDirectory("csharp-private")
        try {Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));val identity=CSharpControlRecords.directoryIdentity(root.toFile())
            assertEquals(identity,CSharpControlRecords.directoryIdentity(root.toFile()))
            Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"))
            assertTrue(runCatching { CSharpControlRecords.directoryIdentity(root.toFile()) }.isFailure)
        }finally{Files.delete(root)}
    }
    @Test fun invalidSelectionDoesNotLeakAllocatedPrivateDirectory() {
        val app=Files.createTempDirectory("csharp-allocation").toFile()
        try {
            java.io.File(app,"runtime-tmp").mkdir()
            val project=java.io.File(app,"workspace").apply {mkdir()}
            java.io.File(project,"global.json").writeText("""{"sdk":{"version":"9.0.100"}}""")
            val hash="a".repeat(64);val activation="00000000-0000-0000-0000-000000000001"
            val identity=CSharpManagedLaunchIdentity("lsp.csharp-ls","0.28.0","runtime.csharp-sdk","10.0.402","runtime.csharp-helper","22.23.3",
                LspCSharpLaunchPolicy.PROFILE,hash,hash,hash,hash,hash,hash,hash,"csharp-ls","${LspCSharpLaunchPolicy.ROOT}/payload/bin/csharp-ls",
                "${LspCSharpLaunchPolicy.ROOT}/payload/tree/tools/net10.0/any/CSharpLanguageServer.dll","${LspCSharpLaunchPolicy.SDK_ROOT}/dotnet",
                LspCSharpLaunchPolicy.SDK_ROOT,LspCSharpLaunchPolicy.HELPER,activation,activation,activation,hash)
            assertTrue(runCatching { LocalCSharpPreparedOwner.allocate(app,identity,CSharpOwnerRequest(project.absolutePath,project.absolutePath,1,hash)) }.isFailure)
            assertEquals("Failed selection must leave no private allocation",0,java.io.File(app,"runtime-tmp").listFiles()!!.size)
        } finally {app.deleteRecursively()}
    }

    @Test fun unknownRetirementRetainsDirectoryAndBlocksReplacement() = kotlinx.coroutines.runBlocking {
        val app=Files.createTempDirectory("csharp-unknown").toFile()
        var owner:LocalCSharpPreparedOwner?=null
        try {
            java.io.File(app,"runtime-tmp").mkdir()
            val project=java.io.File(app,"workspace").apply {mkdir()}
            java.io.File(app,"packages/lsp.csharp-ls/0.28.0/arm64-v8a/"+LocalUbuntuCSharpLaunchAuthority.HEALTH_SEED).apply{parentFile.mkdirs();writeText("{}")}
            val hash="a".repeat(64);val activation="00000000-0000-0000-0000-000000000001"
            val identity=CSharpManagedLaunchIdentity("lsp.csharp-ls","0.28.0","runtime.csharp-sdk","10.0.402","runtime.csharp-helper","22.23.3",
                LspCSharpLaunchPolicy.PROFILE,hash,hash,hash,hash,hash,hash,hash,"csharp-ls","${LspCSharpLaunchPolicy.ROOT}/payload/bin/csharp-ls",
                "${LspCSharpLaunchPolicy.ROOT}/payload/tree/tools/net10.0/any/CSharpLanguageServer.dll","${LspCSharpLaunchPolicy.SDK_ROOT}/dotnet",
                LspCSharpLaunchPolicy.SDK_ROOT,LspCSharpLaunchPolicy.HELPER,activation,activation,activation,hash)
            val request=CSharpOwnerRequest(project.absolutePath,project.absolutePath,1,hash)
            val retained=LocalCSharpPreparedOwner.allocate(app,identity,request);owner=retained
            retained.createProcess({object:Process(){
                override fun getOutputStream()=java.io.ByteArrayOutputStream()
                override fun getInputStream()=java.io.ByteArrayInputStream(byteArrayOf())
                override fun getErrorStream()=java.io.ByteArrayInputStream(byteArrayOf())
                override fun waitFor()=0
                override fun waitFor(timeout:Long,unit:java.util.concurrent.TimeUnit)=true
                override fun exitValue()=0
                override fun isAlive()=false
                override fun destroy(){}
            }},{error("No fallback is needed for the authored already-reaped stand-in")})
            val receipt=retained.retire()
            assertFalse(receipt.complete);assertFalse(receipt.groupEmpty);assertFalse(receipt.privateCleanup)
            assertTrue(retained.localRoot.isDirectory);assertFalse(retained.isCurrent)
            assertEquals(receipt,retained.retire())
            assertTrue(runCatching {LocalCSharpPreparedOwner.allocate(app,identity,request.copy(ownerGeneration=2))}.isFailure)
            // Simulate only loss of the in-memory map; the persistent owner record must still deny
            // replacement.
            CSharpRootOwners.release(retained)
            assertTrue(runCatching {LocalCSharpPreparedOwner.allocate(app,identity,request.copy(ownerGeneration=3))}.isFailure)
        } finally {owner?.let(CSharpRootOwners::release);app.deleteRecursively()}
    }

}
