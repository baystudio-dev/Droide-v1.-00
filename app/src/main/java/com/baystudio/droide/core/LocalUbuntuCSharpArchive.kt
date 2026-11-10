package com.baystudio.droide.core

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** A managed NUPKG is data, never a native executable alias. Whole authenticated original layouts only. */
internal object LocalUbuntuCSharpArchive {
    data class Member(val path: String, val kind: String, val mode: Int, val size: Long = 0,
        val sha256: String = "", val target: String = "")
    class Projection(val root: File, val members: List<Member>, val localArchiveSha256: String) : Closeable {
        val manifest get() = encode(members)
        val manifestSha256 get() = NimRuntimeClosure.sha256(manifest)
        override fun close() { check(PathSecurity.deleteTreeNoFollow(root)) { "C# quarantine cleanup failed" } }
    }
    fun encode(members: List<Member>): String = members.sortedBy { it.path }.joinToString("\n", postfix = "\n") {
        "${it.kind}\t${it.mode}\t${it.size}\t${it.sha256}\t${it.target}\t${it.path}"
    }
    fun decode(text: String): List<Member> {
        require(text.length in 1..16_777_216 && text.endsWith('\n'))
        val members = text.dropLast(1).split('\n').map { row ->
            val p = row.split('\t'); require(p.size == 6); requirePath(p[5])
            val member = Member(p[5], p[0], p[1].toInt(), p[2].toLong(), p[3], p[4])
            require(member.kind in setOf("file", "directory", "link") && (if(member.kind=="link")member.mode==511 else member.mode in setOf(420,493,292,365)))
            require(member.size in 0..536_870_912L)
            if (member.kind == "file") require(member.sha256.matches(Regex("[0-9a-f]{64}")) && member.target.isEmpty())
            else require(member.sha256.isEmpty() && member.size == 0L)
            if (member.kind == "link") requireTarget(member.path, member.target) else require(member.target.isEmpty())
            member
        }
        require(members.size <= 65536 && members.map { it.path.lowercase(Locale.ROOT) }.distinct().size == members.size)
        require(encode(members) == text) { "C# original manifest is not canonical" }
        return members
    }
    fun project(recipe: CSharpInstallRecipe, archive: File, active: () -> Unit = {}): Projection {
        recipe.validate()
        val sha = recipe.publisherDigest.verify(archive, recipe.maxArchiveBytes, recipe.expectedBytes, active)
        val parent = requireNotNull(archive.parentFile)
        require(parent.usableSpace >= Math.addExact(recipe.maxTreeBytes, 268_435_456L))
        val root = Files.createTempDirectory(parent.toPath(), ".csharp-quarantine-").toFile()
        setMode(root,493)
        return try {
            val members = when (recipe.role) {
                CSharpArtifactRole.MANAGED_NUPKG -> zip(recipe, archive, root, active)
                else -> GZIPInputStream(archive.inputStream(), 65536).use { tar(recipe, it, root, active) }
            }
            require(recipe.publisherDigest.verify(archive, recipe.maxArchiveBytes, recipe.expectedBytes, active) == sha)
            Projection(root, members, sha).also { verifyTree(recipe, root, it.members, active) }
        } catch (failure: Throwable) { PathSecurity.deleteTreeNoFollow(root); throw failure }
    }
    fun verifyTree(recipe: CSharpInstallRecipe, root: File, members: List<Member>, active: () -> Unit = {}) {
        recipe.validate(); NimRuntimeClosure.requireDirectory(root)
        require(root.canonicalFile == root.absoluteFile && mode(root) == 493)
        val actual = linkedSetOf<String>(); var bytes = 0L; var files = 0
        val expected = members.associateBy { it.path }; require(expected.size == members.size && members.size <= recipe.maxFiles * 2)
        Files.walk(root.toPath()).use { stream -> stream.forEach { path ->
            active(); if (path == root.toPath()) return@forEach
            val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            require(actual.add(relative) && actual.size <= recipe.maxFiles * 2)
            val member = expected[relative] ?: error("Unauthenticated extra C# tree member: $relative")
            when (member.kind) {
                "directory" -> require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                "link" -> {
                    require(recipe.role != CSharpArtifactRole.MANAGED_NUPKG && Files.isSymbolicLink(path))
                    require(Files.readSymbolicLink(path).toString() == member.target)
                    val resolved = requireTarget(relative, member.target)
                    require(expected[resolved]?.kind == "file") { "C# links must resolve directly to a regular original member" }
                    require(path.toFile().canonicalPath == File(root, resolved).canonicalPath)
                }
                "file" -> {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    require(++files <= recipe.maxFiles)
                    val measured = NimRuntimeClosure.measure(path.toFile(), 536_870_912L, active)
                    require(measured.first == member.size && measured.second == member.sha256)
                    bytes = Math.addExact(bytes, measured.first); require(bytes <= recipe.maxTreeBytes)
                }
                else -> error("Unsupported C# member kind")
            }
            if (member.kind != "link") require(mode(path.toFile()) == member.mode) { "Original C# mode changed: $relative" }
        } }
        require(actual == expected.keys && expected[recipe.entryPoint]?.kind == "file")
        if (recipe.role == CSharpArtifactRole.MANAGED_NUPKG) {
            require(members.filter { it.kind == "file" }.associate { it.path to it.size } == LocalUbuntuCSharpPolicy.catalogMembers)
            require(members.filter { it.kind == "file" }.all { it.mode == 420 }) { "Managed DLL package must not acquire executable authority" }
        }
    }
    private class Builder(val root: File, val recipe: CSharpInstallRecipe, val active: () -> Unit) {
        val members = linkedMapOf<String,Member>(); private val folded = hashSetOf<String>(); var total = 0L; var fileCount = 0
        fun parent(path: String) {
            val parts = path.split('/'); var prefix = ""
            for (part in parts.dropLast(1)) {
                prefix = if(prefix.isEmpty())part else "$prefix/$part"
                if (prefix !in members) add(Member(prefix, "directory", 493), implicit = true)
                require(members.getValue(prefix).kind == "directory") { "C# extraction traverses a non-directory" }
            }
        }
        private val implicitDirs = hashSetOf<String>()
        fun add(m: Member, implicit: Boolean = false) {
            requirePath(m.path); require(members.size < recipe.maxFiles * 2)
            if (!implicit && m.kind == "directory" && m.path in implicitDirs) {
                implicitDirs.remove(m.path); members[m.path] = m; return
            }
            require(m.path !in members && folded.add(m.path.lowercase(Locale.ROOT))) { "Duplicate/case-colliding C# member" }
            members[m.path] = m
            if (m.kind == "directory") {
                Files.createDirectory(File(root,m.path).toPath()); setMode(File(root,m.path),493)
                if (implicit) implicitDirs += m.path
            }
        }
        fun file(path: String, mode: Int, size: Long, input: InputStream): Member {
            active(); parent(path); require(size in 0..536_870_912L && ++fileCount <= recipe.maxFiles)
            total = Math.addExact(total, size); require(total <= recipe.maxTreeBytes)
            require(path !in members && path.lowercase(Locale.ROOT) !in folded)
            val file = File(root,path); val hash = MessageDigest.getInstance("SHA-256")
            Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { out ->
                val buffer = ByteArray(65536); var left = size
                while (left > 0) { active(); val n = input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt()); require(n>0) { "Truncated C# archive member" }
                    hash.update(buffer,0,n); out.write(buffer,0,n); left-=n
                }
            }
            setMode(file,mode)
            return Member(path,"file",mode,size,hash.digest().hex()).also(::add)
        }
        fun finish(): List<Member> {
            members.values.filter { it.kind == "link" }.forEach { m ->
                require(members[requireTarget(m.path,m.target)]?.kind == "file")
                Files.createSymbolicLink(File(root,m.path).toPath(),java.nio.file.Paths.get(m.target))
            }
            members.values.filter { it.kind == "directory" }.sortedByDescending { it.path.length }.forEach { setMode(File(root,it.path),it.mode) }
            return members.values.sortedBy { it.path }
        }
    }
    private fun zip(recipe:CSharpInstallRecipe,archive:File,root:File,active:()->Unit):List<Member> {
        val modes = zipHeaders(archive,recipe.maxFiles,active)
        val builder = Builder(root,recipe,active)
        ZipFile(archive).use { zip ->
            val entries=zip.entries(); var count=0
            while(entries.hasMoreElements()) {
                active();val e=entries.nextElement();require(++count<=recipe.maxFiles*2)
                val path=e.name.removeSuffix("/"); requirePath(path);require(path in modes)
                if(e.isDirectory) { builder.parent(path);builder.add(Member(path,"directory",493));continue }
                require(e.size in 0..536_870_912L && e.compressedSize>=0 && (e.size == 0L || e.compressedSize>0 && e.size<=e.compressedSize*200L))
                val crc=CRC32()
                zip.getInputStream(e).use { raw ->
                    val input=object:java.io.FilterInputStream(raw){override fun read(b:ByteArray,off:Int,len:Int):Int=super.read(b,off,len).also { if(it>0)crc.update(b,off,it) }}
                    builder.file(path,420,e.size,input);require(raw.read()==-1)
                }
                require(crc.value==e.crc)
            };require(count==modes.size)
        };return builder.finish()
    }
    /** Bounded central-directory validation rejects links/devices, encryption, ZIP64 and executable NUPKG members. */
    private fun zipHeaders(file:File,max:Int,active:()->Unit):Map<String,Int> = RandomAccessFile(file,"r").use { r ->
        require(r.length()>=22);val tailSize=minOf(r.length(),65557).toInt();val tail=ByteArray(tailSize);r.seek(r.length()-tailSize);r.readFully(tail)
        fun u16(b:ByteArray,i:Int)=(b[i].toInt()and 255) or ((b[i+1].toInt()and 255) shl 8)
        fun u32(b:ByteArray,i:Int)=u16(b,i).toLong() or (u16(b,i+2).toLong() shl 16)
        val end=(tail.size-22 downTo 0).firstOrNull { u32(tail,it)==0x06054b50L && it+22+u16(tail,it+20)==tail.size }?:error("Missing bounded ZIP end record")
        require(u16(tail,end+4)==0&&u16(tail,end+6)==0)
        val count=u16(tail,end+10);require(count in 1..max*2&&u16(tail,end+8)==count)
        val size=u32(tail,end+12);val offset=u32(tail,end+16);require(size in 1..16_777_216&&offset+size==r.length()-tailSize+end)
        r.seek(offset);val out=linkedMapOf<String,Int>()
        repeat(count) { active();val h=ByteArray(46);r.readFully(h);require(u32(h,0)==0x02014b50L)
            val flags=u16(h,8);require(flags and 0x2041==0 && u16(h,10) in setOf(0,8))
            val n=u16(h,28);val extra=u16(h,30);val comment=u16(h,32);require(n in 1..4096 && extra<=8192 && comment<=4096)
            require(u32(h,20)!=0xffffffffL&&u32(h,24)!=0xffffffffL&&u32(h,42)!=0xffffffffL)
            val name=ByteArray(n);r.readFully(name);val decoded=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(name)).toString()
            val path=decoded.removeSuffix("/");requirePath(path)
            val unix=(u16(h,4) ushr 8)==3;val mode=(u32(h,38) ushr 16).toInt()
            if(unix&&mode!=0) require(mode and 0xf000 in setOf(0,0x8000,0x4000)&&mode and 0x0c00==0 && (decoded.endsWith('/') || mode and 73==0))
            require(out.put(path,mode)==null);r.seek(r.filePointer+extra+comment)
        };require(r.filePointer==offset+size);out
    }
    private fun tar(recipe:CSharpInstallRecipe,input:InputStream,root:File,active:()->Unit):List<Member> {
        val builder=Builder(root,recipe,active);var entries=0;val header=ByteArray(512)
        var longName:String?=null;var longTarget:String?=null
        fun exact(bytes:ByteArray) { var at=0;while(at<bytes.size){active();val n=input.read(bytes,at,bytes.size-at);require(n>0);at+=n} }
        fun text(at:Int,n:Int):String { val end=(at until at+n).firstOrNull { header[it]==0.toByte() }?:at+n
            require((end until at+n).all { header[it]==0.toByte() });return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(header,at,end-at)).toString() }
        fun octal(at:Int,n:Int):Long { val s=String(header,at,n,Charsets.US_ASCII).trim(' ','\u0000');require(s.isNotEmpty()&&s.all{it in '0'..'7'});return s.toLong(8) }
        while(true) {
            exact(header)
            if(header.all { it==0.toByte() }) { require(longName==null&&longTarget==null);exact(header);require(header.all { it==0.toByte() });var tail=0L;val b=ByteArray(512);while(true){active();val n=input.read(b);if(n<0)break;tail+=n;require(tail<=65536&&b.take(n).all{it==0.toByte()})};break }
            require(++entries<=recipe.maxFiles*2)
            val expected=octal(148,8);val sum=header.indices.sumOf { if(it in 148..155)32 else header[it].toInt()and 255 };require(expected==sum.toLong())
            require(text(257,6) in setOf("ustar","ustar ")) { "Unsupported C# TAR header" }
            val kind=header[156].toInt();val mode=octal(100,8).toInt();val size=octal(124,12)
            require(size in 0..536_870_912L)
            if(kind==76||kind==75) {
                require(text(0,100)=="././@LongLink"&&size in 2..4096&&mode in setOf(420,493))
                val data=ByteArray(size.toInt());exact(data);require(data.last()==0.toByte()&&data.dropLast(1).none{it==0.toByte()})
                val value=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data,0,data.size-1)).toString()
                if(kind==76){require(longName==null);longName=value}else{require(longTarget==null);longTarget=value}
                val pad=ByteArray(((512-size%512)%512).toInt());exact(pad);require(pad.all{it==0.toByte()});continue
            }
            val prefix=if(text(257,6)=="ustar ")"" else text(345,155)
            val name=longName?:text(0,100);val path=(if(prefix.isEmpty()||longName!=null)name else "$prefix/$name").removePrefix("./").removeSuffix("/")
            val target=longTarget?:text(157,100);require(longTarget==null||kind==50);longName=null;longTarget=null
            if(path.isEmpty()||path==".") { require(kind==53&&size==0L&&mode==493);continue }
            requirePath(path);builder.parent(path)
            require(if(kind==50)mode==511 else mode in setOf(420,493,292,365)) { "Unreviewed C# original TAR mode" }
            when(kind) {
                0,48 -> builder.file(path,mode,size,input)
                53 -> { require(size==0L);builder.add(Member(path,"directory",mode)) }
                50 -> { require(size==0L);requireTarget(path,target);builder.add(Member(path,"link",511,target=target)) }
                else -> error("Unsupported C# original TAR member type; no links or metadata are rewritten")
            }
            val padding=((512-size%512)%512).toInt();if(padding>0){val bytes=ByteArray(padding);exact(bytes);require(bytes.all { it==0.toByte() })}
        };return builder.finish()
    }
    fun requirePath(path:String) { require(path.length in 1..4096 && !path.startsWith('/') && '\\' !in path && ':' !in path && path.none{it.code<32||it.code==127})
        val parts=path.split('/');require(parts.size<=32&&parts.all{it.length in 1..255&&it !in setOf(".","..")}) }
    fun requireTarget(path:String,target:String):String {
        require(target.length in 1..4096&&!target.startsWith('/')&&'\\' !in target&&':' !in target&&target.none{it.code<32||it.code==127})
        val parent=java.nio.file.Paths.get(path).parent?:java.nio.file.Paths.get("")
        val resolved=parent.resolve(target).normalize().toString().replace(File.separatorChar,'/');requirePath(resolved);return resolved
    }
    fun mode(file:File):Int {
        val path=file.toPath();val unix=Files.getAttribute(path,"unix:mode",NOFOLLOW_LINKS) as? Number?:error("C# original UNIX mode unavailable")
        val mode=unix.toInt() and 4095
        require(mode in setOf(420,493,292,365)) { "C# special or unreviewed filesystem mode" }
        if(Files.isRegularFile(path,NOFOLLOW_LINKS))require((Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as? Number)?.toLong()==1L) { "C# hard-linked original file" }
        val p=Files.getPosixFilePermissions(path,NOFOLLOW_LINKS)
        require(permissions.entries.sumOf{if(it.value in p)it.key else 0}==mode)
        return mode
    }
    fun setMode(file:File,mode:Int){Files.setPosixFilePermissions(file.toPath(),permissions.filterKeys{mode and it!=0}.values.toSet())}
    private val permissions=mapOf(256 to PosixFilePermission.OWNER_READ,128 to PosixFilePermission.OWNER_WRITE,64 to PosixFilePermission.OWNER_EXECUTE,
        32 to PosixFilePermission.GROUP_READ,16 to PosixFilePermission.GROUP_WRITE,8 to PosixFilePermission.GROUP_EXECUTE,
        4 to PosixFilePermission.OTHERS_READ,2 to PosixFilePermission.OTHERS_WRITE,1 to PosixFilePermission.OTHERS_EXECUTE)
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt()and 255)}
}
