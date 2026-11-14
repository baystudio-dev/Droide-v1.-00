package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Exact bounded ZIP32/RECORD projection. No archive tool, import, installer or resolver is executed. */
internal object LocalUbuntuCMakeWheelProjector {
    const val MAX_ENTRIES = 8192
    const val MAX_BYTES = 67_108_864L
    private const val MAX_MEMBER = 16_777_216
    data class Member(val path: String, val bytes: ByteArray, val directory: Boolean)
    data class Projection(val files: Map<String, String>, val totalBytes: Long)
    private fun u16(b: ByteArray, p: Int): Int { require(p >= 0 && p + 2 <= b.size); return (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) }
    private fun u32(b: ByteArray, p: Int): Long = u16(b,p).toLong() or (u16(b,p+2).toLong() shl 16)
    private fun slice(b: ByteArray, p: Int, n: Int): ByteArray { require(p >= 0 && n >= 0 && p.toLong()+n <= b.size); return b.copyOfRange(p,p+n) }
    private fun extra(b: ByteArray) { var p=0; while(p < b.size) { val id=u16(b,p); val n=u16(b,p+2); require(id != 1 && id != 0x7075) { "ZIP64 or alternate path unsupported" }; p += 4; slice(b,p,n); p += n }; require(p == b.size) }
    private fun path(value: String, directory: Boolean) {
        require(value.toByteArray(Charsets.UTF_8).size in 1..512 && value.none { it.code < 32 || it.code == 127 || it == '\\' || it == ':' })
        require(!value.startsWith('/') && value.endsWith('/') == directory)
        val parts=value.removeSuffix("/").split('/')
        require(parts.size in 1..24 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.all { c -> c.code in 32..126 } })
        require(parts.none { it.endsWith(".data", true) || it == "__pycache__" })
        val lower=value.lowercase(Locale.ROOT)
        require(listOf(".pyc", ".pyo", ".pth", ".egg-link", ".so", ".dll", ".dylib", ".exe", ".a", ".o", ".wasm").none { lower.endsWith(it) })
    }
    /** Parser separated from publisher admission so authored malformed fixtures exercise real code. */
    internal fun parse(bytes: ByteArray): List<Member> {
        require(bytes.size in 22..254_702)
        // No comment, trailing envelope, split archive, SFX prefix or ZIP64 locator is admitted.
        val e=bytes.size-22
        require(u32(bytes,e)==0x06054b50L && u16(bytes,e+20)==0 && u16(bytes,e+4)==0 && u16(bytes,e+6)==0)
        val count=u16(bytes,e+10); require(count in 1..4096 && u16(bytes,e+8)==count)
        val central=u32(bytes,e+16); val centralBytes=u32(bytes,e+12)
        require(central < e && central+centralBytes==e.toLong())
        var p=central.toInt(); var localEnd=0; var total=0L
        val caseNames=mutableMapOf<String,String>()
        fun exactCase(value:String) { var prefix="";for(part in value.split('/')) { prefix=if(prefix.isEmpty()) part else "$prefix/$part";val key=prefix.lowercase(Locale.ROOT);require(caseNames[key]==null || caseNames[key]==prefix) { "Casefold path-prefix collision" };caseNames[key]=prefix } }
        val out=mutableListOf<Member>(); val names=mutableSetOf<String>(); val folded=mutableSetOf<String>(); val dirs=mutableSetOf<String>(); val files=mutableSetOf<String>()
        repeat(count) {
            require(u32(bytes,p)==0x02014b50L)
            val made=u16(bytes,p+4); val needed=u16(bytes,p+6); val flags=u16(bytes,p+8); val method=u16(bytes,p+10)
            require(needed <= 20 && flags and 0xF7FF == 0 && method in setOf(0,8)) { "Unsupported ZIP features" }
            val crc=u32(bytes,p+16); val packed=u32(bytes,p+20); val size=u32(bytes,p+24)
            val nameBytes=u16(bytes,p+28); val extraBytes=u16(bytes,p+30); val comment=u16(bytes,p+32)
            require(nameBytes in 1..512 && comment==0 && u16(bytes,p+34)==0 && packed <= bytes.size && size <= MAX_MEMBER)
            val external=u32(bytes,p+38); val offset=u32(bytes,p+42)
            val rawName=slice(bytes,p+46,nameBytes); val name=LocalUbuntuCMakePolicy.utf8(rawName); val directory=name.endsWith('/')
            path(name,directory); extra(slice(bytes,p+46+nameBytes,extraBytes))
            val mode=(external ushr 16).toInt(); val fileType=mode and 0xf000
            require((made ushr 8) in setOf(0,3) && fileType in (if(directory) setOf(0,0x4000) else setOf(0,0x8000)))
            require(mode and (if(directory) 0xE00 else 0xE49) == 0) { "Executable, set-id or sticky wheel member" }
            require((external and 0x10 != 0L)==directory || made ushr 8 == 3)
            require(!directory || size==0L)
            val clean=name.removeSuffix("/");exactCase(clean)
            require(names.add(clean) && folded.add(clean.lowercase(Locale.ROOT))) { "Duplicate or case-colliding wheel path" }
            var prefix=""
            clean.split('/').dropLast(1).forEach { segment -> prefix=if(prefix.isEmpty()) segment else "$prefix/$segment"; require(prefix !in files); dirs.add(prefix) }
            if(directory) { require(clean !in files); dirs.add(clean) } else { require(clean !in dirs); files.add(clean) }
            require(offset == localEnd.toLong()) { "ZIP local overlap, gap or unindexed member" }
            val l=offset.toInt(); require(u32(bytes,l)==0x04034b50L && u16(bytes,l+4)==needed && u16(bytes,l+6)==flags && u16(bytes,l+8)==method)
            require(u16(bytes,l+10)==u16(bytes,p+12) && u16(bytes,l+12)==u16(bytes,p+14))
            require(u32(bytes,l+14)==crc && u32(bytes,l+18)==packed && u32(bytes,l+22)==size)
            val ln=u16(bytes,l+26); val le=u16(bytes,l+28)
            require(ln==nameBytes && slice(bytes,l+30,ln).contentEquals(rawName)); extra(slice(bytes,l+30+ln,le))
            val start=l+30+ln+le; require(start.toLong()+packed <= central)
            val compressed=slice(bytes,start,packed.toInt()); val data=if(method==0) { require(packed==size); compressed } else {
                val inflater=Inflater(true)
                try {
                    inflater.setInput(compressed); val output=ByteArray(size.toInt()); var done=0
                    while(!inflater.finished()) { val remaining=output.size-done
                        if(remaining==0) { val scratch=ByteArray(1); require(inflater.inflate(scratch)==0 && inflater.finished()); break }
                        val n=inflater.inflate(output,done,remaining); require(n>0); done+=n
                    }
                    require(done==output.size && inflater.finished() && inflater.remaining==0); output
                } finally { inflater.end() }
            }
            require(CRC32().apply { update(data) }.value==crc)
            // Reject executable/native magic even under a harmless extension. Python source shebangs are data.
            require(!(data.size>=4 && listOf("7f454c46","0061736d","feedface","feedfacf","cefaedfe","cffaedfe","cafebabe").contains(data.take(4).joinToString("") { "%02x".format(it.toInt() and 255) })))
            require(!(data.size>=2 && data[0]==77.toByte() && data[1]==90.toByte()))
            total=Math.addExact(total,size); require(total<=MAX_BYTES)
            out += Member(name,data,directory); localEnd=start+packed.toInt(); p+=46+nameBytes+extraBytes+comment
        }
        require(p==e && localEnd==central.toInt())
        return out
    }
    internal fun csv(text: String): List<List<String>> {
        require(text.toByteArray().size <= 2_097_152 && '\u0000' !in text)
        val rows=mutableListOf<List<String>>(); val row=mutableListOf<String>(); val cell=StringBuilder(); var i=0; var quote=false; var closed=false
        fun field(){row+=cell.toString();cell.setLength(0);closed=false}
        fun line(){field();require(row.size==3);rows+=row.toList();row.clear()}
        while(i<text.length) { val c=text[i++]
            if(quote) { if(c=='"') { if(i<text.length&&text[i]=='"'){cell.append('"');i++}else{quote=false;closed=true} } else { require(c!='\n'&&c!='\r');cell.append(c) } }
            else when(c) {
                '"'->{require(cell.isEmpty()&&!closed);quote=true}
                ','->field()
                '\n'->line()
                '\r'->{require(i<text.length&&text[i++]=='\n');line()}
                else->{require(!closed);cell.append(c)}
            }
            require(rows.size<=4096 && cell.length<=1024)
        }
        require(!quote); if(row.isNotEmpty()||cell.isNotEmpty()||closed) line(); return rows
    }
    private fun normalizedRequirement(value: String) = value.filterNot { it.isWhitespace() || it == '(' || it == ')' }
    internal fun validate(wheel: LocalUbuntuCMakeWheel, members: List<Member>): Map<String, ByteArray> {
        val files=members.filterNot { it.directory }.associate { it.path to it.bytes }
        val root=wheel.distInfo; val allowed=when(wheel.name) {
            "cmake-language-server"->setOf("cmake_language_server",root)
            "attrs"->setOf("attr","attrs",root)
            else->setOf(wheel.name,root)
        }
        require(members.all { it.path.removeSuffix("/").substringBefore('/') in allowed }) { "Wheel contains an unreviewed top-level package" }
        val metadata=requireNotNull(files["$root/METADATA"]); require(metadata.size<=262144 && LocalUbuntuCMakePolicy.hash(metadata)==wheel.metadataSha256)
        val headers=LocalUbuntuCMakePolicy.utf8(metadata).replace("\r\n","\n").substringBefore("\n\n").lines()
        fun header(key:String)=headers.filter { it.startsWith("$key: ") }.map { it.removePrefix("$key: ") }
        require(header("Name")==listOf(wheel.name) && header("Version")==listOf(wheel.version) && header("Requires-Python")==listOf(wheel.requiresPython) && header("Requires-Dist").map(::normalizedRequirement)==wheel.requiresDist.map(::normalizedRequirement))
        val wheelText=LocalUbuntuCMakePolicy.utf8(requireNotNull(files["$root/WHEEL"]))
        require(wheelText.length<=16384)
        val wh=wheelText.replace("\r\n","\n").lines().filter(String::isNotEmpty)
        require(wh.count { it=="Wheel-Version: 1.0" }==1 && wh.count { it=="Root-Is-Purelib: true" }==1 && wh.count { it=="Tag: py3-none-any" }==1)
        require(wh.all { it in setOf("Wheel-Version: 1.0","Root-Is-Purelib: true","Tag: py3-none-any") || it.startsWith("Generator: ") })
        val entry=files["$root/entry_points.txt"]
        if(wheel.name=="cmake-language-server") {
            require(entry!=null && entry.size<=16384)
            val lines=LocalUbuntuCMakePolicy.utf8(entry).replace("\r\n","\n").lines().map(String::trim).filter(String::isNotEmpty)
            require(lines.size==2 && lines[0]=="[console_scripts]" && lines[1].split('=').map(String::trim)==listOf("cmake-language-server","cmake_language_server.server:main"))
            require(files.containsKey("cmake_language_server/server.py"))
        } else require(entry==null) { "Dependency entry points are not reviewed" }
        require(files.filterKeys { it.startsWith("$root/") && it.substringAfterLast('/').uppercase(Locale.ROOT).let { n -> n.startsWith("LICENSE") || n.startsWith("COPYING") } }.values.any { it.isNotEmpty() && it.size<=262144 }) { "Original wheel license is absent" }
        val recordPath="$root/RECORD"; val record=requireNotNull(files[recordPath]); val rows=csv(LocalUbuntuCMakePolicy.utf8(record))
        require(rows.size==files.size && rows.map { it[0] }.toSet()==files.keys)
        for((name,hash,size) in rows) {
            if(name==recordPath) require(hash.isEmpty()&&size.isEmpty())
            else { val bytes=requireNotNull(files[name]); require(hash=="sha256="+Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) && size==bytes.size.toString()) }
        }
        return files
    }
    fun project(recipe: LocalUbuntuCMakeRecipe, archives: Map<LocalUbuntuCMakeWheel, File>, destination: File,
        checkCancelled: () -> Unit = {}): Projection {
        LocalUbuntuCMakePolicy.requireReviewed(recipe); require(archives.keys==recipe.wheels.toSet())
        require(!Files.exists(destination.toPath(),NOFOLLOW_LINKS))
        val all=linkedMapOf<String,ByteArray>(); val folded=mutableSetOf<String>(); var total=0L; var entries=0
        for(wheel in recipe.wheels) {
            checkCancelled(); val bytes=LocalUbuntuCMakePolicy.read(archives.getValue(wheel),wheel.bytes,checkCancelled)
            require(bytes.size.toLong()==wheel.bytes && LocalUbuntuCMakePolicy.hash(bytes)==wheel.sha256)
            val members=parse(bytes); entries+=members.size; require(entries<=MAX_ENTRIES)
            val files=validate(wheel,members)
            files.forEach { (name,data)-> require(all.put(name,data)==null && folded.add(name.lowercase(Locale.ROOT)));total+=data.size;require(total<=MAX_BYTES) }
        }
        require(destination.mkdirs())
        for((name,data) in all) { checkCancelled();val file=File(destination,name);check(file.parentFile.mkdirs()||file.parentFile.isDirectory)
            require(!Files.exists(file.toPath(),NOFOLLOW_LINKS));Files.write(file.toPath(),data,java.nio.file.StandardOpenOption.CREATE_NEW)
            check(file.setReadable(false,false)&&file.setWritable(false,false)&&file.setExecutable(false,false)&&file.setReadable(true,true)&&file.setWritable(true,true))
        }
        return Projection(all.mapValues { LocalUbuntuCMakePolicy.hash(it.value) },total)
    }
}
