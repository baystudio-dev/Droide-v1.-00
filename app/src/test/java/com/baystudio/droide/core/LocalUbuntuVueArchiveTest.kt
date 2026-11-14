package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored synthetic TAR data reaches the real finite decoder; authentication failure
 * reaches the real archive admission. No original package bytes are fetched or executed. */
class LocalUbuntuVueArchiveTest {
    private data class Entry(val name:String,val text:String="x",val kind:Char='0',val mode:Int=420)
    private fun tar(entries:List<Entry>):ByteArray {
        val out=ByteArrayOutputStream()
        entries.forEach { e ->
            val h=ByteArray(512)
            fun put(at:Int,n:Int,s:String){val b=s.toByteArray();require(b.size<=n);b.copyInto(h,at)}
            put(0,100,e.name);put(100,8,e.mode.toString(8).padStart(7,'0'));put(108,8,"0000000");put(116,8,"0000000")
            val data=e.text.toByteArray();put(124,12,data.size.toString(8).padStart(11,'0'));put(136,12,"00000000000")
            for(i in 148..155)h[i]=32;h[156]=e.kind.code.toByte();put(257,6,"ustar")
            val checksum=h.sumOf { it.toInt() and 255 };put(148,8,checksum.toString(8).padStart(6,'0')+"\u0000 ")
            out.write(h);out.write(data);repeat((512-data.size%512)%512){out.write(0)}
        };out.write(ByteArray(1024));return out.toByteArray()
    }
    private fun decode(bytes:ByteArray):Map<String,JsonElement> {
        val root=Files.createTempDirectory("vue-authored-tar-").toFile()
        try { val files=linkedMapOf<String,JsonElement>();LocalUbuntuVueArchive.decodeData(bytes.inputStream(),listOf(root),LocalUbuntuVueArchive.Budget(),files);return files }
        finally { root.deleteRecursively() }
    }
    @Test fun ordinaryOriginalDataIsPreserved() {
        val files=decode(tar(listOf(Entry("package/package.json","{}"),Entry("package/lib/main.js","// data only"))))
        assertEquals(setOf("package.json","lib/main.js"),files.keys)
        assertEquals(JsonPrimitive(12),files.getValue("lib/main.js").jsonObject["size"])
    }
    @Test fun rejectsEscapesAndNestedPackages() {
        listOf("evil/x","package/../x","package//x","package/x\\y","package/node_modules/extra/package.json","package/.bin/tool").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { decode(tar(listOf(Entry(name)))) }
        }
    }
    @Test fun rejectsAllLinksAndSpecialTypes() {
        listOf('1','2','3','4','6','g','L','K').forEach { type -> assertThrows(IllegalArgumentException::class.java) { decode(tar(listOf(Entry("package/x","",type)))) } }
    }
    @Test fun rejectsDuplicateSpecialModeAndTruncation() {
        assertThrows(IllegalArgumentException::class.java) { decode(tar(listOf(Entry("package/a"),Entry("package/a")))) }
        assertThrows(IllegalArgumentException::class.java) { decode(tar(listOf(Entry("package/a",mode=2541)))) }
        assertThrows(IllegalArgumentException::class.java) { decode(tar(listOf(Entry("package/a"))).copyOf(600)) }
        val bad=tar(listOf(Entry("package/a")));bad[30]=1
        assertThrows(IllegalArgumentException::class.java) { decode(bad) }
    }
    @Test fun wrongSha512RejectedBeforeAnyPackageCreation() {
        val root=Files.createTempDirectory("vue-authored-authentication-").toFile()
        try {
            val archive=java.io.File(root,"authored.tgz")
            java.util.zip.GZIPOutputStream(archive.outputStream()).use { it.write(tar(listOf(Entry("package/package.json","{}")))) }
            val npm=java.io.File(root,"npm")
            assertThrows(RuntimeException::class.java) { LocalUbuntuVueArchive.extract(LocalUbuntuVuePolicy.artifacts.keys.first(),archive,npm,LocalUbuntuVueArchive.Budget()) }
            assertFalse(npm.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun finiteAggregateBudgetsAndExactSpecs() {
        val budget=LocalUbuntuVueArchive.Budget()
        repeat(4){budget.archive("fixture-$it",LocalUbuntuVuePolicy.MAX_ARCHIVE_BYTES)}
        assertThrows(IllegalArgumentException::class.java) { budget.archive("fixture-5",1) }
        val files=LocalUbuntuVueArchive.Budget();repeat(LocalUbuntuVuePolicy.MAX_FILES){files.file(0,1)}
        assertThrows(IllegalArgumentException::class.java) { files.file(0,1) }
        LocalUbuntuVuePolicy.artifacts.forEach { (id,a) ->
            val spec=LocalUbuntuVueArchive.spec(id)
            assertEquals(a.getValue("sha512").jsonPrimitive.content,spec.sha512)
            assertTrue(spec.url.startsWith("https://registry.npmjs.org/"))
        }
    }
}
