package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

/** ELP-only bounded single gzip and exact single regular root member. No guest extractor. */
internal object LocalUbuntuElpArchive {
    data class Member(val size: Long, val sha256: String)
    fun extract(recipe: LocalUbuntuVendorRecipe, archive: File, tree: File, active: () -> Unit = {}): Member {
        recipe.validate(); LocalUbuntuElpPolicy.requireReviewed(recipe)
        requireOriginal(archive,active)
        val result = extractChecked(archive,tree,active)
        requireOriginal(archive,active)
        LocalUbuntuElpPolicy.requireNative(File(tree,"elp"),active)
        return result
    }
    fun requireOriginal(archive: File, active: () -> Unit = {}) {
        require(LocalUbuntuElpPolicy.measure(archive,LocalUbuntuElpPolicy.ARTIFACT_BYTES,active) ==
            (LocalUbuntuElpPolicy.ARTIFACT_BYTES to LocalUbuntuElpPolicy.SHA256)) { "ELP original archive identity changed" }
    }
    /** Byte-only reproof: bind the expanded member to this exact compressed stream, with no extraction or execution. */
    fun proveOriginalMember(archive:File,active:()->Unit={}):Member {
        requireOriginal(archive,active)
        val digest=MessageDigest.getInstance("SHA-256");var compressed=0L
        val result=Files.newInputStream(archive.toPath(),NOFOLLOW_LINKS).use { source ->
            val checked=object:java.io.FilterInputStream(source) {
                override fun read():Int {active();val n=source.read();if(n>=0){require(++compressed<=LocalUbuntuElpPolicy.ARTIFACT_BYTES);digest.update(n.toByte())};return n}
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {active();val n=source.read(bytes,offset,length);if(n>0){compressed=Math.addExact(compressed,n.toLong());require(compressed<=LocalUbuntuElpPolicy.ARTIFACT_BYTES);digest.update(bytes,offset,n)};return n}
            }
            LocalUbuntuGraphqlArchive.SingleGzip(checked,LocalUbuntuElpPolicy.MAX_MEMBER_BYTES+65_536L,active).use { input -> decode(input,null,active) }
        }
        require(compressed==LocalUbuntuElpPolicy.ARTIFACT_BYTES&&digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}==LocalUbuntuElpPolicy.SHA256)
        requireOriginal(archive,active)
        return result
    }
    /** Synthetic structural seam only. Production always enters through extract and exact original proof. */
    internal fun extractChecked(archive: File, tree: File, active: () -> Unit = {}): Member {
        require(Files.isRegularFile(archive.toPath(),NOFOLLOW_LINKS) && archive.length() in 1..LocalUbuntuElpPolicy.ARTIFACT_BYTES)
        require(!Files.exists(tree.toPath(),NOFOLLOW_LINKS) && tree.parentFile!!.canonicalFile == tree.parentFile!!.absoluteFile)
        require(tree.mkdir())
        archive.inputStream().use { raw ->
            LocalUbuntuGraphqlArchive.SingleGzip(raw,LocalUbuntuElpPolicy.MAX_MEMBER_BYTES+65_536L,active).use { input ->
                return decode(input,File(tree,"elp"),active)
            }
        }
    }
    internal fun decode(input: InputStream, output: File?, active: () -> Unit = {}): Member {
        val header=ByteArray(512)
        fun exact(bytes:ByteArray,n:Int=bytes.size) { var at=0;while(at<n){active();val count=input.read(bytes,at,n-at);require(count>0){"Truncated ELP tar"};at+=count} }
        fun text(at:Int,size:Int):String {
            val b=header.copyOfRange(at,at+size);val zero=b.indexOf(0)
            val end=if(zero<0)b.size else zero
            require(b.drop(end).all{it==0.toByte()} && b.take(end).all{it.toInt() in 32..126})
            return String(b,0,end,Charsets.US_ASCII)
        }
        fun octal(at:Int,size:Int):Long { val s=String(header,at,size,Charsets.US_ASCII).trim(' ','\u0000');require(s.isNotEmpty()&&s.all{it in '0'..'7'});return s.toLong(8) }
        exact(header)
        val expected=octal(148,8)
        val sum=header.indices.sumOf{if(it in 148..155)32 else header[it].toInt() and 255}.toLong()
        require(sum==expected){"ELP tar checksum mismatch"}
        require(text(0,100)=="elp" && text(345,155).isEmpty() && text(157,100).isEmpty()) { "ELP tar requires exact root elp and no link target" }
        require(header[156].toInt() in setOf(0,48)) { "ELP tar rejects links, sparse, metadata and special entries" }
        val posix=header.copyOfRange(257,263).contentEquals(byteArrayOf(117,115,116,97,114,0)) &&
            String(header,263,2,Charsets.US_ASCII)=="00"
        val gnu=header.copyOfRange(257,265).contentEquals(byteArrayOf(117,115,116,97,114,32,32,0))
        require(posix||gnu) { "ELP tar requires ordinary POSIX ustar or GNU header" }
        if(gnu) require(header.copyOfRange(345,512).all{it==0.toByte()}) { "ELP GNU extensions and sparse metadata are rejected" }
        val mode=octal(100,8);require(mode and 0xE00L==0L && mode and 0x49L!=0L) { "ELP member modes are unsafe or nonexecutable" }
        fun device(at:Int):Long=if(gnu&&header.copyOfRange(at,at+8).all{it==0.toByte()})0L else octal(at,8)
        require(device(329)==0L&&device(337)==0L) { "ELP special device metadata rejected" }
        val size=octal(124,12);require(size in 1..LocalUbuntuElpPolicy.MAX_MEMBER_BYTES)
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var remaining=size
        output?.let{Files.newOutputStream(it.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS)}.use { target ->
            while(remaining>0){val n=minOf(buffer.size.toLong(),remaining).toInt();exact(buffer,n);target?.write(buffer,0,n);digest.update(buffer,0,n);remaining-=n}
        }
        val padding=((512-size%512)%512).toInt()
        if(padding>0){exact(buffer,padding);require(buffer.take(padding).all{it==0.toByte()})}
        // Exactly two terminator blocks, then only bounded tar record padding. No second member.
        repeat(2){exact(header);require(header.all{it==0.toByte()}) { "ELP tar has an extra logical member" }}
        var trailing=0
        while(true){active();val n=input.read(buffer);if(n<0)break;require(n>0);trailing+=n;require(trailing<=65536&&buffer.take(n).all{it==0.toByte()}) { "ELP tar has trailing payload" }}
        require(trailing%512==0)
        if(output!=null)require(output.setReadable(true,false)&&output.setWritable(true,true)&&output.setExecutable(true,false))
        return Member(size,digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
    }
}
