package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCSharpArchiveTest {
    @Test fun canonicalManifestKeepsKindsModesBytesAndLinks() {
        val members=listOf(LocalUbuntuCSharpArchive.Member("tree","directory",493),LocalUbuntuCSharpArchive.Member("tree/data.dll","file",420,5,"1".repeat(64)),LocalUbuntuCSharpArchive.Member("tree/alias","link",511,target="data.dll"))
        assertEquals(members.sortedBy{it.path},LocalUbuntuCSharpArchive.decode(LocalUbuntuCSharpArchive.encode(members)))
        assertEquals("tree/data.dll",LocalUbuntuCSharpArchive.requireTarget("tree/alias","data.dll"))
        for(path in listOf("/etc/passwd","../escape","a/../b","a\\b","C:drive","a\u0000b","a//b"))reject{LocalUbuntuCSharpArchive.requirePath(path)}
        reject{LocalUbuntuCSharpArchive.requireTarget("tree/alias","../../escape")}
        reject{LocalUbuntuCSharpArchive.decode(LocalUbuntuCSharpArchive.encode(members+members[0]))}
    }
    @Test fun typedArchiveParserKeepsManagedDataAndRejectsTraversalModesAndTruncation() {
        val recipe=LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpHelperPolicy.FAMILY)
        fun extract(bytes:ByteArray):List<LocalUbuntuCSharpArchive.Member> {
            val root=Files.createTempDirectory("csharp-tar-fixture").toFile()
            try {
                val method=LocalUbuntuCSharpArchive::class.java.getDeclaredMethod("tar",CSharpInstallRecipe::class.java,InputStream::class.java,File::class.java,kotlin.jvm.functions.Function0::class.java)
                method.isAccessible=true
                @Suppress("UNCHECKED_CAST") return method.invoke(LocalUbuntuCSharpArchive,recipe,ByteArrayInputStream(bytes),root,{}) as List<LocalUbuntuCSharpArchive.Member>
            } finally {PathSecurity.deleteTreeNoFollow(root)}
        }
        val tar=tar("node-v22.23.3-linux-arm64/bin/node",493,"authored-data".toByteArray())
        assertEquals(493,extract(tar).single{it.kind=="file"}.mode)
        reject{extract(tar("../escape",420,byteArrayOf(1)))}
        reject{extract(tar("data",2541,byteArrayOf(1)))}
        reject{extract(tar.copyOf(520))}
        val long="node-v22.23.3-linux-arm64/"+"a".repeat(110)
        val ext=tar("././@LongLink",420,(long+"\u0000").toByteArray(),76)
        val ordinary=tar("truncated",420,byteArrayOf(1))
        assertTrue(extract(ext.dropLast(1024).toByteArray()+ordinary).any{it.path==long})
        reject{extract(tar("././@LongLink",420,("../escape\u0000").toByteArray(),76).dropLast(1024).toByteArray()+ordinary)}
        reject{extract(ext)}
        val target="node-v22.23.3-linux-arm64/data"
        val data=tar(target,420,byteArrayOf(1)).dropLast(1024).toByteArray()
        val link=tar("node-v22.23.3-linux-arm64/link",511,byteArrayOf(),50,"data")
        assertEquals(511,extract(data+link).single{it.kind=="link"}.mode)
        reject{extract(data+tar("node-v22.23.3-linux-arm64/link",2541,byteArrayOf(),50,"data"))}
    }
    @Test fun authenticatedTreeRejectsSetIdHardlinksExtrasAndChangedModes() {
        val root=Files.createTempDirectory("csharp-tree-fixture").toFile()
        try {
            val recipe=LocalUbuntuCSharpPolicy.recipe(LocalUbuntuCSharpHelperPolicy.FAMILY)
            val f=File(root,recipe.entryPoint);f.parentFile.mkdirs();f.writeText("authored, never executed")
            val members=mutableListOf<LocalUbuntuCSharpArchive.Member>();LocalUbuntuCSharpArchive.setMode(root,493)
            Files.walk(root.toPath()).use { paths->paths.filter{it!=root.toPath()}.forEach { p->
                val file=p.toFile();val rel=root.toPath().relativize(p).toString()
                if(file.isDirectory){LocalUbuntuCSharpArchive.setMode(file,493);members+=LocalUbuntuCSharpArchive.Member(rel,"directory",493)}
                else {LocalUbuntuCSharpArchive.setMode(file,493);members+=LocalUbuntuCSharpArchive.Member(rel,"file",493,file.length(),NimRuntimeClosure.measure(file,4096).second)}
            }}
            LocalUbuntuCSharpArchive.verifyTree(recipe,root,members)
            Files.setAttribute(f.toPath(),"unix:mode",2541);reject{LocalUbuntuCSharpArchive.verifyTree(recipe,root,members)}
            LocalUbuntuCSharpArchive.setMode(f,493);LocalUbuntuCSharpArchive.verifyTree(recipe,root,members)
            val link=File(root,"same");Files.createLink(link.toPath(),f.toPath());reject{LocalUbuntuCSharpArchive.mode(f)};link.delete()
            File(root,"extra").writeText("extra");reject{LocalUbuntuCSharpArchive.verifyTree(recipe,root,members)};File(root,"extra").delete()
            LocalUbuntuCSharpArchive.setMode(f,420);reject{LocalUbuntuCSharpArchive.verifyTree(recipe,root,members)}
        }finally{PathSecurity.deleteTreeNoFollow(root)}
    }
    private fun tar(path:String,mode:Int,bytes:ByteArray,kind:Int=48,target:String=""):ByteArray {
        val h=ByteArray(512)
        fun text(at:Int,s:String){s.toByteArray().copyInto(h,at)}
        fun oct(at:Int,n:Int,value:Long){text(at,value.toString(8).padStart(n-1,'0')+"\u0000")}
        text(0,path);oct(100,8,mode.toLong());oct(108,8,0);oct(116,8,0);oct(124,12,bytes.size.toLong());oct(136,12,0);text(148,"        ");h[156]=kind.toByte();text(157,target); text(257,"ustar\u0000");text(263,"00")
        val sum=h.sumOf{it.toInt()and 255};text(148,sum.toString(8).padStart(6,'0')+"\u0000 ")
        return h+bytes+ByteArray((512-bytes.size%512)%512)+ByteArray(1024)
    }
    private fun reject(block:()->Unit){assertTrue(runCatching(block).isFailure)}
}
