package com.baystudio.droide.core

/** Isolated zero-document transport and ordinary owned cleanup, never compiler/backend readiness. */
internal object LocalUbuntuNimLspHealth {
    const val TIMEOUT_MS = 45_000L
    const val SUCCESS = "DROIDE_NIM_HEALTH_OK"
    fun succeeded(result: ExecResult): Boolean = result.exitCode==0 && !result.timedOut && !result.outputIncomplete && result.output.trim()==SUCCESS
    fun installCommand(root:String,server:String,helper:String):List<String> {
        require(listOf(root,server,helper).all(LspNimLaunchPolicy::safeManagedPath));require(helper==LspNimLaunchPolicy.HELPER)
        return listOf("/usr/bin/env","-i","HOME=/nonexistent","PATH=","LANG=C.UTF-8","LC_ALL=C.UTF-8","TERM=dumb",
            helper,"$root/${LspNimLaunchPolicy.HEALTH}","--health",root,server)
    }
    fun command(guestRoot:String,identity:NimManagedLaunchIdentity):List<String> {
        require(LspNimLaunchPolicy.matchesIdentity(identity));return installCommand(guestRoot,identity.serverExecutable,identity.helperExecutable)
    }
}
