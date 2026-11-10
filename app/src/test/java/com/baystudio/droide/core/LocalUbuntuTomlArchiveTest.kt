package com.baystudio.droide.core
import java.io.*
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuTomlArchiveTest {
    private fun denied(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun member(path:String,content:String="",kind:Char='0'):ByteArray {val h=ByteArray(512);fun put(at:Int,n:Int,s:String){s.toByteArray().copyInto(h,at,0,minOf(n,s.length))};put(0,100,path);put(100,8,"0000644\u0000");put(108,8,"0000000\u0000");put(116,8,"0000000\u0000");put(124,12,content.toByteArray().size.toString(8).padStart(11,'0')+"\u0000");put(136,12,"00000000000\u0000");for(i in 148..155)h[i]=32;h[156]=kind.code.toByte();put(257,6,"ustar\u0000");val sum=h.sumOf{it.toInt()and 255};put(148,8,sum.toString(8).padStart(6,'0')+"\u0000 ");return h+content.toByteArray()+ByteArray((512-content.toByteArray().size%512)%512)}
    private fun root()=Files.createTempDirectory("toml-authored-tar-").toFile()
    @Test fun allPassiveOriginalMembersArePreserved(){val r=root();val files=linkedMapOf<String,JsonElement>();val tar=member("package/package.json","{}")+member("package/README.md","notice")+member("package/dist/index.js","authored data")+ByteArray(1024);val dirs=LocalUbuntuTomlArchive.decodeData(tar.inputStream(),listOf(r),LocalUbuntuTomlArchive.Budget(),files);assertEquals(setOf("package.json","README.md","dist/index.js"),files.keys);assertEquals(setOf("dist"),dirs);assertEquals("notice",File(r,"README.md").readText());assertEquals(420,files.getValue("README.md").jsonObject["mode"]!!.jsonPrimitive.int)}
    @Test fun traversalLinksNestedModulesDuplicatesAndTruncationFail(){for(tar in listOf(member("package/../bad"),member("package/link","",'2'),member("package/node_modules/x","bad"),member("package/a","1")+member("package/a","2"),member("package/a","a").dropLast(20).toByteArray()))denied{LocalUbuntuTomlArchive.decodeData((tar+ByteArray(1024)).inputStream(),listOf(root()),LocalUbuntuTomlArchive.Budget(),linkedMapOf())}}
    @Test fun publisherAuthenticationPrecedesTarDecodeAndTargetCreation(){val r=root();val archive=File(r,"authored-corrupt.tgz").apply{writeText("not a tar or authenticated original")};val npm=File(r,"npm");denied{LocalUbuntuTomlArchive.extract("@taplo/lsp@0.8.0",archive,npm,LocalUbuntuTomlArchive.Budget())};assertFalse(npm.exists())}
}
