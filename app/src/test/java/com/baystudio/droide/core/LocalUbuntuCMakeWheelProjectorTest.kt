package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCMakeWheelProjectorTest {
    private data class Entry(val name:String,val bytes:ByteArray="authored fixture".toByteArray(),val mode:Int=0x81a4,val method:Int=0,val flags:Int=0)
    private fun archive(entries:List<Entry>):ByteArray {
        val local=ByteArrayOutputStream();val central=ByteArrayOutputStream()
        fun ByteArrayOutputStream.le(v:Long,n:Int){repeat(n){write(((v ushr (8*it)) and 255).toInt())}}
        for(e in entries){val name=e.name.toByteArray();val crc=CRC32().apply {update(e.bytes)}.value
            val packed=if(e.method==8){val d=Deflater(6,true);d.setInput(e.bytes);d.finish();val b=ByteArray(e.bytes.size+200);val n=d.deflate(b);d.end();b.copyOf(n)}else e.bytes
            val offset=local.size();local.le(0x04034b50,4);local.le(20,2);local.le(e.flags.toLong(),2);local.le(e.method.toLong(),2)
            local.le(0,4);local.le(crc,4);local.le(packed.size.toLong(),4);local.le(e.bytes.size.toLong(),4);local.le(name.size.toLong(),2);local.le(0,2);local.write(name);local.write(packed)
            central.le(0x02014b50,4);central.le(0x0314,2);central.le(20,2);central.le(e.flags.toLong(),2);central.le(e.method.toLong(),2);central.le(0,4)
            central.le(crc,4);central.le(packed.size.toLong(),4);central.le(e.bytes.size.toLong(),4);central.le(name.size.toLong(),2);central.le(0,2);central.le(0,2);central.le(0,2);central.le(0,2)
            central.le(e.mode.toLong() shl 16,4);central.le(offset.toLong(),4);central.write(name)
        }
        val offset=local.size();local.write(central.toByteArray());local.le(0x06054b50,4);local.le(0,4);local.le(entries.size.toLong(),2);local.le(entries.size.toLong(),2)
        local.le(central.size().toLong(),4);local.le(offset.toLong(),4);local.le(0,2);return local.toByteArray()
    }
    private fun fixture():Pair<LocalUbuntuCMakeWheel,Map<String,ByteArray>> {
        val original=LocalUbuntuCMakeCatalog.recipe.wheels.first();val d=original.distInfo
        val metadata="Metadata-Version: 2.1\nName: ${original.name}\nVersion: ${original.version}\nRequires-Python: ${original.requiresPython}\nRequires-Dist: pygls>=1.1.1\n\nAuthored fixture metadata.\n".toByteArray()
        val wheel=original.copy(metadataSha256=LocalUbuntuCMakePolicy.hash(metadata))
        val files=linkedMapOf("cmake_language_server/server.py" to "# Authored source; never executed\ndef main(): pass\n".toByteArray(),
            "$d/METADATA" to metadata,"$d/WHEEL" to "Wheel-Version: 1.0\nGenerator: authored-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n".toByteArray(),
            "$d/LICENSE" to "Authored license fixture".toByteArray(),"$d/entry_points.txt" to "[console_scripts]\ncmake-language-server = cmake_language_server.server:main\n".toByteArray())
        return wheel to recorded(files,d)
    }
    private fun recorded(input:Map<String,ByteArray>,dist:String):Map<String,ByteArray> {
        val files=input.filterKeys {it!="$dist/RECORD"}.toMutableMap()
        fun quote(s:String)=if(',' in s || '"' in s) "\""+s.replace("\"","\"\"")+"\"" else s
        val record=files.entries.joinToString("\n"){(name,b)->quote(name)+",sha256="+Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(b))+","+b.size}+"\n$dist/RECORD,,\n"
        files["$dist/RECORD"]=record.toByteArray();return files
    }
    private fun members(files:Map<String,ByteArray>)=LocalUbuntuCMakeWheelProjector.parse(archive(files.map {(n,b)->Entry(n,b)}))
    @Test fun admitsStoredAndDeflatedAuthoredDataWithoutExecutingIt() {
        for(method in listOf(0,8)) {
            val bytes="# Authored harmless data\n".toByteArray()
            val parsed=LocalUbuntuCMakeWheelProjector.parse(archive(listOf(Entry("package/data.py",bytes,method=method))))
            assertArrayEquals(bytes,parsed.single().bytes)
        }
        val(wheel,files)=fixture();assertEquals(files.keys,LocalUbuntuCMakeWheelProjector.validate(wheel,members(files)).keys)
    }
    @Test fun rejectsUnsupportedArchiveMembers() {
        val cases=listOf(
            listOf(Entry("../escape")),listOf(Entry("/absolute")),listOf(Entry("a/./b")),listOf(Entry("a//b")),listOf(Entry("a\\b")),
            listOf(Entry("a\nb")),listOf(Entry("a:b")),listOf(Entry("a.data/purelib/x.py")),listOf(Entry("p/__pycache__/x.pyc")),
            listOf(Entry("p/hook.pth")),listOf(Entry("p/a.egg-link")),listOf(Entry("p/native.so")),listOf(Entry("p/data",byteArrayOf(127,69,76,70))),
            listOf(Entry("p/data",byteArrayOf(77,90))),listOf(Entry("p/link",mode=0xa1ff)),listOf(Entry("p/fifo",mode=0x11a4)),
            listOf(Entry("p/executable",mode=0x81ed)),listOf(Entry("p/setid",mode=0x89a4)),listOf(Entry("p/x",flags=1)),listOf(Entry("p/x",flags=8)),
            listOf(Entry("p/x",method=12)),listOf(Entry("p/x"),Entry("p/x")),listOf(Entry("p/X"),Entry("p/x")),
            listOf(Entry("P/one"),Entry("p/two")),listOf(Entry("p"),Entry("p/child")),listOf(Entry("p/child"),Entry("p")),
            listOf(Entry("p/",ByteArray(0),mode=0x41ed),Entry("p",ByteArray(0))),listOf(Entry("p/",byteArrayOf(1),mode=0x41ed)),
            listOf(Entry("a/".repeat(25)+"x")),listOf(Entry("a".repeat(513)))
        )
        cases.forEachIndexed {i,case->assertTrue("malformed archive case $i",runCatching {LocalUbuntuCMakeWheelProjector.parse(archive(case))}.isFailure)}
    }
    @Test fun rejectsAmbiguousEnvelopeAndLocalCentralDrift() {
        val good=archive(listOf(Entry("p/x")))
        val variants=listOf(good+byteArrayOf(1),byteArrayOf(1)+good,good.copyOf(good.size-1),
            good.copyOf().apply {this[14]=(this[14].toInt() xor 1).toByte()},
            good.copyOf().apply {this[30]='q'.code.toByte()},
            good.copyOf().apply {this[size-22+4]=1},
            good.copyOf().apply {this[size-22+20]=1},
            good.copyOf().apply {this[18]=0xff.toByte();this[19]=0xff.toByte();this[20]=0xff.toByte();this[21]=0xff.toByte()})
        variants.forEachIndexed {i,b->assertTrue("ZIP envelope case $i",runCatching {LocalUbuntuCMakeWheelProjector.parse(b)}.isFailure)}
    }
    @Test fun rejectsMissingChangedAndUnreviewedWheelMetadata() {
        val(w,files)=fixture();val d=w.distInfo
        val variants=listOf(
            files-"$d/LICENSE",files-"$d/METADATA",files-"$d/WHEEL",files-"$d/RECORD",files-"$d/entry_points.txt",
            files+("$d/METADATA" to "Name: evil\n".toByteArray()),
            files+("$d/WHEEL" to "Wheel-Version: 1.0\nRoot-Is-Purelib: false\nTag: py3-none-any\n".toByteArray()),
            files+("$d/WHEEL" to "Wheel-Version: 1.1\nRoot-Is-Purelib: true\nTag: py3-none-any\n".toByteArray()),
            files+("$d/WHEEL" to "Wheel-Version: 1.0\nRoot-Is-Purelib: true\nTag: cp312-linux_aarch64\n".toByteArray()),
            files+("$d/entry_points.txt" to "[console_scripts]\ncmake-language-server = evil:main\n".toByteArray()),
            files+("$d/entry_points.txt" to "[console_scripts]\ncmake-language-server=cmake_language_server.server:main\nevil=evil:main\n".toByteArray()),
            files+("unreviewed/__init__.py" to "# Authored unknown package".toByteArray()))
        variants.forEachIndexed {i,f->assertTrue("wheel metadata case $i",runCatching {LocalUbuntuCMakeWheelProjector.validate(w,members(f))}.isFailure)}
    }
    @Test fun rejectsRecordForgeryAndUnhashedRows() {
        val(w,files)=fixture();val d=w.distInfo;val record=String(files.getValue("$d/RECORD"))
        val rows=record.lines().filter(String::isNotEmpty)
        val variants=listOf(record.replace("sha256=","sha512="),record.replace("sha256=",""),rows.drop(1).joinToString("\n"),record+rows.first()+"\n",
            record.replace("server.py,sha256=","evil.py,sha256="),record.replace("$d/RECORD,,","$d/RECORD,sha256=bad,10"),
            record.replaceFirst(",${files.getValue("cmake_language_server/server.py").size}",",0"),"\"unterminated,hash,1")
        variants.forEachIndexed {i,r->assertTrue("RECORD case $i",runCatching {LocalUbuntuCMakeWheelProjector.validate(w,members(files+("$d/RECORD" to r.toByteArray())))}.isFailure)}
    }
    @Test fun preservesQuotedRecordAndEmptyOriginalData() {
        val(w,files)=fixture();val f=recorded(files+mapOf("cmake_language_server/comma,data.txt" to ByteArray(0)),w.distInfo)
        assertEquals(0,LocalUbuntuCMakeWheelProjector.validate(w,members(f)).getValue("cmake_language_server/comma,data.txt").size)
        assertEquals(listOf(listOf("a,b","hash","1")),LocalUbuntuCMakeWheelProjector.csv("\"a,b\",hash,1\r\n"))
        listOf("a,b", "a,b,c,d", "a,b,\"c\"tail", "a,b,\"x\ny\"").forEach {assertTrue(runCatching {LocalUbuntuCMakeWheelProjector.csv(it)}.isFailure)}
    }
}
