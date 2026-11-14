package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.*

/** Native positive acceptance remains separate. This command tests only zero-workspace stdio and owned retirement. */
internal object LocalUbuntuCSharpLspHealth {
    const val TIMEOUT_MS = CSharpResourcePolicy.HEALTH_TOTAL_MS
    const val SUCCESS = "DROIDE_CSHARP_HEALTH_OK"
    const val CLEANUP = "DROIDE_CSHARP_CLEANUP_OK"
    fun cleanupVerified(result: ExecResult): Boolean = !result.timedOut && !result.outputIncomplete &&
        result.output.lineSequence().filter { it.isNotBlank() }.lastOrNull() == CLEANUP
    fun succeeded(result: ExecResult): Boolean = result.exitCode == 0 && cleanupVerified(result) &&
        result.output.trim() == "$SUCCESS\n$CLEANUP"
    /** Only this staged host lane supplies the required private /tmp bind. Generic health fails closed. */
    suspend fun runStaged(appRoot: String, stage: File, sdk: ManagedPackageRecord, helper: ManagedPackageRecord): ExecResult =
        LocalExecutionSubstrate.runCSharpStagedHealth(appRoot,stage,sdk,helper)
    fun installCommand(root: String, sdkRoot: String, helperExecutable: String, privateRoot: String? = null): List<String> {
        require(listOf(root,sdkRoot,helperExecutable).all(LspCSharpLaunchPolicy::safeManagedPath))
        require(sdkRoot==LspCSharpLaunchPolicy.SDK_ROOT && helperExecutable==LspCSharpLaunchPolicy.HELPER)
        return listOf("/usr/bin/env","-i","HOME=/nonexistent","PATH=","LANG=C.UTF-8","LC_ALL=C.UTF-8","TERM=dumb") +
            CSharpResourcePolicy.HEALTH.shellPrefix() + listOf(helperExecutable) + CSharpResourcePolicy.HEALTH.helperArguments +
            listOf("$root/${LspCSharpLaunchPolicy.HEALTH}","--health",root,sdkRoot) + listOfNotNull(privateRoot)
    }
}


internal class CSharpHealthPrivateState private constructor(val localRoot: File, val guestRoot: String) {
    val identity = CSharpControlRecords.directoryIdentity(localRoot)
    val localWorkspace = File(localRoot,"workspace")
    val guestWorkspace = "$guestRoot/workspace"
    fun verify() { check(CSharpControlRecords.directoryIdentity(localRoot)==identity) }
    companion object {
        fun allocate(appRoot: File): CSharpHealthPrivateState {
            val parent=File(appRoot,"runtime-tmp")
            require(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS)&&parent.canonicalPath==parent.absolutePath)
            val root=File(parent,"droide-csharp-health-"+UUID.randomUUID().toString().replace("-",""))
            val mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            Files.createDirectory(root.toPath(),mode)
            for(name in listOf("home","config","cache","data","state","run","tmp","nuget","nuget-http","workspace"))
                Files.createDirectory(File(root,name).toPath(),mode)
            return CSharpHealthPrivateState(root,"/run/droide-csharp/${root.name}")
        }
    }
}
