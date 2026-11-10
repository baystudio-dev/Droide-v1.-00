package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCSharpInstallerTest {
    @Test fun csharpHoldRetainsUnknownOwnerAndBlocksRecoveryWithoutBorrowingVue() {
        val root=Files.createTempDirectory("csharp-journal-fixture").toFile()
        try {
            val paths=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuCSharpPolicy.FAMILY,LocalUbuntuCSharpPolicy.VERSION,LocalUbuntuCSharpPolicy.KIND)
            assertNotNull(paths.csharpHealthHold);assertNull(paths.vueHealthHold)
            val hold=LocalPackageInstallJournal.beginCSharpHealth(paths)
            LocalPackageInstallJournal.finishCSharpHealth(hold,false)
            assertTrue(runCatching{LocalPackageInstallJournal.recover(paths,null)}.isFailure)
            assertTrue(paths.csharpHealthHold!!.exists())
            LocalPackageInstallJournal.finishCSharpHealth(hold,true)
            LocalPackageInstallJournal.requireNoCSharpHealthHold(paths)
            assertFalse(paths.csharpHealthHold!!.exists())
        }finally{PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun unknownHoldNeverDeletesStageOrPreviousAndReplacementCannotBorrowNonce() {
        val root=Files.createTempDirectory("csharp-hold-fixture").toFile()
        try {
            val paths=LocalPackageInstallJournal.paths(root.absolutePath,LocalUbuntuCSharpPolicy.FAMILY,LocalUbuntuCSharpPolicy.VERSION,LocalUbuntuCSharpPolicy.KIND)
            paths.stage.mkdirs();java.io.File(paths.stage,"sentinel").writeText("keep")
            val hold=LocalPackageInstallJournal.beginCSharpHealth(paths)
            paths.csharpHealthHold!!.writeText("0".repeat(36))
            assertTrue(runCatching{LocalPackageInstallJournal.finishCSharpHealth(hold,true)}.isFailure)
            assertTrue(runCatching{LocalPackageInstallJournal.recover(paths,null)}.isFailure)
            assertEquals("keep",java.io.File(paths.stage,"sentinel").readText())
        }finally{PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun privateSdkAndHelperDoNotEnterTerminalPath() {
        val root="/fixture";val records=LocalUbuntuCSharpPolicy.families.map { f->val r=LocalUbuntuCSharpPolicy.recipe(f)
            ManagedPackageRecord(f,r.version,ExecutionScope.LOCAL_LINUX_ARM64.name,"$root/packages/$f/${r.version}/arm64-v8a",1,
                commands=mapOf(r.command to "unused"),metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalUbuntuCSharpPolicy.KIND)) }
        val paths=LocalLinuxPackageEnvironment.guestBinPaths(records,emptyMap(),root)
        assertEquals(listOf(LocalUbuntuCSharpPolicy.ROOT+"/payload/bin"),paths)
    }
    @Test fun metadataDiscoveryNeverAuthorizesIncompleteTreeAndCannotBorrowOtherReceipt() {
        val app=Files.createTempDirectory("csharp-header-fixture").toFile()
        try {
            val recipe=LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpHelperPolicy.FAMILY)
            val paths=LocalPackageInstallJournal.paths(app.absolutePath,recipe.familyId,recipe.version,LocalUbuntuCSharpPolicy.KIND)
            val root=paths.final;root.mkdirs();val id=LocalPackageInstallJournal.prepare(root)
            fun write(path:String,value:String){java.io.File(root,path).writeText(value)}
            val tree="authored incomplete data manifest\n";val seal="authored incomplete package seal\n";val closureFiles=mapOf("usr/bin/bash" to "0".repeat(64))
            val closure=NimRuntimeClosure.sha256(closureFiles.entries.joinToString("\n"){"${it.key}\u0000${it.value}"})
            write(LocalUbuntuCSharpInstaller.TREE_MANIFEST,tree);write("SHA256SUMS",seal)
            fun map(m:Map<String,String>)=kotlinx.serialization.json.JsonObject(m.mapValues{kotlinx.serialization.json.JsonPrimitive(it.value)})
            val header=kotlinx.serialization.json.buildJsonObject {
                put("schema",kotlinx.serialization.json.JsonPrimitive(1));put("policy",kotlinx.serialization.json.JsonPrimitive(LocalUbuntuCSharpPolicy.POLICY));put("launchPolicy",kotlinx.serialization.json.JsonPrimitive(LocalUbuntuCSharpLaunchAuthority.POLICY))
                for((k,v)in mapOf("family" to recipe.familyId,"version" to recipe.version,"role" to recipe.role.name,"final" to LocalUbuntuCSharpPolicy.root(recipe.familyId),"activation" to id,"publisherAlgorithm" to recipe.publisherDigest.algorithm.jca,"publisherDigest" to recipe.publisherDigest.hex,"localArchiveSha256" to "1".repeat(64),"url" to recipe.url,"provenance" to recipe.provenanceUrl,"treeSha256" to NimRuntimeClosure.sha256(tree),"configSha256" to "2".repeat(64),"closureSha256" to closure,"signaturePolicy" to "Publisher artifact digest verified."))put(k,kotlinx.serialization.json.JsonPrimitive(v))
                put("publisherBase64",kotlinx.serialization.json.JsonNull);put("archiveBytes",kotlinx.serialization.json.JsonPrimitive(recipe.expectedBytes!!));put("helpers",map(emptyMap()));put("dependencyReceipts",map(emptyMap()));put("notices",map(emptyMap()))
            }
            write(LocalUbuntuCSharpInstaller.AUTHORITY,header.toString())
            write(LocalUbuntuCSharpInstaller.CLOSURE,kotlinx.serialization.json.buildJsonObject{put("schema",kotlinx.serialization.json.JsonPrimitive(1));put("files",map(closureFiles));put("sha256",kotlinx.serialization.json.JsonPrimitive(closure))}.toString())
            val record=ManagedPackageRecord(recipe.familyId,recipe.version,ExecutionScope.LOCAL_LINUX_ARM64.name,root.absolutePath,1,
                pathEntries=listOf("$root/payload/host-bin"),commands=mapOf(recipe.command to "$root/payload/host-bin/${recipe.command}"),artifactSha256="1".repeat(64),abi="arm64-v8a",
                metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalUbuntuCSharpPolicy.KIND,LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name,PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,LocalManagedPackageMetadata.SEAL_SHA256_KEY to NimRuntimeClosure.sha256(seal),LocalManagedPackageMetadata.TREE_SHA256_KEY to NimRuntimeClosure.sha256(tree)))
            assertEquals(recipe.familyId,LocalUbuntuCSharpInstaller.inspectPackage(app.absolutePath,record).record.familyId)
            assertTrue(runCatching{LocalUbuntuCSharpInstaller.provePackage(app.absolutePath,record)}.isFailure)
            for((key,value)in listOf("publisherDigest" to "0".repeat(64),"role" to "MANAGED_NUPKG","signaturePolicy" to "Cryptographic signature verified")) {
                write(LocalUbuntuCSharpInstaller.AUTHORITY,kotlinx.serialization.json.JsonObject(header+mapOf(key to kotlinx.serialization.json.JsonPrimitive(value))).toString())
                assertTrue(runCatching{LocalUbuntuCSharpInstaller.inspectPackage(app.absolutePath,record)}.isFailure)
            }
            write(LocalUbuntuCSharpInstaller.AUTHORITY,kotlinx.serialization.json.JsonObject(header+mapOf("dependencyReceipts" to map(mapOf("runtime.vue-helper" to "0".repeat(64))))).toString())
            assertTrue(runCatching{LocalUbuntuCSharpInstaller.inspectPackage(app.absolutePath,record)}.isFailure)
        }finally{PathSecurity.deleteTreeNoFollow(app)}
    }

}
