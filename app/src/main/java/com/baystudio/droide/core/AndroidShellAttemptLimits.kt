package com.baystudio.droide.core

/** Runtime-only capability. Failure leaves old path intact, never substitutes guessed limits. */
internal object AndroidShellAttemptLimits {
    fun current():CountedShellArgv.Limits? = try {
        val page=android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        val args=android.system.Os.sysconf(android.system.OsConstants._SC_ARG_MAX)
        if(page<=0 || args<=0) null else CountedShellArgv.Limits(page,args,if(android.os.Process.is64Bit())8 else 4)
    } catch(_:Exception) {null} catch(_:LinkageError) {null}
}
