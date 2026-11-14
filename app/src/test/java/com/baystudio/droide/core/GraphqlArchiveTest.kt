package com.baystudio.droide.core
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test
import android.app.Application
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Reuse only the verified owned-root chmod/lstat boundary. No descriptor reflection
// or additional JVM module openings are needed by this archive fixture.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [CMakeHostOs::class])
class GraphqlArchiveTest {
    private data class OwnedFixture(val path: Path, val fileKey: Any)
    private val temporaryRoots = mutableListOf<OwnedFixture>()

    private fun track(root: File): File {
        val path = root.toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink)
        temporaryRoots += OwnedFixture(path, requireNotNull(attributes.fileKey()))
        return root
    }

    private fun extractionRoot(prefix: String): File = track(CMakeHostOs.temporaryRoot(prefix))

    // Deliberately not registered with the Os shadow: archive operations get no
    // authority to chmod or inspect the outside sentinel through this fixture.
    private fun outsideRoot(prefix: String): File = track(Files.createTempDirectory(prefix).toFile())

    @After fun releaseOwnedFixtures() {
        var failure: Throwable? = null
        try {
            for (owned in temporaryRoots.asReversed()) {
                try {
                    val current = Files.readAttributes(owned.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    check(current.isDirectory && !current.isSymbolicLink && current.fileKey() == owned.fileKey) {
                        "Owned archive fixture root was replaced; refusing cleanup"
                    }
                    // Files.walk does not follow links without FOLLOW_LINKS. Sorting
                    // materializes paths before deletion and never traverses Node aliases.
                    Files.walk(owned.path).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                    }
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure.addSuppressed(problem)
                }
            }
        } finally {
            temporaryRoots.clear()
            try {
                CMakeHostOs.reset()
            } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    private fun gzip(bytes:ByteArray)=ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(bytes)}}.toByteArray()
    private fun decode(bytes:ByteArray,limit:Long=8192)=LocalUbuntuGraphqlArchive.SingleGzip(ByteArrayInputStream(bytes),limit).use{it.readBytes()}
    private fun denied(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    @Test fun singleGzipAuthoredBytesOnly() { val original="authored data fixture".toByteArray();assertArrayEquals(original,decode(gzip(original))) }
    @Test fun concatenatedMembersAndTrailingBytesReject() { val one=gzip("one".toByteArray());denied{decode(one+one)};denied{decode(one+byteArrayOf(0))};denied{decode(one.copyOf(one.size-1))} }
    @Test fun expandedBudgetAndCrcReject() { val bytes=gzip(ByteArray(8192){1});denied{decode(bytes,8191)};bytes[bytes.size-8]=(bytes[bytes.size-8].toInt() xor 1).toByte();denied{decode(bytes)} }
    @Test fun aggregateBudgetsArePhysical() { val budget=LocalUbuntuGraphqlArchive.Budget();repeat(4){budget.archive("authored-$it",4_194_304)};denied{budget.archive("excess",1)};denied{budget.archive("authored-0",1)};val physical=LocalUbuntuGraphqlArchive.Budget();repeat(2048){physical.file(0)};denied{physical.file(0)} }
    private data class Entry(val name:String,val bytes:ByteArray="authored".toByteArray(),val kind:Char='0',val mode:Int=420,val link:String="")
    private fun tar(vararg entries:Entry):ByteArray {
        val out=ByteArrayOutputStream()
        fun put(h:ByteArray,start:Int,size:Int,text:String){val bytes=text.toByteArray();require(bytes.size<=size);bytes.copyInto(h,start)}
        for(e in entries) {
            val h=ByteArray(512);put(h,0,100,e.name);put(h,100,8,e.mode.toString(8).padStart(7,'0'));put(h,108,8,"0000000");put(h,116,8,"0000000");put(h,124,12,e.bytes.size.toString(8).padStart(11,'0'));put(h,136,12,"00000000000");for(i in 148..155)h[i]=32
            h[156]=e.kind.code.toByte();put(h,157,100,e.link);put(h,257,6,"ustar");put(h,263,2,"00");val checksum=h.sumOf{it.toInt()and 255};put(h,148,8,checksum.toString(8).padStart(6,'0')+"\u0000 ")
            out.write(h);out.write(e.bytes);out.write(ByteArray((512-e.bytes.size%512)%512))
        };out.write(ByteArray(1024));return out.toByteArray()
    }
    private fun project(bytes:ByteArray):Pair<java.io.File,kotlinx.serialization.json.JsonObject> {
        val root=extractionRoot("authored-graphql-archive-");val files=linkedMapOf<String,kotlinx.serialization.json.JsonElement>()
        val result=LocalUbuntuGraphqlArchive.decodeData(ByteArrayInputStream(bytes),root,LocalUbuntuGraphqlArchive.Budget(),files)
        return root to result
    }
    @Test fun authoredTarProjectsEveryRegularFileAndDirectoryMode() {
        val (root,result)=project(tar(Entry("package/",byteArrayOf(),'5',448),Entry("package/data/",byteArrayOf(),'5',488),Entry("package/data/a.txt","fixture".toByteArray())))
        assertEquals("fixture",java.io.File(root,"data/a.txt").readText());assertEquals(448,android.system.Os.lstat(root.path).st_mode and 511)
        assertEquals(488,android.system.Os.lstat(java.io.File(root,"data").path).st_mode and 511)
        assertTrue(result.toString().contains("rootMode"))
    }
    @Test fun unsafeNamesAndEmbeddedDependencyTreesReject() {
        for(name in listOf("/package/a","package/../a","package/a\\b","package/a\nb","package/node_modules/other/a","package/.bin/a","package/a/../b","package/a//b","package/./a"))denied{project(tar(Entry(name)))}
    }
    @Test fun unsupportedArchiveTypesAndSpecialModesReject() {
        for(kind in listOf('1','2','3','4','6','7','g','L','S'))denied{project(tar(Entry("package/a",byteArrayOf(),kind)))}
        denied{project(tar(Entry("package/a",mode=2541)))}
    }
    @Test fun duplicateCaseAndAncestorCollisionsReject() {
        denied{project(tar(Entry("package/a"),Entry("package/a")))}
        denied{project(tar(Entry("package/A"),Entry("package/a")))}
        denied{project(tar(Entry("package/a"),Entry("package/a/b")))}
        denied{project(tar(Entry("package/A/b"),Entry("package/a/c")))}
    }
    @Test fun truncationBadChecksumAndNonzeroPaddingReject() {
        val good=tar(Entry("package/a"));denied{project(good.copyOf(600))}
        val header=good.copyOf();header[0]=(header[0].toInt()xor 1).toByte();denied{project(header)}
        val padding=good.copyOf();padding[520]=1;denied{project(padding)}
        denied{project(good+byteArrayOf(1))};denied{project(good+byteArrayOf(0))}
    }
    @Test fun preexistingSymlinkParentCannotRedirectWrites() {
        val root=extractionRoot("authored-graphql-parent-");val outside=outsideRoot("authored-graphql-outside-")
        java.nio.file.Files.createSymbolicLink(java.io.File(root,"data").toPath(),outside.toPath())
        denied{LocalUbuntuGraphqlArchive.decodeData(ByteArrayInputStream(tar(Entry("package/data/a"))),root,LocalUbuntuGraphqlArchive.Budget(),linkedMapOf())};assertFalse(java.io.File(outside,"a").exists())
    }
    private fun nodeData(vararg entries:Entry):java.io.File {
        val root=extractionRoot("authored-node-data-only-")
        LocalUbuntuGraphqlArchive.decodeNodeData(ByteArrayInputStream(tar(*entries)),root,linkedMapOf());return root
    }
    @Test fun nodeLinksAreDataOnlyAndStayInsideOriginalTree() {
        val top=LocalUbuntuGraphqlNodePolicy.TOP
        val root=nodeData(Entry("$top/lib/data.txt"),Entry("$top/bin/alias",byteArrayOf(),'2',511,"../lib/data.txt"))
        assertTrue(java.nio.file.Files.isSymbolicLink(java.io.File(root,"$top/bin/alias").toPath()))
        assertEquals("../lib/data.txt",java.nio.file.Files.readSymbolicLink(java.io.File(root,"$top/bin/alias").toPath()).toString())
        for(link in listOf("/etc/passwd","../../outside","missing","alias"))denied{nodeData(Entry("$top/lib/data.txt"),Entry("$top/bin/alias",byteArrayOf(),'2',511,link))}
        denied{nodeData(Entry("$top/lib/data.txt"),Entry("$top/bin/node",byteArrayOf(),'2',511,"../lib/data.txt"))}
        denied{nodeData(Entry("$top/lib/data.txt"),Entry("$top/bin/alias",byteArrayOf(),'2',420,"../lib/data.txt"))}
        denied{nodeData(Entry("$top/bin/alias",byteArrayOf(),'2',511,"../lib"),Entry("$top/bin/alias/data.txt"))}
    }

}
