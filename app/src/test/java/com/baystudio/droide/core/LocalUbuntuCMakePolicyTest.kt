package com.baystudio.droide.core

import android.app.Application
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class LocalUbuntuCMakePolicyTest {
    @After fun resetOwnedHostBoundary() = CMakeHostOs.reset()

    @Test fun exactFinitePublisherClosure() {
        val r=LocalUbuntuCMakeCatalog.recipe
        assertEquals(listOf("cmake-language-server","pygls","lsprotocol","cattrs","attrs"),r.wheels.map {it.name})
        assertEquals(listOf("0.1.11","1.3.1","2023.0.1","23.2.3","23.2.0"),r.wheels.map {it.version})
        assertEquals(254702L,r.wheels.sumOf {it.bytes})
        assertEquals(5,r.wheels.map {it.url}.distinct().size)
        assertTrue(r.wheels.all {it.url.startsWith("https://files.pythonhosted.org/packages/") && it.filename.endsWith("-py3-none-any.whl") && it.sha256.matches(Regex("[a-f0-9]{64}"))})
        assertTrue(runCatching {LocalUbuntuCMakePolicy.requireReviewed(r.copy(wheels=r.wheels.dropLast(1)))}.isFailure)
        assertTrue(runCatching {LocalUbuntuCMakePolicy.requireReviewed(r.copy(version="latest"))}.isFailure)
        assertEquals(listOf("python3","cmake"),LocalUbuntuCMakePolicy.packages)
    }
    @Test fun strictJsonRejectsAmbiguity() {
        listOf("{\"a\":1,\"a\":2}","{\"a\":NaN}","{\"a\":1}x","{\"a\":{\"x\":1,\"x\":2}}","{\"a\":1,}").forEach {
            assertTrue(it,runCatching {LocalUbuntuCMakePolicy.strictObject(it)}.isFailure)
        }
    }
    @Test fun fileProofRejectsSymlinkAndOversize() {
        val root=CMakeHostOs.temporaryRoot("cmake-policy-test")
        try {
            val file=File(root,"one").apply {writeText("test")}
            assertEquals("test",LocalUbuntuCMakePolicy.text(file,4))
            assertTrue(runCatching {LocalUbuntuCMakePolicy.read(file,3)}.isFailure)
            val hard=File(root,"hard");Files.createLink(hard.toPath(),file.toPath())
            assertTrue(runCatching {LocalUbuntuCMakePolicy.read(file,4)}.isFailure);hard.delete()
            val link=File(root,"link");Files.createSymbolicLink(link.toPath(),file.toPath())
            assertTrue(runCatching {LocalUbuntuCMakePolicy.read(link,4)}.isFailure)
            assertTrue(runCatching {LocalUbuntuCMakePolicy.read(file,4){error("cancel")}}.isFailure)
        } finally {root.deleteRecursively()}
    }
    @Test fun sealIncludesEmptyDirectoriesAndHelperFiles() {
        val root=CMakeHostOs.temporaryRoot("cmake-tree-test")
        try {
            File(root,"empty").mkdir();File(root,"helper").writeText("fixture")
            val before=LocalUbuntuCMakePolicy.tree(root)
            File(root,"extra").mkdir();assertNotEquals(before,LocalUbuntuCMakePolicy.tree(root))
            File(root,"helper").writeText("mutation");assertNotEquals(before,LocalUbuntuCMakePolicy.tree(root))
        } finally {root.deleteRecursively()}
    }
    @Test fun runtimeFileProofDetectsLoaderSearchAndPreloadDrift() {
        val root=CMakeHostOs.temporaryRoot("cmake-runtime-proof")
        try {
            val guest=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
            fun file(path:String)=File(guest,path.removePrefix("/"))
            val content=mapOf("/usr/bin/python3.12" to "authored Python placeholder", "/usr/bin/cmake" to "authored CMake placeholder",
                "/var/lib/dpkg/status" to "authored package status", "/etc/ld.so.cache" to "authored cache placeholder",
                "/etc/ld.so.conf" to "include /etc/ld.so.conf.d/*.conf", "/etc/ld.so.conf.d/arm64.conf" to "/usr/lib")
            content.forEach { (path,value)->file(path).apply {parentFile.mkdirs();writeText(value)} }
            listOf("/usr/lib/python3.12","/usr/share/cmake-3.28").forEach {file(it).mkdirs()}
            val snapshot=buildJsonObject {
                put("schema",1);put("implementation","CPython");put("pythonVersion","3.12.3 authored");put("unicodeVersion","15.0.0");put("cmakeMajorMinor","3.28")
                put("packageVersions",buildJsonObject {put("python3.12","3.12.3");put("cmake","3.28.3")})
                put("files",JsonObject(content.mapValues { (_,value)->buildJsonObject {put("sha256",LocalUbuntuCMakePolicy.hash(value.toByteArray()));put("bytes",value.toByteArray().size)} }))
                put("links",JsonObject(emptyMap()));put("directories",JsonArray(listOf("/usr/lib/python3.12","/usr/share/cmake-3.28").map(::JsonPrimitive)));put("pythonZipAbsent",true)
                put("loaderConfigNames",JsonArray(listOf(JsonPrimitive("arm64.conf"))))
                put("loaderAbsentDirectories",JsonArray(listOf(JsonPrimitive("/usr/local/lib"))))
                put("loaderInventories",buildJsonObject {put("/usr/lib",buildJsonObject {put("python3.12",buildJsonObject {put("type","directory")})})})
            }
            LocalUbuntuCMakePolicy.verifyRuntime(root.path,snapshot)
            fun rejectMutation(path:String,value:String) {
                val f=file(path);val before=f.takeIf {it.exists()}?.readBytes()
                f.parentFile.mkdirs();f.writeText(value)
                assertTrue(path,runCatching {LocalUbuntuCMakePolicy.verifyRuntime(root.path,snapshot)}.isFailure)
                if(before==null) f.delete() else f.writeBytes(before)
                LocalUbuntuCMakePolicy.verifyRuntime(root.path,snapshot)
            }
            rejectMutation("/etc/ld.so.preload","")
            rejectMutation("/etc/ld.so.cache","mutated cache")
            rejectMutation("/etc/ld.so.conf.d/extra.conf","/usr/lib")
            rejectMutation("/usr/lib/injected.so","authored injected file")
            file("/usr/local/lib").mkdirs()
            assertTrue(runCatching {LocalUbuntuCMakePolicy.verifyRuntime(root.path,snapshot)}.isFailure)
        } finally {root.deleteRecursively()}
    }
    private class AdmissionFixture(val root:File,val packageRoot:File,val records:List<ManagedPackageRecord>,val identity:CMakeManagedLaunchIdentity) {
        var now=10_000_000_000L
        var advance=false
        fun witness()=CMakeManagedLaunchAuthority.proveAndCaptureAdmission(root.path,identity,records,{}) {if(advance)now+=2_000_000_001L;now}
        val nested get()=File(packageRoot,"payload/purelib/cmake_language_server/authored.py")
    }
    /** Trusted receipt setup is wholly authored test data; this does not simulate wheel adoption or real health. */
    private fun admissionFixture():AdmissionFixture {
        val root=CMakeHostOs.temporaryRoot("cmake-admission-authored")
        val p=LocalPackageInstallJournal.paths(root.path,LocalUbuntuCMakePolicy.FAMILY,LocalUbuntuCMakePolicy.VERSION,LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE)
        val dir=p.final.apply {mkdirs()};val activation="12345678-1234-1234-1234-123456789abc"
        fun write(base:File,path:String,text:String)=File(base,path).apply {parentFile.mkdirs();writeText(text)}
        write(dir,"DROIDE_ACTIVATION_ID",activation)
        write(dir,"payload/purelib/cmake_language_server/authored.py","# authored inert fixture\n")
        val assetRoot=listOf(File("app/src/main/assets/lsp/cmake"),File("src/main/assets/lsp/cmake")).first {it.isDirectory}
        for(name in listOf("cmake-bootstrap.py","cmake-supervisor.py"))write(dir,"payload/$name",File(assetRoot,name).readText())
        write(dir,LocalUbuntuCMakePolicy.HEALTH,LocalUbuntuCMakeLspHealth.script)
        write(dir,LocalUbuntuCMakePolicy.LOCK,LocalUbuntuCMakePolicy.lock.toString())
        val guestRoot=File(root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")
        val content=mapOf("/usr/bin/python3.12" to "authored inert Python bytes", "/usr/bin/cmake" to "authored inert CMake bytes",
            "/var/lib/dpkg/status" to "authored inert status", "/etc/ld.so.cache" to "authored inert cache",
            "/etc/ld.so.conf" to "include /etc/ld.so.conf.d/*.conf", "/etc/ld.so.conf.d/arm64.conf" to "/usr/lib")
        content.forEach {(path,value)->write(guestRoot,path.removePrefix("/"),value)}
        val directories=listOf("/usr/lib/python3.12","/usr/lib/python3.12/lib-dynload","/usr/share/cmake-3.28","/usr/share/cmake-3.28/Modules")
        directories.forEach {File(guestRoot,it.removePrefix("/")).mkdirs()}
        val runtime=buildJsonObject {
            put("schema",1);put("implementation","CPython");put("pythonVersion","3.12.3 authored");put("unicodeVersion","15.0.0");put("cmakeMajorMinor","3.28")
            put("packageVersions",buildJsonObject {put("python3.12","3.12.3");put("cmake","3.28.3")})
            put("files",JsonObject(content.mapValues {(_,value)->buildJsonObject {put("sha256",LocalUbuntuCMakePolicy.hash(value.toByteArray()));put("bytes",value.toByteArray().size)}}))
            put("links",JsonObject(emptyMap()));put("directories",JsonArray(directories.map(::JsonPrimitive)));put("pythonZipAbsent",true)
            put("loaderConfigNames",JsonArray(listOf(JsonPrimitive("arm64.conf"))));put("loaderAbsentDirectories",JsonArray(listOf(JsonPrimitive("/usr/local/lib"))))
            put("loaderInventories",buildJsonObject {put("/usr/lib",buildJsonObject {put("python3.12",buildJsonObject {put("type","directory")})})})
        }.toString()
        write(dir,LocalUbuntuCMakePolicy.RUNTIME,runtime)
        val guest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(dir.path,"${root.path}/packages/"))
        val stage=requireNotNull(LocalLinuxPackageEnvironment.guestPath(p.stage.path,"${root.path}/packages/"))
        val purelib=LocalUbuntuCMakePolicy.tree(File(dir,LocalUbuntuCMakePolicy.PURELIB)).getValue("files").jsonObject.mapValues {it.value.jsonPrimitive.content}
        write(dir,LocalUbuntuCMakePolicy.AUTHORITY,LocalUbuntuCMakePolicy.authority(activation,stage,guest,LocalUbuntuCMakePolicy.hash(runtime.toByteArray()),purelib).toString())
        write(dir,"payload/bin/${LocalUbuntuCMakePolicy.COMMAND}",LocalUbuntuCMakePolicy.guestWrapper(guest)).setExecutable(true,true)
        write(dir,"payload/host-bin/${LocalUbuntuCMakePolicy.COMMAND}",LocalUbuntuCMakePolicy.hostWrapper("${root.path}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch",guest)).setExecutable(true,true)
        write(dir,LocalUbuntuCMakePolicy.SEAL,LocalUbuntuCMakePolicy.tree(dir).toString())
        val seal=LocalUbuntuCMakePolicy.sha(File(dir,LocalUbuntuCMakePolicy.SEAL))
        val layerRoot=File(root,"packages/runtime.authored/1/arm64-v8a")
        val layerContent=mapOf("LAYER.txt" to "authored layer", "payload/verify-layer" to "authored inert verify-layer")
        layerContent.forEach {(path,value)->write(layerRoot,path,value)}
        write(layerRoot,"SHA256SUMS",layerContent.entries.joinToString("") {(path,value)->LocalUbuntuCMakePolicy.hash(value.toByteArray())+"  "+path+"\n"})
        val layer=ManagedPackageRecord("runtime.authored","1",ExecutionScope.LOCAL_LINUX_ARM64.name,layerRoot.path,1,
            metadata=mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to LocalUbuntuCMakePolicy.packages.joinToString(",")))
        val record=ManagedPackageRecord(LocalUbuntuCMakePolicy.FAMILY,LocalUbuntuCMakePolicy.VERSION,ExecutionScope.LOCAL_LINUX_ARM64.name,dir.path,1,
            abi="arm64-v8a",artifactSha256=LocalUbuntuCMakePolicy.recipeSha256,commands=mapOf(LocalUbuntuCMakePolicy.COMMAND to "${dir.path}/payload/host-bin/${LocalUbuntuCMakePolicy.COMMAND}"),
            pathEntries=listOf("${dir.path}/payload/host-bin"),dependencies=listOf("${layer.familyId}@${layer.version}"),healthChecks=listOf(ManagedPackageHealthCheck("host-bin/${LocalUbuntuCMakePolicy.COMMAND}",listOf("--droide-health"))),
            metadata=mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE,
                LocalManagedPackageMetadata.ACTIVATION_ID_KEY to activation,LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
        val records=listOf(record,layer);val wrapper="$guest/payload/bin/${LocalUbuntuCMakePolicy.COMMAND}"
        val identity=CMakeManagedLaunchIdentity(record.familyId,record.version,LocalUbuntuCMakePolicy.REVISION,CMakeManagedLaunchAuthority.receiptHash(record,records),LocalUbuntuCMakePolicy.recipeSha256,seal,
            LocalUbuntuCMakePolicy.hash((record.familyId+"@"+record.version+":"+activation+":"+record.installRoot).toByteArray()),wrapper,wrapper,"$guest/${LocalUbuntuCMakePolicy.BOOTSTRAP}",
            "$guest/${LocalUbuntuCMakePolicy.SUPERVISOR}","$guest/${LocalUbuntuCMakePolicy.PURELIB}",LocalUbuntuCMakePolicy.PYTHON,LocalUbuntuCMakePolicy.CMAKE,LocalUbuntuCMakePolicy.hash(runtime.toByteArray()),
            LocalUbuntuCMakePolicy.BOOTSTRAP_SHA256,LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256)
        return AdmissionFixture(root,dir,records,identity)
    }
    @Test fun ownerWitnessReusesThenRejectsDueNestedDrift() {
        val f=admissionFixture()
        try {
            val w=f.witness();assertTrue(w.isCurrent(f.records,emptyMap(),{}));f.nested.writeText("# changed inert fixture!\n")
            f.now+=4_999_999_999L;assertTrue(w.isCurrent(f.records,emptyMap(),{})) // Explicit bounded metadata window.
            f.now+=1;assertFalse(w.isCurrent(f.records,emptyMap(),{}))
            f.nested.writeText("# authored inert fixture\n");assertFalse(w.isCurrent(f.records,emptyMap(),{}))
        } finally {f.root.deleteRecursively()}
    }
    @Test fun unrelatedSiblingAndSecondOwnerPreparationPreserveExistingWitness() {
        val f=admissionFixture()
        try {
            val first=f.witness()
            File(f.root,"run/private-owner-one/transport").mkdirs()
            File(f.root,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs/opt/droide/private-owner-two").mkdirs()
            assertTrue(first.isCurrent(f.records,emptyMap(),{}))
            val second=f.witness()
            File(f.root,"run/private-owner-two/transport").mkdirs()
            f.now+=5_000_000_000L
            assertTrue(first.isCurrent(f.records,emptyMap(),{}));assertTrue(second.isCurrent(f.records,emptyMap(),{}))
            assertTrue(first.requireCurrentBeforeStart(f.records,emptyMap(),{}))
        } finally {f.root.deleteRecursively()}
    }
    @Test fun ownerWitnessSentinelAndReceiptChangesRetireImmediately() {
        val f=admissionFixture()
        try {
            val w=f.witness();val bootstrap=File(f.packageRoot,LocalUbuntuCMakePolicy.BOOTSTRAP);val bytes=bootstrap.readBytes()
            bootstrap.appendText("\n# changed");assertFalse(w.isCurrent(f.records,emptyMap(),{}));bootstrap.writeBytes(bytes)
            assertFalse(w.isCurrent(f.records,emptyMap(),{}))
            val fresh=f.witness();assertFalse(fresh.isCurrent(f.records,mapOf(LocalUbuntuCMakePolicy.FAMILY to "latest"),{}))
            val receipt=f.witness();assertFalse(receipt.isCurrent(f.records.map {if(it.familyId==LocalUbuntuCMakePolicy.FAMILY)it.copy(installedAtEpochMs=2) else it},emptyMap(),{}))
        } finally {f.root.deleteRecursively()}
    }
    @Test fun ownerWitnessTimeoutCancellationAndExplicitRetirementAreSticky() {
        val f=admissionFixture()
        try {
            val w=f.witness();f.advance=true;assertFalse(w.isCurrent(f.records,emptyMap(),{}));f.advance=false;assertFalse(w.isCurrent(f.records,emptyMap(),{}))
            val cancel=f.witness();assertTrue(runCatching {cancel.isCurrent(f.records,emptyMap()){throw kotlinx.coroutines.CancellationException("authored")}}.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(cancel.isCurrent(f.records,emptyMap(),{}))
            val retired=f.witness();retired.retire();assertFalse(retired.isCurrent(f.records,emptyMap(),{}))
        } finally {f.root.deleteRecursively()}
    }
    @Test fun protectedStartDoesNotRefreshChangedMetadataBaseline() {
        val f=admissionFixture()
        try {
            val w=f.witness();val bytes=f.nested.readBytes();f.nested.writeBytes(bytes)
            CMakeManagedLaunchAuthority.prove(f.root.path,f.identity,f.records,{}) // Byte-identical rewriting still passes the full byte proof.
            assertFalse(w.requireCurrentBeforeStart(f.records,emptyMap(),{}))
            val next=f.witness();File(f.nested.parentFile,"injected.py").writeText("# authored inert addition")
            assertFalse(next.requireCurrentBeforeStart(f.records,emptyMap(),{}))
            assertTrue(runCatching {CMakeManagedLaunchAuthority.prove(f.root.path,f.identity,f.records,{})}.isFailure)
        } finally {f.root.deleteRecursively()}
    }
    @Test fun exactRealHealthSuccessAndBudgets() {
        assertEquals(60000L,LocalUbuntuCMakeLspHealth.TIMEOUT_MS)
        assertEquals(45000L,LocalUbuntuCMakeLspHealth.WORK_MS)
        assertEquals(8000L,LocalUbuntuCMakeLspHealth.CLEANUP_MS)
        val ok=ExecResult(0,"DROIDE_CMAKE_HEALTH_OK\n",false)
        assertTrue(LocalUbuntuCMakeLspHealth.succeeded(ok))
        assertFalse(LocalUbuntuCMakeLspHealth.succeeded(ok.copy(timedOut=true)))
        assertFalse(LocalUbuntuCMakeLspHealth.succeeded(ok.copy(outputIncomplete=true)))
        assertFalse(LocalUbuntuCMakeLspHealth.succeeded(ok.copy(exitCode=1)))
        assertFalse(LocalUbuntuCMakeLspHealth.succeeded(ok.copy(output="extra\nDROIDE_CMAKE_HEALTH_OK")))
        assertFalse(LocalUbuntuCMakeLspHealth.succeeded(ok.copy(output="")))
    }
}
