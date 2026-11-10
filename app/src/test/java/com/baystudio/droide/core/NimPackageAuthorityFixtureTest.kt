package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class NimPackageAuthorityFixtureTest {
    private val assets=sequenceOf(System.getProperty("nim.source")?.let { File(it,"app/src/main/assets/lsp/nim") },
        File("app/src/main/assets/lsp/nim"),File("src/main/assets/lsp/nim")).filterNotNull().first { it.isDirectory }
    private val fixtures=LocalUbuntuPowerShellRuntimePolicyTest()
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun write(root:File,path:String,bytes:ByteArray,mode:Int=420) {val f=File(root,path);f.parentFile.mkdirs();f.writeBytes(bytes);Files.setAttribute(f.toPath(),"unix:mode",mode)}
    private fun write(root:File,path:String,text:String,mode:Int=420)=write(root,path,text.toByteArray(),mode)
    private fun guest(root:File) {
        val g=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs");val d=File(g,"usr/lib/aarch64-linux-gnu").apply{mkdirs()}
        Files.createSymbolicLink(File(g,"lib").toPath(),java.nio.file.Path.of("usr/lib"))
        Files.createSymbolicLink(File(g,"usr/lib/ld-linux-aarch64.so.1").toPath(),java.nio.file.Path.of("aarch64-linux-gnu/ld-linux-aarch64.so.1"))
        for(n in listOf("libc.so.6","ld-linux-aarch64.so.1","libssl.so.3","libcrypto.so.3","libpcre.so.3"))write(d,n,NimRuntimeClosureTest().symbolElf(n=="libc.so.6",soname=n,exports=NimRuntimeClosure.ordinaryMandatoryExports[n].orEmpty().toList()))
        write(g,"usr/bin/dash",NimRuntimeClosureTest().symbolElf(true,needed=listOf("libc.so.6")),493)
        Files.createSymbolicLink(File(g,"bin").toPath(),java.nio.file.Path.of("usr/bin"));Files.createSymbolicLink(File(g,"usr/bin/sh").toPath(),java.nio.file.Path.of("dash"))
    }
    private fun recipe(family:String):LocalUbuntuVendorRecipe {
        val d=LocalUbuntuVendorCatalog.find(family)!!
        return when(family) {
            LocalUbuntuNimPolicy.FAMILY->LocalUbuntuVendorRecipe(d,LocalUbuntuNimPolicy.VERSION,LocalUbuntuNimPolicy.TAG,LocalUbuntuNimPolicy.ASSET,LocalUbuntuNimPolicy.ASSET_URL,LocalUbuntuNimPolicy.SHA256,LocalUbuntuNimPolicy.ARTIFACT_BYTES,LocalUbuntuNimPolicy.RELEASE_URL)
            LocalUbuntuNimToolchainPolicy.FAMILY->LocalUbuntuVendorRecipe(d,LocalUbuntuNimToolchainPolicy.VERSION,LocalUbuntuNimToolchainPolicy.TAG,LocalUbuntuNimToolchainPolicy.ASSET,LocalUbuntuNimToolchainPolicy.ASSET_URL,LocalUbuntuNimToolchainPolicy.SHA256,LocalUbuntuNimToolchainPolicy.ARTIFACT_BYTES,LocalUbuntuNimToolchainPolicy.RELEASE_URL)
            else->LocalUbuntuNimHelperPolicy.releaseRecipe(d,LocalUbuntuNimHelperPolicy.VERSION,"${LocalUbuntuNimHelperPolicy.SHA256}  ${LocalUbuntuNimHelperPolicy.ASSET}\n")
        }
    }
    private suspend fun installed(root:File,r:LocalUbuntuVendorRecipe,deps:List<ManagedPackageRecord> = emptyList()):ManagedPackageRecord {
        val paths=LocalPackageInstallJournal.paths(root.path,r.familyId,r.version,LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE)
        paths.stage.mkdirs();val activation=LocalPackageInstallJournal.prepare(paths.stage)
        val stage=paths.stage;val tree=File(stage,"payload/tree").apply{mkdirs()}
        when(r.familyId) {
            LocalUbuntuNimHelperPolicy.FAMILY->{
                write(tree,r.entryPoint,fixtures.elf(true,needed=listOf("libc.so.6")),493)
                write(tree,LocalUbuntuNimHelperPolicy.CONFIG,"{'target_defaults':{},'variables':{'target_arch':'arm64','node_shared':'false','node_shared_libuv':'false','node_builtin_modules_path':'','node_module_version':127}}")
                write(tree,"${LocalUbuntuNimHelperPolicy.TOP}/LICENSE","authored retained notice")
                write(tree,"${LocalUbuntuNimHelperPolicy.TOP}/README.md","authored data")
            }
            LocalUbuntuNimToolchainPolicy.FAMILY->{
                for(path in LocalUbuntuNimToolchainPolicy.executablePaths) {
                    val bytes=fixtures.elf(true,needed=listOf("libc.so.6"))
                    if(path.endsWith("nimble"))"libssl.so.3\u0000libcrypto.so.3\u0000".toByteArray().copyInto(bytes,0xa00)
                    write(tree,path,bytes,493)
                }
                for(path in listOf("nim.nimble","copying.txt","config/nim.cfg","lib/system.nim"))write(tree,"${LocalUbuntuNimToolchainPolicy.TOP}/$path","authored data")
            }
            else->write(tree,r.entryPoint,fixtures.elf(true,needed=listOf("libc.so.6")),493)
        }
        val files=tree.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(tree).invariantSeparatorsPath}.toList()
        val hashes=files.associate{it.relativeTo(tree).invariantSeparatorsPath to hash(it.readBytes())}
        val treeSha=hash(hashes.entries.joinToString(""){(p,h)->val mode=Files.getAttribute(File(tree,p).toPath(),"unix:mode") as Int;"$p\u0000${mode and 511}\u0000$h\n"}.toByteArray())
        write(stage,"TREE_SHA256SUMS",hashes.entries.joinToString("\n",postfix="\n"){"${it.value}  ${it.key}"})
        write(stage,"TREE_LINKS.tsv","");write(stage,"TREE_COMMANDS.tsv","${r.command}\t${r.entryPoint}\n")
        val helpers=LocalUbuntuNimLaunchAuthority.helperPaths.associateWith { File(assets,it.substringAfterLast('/')).readText() }
        helpers.forEach{(p,t)->write(stage,p,t)}
        val guest="/opt/droide/packages/${r.familyId}/${r.version}/arm64-v8a"
        val generated=LocalUbuntuNimLaunchAuthority.prepare(r,stage,activation,guest,deps,root.path,helpers)
        generated.forEach{(p,t)->write(stage,p,if(p=="payload/nim-health-identity.json")LocalUbuntuNimLaunchAuthority.finalHealthSeed(t,stage.path,guest) else t)}
        write(stage,"payload/bin/${r.command}",LocalUbuntuVendorPolicy.guestWrapper(r,"$guest/payload/tree/${r.entryPoint}",guest),493)
        write(stage,"payload/host-bin/${r.command}",LocalUbuntuVendorPolicy.hostWrapper("${root.path}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch","$guest/payload/bin/${r.command}"),493)
        write(stage,"RECIPE_LOCK.json",buildJsonObject{
            put("schema",1);put("provider","LOCAL_UBUNTU_VENDOR");put("familyId",r.familyId);put("version",r.version);put("artifactFormat",r.definition.format.name)
            put("command",r.command);put("treeEntryPoint",r.entryPoint);put("releaseTag",r.releaseTag);put("assetName",r.assetName);put("assetUrl",r.assetUrl)
            put("assetSha256",r.assetSha256);put("assetSize",r.assetSize!!);put("provenanceUrl",r.provenanceUrl)
        }.toString())
        val seal=ReviewedWholeTreeSeal.create(stage,r.familyId,120_000,12L*1024*1024*1024)
        paths.final.parentFile.mkdirs();assertTrue(stage.renameTo(paths.final))
        val final=paths.final.path
        return ManagedPackageRecord(r.familyId,r.version,ExecutionScope.LOCAL_LINUX_ARM64.name,final,1,active=true,
            pathEntries=listOf("$final/payload/host-bin"),commands=mapOf(r.command to "$final/payload/host-bin/${r.command}"),
            dependencies=(listOf("layer@1")+deps.map{"${it.familyId}@${it.version}"}).sorted(),healthChecks=listOf(ManagedPackageHealthCheck("host-bin/${r.command}",listOf("--health"))),
            artifactSha256=r.assetSha256,abi="arm64-v8a",metadata=mapOf(LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
                LocalUbuntuVendorPolicy.POLICY_KEY to LocalUbuntuVendorPolicy.POLICY_VERSION,LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activation,
                LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,LocalManagedPackageMetadata.TREE_SHA256_KEY to treeSha,
                LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
    }
    @Test fun reviewedMetadataModesAreExplicitAndPreserveContents() = runBlocking<Unit> {
        val families = listOf(
            LocalUbuntuNimPolicy.FAMILY,
            LocalUbuntuNimToolchainPolicy.FAMILY,
            LocalUbuntuNimHelperPolicy.FAMILY,
            LocalUbuntuPowerShellRuntimePolicy.FAMILY,
            LocalUbuntuPowerShellEditorServicesPolicy.FAMILY,
            LocalUbuntuVueHelperPolicy.FAMILY,
        )
        for (family in families) withTemporaryTree { stage ->
            LocalPackageInstallJournal.prepare(stage)
            val activation = File(stage, "DROIDE_ACTIVATION_ID")
            val originalActivation = activation.readBytes()
            Files.setAttribute(activation.toPath(), "unix:mode", 384)
            write(stage, "payload.txt", "retained payload", 384)
            write(stage, "SHA256SUMS", "old owned seal", 384)

            val digest = ReviewedWholeTreeSeal.create(stage, family, 32, 1024 * 1024)
            val manifest = File(stage, "SHA256SUMS")

            assertEquals(family, 420, mode(activation))
            assertEquals(family, 420, mode(manifest))
            assertArrayEquals(originalActivation, activation.readBytes())
            assertEquals(hash(manifest.readBytes()), digest)
            assertTrue(LocalPackageIntegrity.verify(stage, manifest, exactInventory = true))
            assertEquals(384, mode(File(stage, "payload.txt")))
            assertEquals("retained payload", File(stage, "payload.txt").readText())
        }
    }

    @Test fun unrelatedFamiliesRetainTheirMetadataModes() = runBlocking<Unit> {
        withTemporaryTree { stage ->
            LocalPackageInstallJournal.prepare(stage)
            Files.setAttribute(File(stage, "DROIDE_ACTIVATION_ID").toPath(), "unix:mode", 384)
            write(stage, "SHA256SUMS", "old owned seal", 384)

            ReviewedWholeTreeSeal.create(stage, "runtime.node", 32, 1024 * 1024)

            assertEquals(384, mode(File(stage, "DROIDE_ACTIVATION_ID")))
            assertEquals(384, mode(File(stage, "SHA256SUMS")))
            assertTrue(LocalPackageIntegrity.verify(stage, File(stage, "SHA256SUMS"), exactInventory = true))
        }
    }

    @Test fun linkedActivationIsRejectedWithoutChangingItsTarget() = runBlocking<Unit> {
        assertRejectedActivationAlias(symbolic = true)
    }

    @Test fun multiplyLinkedActivationIsRejectedWithoutChangingItsTarget() = runBlocking<Unit> {
        assertRejectedActivationAlias(symbolic = false)
    }

    @Test fun cancelledSealingDoesNotChangeModesOrPublishAManifest() = runBlocking<Unit> {
        withTemporaryTree { stage ->
            LocalPackageInstallJournal.prepare(stage)
            val activation = File(stage, "DROIDE_ACTIVATION_ID")
            val original = activation.readBytes()
            Files.setAttribute(activation.toPath(), "unix:mode", 384)
            val failure = runCatching {
                withContext(Job().apply { cancel() }) {
                    ReviewedWholeTreeSeal.create(stage, LocalUbuntuNimPolicy.FAMILY, 32, 1024 * 1024)
                }
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(384, mode(activation))
            assertArrayEquals(original, activation.readBytes())
            assertFalse(File(stage, "SHA256SUMS").exists())
        }
    }

    private suspend fun assertRejectedActivationAlias(symbolic: Boolean) = withTemporaryTree { root ->
        val stage = File(root, "stage").apply { mkdir() }
        write(root, "outside.txt", "preserve this file", 384)
        val outside = File(root, "outside.txt").toPath()
        val activation = File(stage, "DROIDE_ACTIVATION_ID").toPath()
        if (symbolic) Files.createSymbolicLink(activation, outside) else Files.createLink(activation, outside)

        val result = runCatching {
            ReviewedWholeTreeSeal.create(stage, LocalUbuntuNimPolicy.FAMILY, 32, 1024 * 1024)
        }

        assertTrue(result.isFailure)
        assertEquals("preserve this file", outside.toFile().readText())
        assertEquals(384, mode(outside.toFile()))
        assertFalse(File(stage, "SHA256SUMS").exists())
        assertTrue(Files.isSameFile(activation, outside))
    }

    private fun mode(file: File): Int = (Files.getAttribute(file.toPath(), "unix:mode") as Int) and 4095

    private suspend fun withTemporaryTree(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("reviewed-metadata-modes-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun completeIdentityAndProtectedProofRejectTamperedGeneration()=runBlocking {
        val root=Files.createTempDirectory("nim-three-original-model-").toFile()
        try {
            guest(root)
            val helper=installed(root,recipe(LocalUbuntuNimHelperPolicy.FAMILY));val tool=installed(root,recipe(LocalUbuntuNimToolchainPolicy.FAMILY))
            val server=installed(root,recipe(LocalUbuntuNimPolicy.FAMILY),listOf(helper,tool))
            val layer=ManagedPackageRecord("layer","1",ExecutionScope.LOCAL_LINUX_ARM64.name,File(root,"layer").path,0,
                metadata=mapOf(FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                    FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to recipe(LocalUbuntuNimPolicy.FAMILY).requiredPackages.sorted().joinToString(",")))
            val records=listOf(helper,tool,server,layer)
            val discovered="${LocalUbuntuNimPolicy.ROOT}/payload/bin/nimlangserver"
            val identity=requireNotNull(LocalUbuntuNimLaunchAuthority.inspect(root.path,server,records,emptyMap(),discovered))
            LocalUbuntuNimLaunchAuthority.prove(root.path,identity,records,{})
            assertNull(LocalUbuntuNimLaunchAuthority.inspect(root.path,server,records,emptyMap(),"/usr/bin/nimlangserver"))
            val binary=File(helper.installRoot,"payload/tree/${LocalUbuntuNimHelperPolicy.ENTRY}");val saved=binary.readBytes();binary.appendBytes(byteArrayOf(42))
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.prove(root.path,identity,records,{})}.isFailure)
            binary.writeBytes(saved)
            val conf=File(helper.installRoot,"payload/tree/${LocalUbuntuNimHelperPolicy.CONFIG}");val oldConfig=conf.readText();conf.appendText(" # changed")
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.prove(root.path,identity,records,{})}.isFailure);conf.writeText(oldConfig)
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.prove(root.path,identity.copy(serverExecutable="/opt/droide/packages/other/1/arm64-v8a/payload/tree/nimlangserver"),records,{})}.isFailure)
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.prove(root.path,identity.copy(sealSha256="0".repeat(64)),records,{})}.isFailure)
            val metadataLink=File(helper.installRoot,"extra-link");Files.createSymbolicLink(metadataLink.toPath(),java.nio.file.Path.of("NIM_AUTHORITY.json"))
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.provePackage(root.path,helper)}.isFailure);Files.delete(metadataLink.toPath())
            val closure=File(server.installRoot,LocalUbuntuNimLaunchAuthority.CLOSURE_FILE);val oldClosure=closure.readText();closure.writeText(oldClosure.replace("libc.so.6","libc.so.7"))
            assertNull(LocalUbuntuNimLaunchAuthority.inspect(root.path,server,records,emptyMap(),discovered));closure.writeText(oldClosure)
            val preload=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs/etc/ld.so.preload");preload.parentFile.mkdirs();preload.writeText("/unreviewed/loader.so")
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.provePackage(root.path,helper)}.isFailure);preload.delete()
            val cache=File(preload.parentFile,"ld.so.cache");cache.writeText("unknown cache format")
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.provePackage(root.path,helper)}.isFailure);cache.delete()
            val cap=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs/usr/lib/aarch64-linux-gnu/glibc-hwcaps/new");cap.mkdirs()
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.provePackage(root.path,helper)}.isFailure);cap.deleteRecursively()
            val seal=File(helper.installRoot,"SHA256SUMS");Files.setAttribute(seal.toPath(),"unix:mode",493)
            assertTrue(runCatching{LocalUbuntuNimLaunchAuthority.provePackage(root.path,helper)}.isFailure);Files.setAttribute(seal.toPath(),"unix:mode",420)
            LocalUbuntuNimLaunchAuthority.proveOwnedPackages(root.path,server,listOf(helper,tool))
            LocalUbuntuNimLaunchAuthority.prove(root.path,identity,records,{})
        } finally{root.deleteRecursively()}
    }
}
