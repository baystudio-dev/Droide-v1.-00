package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Only genuine original outputs can satisfy these health markers; host independently appends cleanup. */
internal object LocalUbuntuTomlLspHealth {
    const val TIMEOUT_MS=TomlResourcePolicy.HEALTH_TOTAL_MS
    const val SUCCESS="DROIDE_TOML_GENUINE_HEALTH_OK"
    const val NODE_SUCCESS="DROIDE_TOML_NODE_IDENTITY_OK"
    const val CLEANUP="DROIDE_TOML_OWNED_CLEANUP_OK"
    fun cleanupVerified(result:ExecResult)=!result.outputIncomplete&&result.output.lineSequence().filter{it.isNotBlank()}.lastOrNull()==CLEANUP
    fun nodeCleanupVerified(result:ExecResult)=cleanupVerified(result)
    fun succeeded(result:ExecResult)=result.exitCode==0&&!result.timedOut&&cleanupVerified(result)&&result.output.trim()=="$SUCCESS\n$CLEANUP"
    fun nodeSucceeded(result:ExecResult)=result.exitCode==0&&!result.timedOut&&nodeCleanupVerified(result)&&result.output.trim()=="$NODE_SUCCESS\n$CLEANUP"
    suspend fun runStaged(appRoot:String,stage:File,node:ManagedPackageRecord,hold:LocalPackageInstallJournal.TomlHealthHold):ExecResult=LocalExecutionSubstrate.runTomlStagedHealth(appRoot,stage,node,hold)
    suspend fun runNodeStaged(appRoot:String,stage:File,hold:LocalPackageInstallJournal.TomlHealthHold):ExecResult=LocalExecutionSubstrate.runTomlStagedHealth(appRoot,stage,null,hold)
}

/** TOML-only bounded capture. Generic supervisor semantics and other routes are unchanged. */
internal object TomlHealthCapture {
    suspend fun capture(process:Process,deadline:TomlDeadline,observe:()->Unit,terminate:()->Unit,finish:()->Boolean):ExecResult {
        val work=deadline.reserve(TomlResourcePolicy.RETIRE_MS)
        val bytes=java.io.ByteArrayOutputStream();val lock=Any();var accepting=true;var truncated=false
        val attempts=mutableListOf<TomlIoAttempt<*>>()
        fun<T> attempt(name:String,action:()->T)=TomlIoAttempt("health-$name",action).also{attempts+=it}
        val reader=attempt("read"){val chunk=ByteArray(8192);while(true){val n=process.inputStream.read(chunk);if(n<0)break;check(n>0);synchronized(lock){if(accepting){val keep=minOf(n,1_048_576-bytes.size());if(keep>0)bytes.write(chunk,0,keep);if(keep<n)truncated=true}}}}
        attempt("stdin-close"){process.outputStream.close()}
        val reaping=attempt("reap"){process.waitFor()}
        var failure:Throwable?=null;var timedOut=false;var cleanup=false;var exit=70
        try{while(process.isAlive){currentCoroutineContext().ensureActive();if(work.expired){timedOut=true;break};reader.failure?.let{throw it};observe();delay(minOf(TomlResourcePolicy.MONITOR_MS,work.remainingMillis().coerceAtLeast(1)))}
            if(!process.isAlive)exit=process.exitValue()
        }catch(t:Throwable){failure=t}
        finally{withContext(NonCancellable){
            // Exact group signalling is independent of every pipe monitor and the reader.
            attempt("group-stop",terminate).await(deadline.limited(2000))
            if(process.isAlive){attempt("destroy"){process.destroy()}.await(deadline.limited(250));if(process.isAlive)attempt("kill"){process.destroyForcibly()}.await(deadline.limited(250))}
            // Give a naturally closed stdout a bounded drain before forcing pipe closure.
            reader.await(deadline.limited(500))
            synchronized(lock){accepting=false}
            attempt("stdout-close"){process.inputStream.close()};attempt("stderr-close"){process.errorStream.close()}
            val settle=deadline.reserve(500);while(!settle.expired&&attempts.any{!it.complete})delay(10)
            if(attempts.all{it.succeeded}&&reaping.succeeded&&!process.isAlive&&!deadline.expired)cleanup=runCatching{finish()}.getOrDefault(false)
        }}
        if(failure is CancellationException)throw failure as CancellationException
        timedOut=timedOut||deadline.expired
        val incomplete=truncated||!reader.succeeded||!cleanup
        val output=synchronized(lock){bytes.toString("UTF-8").trimEnd()}+(if(truncated)"\n[output truncated]\n" else "")+
            if(cleanup)"\n${LocalUbuntuTomlLspHealth.CLEANUP}\n" else "\nTOML owned or I/O cleanup UNKNOWN\n"
        return ExecResult(if(timedOut)124 else if(failure!=null||!cleanup)70 else exit,output,timedOut,outputIncomplete=incomplete)
    }
}

internal class TomlHealthPrivateState private constructor(val localRoot:File,val guestRoot:String,val nodeExecutable:String,val token:String) {
    internal val identity=TomlWire.directoryIdentity(localRoot)
    val localWorkspace=File(localRoot,"workspace")
    @Volatile private var birth:TomlWire.ProcIdentity?=null
    @Volatile private var nodeBirth:TomlWire.ProcIdentity?=null
    fun verify(){check(TomlWire.directoryIdentity(localRoot)==identity)}
    fun shellCommand(command:List<String>):String {
        val q=LocalExecutionSubstrate::shellQuote;val body="set -eu; umask 077; line=\$(/system/bin/toybox cat /proc/\$\$/stat); fields=\${line##*) }; set -- \$fields; [ \"\$3\" = \"\$\$\" ]; start=\${20}; printf '{\"schema\":1,\"token\":\"$token\",\"pid\":%s,\"start\":\"%s\",\"group\":%s}' \"\$\$\" \"\$start\" \"\$3\" > ${q(File(localRoot,"host.json").absolutePath)}; exec "+command.joinToString(" ",transform=q)
        return "exec /system/bin/toybox setsid /system/bin/sh -c "+q(body)
    }
    fun observe(){verify();if(birth==null){val file=File(localRoot,"host.json");if(!file.exists())return;val r=TomlWire.strict(TomlWire.readBytes(file,65536));require(r["token"]?.jsonPrimitive?.content==token);val p=TomlWire.proc(r["pid"]!!.jsonPrimitive.int);require(p.start==r["start"]?.jsonPrimitive?.content&&p.pid==p.group);birth=p}
        val b=requireNotNull(birth);val group=TomlWire.group(b.group);require(group.size<=TomlResourcePolicy.GROUP_MEMBERS_MAX&&group.sumOf{it.rss}<=TomlResourcePolicy.OBSERVED_RSS_BYTES_MAX)
        val nodeFile=File(localRoot,"server.json");if(nodeBirth==null&&nodeFile.exists()){val r=TomlWire.strict(TomlWire.readBytes(nodeFile,65536));require(r["token"]?.jsonPrimitive?.content==token);val n=TomlWire.proc(r["pid"]!!.jsonPrimitive.int);require(n.start==r["start"]?.jsonPrimitive?.content&&n.group==b.group);nodeBirth=n};nodeBirth?.let{n->require(group.none{it.parent==n.pid&&it.pid!=n.pid}){"Unexpected Taplo health child"}}

    }
    fun terminateExact(){verify();val b=birth?:runCatching{val r=TomlWire.strict(TomlWire.readBytes(File(localRoot,"host.json"),65536));require(r["token"]?.jsonPrimitive?.content==token);TomlWire.recordedBirth(r)}.getOrNull()?:error("TOML health birth custody unavailable")
        birth=b;for(sig in listOf(15,9)){val group=TomlWire.group(b.group);if(group.isEmpty())return;val leader=group.singleOrNull{it.pid==b.pid};require(leader?.start==b.start){"TOML health group custody UNKNOWN"};TomlWire.signalGroup(b,sig);if(sig==15)Thread.sleep(200)}
    }
    fun finish(process:Process):Boolean {val b=birth?:return false;return !process.isAlive&&process.waitFor(250,java.util.concurrent.TimeUnit.MILLISECONDS)&&TomlWire.group(b.group).isEmpty()&&runCatching{TomlWire.removePrivate(localRoot,identity);true}.getOrDefault(false)}
    companion object {
        fun allocate(appRoot:File,nodeExecutable:String):TomlHealthPrivateState {val parent=File(appRoot,"runtime-tmp");require(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS)&&parent.canonicalPath==parent.absolutePath)
            val token=UUID.randomUUID().toString().replace("-","");val root=File(parent,"droide-toml-health-$token");val mode=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));Files.createDirectory(root.toPath(),mode)
            for(name in listOf("home","tmp","config","cache","data","state","run","workspace"))Files.createDirectory(File(root,name).toPath(),mode)
            return TomlHealthPrivateState(root,"/run/droide-toml/${root.name}",nodeExecutable,token)
        }
    }
}
