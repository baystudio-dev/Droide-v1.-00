package com.baystudio.droide.core

import org.junit.Test
import org.junit.Assert.*
import java.io.*
import java.util.zip.GZIPOutputStream
import java.nio.file.Files

class ElpArchiveTest {
    companion object {
        fun tar(name:String="elp",type:Char='0',body:ByteArray="authored ELF-shaped data only".toByteArray(),mode:String="0000755",declared:Long=body.size.toLong(),extra:ByteArray=byteArrayOf()):ByteArray {
            val h=ByteArray(512)
            fun field(at:Int,n:Int,v:String){require(v.length<n);v.toByteArray().copyInto(h,at)}
            field(0,100,name);field(100,8,mode);field(108,8,"0000000");field(116,8,"0000000")
            field(124,12,declared.toString(8).padStart(11,'0'));field(136,12,"00000000000")
            for(i in 148..155)h[i]=32;h[156]=type.code.toByte();"ustar\u000000".toByteArray().copyInto(h,257)
            field(329,8,"0000000");field(337,8,"0000000")
            val sum=h.sumOf{it.toInt()and 255};field(148,8,sum.toString(8).padStart(6,'0'));h[155]=32
            return h+body+ByteArray((512-body.size%512)%512)+extra+ByteArray(1024)
        }
        fun gzip(bytes:ByteArray):ByteArray=ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(bytes)}}.toByteArray()
        fun directory():File=System.getProperty("elp.test.root")?.let{Files.createTempDirectory(File(it).toPath(),"archive-")}?.toFile()?:Files.createTempDirectory("authored-elp-archive-").toFile()
        internal fun extract(bytes:ByteArray):LocalUbuntuElpArchive.Member {
            val base=directory();val f=File(base,"fixture.gz").apply{writeBytes(bytes)}
            return LocalUbuntuElpArchive.extractChecked(f,File(base,"tree"))
        }
        fun rejects(action:()->Unit){try{action();fail("unsafe fixture admitted")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    }
    @Test fun ordinarySingleMemberPreservesExactBytes(){val bytes="original authored test bytes".toByteArray();val m=extract(gzip(tar(body=bytes)));assertEquals(bytes.size.toLong(),m.size);assertEquals(LocalUbuntuElpPolicy.sha(bytes),m.sha256)}
    @Test fun adversarialNamesAndTypes(){for(name in listOf("../elp","./elp","/elp","elp/","other","a/elp"))rejects{extract(gzip(tar(name=name)))};for(type in listOf('1','2','3','4','5','6','7','x','g','L','K','S'))rejects{extract(gzip(tar(type=type)))}}
    @Test fun modesChecksumBoundsAndExtraPayload(){for(mode in listOf("0004755","0002755","0001755","0000644"))rejects{extract(gzip(tar(mode=mode)))};rejects{extract(gzip(tar(declared=268435457)))};val corrupt=tar();corrupt[4]=1;rejects{extract(gzip(corrupt))};rejects{extract(gzip(tar()+byteArrayOf(1)))};rejects{extract(gzip(tar(extra=tar())))};rejects{extract(gzip(tar()).dropLast(4).toByteArray())}}
    @Test fun gzipMustEndExactly(){val g=gzip(tar());rejects{extract(g+g)};rejects{extract(g+byteArrayOf(0))};val c=g.copyOf();c[c.size-8]=(c[c.size-8].toInt()xor 1).toByte();rejects{extract(c)}}
    @Test fun syntheticCannotBecomeOriginal(){val f=File(directory(),"fixture.gz").apply{writeBytes(gzip(tar()))};rejects{LocalUbuntuElpArchive.requireOriginal(f)}}
    private fun ordinaryGnu():ByteArray=tar().also{b->
        byteArrayOf(117,115,116,97,114,32,32,0).copyInto(b,257)
        for(i in 329..344)b[i]=0
        gnuChecksum(b)
    }
    private fun gnuChecksum(b:ByteArray){for(i in 148..155)b[i]=32;val n=(0..511).sumOf{b[it].toInt()and 255};(n.toString(8).padStart(6,'0')+"\u0000 ").toByteArray().copyInto(b,148)}
    @Test fun ordinaryGnuZeroDeviceFieldsRemainCompatible(){assertTrue(extract(gzip(ordinaryGnu())).size>0)}
    @Test fun gnuSparseAndExtensionFieldsStayRejected(){for(at in listOf(345,356,369,386,482,483,500,511)){val b=ordinaryGnu();b[at]=1;gnuChecksum(b);rejects{extract(gzip(b))}}}
    @Test fun gnuUnknownMagicAndNonzeroDeviceFieldsStayRejected(){for(at in listOf(257,263,329,337)){val b=ordinaryGnu();b[at]='1'.code.toByte();gnuChecksum(b);rejects{extract(gzip(b))}}}

}
