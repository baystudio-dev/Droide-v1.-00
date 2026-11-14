package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Five exact original wheels projected without pip, an sdist, install hooks or a dependency resolver. */
internal class LocalUbuntuCMakeInstaller(
    context: Context,
    private val projectRoot: File,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val appContext=context.applicationContext
    private val root=appContext.filesDir.canonicalPath
    private val transfer=UserInitiatedArtifactTransfer(appContext)

    suspend fun install(recipe:LocalUbuntuCMakeRecipe,progress:(String)->Unit={}):ManagedPackageRecord = withContext(Dispatchers.IO) {
        LocalUbuntuCMakePolicy.requireReviewed(recipe)
        require(Build.SUPPORTED_ABIS.firstOrNull()=="arm64-v8a") { "Managed CMake requires ARM64 Android and Ubuntu 24.04" }
        PackageBackendContract.requireSame("CMake five-wheel recipe",PackageBackendId.LOCAL_APP,authority.backendId,ubuntu.backendId)
        val existing=authority.ownedReceipt(recipe.familyId,recipe.version)
        LocalInstalledPackageReuse.artifact(existing,recipe.familyId,recipe.version,LocalUbuntuCMakePolicy.recipeSha256,authority::owns,authority::verify,
            identity={it.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE})?.let { return@withContext it }
        // A layer has no commands. Neither the interactive Python nor CMake selection is changed.
        val layer=requireNotNull(ubuntu.ensurePackages(LocalUbuntuCMakePolicy.packages,progress,allowSharedRepair=false))
        authority.adopt(layer)
        require(File(root).usableSpace>=150_000_000L) { "Free at least 150 MB before installing managed CMake" }
        val launch=LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        suspend fun run(argv:List<String>,timeout:Long=LocalUbuntuCMakeLspHealth.TIMEOUT_MS,max:Int=128_000):ExecResult = OwnedInstallerCommand.capture(
            launch,projectRoot,argv,timeoutMs=timeout,maxOutputBytes=max)
        val paths=LocalPackageInstallJournal.paths(root,recipe.familyId,recipe.version,LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE)
        val descriptors=recipe.wheels.map { it.artifact() }
        progress("Fetching the exact five reviewed CMake wheels with publisher SHA-256…\n")
        transfer.withArtifacts(descriptors) { downloaded ->
            LocalPackageInstallJournal.transactionMutex.withLock {
                val old=authority.recoveryReceipt(recipe.familyId,recipe.version,LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE)
                LocalPackageInstallJournal.recover(paths,old?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
                var activation:LocalPackageInstallJournal.Activation?=null
                try {
                    check(paths.stage.mkdirs()) { "Cannot create CMake staging tree" }
                    val id=LocalPackageInstallJournal.prepare(paths.stage)
                    val stageGuest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath,"$root/packages/"))
                    val finalGuest=requireNotNull(LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath,"$root/packages/"))
                    val coroutine=currentCoroutineContext();val active={coroutine.ensureActive()}
                    val archives=recipe.wheels.mapIndexed { i,wheel->wheel to downloaded.getValue(descriptors[i]) }.toMap()
                    val projection=LocalUbuntuCMakeWheelProjector.project(recipe,archives,File(paths.stage,LocalUbuntuCMakePolicy.PURELIB),active)
                    fun asset(name:String,relative:String,expected:String) {
                        val bytes=appContext.assets.open("lsp/cmake/$name").use { it.readBytes() }
                        require(bytes.size<=1_000_000 && LocalUbuntuCMakePolicy.hash(bytes)==expected) { "Packaged CMake helper changed" }
                        File(paths.stage,relative).writeBytes(bytes)
                    }
                    asset("cmake-bootstrap.py",LocalUbuntuCMakePolicy.BOOTSTRAP,LocalUbuntuCMakePolicy.BOOTSTRAP_SHA256)
                    asset("cmake-supervisor.py",LocalUbuntuCMakePolicy.SUPERVISOR,LocalUbuntuCMakeLaunchPolicy.SUPERVISOR_SHA256)
                    File(paths.stage,LocalUbuntuCMakePolicy.HEALTH).writeText(LocalUbuntuCMakeLspHealth.script)
                    File(paths.stage,LocalUbuntuCMakePolicy.LOCK).writeText(LocalUbuntuCMakePolicy.lock.toString())
                    progress("Recording CPython 3.12, Unicode 15 and CMake 3.28 runtime fingerprints…\n")
                    LocalUbuntuCMakePolicy.verifyLoaderBoundary(root)
                    val fingerprint=run(listOf("/bin/sh","-c",
                        "cd /; exec /usr/bin/env -i HOME=/nonexistent TMPDIR=/tmp PATH= LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb /usr/bin/python3.12 -I -S -B " +
                            LocalExecutionSubstrate.shellQuote("$stageGuest/${LocalUbuntuCMakePolicy.BOOTSTRAP}") + " --runtime-fingerprint"),max=4_000_000)
                    require(fingerprint.exitCode==0 && !fingerprint.timedOut && !fingerprint.outputIncomplete) { "Reviewed Python/CMake runtime fingerprint failed" }
                    val runtime=LocalUbuntuCMakePolicy.strictObject(fingerprint.output)
                    LocalUbuntuCMakePolicy.verifyRuntime(root,runtime,active)
                    // Keep the canonical bytes generated by the fixed interpreter helper, excluding only its line terminator.
                    val runtimeText=fingerprint.output.trimEnd('\n')
                    require(runtimeText==fingerprint.output || runtimeText+"\n"==fingerprint.output)
                    File(paths.stage,LocalUbuntuCMakePolicy.RUNTIME).writeText(runtimeText)
                    val proof=LocalUbuntuCMakePolicy.authority(id,stageGuest,finalGuest,LocalUbuntuCMakePolicy.hash(runtimeText.toByteArray()),projection.files)
                    File(paths.stage,LocalUbuntuCMakePolicy.AUTHORITY).writeText(proof.toString())
                    for(relative in listOf("payload/bin","payload/host-bin")) check(File(paths.stage,relative).mkdirs())
                    File(paths.stage,"payload/bin/${recipe.command}").apply {writeText(LocalUbuntuCMakePolicy.guestWrapper(finalGuest));check(setExecutable(true,true))}
                    File(paths.stage,"payload/host-bin/${recipe.command}").apply {writeText(LocalUbuntuCMakePolicy.hostWrapper(ubuntu.launcherPath(),finalGuest));check(setExecutable(true,true))}
                    progress("Checking isolated CMake help and zero-document initialize/shutdown…\n")
                    val health=run(LocalUbuntuCMakeLspHealth.command(stageGuest))
                    require(LocalUbuntuCMakeLspHealth.succeeded(health)) { "CMake isolated lifecycle or owned-group cleanup failed: ${health.output.takeLast(2000)}" }
                    LocalUbuntuCMakePolicy.verifyRuntime(root,runtime,active)
                    File(paths.stage,LocalUbuntuCMakePolicy.SEAL).writeText(LocalUbuntuCMakePolicy.tree(paths.stage,active).toString())
                    val seal=LocalUbuntuCMakePolicy.sha(File(paths.stage,LocalUbuntuCMakePolicy.SEAL),4_194_304,active)
                    activation=LocalPackageInstallJournal.activate(paths.stage,paths.final,paths.previous)
                    val record=ManagedPackageRecord(recipe.familyId,recipe.version,ExecutionScope.LOCAL_LINUX_ARM64.name,paths.final.absolutePath,System.currentTimeMillis(),
                        abi="arm64-v8a",artifactSha256=LocalUbuntuCMakePolicy.recipeSha256,
                        commands=mapOf(recipe.command to "${paths.final}/payload/host-bin/${recipe.command}"),pathEntries=listOf("${paths.final}/payload/host-bin"),
                        dependencies=listOf("${layer.familyId}@${layer.version}"),healthChecks=listOf(ManagedPackageHealthCheck("host-bin/${recipe.command}",listOf("--droide-health"))),
                        metadata=mapOf(PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                            LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE,
                            LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,
                            LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                            LocalManagedPackageMetadata.LIBC_KEY to DeclarativeLibcCompatibility.GLIBC.name))
                    authority.adopt(record) // final-root byte proof and real health precede durable receipt/projection commit
                    activation=null
                    withContext(NonCancellable){LocalPackageInstallJournal.commit(paths.previous)}
                    record
                } catch(failure:Throwable) {
                    withContext(NonCancellable) {
                        activation?.let { runCatching {LocalPackageInstallJournal.rollback(paths.final,paths.previous,it)}.exceptionOrNull()?.let(failure::addSuppressed) }
                        if(!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("Cannot clean CMake staging tree"))
                    };throw failure
                }
            }
        }
    }
}
