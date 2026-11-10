package com.baystudio.droide.core

/** Genuine staged activation only. Authored fixture has no imports, schemas, plugins, or inherited config. */
internal object LocalUbuntuVueLspHealth {
    const val TIMEOUT_MS=60_000L
    const val SUCCESS="DROIDE_VUE_HEALTH_OK"
    const val HELPER_SUCCESS="DROIDE_VUE_HELPER_IDENTITY_OK"
    const val CLEANUP="DROIDE_VUE_HEALTH_CLEANUP_OK"
    fun succeeded(result:ExecResult):Boolean=result.exitCode==0&&!result.timedOut&&!result.outputIncomplete&&result.output.trim()=="$CLEANUP\n$SUCCESS"
    fun helperSucceeded(result:ExecResult):Boolean=result.exitCode==0&&!result.timedOut&&!result.outputIncomplete&&result.output.trim()=="$HELPER_SUCCESS\n$CLEANUP"
    fun cleanupVerified(result:ExecResult):Boolean=!result.timedOut&&!result.outputIncomplete&&result.output.lineSequence().count{it==CLEANUP}==1
    fun helperCleanupVerified(result:ExecResult):Boolean=cleanupVerified(result)
    fun installCommand(root:String,helper:String,helperOnly:Boolean):List<String>{
        require(root.startsWith('/')&&'\u0000' !in root&&'\n' !in root&&helper.startsWith('/')&&'\u0000' !in helper&&'\n' !in helper)
        fun q(s:String)="'"+s.replace("'","'\\''")+"'"
        val script=if(helperOnly)LspVueLaunchPolicy.HELPER_HEALTH else LspVueLaunchPolicy.HEALTH
        val mode=if(root==LocalUbuntuVueHelperPolicy.ROOT)"--installed-health" else "--health"
        val command=buildString{
            append("umask 077; p=$(/usr/bin/mktemp -d /tmp/droide-vue-health-XXXXXXXXXXXX) || exit 70; ")
            append("/usr/bin/mkdir \"$"+"p/home\" \"$"+"p/tmp\" \"$"+"p/config\" \"$"+"p/cache\" \"$"+"p/data\" \"$"+"p/state\" \"$"+"p/run\" \"$"+"p/commands\" || exit 70; ")
            append("/usr/bin/env -i HOME=\"$"+"p/home\" TMPDIR=\"$"+"p/tmp\" XDG_CONFIG_HOME=\"$"+"p/config\" XDG_CACHE_HOME=\"$"+"p/cache\" XDG_DATA_HOME=\"$"+"p/data\" XDG_STATE_HOME=\"$"+"p/state\" XDG_RUNTIME_DIR=\"$"+"p/run\" PATH=\"$"+"p/commands\" LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=dumb ")
            append(q(helper)).append(' ').append(q("$root/$script")).append(' ').append(if(helperOnly)mode else "--health").append(' ').append(q(root))
            if(!helperOnly)append(" \"$"+"p\"")
            if(helperOnly){append("; result=$?; /usr/bin/rmdir \"$"+"p/home\" \"$"+"p/tmp\" \"$"+"p/config\" \"$"+"p/cache\" \"$"+"p/data\" \"$"+"p/state\" \"$"+"p/run\" \"$"+"p/commands\" \"$"+"p\" || exit 70; printf '%s\\n' '$CLEANUP'; exit \"$"+"result\"")}
        }
        return listOf("/usr/bin/env","-i","/bin/sh","-c",command)
    }
    fun command(root:String,identity:VueManagedLaunchIdentity):List<String>{require(LspVueLaunchPolicy.matchesIdentity(identity));return installCommand(root,identity.helperExecutable,false)}
}
