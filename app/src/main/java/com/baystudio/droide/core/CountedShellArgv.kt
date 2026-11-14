package com.baystudio.droide.core

import java.io.Reader
import java.security.MessageDigest

/** Pure prerequisite, NOT launch authority or an OS-success guarantee. No production caller yet. */
internal object CountedShellArgv {
    enum class Encoding { UTF8, JNI_MODIFIED_UTF8 }
    data class Limits(val pageSize: Long, val argMax: Long, val pointerBytes: Int)
    /** Must describe actual native envp bytes including '=' but excluding terminating NUL.
     * Reencoding ProcessBuilder.environment() does NOT attest inherited raw bytes. */
    sealed class Environment {
        object Unknown : Environment()
        class NativeLengths(lengths: List<Long>) : Environment() { val lengths = lengths.toList() }
    }
    data class Stats(val chars: Long, val utf8: Long, val modifiedUtf8: Long,
                     val quotes: Long, val safe: Boolean) {
        fun bytes(encoding: Encoding) = if (encoding == Encoding.UTF8) utf8 else modifiedUtf8
    }
    class Node private constructor(val stats: Stats, private val emit: ((Char) -> Unit) -> Unit) {
        internal fun write(sink: (Char) -> Unit) = emit(sink)
        companion object {
            fun literal(value: String): Node = source { value.reader() }
            /** Reader factory must reopen the same verified owned source; digest guards replacement.
             * Scan is O(n), bounded memory, including chunk-split surrogate validation. */
            fun source(checkCancelled: () -> Unit = {}, open: () -> Reader): Node {
                val first = scan(open, checkCancelled) {}
                return Node(first.first) { sink ->
                    val again = scan(open, checkCancelled, sink)
                    check(again.first == first.first && again.second.contentEquals(first.second)) { "Source changed" }
                }
            }
            fun concat(parts: List<Node>): Node {
                val frozen = parts.toList()
                var s = Stats(0, 0, 0, 0, true)
                for (p in frozen) s = Stats(add(s.chars,p.stats.chars), add(s.utf8,p.stats.utf8),
                    add(s.modifiedUtf8,p.stats.modifiedUtf8),add(s.quotes,p.stats.quotes),s.safe && p.stats.safe)
                return Node(s) { sink -> frozen.forEach { it.write(sink) } }
            }
            fun quote(node: Node, always: Boolean = false): Node {
                val s = node.stats
                if (!always && s.chars > 0 && s.safe) return node
                val extra = add(2, mul(3,s.quotes))
                return Node(Stats(add(s.chars,extra),add(s.utf8,extra),add(s.modifiedUtf8,extra),
                    add(2,mul(3,s.quotes)),false)) { sink ->
                    sink('\''); node.write { c -> if(c == '\'') "'\\''".forEach(sink) else sink(c) }; sink('\'')
                }
            }
        }
    }
    sealed class Admission {
        data class Unsupported(val reason: String): Admission()
        data class Rejected(val reason: String): Admission()
        class Admitted internal constructor(private val argv: List<Node>, private val encoding: Encoding,
                                            val countedBytes: Long) : Admission() {
            /** No intermediate quoted Strings; total allocation bounded by counted final argv.
             * Failure returns no argv; not permission, launch, retry or descendant admission. */
            fun render(): List<String> = argv.map { node ->
                require(node.stats.chars <= Int.MAX_VALUE) { "JVM string capacity" }
                val out = StringBuilder(node.stats.chars.toInt())
                node.write { c -> check(out.length.toLong() < node.stats.chars) { "Source expanded" }; out.append(c) }
                check(out.length.toLong() == node.stats.chars)
                val rendered = out.toString()
                val actual = scan({rendered.reader()}) {}.first
                check(actual.bytes(encoding) == node.stats.bytes(encoding))
                rendered
            }
        }
    }
    /** Linux-style injected necessary bounds. Limits/provenance supplied by trusted backend;
     * unknown transport/encoding/env must not opt into expansion. exec path extra bytes are
     * separate kernel stack use, not argv pointer count. Dynamic inner exec remains unknown. */
    fun admit(argv: List<Node>, environment: Environment, limits: Limits?, encoding: Encoding?,
              executablePathBytes: Long?): Admission {
        if (limits == null || encoding == null || executablePathBytes == null || environment !is Environment.NativeLengths)
            return Admission.Unsupported("Native byte provenance or limits unavailable")
        if(limits.pageSize <= 0 || limits.argMax <= 0 || limits.pointerBytes !in listOf(4,8) || executablePathBytes < 0)
            return Admission.Unsupported("Invalid runtime limits")
        return try {
            val single = mul(limits.pageSize,32)
            var strings = add(executablePathBytes,1)
            for (n in argv) {
                val bytes = add(n.stats.bytes(encoding),1)
                if(bytes > single) return Admission.Rejected("Argument exceeds per-string native bound")
                if(n.stats.chars > Int.MAX_VALUE) return Admission.Rejected("JVM string capacity")
                strings = add(strings,bytes)
            }
            for(length in environment.lengths) {
                if(length < 0) return Admission.Unsupported("Invalid environment provenance")
                val bytes = add(length,1)
                if(bytes > single) return Admission.Rejected("Environment exceeds per-string native bound")
                strings=add(strings,bytes)
            }
            val pointers=mul(add(maxOf(argv.size,1).toLong(),environment.lengths.size.toLong()),limits.pointerBytes.toLong())
            val total=add(strings,pointers)
            if(total > limits.argMax) Admission.Rejected("Aggregate native bound exceeded")
            else Admission.Admitted(argv.toList(),encoding,total)
        } catch (_: ArithmeticException) { Admission.Rejected("Native length arithmetic overflow") }
    }
    private fun add(a:Long,b:Long)=Math.addExact(a,b)
    private fun mul(a:Long,b:Long)=Math.multiplyExact(a,b)
    private fun scan(open:()->Reader, checkCancelled:()->Unit = {}, sink:(Char)->Unit):Pair<Stats,ByteArray> {
        var chars=0L; var utf8=0L; var modified=0L; var quotes=0L; var safe=true; var high=false
        val digest=MessageDigest.getInstance("SHA-256")
        open().use { reader ->
            val buffer=CharArray(4096)
            while(true) {
                checkCancelled()
                val n=reader.read(buffer); if(n<0) break
                check(n>0) { "Reader made no progress" }
                for(i in 0 until n) {
                    val c=buffer[i]; require(c!='\u0000') { "NUL in argument" }
                    digest.update((c.code ushr 8).toByte()); digest.update(c.code.toByte())
                    chars=add(chars,1); if(c=='\'') quotes=add(quotes,1)
                    safe=safe && (c.isLetterOrDigit() || c in "-_.,+/")
                    modified=add(modified,when {c.code<128->1L;c.code<2048->2L;else->3L})
                    if(high) { require(c.isLowSurrogate()) { "Unpaired surrogate" }; utf8=add(utf8,4); high=false }
                    else when { c.isHighSurrogate()->high=true; c.isLowSurrogate()->error("Unpaired surrogate")
                        else->utf8=add(utf8,when {c.code<128->1L;c.code<2048->2L;else->3L}) }
                    sink(c)
                }
            }
        }
        checkCancelled()
        require(!high) { "Unpaired surrogate" }
        return Stats(chars,utf8,modified,quotes,safe) to digest.digest()
    }
}
