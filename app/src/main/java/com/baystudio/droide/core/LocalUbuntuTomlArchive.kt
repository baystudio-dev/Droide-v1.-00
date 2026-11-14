package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.*

/** Finite, authenticated, data-only npm TAR projection. No package-manager invocation,
 * imports, lifecycle scripts, native tools, .bin shims, symlinks or hardlinks. */
internal object LocalUbuntuTomlArchive {
    class Budget {
        var compressedBytes: Long = 0; private set
        var physicalBytes: Long = 0; private set
        var physicalFiles: Int = 0; private set
        private val identities = hashSetOf<String>()
        fun archive(identity: String, bytes: Long) {
            require(identities.add(identity) && bytes in 1..LocalUbuntuTomlPolicy.MAX_ARCHIVE_BYTES)
            compressedBytes = Math.addExact(compressedBytes,bytes)
            require(compressedBytes <= LocalUbuntuTomlPolicy.MAX_ARCHIVES_BYTES)
        }
        fun file(bytes: Long, copies: Int) {
            physicalBytes = Math.addExact(physicalBytes,Math.multiplyExact(bytes,copies.toLong()))
            physicalFiles = Math.addExact(physicalFiles,copies)
            require(physicalBytes <= LocalUbuntuTomlPolicy.MAX_TREE_BYTES && physicalFiles <= LocalUbuntuTomlPolicy.MAX_FILES)
        }
    }
    fun spec(identity: String): TrustedSha512ArtifactSpec {
        val a = LocalUbuntuTomlPolicy.artifacts.getValue(identity)
        val url = a.getValue("dist").jsonObject.getValue("tarball").jsonPrimitive.content
        LocalUbuntuNpmPolicy.requireRegistryTarball(url)
        return TrustedSha512ArtifactSpec("toml-"+LocalUbuntuTomlPolicy.sha256(identity.toByteArray()).take(40),url,
            a.getValue("sha512").jsonPrimitive.content,url.substringAfterLast('/'),LocalUbuntuTomlPolicy.MAX_ARCHIVE_BYTES)
    }
    fun extract(identity: String, archive: File, npmRoot: File, budget: Budget, active: () -> Unit = {}): JsonObject {
        val artifact = LocalUbuntuTomlPolicy.artifacts.getValue(identity)
        val targets = LocalUbuntuTomlPolicy.placements.filterValues { it == identity }.keys.map { File(npmRoot,it) }
        require(targets.isNotEmpty())
        val started = System.nanoTime()
        fun check() { active(); require(System.nanoTime() - started <= 120_000_000_000L) { "Taplo archive validation deadline" } }
        val attrs = Files.readAttributes(archive.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(attrs.isRegularFile && !attrs.isSymbolicLink)
        budget.archive(identity,attrs.size())
        val digest = MessageDigest.getInstance("SHA-512"); var measured = 0L
        Files.newInputStream(archive.toPath(),NOFOLLOW_LINKS).use { input ->
            val b = ByteArray(65536)
            while(true) { check(); val n = input.read(b); if(n<0)break; measured = Math.addExact(measured,n.toLong());require(measured<=attrs.size());digest.update(b,0,n) }
        }
        require(measured == attrs.size() && digest.digest().hex() == artifact.getValue("sha512").jsonPrimitive.content) { "Taplo original SHA-512 authentication failed" }
        val after = Files.readAttributes(archive.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(after.isRegularFile && after.fileKey()==attrs.fileKey() && after.size()==attrs.size() && after.lastModifiedTime()==attrs.lastModifiedTime())
        // The cache lease held by the caller and same-file checks protect the second read;
        // also authenticate the exact stream consumed by the decoder before accepting proof.
        targets.forEach { require(!Files.exists(it.toPath(),NOFOLLOW_LINKS)); require(it.mkdirs()) }
        val files = linkedMapOf<String,JsonElement>()
        val secondDigest = MessageDigest.getInstance("SHA-512")
        val counted = object : java.io.FilterInputStream(Files.newInputStream(archive.toPath(),NOFOLLOW_LINKS)) {
            var size = 0L
            override fun read(): Int { val n = super.read(); if(n>=0){size++;require(size<=attrs.size());secondDigest.update(n.toByte())};return n }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = `in`.read(b,off,len);if(n>0){size+=n;require(size<=attrs.size());secondDigest.update(b,off,n)};return n }
        }
        val directories = GZIPInputStream(counted,65536).use { input -> decodeData(input,targets,budget,files,::check) }
        require(counted.size==attrs.size() && secondDigest.digest().hex()==artifact.getValue("sha512").jsonPrimitive.content)
        val finalAttrs = Files.readAttributes(archive.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(finalAttrs.fileKey()==attrs.fileKey() && finalAttrs.size()==attrs.size() && finalAttrs.lastModifiedTime()==attrs.lastModifiedTime())
        require("package.json" in files)
        targets.forEach { LocalUbuntuTomlPolicy.requirePackage(identity,LocalUbuntuTomlPolicy.strictObject(LocalUbuntuTomlPolicy.read(File(it,"package.json")))) }
        return buildJsonObject { put("sha512",artifact.getValue("sha512"));put("files",JsonObject(files));put("directories",JsonArray(directories.sorted().map(::JsonPrimitive))) }
    }
    internal fun decodeData(input: InputStream, roots: List<File>, budget: Budget, files: MutableMap<String,JsonElement>, active: () -> Unit = {}): Set<String> {
        val header = ByteArray(512); val buffer = ByteArray(65536)
        var entries=0; var unpacked=0L; var pending: Map<String,String>?=null
        val names=hashSetOf<String>(); val directories=hashSetOf<String>()
        fun exact(b: ByteArray, n: Int) { var at=0; while(at<n){active();val got=input.read(b,at,n-at);require(got>0){"Truncated Taplo TAR"};at+=got} }
        fun number(at: Int,n: Int): Long { val s=String(header,at,n,Charsets.US_ASCII).trim(' ','\u0000');require(s.isNotEmpty()&&s.length<=12&&s.all{it in '0'..'7'});return s.toLong(8) }
        fun text(at: Int,n: Int): String { val end=(at until at+n).firstOrNull{header[it]==0.toByte()}?:at+n;return utf8(header.copyOfRange(at,end)) }
        fun padding(size: Long) { val n=((512-size%512)%512).toInt();if(n>0){exact(buffer,n);require((0 until n).all{buffer[it]==0.toByte()})} }
        while(true) {
            exact(header,512)
            if(header.all{it==0.toByte()}) {
                exact(header,512);require(header.all{it==0.toByte()} && pending==null)
                var tail=0;while(true){active();val n=input.read(buffer);if(n<0)break;tail+=n;require(tail<=10240 && (0 until n).all{buffer[it]==0.toByte()})};break
            }
            require(++entries<=LocalUbuntuTomlPolicy.MAX_ENTRIES)
            val checksum=number(148,8)
            val computed=header.indices.sumOf { if(it in 148..155)32 else header[it].toInt() and 255 }
            require(checksum==computed.toLong()) { "Taplo TAR checksum mismatch" }
            val magic=text(257,6);require(magic=="ustar" || magic.isEmpty())
            val mode=number(100,8);require(mode and 3584L==0L){"Taplo TAR special mode rejected"}
            val headerSize=number(124,12);require(headerSize in 0..LocalUbuntuTomlPolicy.MAX_ARCHIVE_UNPACKED)
            val kind=header[156].toInt().toChar()
            require(kind in setOf('\u0000','0','5','x')) { "Taplo TAR links, devices and unsupported extensions rejected" }
            if(kind=='x') {
                require(pending==null && headerSize in 1..65536)
                val bytes=ByteArray(headerSize.toInt());exact(bytes,bytes.size);pending=pax(bytes);padding(headerSize);continue
            }
            val fields=pending.orEmpty();pending=null
            val prefix=text(345,155)
            val name=fields["path"] ?: if(prefix.isEmpty())text(0,100) else "$prefix/${text(0,100)}"
            val size=fields["size"]?.also{require(it.matches(Regex("0|[1-9][0-9]{0,11}")))}?.toLong() ?: headerSize
            require(size in 0..LocalUbuntuTomlPolicy.MAX_ARCHIVE_UNPACKED)
            require(name.startsWith("package/") && name.toByteArray().size<=4096)
            val relative=name.removePrefix("package/").let{if(kind=='5')it.trimEnd('/') else it}
            if(relative.isEmpty()) { require(kind=='5'&&size==0L);continue }
            LocalUbuntuTomlPolicy.safeRelative(relative)
            require(relative.split('/').none { it=="node_modules" || it==".bin" }) { "Embedded package tree or executable shim rejected" }
            require(names.add(relative)) { "Duplicate Taplo TAR path" }
            var parent=relative.substringBeforeLast('/',"")
            while(parent.isNotEmpty()){require(parent !in files);directories+=parent;parent=parent.substringBeforeLast('/',"")}
            if(kind=='5') {
                require(size==0L && relative !in files);directories+=relative
                roots.forEach { val f=File(it,relative);require(f.mkdirs()||f.isDirectory) }
            } else {
                require(relative !in directories)
                unpacked=Math.addExact(unpacked,size);require(unpacked<=LocalUbuntuTomlPolicy.MAX_ARCHIVE_UNPACKED)
                budget.file(size,roots.size)
                val digest=MessageDigest.getInstance("SHA-256")
                val outputs=roots.map { root -> val f=File(root,relative);require(f.parentFile!!.mkdirs()||f.parentFile!!.isDirectory);require(f.createNewFile());FileOutputStream(f) }
                try {
                    var left=size
                    while(left>0){val n=minOf(left,buffer.size.toLong()).toInt();exact(buffer,n);digest.update(buffer,0,n);outputs.forEach{it.write(buffer,0,n)};left-=n}
                    outputs.forEach{it.fd.sync()}
                } finally { outputs.forEach{it.close()} }
                roots.forEach { android.system.Os.chmod(File(it,relative).absolutePath,(mode and 511L).toInt()) }
                files[relative]=buildJsonObject{put("size",size);put("mode",mode and 511L);put("sha256",digest.digest().hex())}
                padding(size)
            }
        }
        require(files.isNotEmpty())
        return directories
    }
    private fun pax(bytes: ByteArray): Map<String,String> {
        val out=linkedMapOf<String,String>();var at=0
        while(at<bytes.size) {
            val start=at;while(at<bytes.size&&bytes[at]!=32.toByte())at++
            require(at>start&&at-start<=6&&at<bytes.size)
            val n=String(bytes,start,at-start,Charsets.US_ASCII).also{require(it.all{c->c in '0'..'9'})}.toInt()
            require(n>at-start+3&&start+n<=bytes.size&&bytes[start+n-1]==10.toByte())
            val value=utf8(bytes.copyOfRange(at+1,start+n-1));val equal=value.indexOf('=');require(equal>0)
            val key=value.substring(0,equal);val v=value.substring(equal+1)
            require(key in setOf("path","size","mtime","atime","ctime","uid","gid","uname","gname") && out.put(key,v)==null)
            require(v.length<=4096 && v.none{it=='\u0000'||it=='\r'||it=='\n'})
            at=start+n
        };return out
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
}
