package com.baystudio.droide.core

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Necessary argv-only bounds and explicit render reservation. */

internal object LocalShellAttemptPlan {
    // Shared budget for NEW expanded launches only. Legacy launches are unaffected. Covers final
    // builder/string/encoder copies and retained input conservatively, not all JVM heap.
    internal class MemoryBudget(private val maximumBytes: Long = 8L * 1024 * 1024) {
        private var held = 0L
        @Synchronized fun reserve(bytes: Long): Closeable {
            require(bytes > 0 && bytes <= maximumBytes - held) { "Expanded command memory budget is busy or insufficient" }
            held += bytes
            val released=AtomicBoolean(false)
            return Closeable { if(released.compareAndSet(false,true)) synchronized(this) {held-=bytes} }
        }
        @Synchronized fun heldBytes():Long=held
    }
    private val memory=MemoryBudget()
    class Prepared internal constructor(private val nodes:List<CountedShellArgv.Node>,
        private val reserved:Closeable, private val checkCancelled:()->Unit,
        val limits:CountedShellArgv.Limits, val knownArgvStackBytes:Long) : Closeable {
        private var closed=false
        private var rendered=false
        @Synchronized fun render():List<String> {
            check(!closed && !rendered) { "Expanded launch plan already consumed" }
            rendered=true
            return nodes.map { node ->
                checkCancelled()
                val builder=StringBuilder(node.stats.chars.toInt())
                var remaining=0
                node.write { c ->
                    if(remaining--<=0) {checkCancelled();remaining=4095}
                    check(c.code in 1..127 && builder.length.toLong()<node.stats.chars) { "Expanded source changed" }
                    builder.append(c)
                }
                check(builder.length.toLong()==node.stats.chars)
                builder.toString()
            }.also {checkCancelled()}
        }
        @Synchronized fun revalidate(current:CountedShellArgv.Limits?) {
            check(!closed) { "Expanded plan released" }
            checkCancelled()
            check(current!=null) { "Expanded command runtime limits unavailable" }
            validate(nodes,current)
        }
        @Synchronized override fun close() {if(!closed){closed=true;reserved.close()}}
    }
    /** ASCII-only expansion until selected ProcessBuilder Unicode encoder is verified. No changes to
     * old <=32k Unicode behavior. Source/output are otherwise exact. */
    fun prepare(nodes:List<CountedShellArgv.Node>, limits:CountedShellArgv.Limits,
                originalChars:Long, checkCancelled:()->Unit={}, budget:MemoryBudget=memory):Prepared {
        require(originalChars >= 0) { "Invalid source length" }
        checkCancelled()
        val frozen=nodes.toList()
        val known=validate(frozen,limits)
        var chars=0L
        frozen.forEach { chars=Math.addExact(chars,it.stats.chars) }

        val bytes=Math.addExact(65_536L,Math.addExact(Math.multiplyExact(originalChars,2),Math.multiplyExact(chars,12)))
        val ticket=budget.reserve(bytes)
        return try {checkCancelled();Prepared(frozen,ticket,checkCancelled,limits,known)} catch(t:Throwable){ticket.close();throw t}
    }
    private fun validate(nodes:List<CountedShellArgv.Node>, limits:CountedShellArgv.Limits):Long {
        require(limits.pageSize>0 && limits.argMax>0 && limits.pointerBytes in listOf(4,8)) { "Invalid native limits" }
        require(nodes.isNotEmpty()) { "Executable missing" }
        val single=Math.multiplyExact(limits.pageSize,32)
        var sum=0L
        nodes.forEach {n ->
            require(n.stats.chars==n.stats.utf8 && n.stats.chars==n.stats.modifiedUtf8) { "Expanded command encoding is not verified" }
            require(n.stats.chars<=Int.MAX_VALUE && Math.addExact(n.stats.utf8,1)<=single) { "Expanded command exceeds native per-string bound" }
            sum=Math.addExact(sum,Math.addExact(n.stats.utf8,1))
        }

        sum=Math.addExact(sum,Math.addExact(nodes.first().stats.utf8,1))
        sum=Math.addExact(sum,Math.multiplyExact(nodes.size.toLong(),limits.pointerBytes.toLong()))
        require(sum<=limits.argMax) { "Expanded command exceeds known argv native bound" }
        return sum
    }
    fun shellArgv(spec:TerminalLaunchSpec,shellText:String,injectedEnvironment:Map<String,String>,
                  checkCancelled:()->Unit={}):List<CountedShellArgv.Node> {
        require('\u0000' !in shellText) { "Shell command contains NUL" }
        ProcessSecurityPolicy.validateEnvironment(injectedEnvironment)
        val guest=if(spec.prefix.isEmpty()) emptyList() else injectedEnvironment.map {(k,v)->"$k=$v"}
        return (spec.prefix+guest+listOf(spec.shell,"-c")).map {CountedShellArgv.Node.literal(it)}+
            CountedShellArgv.Node.source(checkCancelled) {shellText.reader()}
    }
    fun joinTarget(argv:List<CountedShellArgv.Node>):CountedShellArgv.Node =
        CountedShellArgv.Node.concat(argv.flatMapIndexed {index,node->
            listOfNotNull(if(index==0) null else CountedShellArgv.Node.literal(" "),CountedShellArgv.Node.quote(node))
        })
}
