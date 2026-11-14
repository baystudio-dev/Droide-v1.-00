package com.baystudio.droide.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.serialization.json.*

/** Authenticated bounded data-only single-member gzip/ustar/local-PAX decoder. No native extractor. */
internal object LocalUbuntuGraphqlArchive {
    class Budget {
        var compressedBytes=0L; private set
        var physicalBytes=0L; private set
        var physicalFiles=0; private set
        var entries=0; private set
        var members=0; private set
        private val identities=hashSetOf<String>()
        fun archive(identity:String,bytes:Long) {
            require(identities.add(identity) && bytes in 1..LocalUbuntuGraphqlPolicy.MAX_ARCHIVE_BYTES)
            compressedBytes=Math.addExact(compressedBytes,bytes);require(compressedBytes<=LocalUbuntuGraphqlPolicy.MAX_ARCHIVES_BYTES)
        }
        fun file(bytes:Long) {
            physicalBytes=Math.addExact(physicalBytes,bytes);physicalFiles=Math.addExact(physicalFiles,1)
            require(physicalBytes<=LocalUbuntuGraphqlPolicy.MAX_TREE_BYTES && physicalFiles<=LocalUbuntuGraphqlPolicy.MAX_FILES)
        }
        fun entry() { entries=Math.addExact(entries,1);require(entries<=LocalUbuntuGraphqlPolicy.MAX_ENTRIES) }
        fun member() { members=Math.addExact(members,1);require(members<=LocalUbuntuGraphqlPolicy.MAX_ENTRIES) }
    }
    fun spec(identity:String):TrustedSha512ArtifactSpec {
        val a=LocalUbuntuGraphqlPolicy.artifacts.getValue(identity);val url=a.getValue("dist").jsonObject.getValue("tarball").jsonPrimitive.content
        LocalUbuntuNpmPolicy.requireRegistryTarball(url)
        return TrustedSha512ArtifactSpec("graphql-"+LocalUbuntuGraphqlPolicy.sha256(identity.toByteArray()).take(40),url,a.getValue("sha512").jsonPrimitive.content,url.substringAfterLast('/'),LocalUbuntuGraphqlPolicy.MAX_ARCHIVE_BYTES)
    }
    fun extract(identity:String,archive:File,npmRoot:File,budget:Budget,active:()->Unit={}):JsonObject {
        val a=LocalUbuntuGraphqlPolicy.artifacts.getValue(identity);val placement=LocalUbuntuGraphqlPolicy.placements.entries.single{it.value==identity}.key
        val root=File(npmRoot,placement);val files=linkedMapOf<String,JsonElement>();val before=attributes(archive)
        budget.archive(identity,before.size())
        val result=authenticated(archive,before,a.getValue("sha512").jsonPrimitive.content,"SHA-512",active) { input ->
            makeRoot(root)
            SingleGzip(input,LocalUbuntuGraphqlPolicy.MAX_ARCHIVE_UNPACKED+4_194_304L,active).use { gzip ->
                decode(gzip,root,"package",false,LocalUbuntuGraphqlPolicy.MAX_ARCHIVE_UNPACKED,LocalUbuntuGraphqlPolicy.MAX_FILES,LocalUbuntuGraphqlPolicy.MAX_ENTRIES,files,budget,active)
            }
        }
        require(files.containsKey("package.json"))
        LocalUbuntuGraphqlPolicy.requirePackage(identity,LocalUbuntuGraphqlPolicy.strictObject(LocalUbuntuGraphqlPolicy.read(File(root,"package.json"),262144,active)))
        val dist=a.getValue("dist").jsonObject
        require(files.size==dist.getValue("fileCount").jsonPrimitive.int && files.values.sumOf{it.jsonObject.getValue("size").jsonPrimitive.long}==dist.getValue("unpackedSize").jsonPrimitive.long) { "GraphQL original distribution disagrees with reviewed metadata; admission stopped" }
        return buildJsonObject{put("sha512",a.getValue("sha512"));put("files",JsonObject(files));put("directories",result.getValue("directories"));put("links",result.getValue("links"));put("rootMode",result.getValue("rootMode"))}
    }
    fun extractNode(archive:File,tree:File,active:()->Unit={}):JsonObject {
        val before=attributes(archive);require(before.size()==LocalUbuntuGraphqlNodePolicy.ARTIFACT_BYTES)
        val files=linkedMapOf<String,JsonElement>()
        val inventory=authenticated(archive,before,LocalUbuntuGraphqlNodePolicy.SHA256,"SHA-256",active) { input ->
            makeRoot(tree)
            SingleGzip(input,LocalUbuntuGraphqlNodePolicy.MAX_TREE_BYTES+16_777_216L,active).use { gzip ->
                decode(gzip,tree,LocalUbuntuGraphqlNodePolicy.TOP,true,LocalUbuntuGraphqlNodePolicy.MAX_TREE_BYTES,8192,16384,files,null,active)
            }
        }
        LocalUbuntuGraphqlNodePolicy.requireTree(File(tree,LocalUbuntuGraphqlNodePolicy.ENTRY),active)
        return buildJsonObject{put("sha256",LocalUbuntuGraphqlNodePolicy.SHA256);put("files",JsonObject(files));put("directories",inventory.getValue("directories"));put("links",inventory.getValue("links"))}
    }
    private fun attributes(file:File)=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).also{require(it.isRegularFile&&!it.isSymbolicLink&&it.fileKey()!=null)}
    private fun <T> authenticated(file:File,before:BasicFileAttributes,expected:String,algorithm:String,active:()->Unit,body:(InputStream)->T):T {
        val digest=MessageDigest.getInstance(algorithm);var size=0L
        val result=Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { raw ->
            val checked=object:FilterInputStream(raw) {
                override fun read():Int { active();val v=`in`.read();if(v>=0){size=Math.addExact(size,1);require(size<=before.size());digest.update(v.toByte())};return v }
                override fun read(b:ByteArray,off:Int,len:Int):Int { active();val n=`in`.read(b,off,len);if(n>0){size=Math.addExact(size,n.toLong());require(size<=before.size());digest.update(b,off,n)};return n }
                override fun close() {} // Outer lease/stream remains owned through authentication.
            }
            body(checked).also { require(checked.read()==-1) }
        }
        val after=attributes(file)
        require(size==before.size()&&digest.digest().hex()==expected&&after.fileKey()==before.fileKey()&&after.size()==before.size()&&after.lastModifiedTime()==before.lastModifiedTime()) { "GraphQL exact decoder-consumed archive authentication failed" }
        return result
    }
    private fun makeRoot(root:File) {
        require(!Files.exists(root.toPath(),NOFOLLOW_LINKS));var ancestor=root.parentFile
        while(ancestor!=null&&!Files.exists(ancestor.toPath(),NOFOLLOW_LINKS))ancestor=ancestor.parentFile
        require(ancestor!=null);NimRuntimeClosure.requireDirectory(ancestor);require(ancestor.canonicalFile==ancestor.absoluteFile)
        require(root.mkdirs());require(root.canonicalFile==root.absoluteFile)
    }
    /** Visible to authored fixtures; never grants authentic-archive status. */
    internal fun decodeData(input:InputStream,root:File,budget:Budget,files:MutableMap<String,JsonElement>,active:()->Unit={}):JsonObject =
        decode(input,root,"package",false,LocalUbuntuGraphqlPolicy.MAX_ARCHIVE_UNPACKED,LocalUbuntuGraphqlPolicy.MAX_FILES,LocalUbuntuGraphqlPolicy.MAX_ENTRIES,files,budget,active)
    /** Authored data decoder only: does not authenticate an archive or admit a runtime. */
    internal fun decodeNodeData(input:InputStream,root:File,files:MutableMap<String,JsonElement>,active:()->Unit={}):JsonObject =
        decode(input,root,LocalUbuntuGraphqlNodePolicy.TOP,true,LocalUbuntuGraphqlNodePolicy.MAX_TREE_BYTES,LocalUbuntuGraphqlNodePolicy.MAX_FILES,16384,files,null,active)
    private fun decode(input:InputStream,root:File,top:String,node:Boolean,maxBytes:Long,maxFiles:Int,maxEntries:Int,files:MutableMap<String,JsonElement>,budget:Budget?,active:()->Unit):JsonObject {
        NimRuntimeClosure.requireDirectory(root);require(root.canonicalFile==root.absoluteFile)
        val rootKey=Files.readAttributes(root.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey();require(rootKey!=null)
        val header=ByteArray(512);val buffer=ByteArray(65536);var entries=0;var unpacked=0L;var pending:Map<String,String>?=null;var rootMode:Int?=null
        val explicit=hashSetOf<String>();val caseNames=hashMapOf<String,String>();val dirs=sortedMapOf<String,Int>();val links=sortedMapOf<String,String>()
        fun exact(b:ByteArray,n:Int) { var at=0;while(at<n){active();val got=input.read(b,at,n-at);require(got>0){"Truncated GraphQL TAR"};at+=got} }
        fun number(at:Int,n:Int):Long { val s=String(header,at,n,Charsets.US_ASCII).trim(' ','\u0000');require(s.isNotEmpty()&&s.length<=12&&s.all{it in '0'..'7'});return s.toLong(8) }
        fun text(at:Int,n:Int):String { val end=(at until at+n).firstOrNull{header[it]==0.toByte()}?:at+n;require((end until at+n).all{header[it]==0.toByte()});return utf8(header.copyOfRange(at,end)) }
        fun padding(size:Long) { val n=((512-size%512)%512).toInt();if(n>0){exact(buffer,n);require((0 until n).all{buffer[it]==0.toByte()})} }
        fun path(name:String) { LocalUbuntuGraphqlPolicy.safeRelative(name);val folded=name.lowercase(Locale.ROOT);val previous=caseNames.putIfAbsent(folded,name);require(previous==null||previous==name){"GraphQL archive case collision"} }
        fun parents(relative:String) {
            require(Files.readAttributes(root.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()==rootKey)
            var prefix="";for(part in relative.split('/').dropLast(1)) {
                prefix=if(prefix.isEmpty())part else "$prefix/$part";path(prefix);require(prefix !in files&&prefix !in links)
                val dir=File(root,prefix);if(!Files.exists(dir.toPath(),NOFOLLOW_LINKS)){require(dirs.size+files.size+links.size<maxEntries);budget?.member();Files.createDirectory(dir.toPath());android.system.Os.chmod(dir.absolutePath,493)}
                NimRuntimeClosure.requireDirectory(dir);require(dir.canonicalFile==dir.absoluteFile);dirs.putIfAbsent(prefix,493)
            }
        }
        while(true) {
            exact(header,512)
            if(header.all{it==0.toByte()}) {
                exact(header,512);require(header.all{it==0.toByte()}&&pending==null)
                var tail=0L;while(true){active();val n=input.read(buffer);if(n<0)break;tail=Math.addExact(tail,n.toLong());require(tail<=10240&&(0 until n).all{buffer[it]==0.toByte()})};require(tail%512==0L);break
            }
            require(++entries<=maxEntries);budget?.entry()
            require(number(148,8)==header.indices.sumOf{if(it in 148..155)32L else (header[it].toInt() and 255).toLong()}) { "GraphQL TAR checksum mismatch" }
            require(text(257,6)=="ustar" && text(263,2) in setOf("00","")) { "Only reviewed ustar/local PAX accepted" }
            val mode=number(100,8);require(mode in 0..511){"GraphQL TAR special mode"}
            val headerSize=number(124,12);require(headerSize in 0..maxBytes)
            val kind=header[156].toInt().toChar();require(kind in if(node)setOf('\u0000','0','5','x','2')else setOf('\u0000','0','5','x')) { "Unsupported GraphQL archive type" }
            if(kind=='x') { require(pending==null&&headerSize in 1..65536);val bytes=ByteArray(headerSize.toInt());exact(bytes,bytes.size);pending=pax(bytes);padding(headerSize);continue }
            val fields=pending.orEmpty();pending=null;val prefix=text(345,155);val name=fields["path"]?:if(prefix.isEmpty())text(0,100)else "$prefix/${text(0,100)}"
            val size=fields["size"]?.also{require(it.matches(Regex("0|[1-9][0-9]{0,11}")))}?.toLong()?:headerSize
            require(size in 0..maxBytes && name.toByteArray(Charsets.UTF_8).size<=4096)
            require(name=="$top/"||name.startsWith("$top/")) { "Unexpected archive top" }
            val relative=(if(node)name else name.removePrefix("$top/")).let{if(kind=='5')it.removeSuffix("/")else it}
            if(relative.isEmpty()){require(kind=='5'&&size==0L&&mode and 448L==448L);require(explicit.add(""));rootMode=mode.toInt();continue}
            path(relative);require(explicit.add(relative)){"Duplicate GraphQL TAR entry"}
            if(!node)require(relative.split('/').none{it=="node_modules"||it==".bin"})
            parents(relative);val target=File(root,relative)
            when(kind) {
                '5' -> { require(size==0L&&relative !in files&&relative !in links);require(mode and 448L==448L) { "Original directory lacks owner rwx" };if(!Files.exists(target.toPath(),NOFOLLOW_LINKS)){require(dirs.size+files.size+links.size<maxEntries);budget?.member();Files.createDirectory(target.toPath())};NimRuntimeClosure.requireDirectory(target);dirs[relative]=mode.toInt() }
                '2' -> { require(mode==511L) { "Original Node symlink mode cannot be preserved" };require(size==0L&&relative !in dirs&&relative !in files&&links.size<256);val link=text(157,100);require(link.isNotEmpty()&&!link.startsWith('/')&&'\\' !in link&&link.none{it.code<32||it.code==127});require(relative!=LocalUbuntuGraphqlNodePolicy.ENTRY&&dirs.size+files.size+links.size<maxEntries);links[relative]=link }
                else -> {
                    require(relative !in dirs&&relative !in links);unpacked=Math.addExact(unpacked,size);require(unpacked<=maxBytes&&files.size<maxFiles);budget?.file(size)
                    require(dirs.size+files.size+links.size<maxEntries);budget?.member();val digest=MessageDigest.getInstance("SHA-256")
                    java.nio.channels.FileChannel.open(target.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use { out ->
                        var left=size;while(left>0){val n=minOf(left,buffer.size.toLong()).toInt();exact(buffer,n);digest.update(buffer,0,n);val bytes=ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining()){active();require(out.write(bytes)>0)};left-=n};out.force(true)
                    }
                    android.system.Os.chmod(target.absolutePath,mode.toInt());files[relative]=buildJsonObject{put("size",size);put("mode",mode);put("sha256",digest.digest().hex())};padding(size)
                }
            }
        }
        require(files.isNotEmpty())
        // Links materialize only after all data writes. Resolve chains as data; no symlink ancestor writes.
        for((name,link)in links) {
            var current=name;val seen=hashSetOf<String>()
            while(current in links) { require(seen.add(current));val parent=java.nio.file.Paths.get(current).parent?:java.nio.file.Paths.get("");val normalized=parent.resolve(links.getValue(current)).normalize().toString().replace(File.separatorChar,'/');require(normalized.startsWith("$top/")&&!normalized.startsWith("../"));current=normalized }
            require(current in files) { "Node dangling/external/nonregular symlink target" }
            require((files.keys+dirs.keys+links.keys).none{it.startsWith("$name/")})
            Files.createSymbolicLink(File(root,name).toPath(),java.nio.file.Paths.get(link))
        }
        for((name,mode)in dirs.entries.sortedByDescending{it.key.length})android.system.Os.chmod(File(root,name).absolutePath,mode)
        if(rootMode!=null)android.system.Os.chmod(root.absolutePath,requireNotNull(rootMode))
        for(dir in (dirs.keys.map{File(root,it)}+root).sortedByDescending{it.path.length}) {
            NimRuntimeClosure.requireDirectory(dir);java.nio.channels.FileChannel.open(dir.toPath(),READ).use{it.force(true)}
        }
        return buildJsonObject{put("directories",JsonObject(dirs.mapValues{JsonPrimitive(it.value)}));put("links",JsonObject(links.mapValues{JsonPrimitive(it.value)}));put("rootMode",android.system.Os.lstat(root.absolutePath).st_mode and 511)}
    }
    private fun pax(bytes:ByteArray):Map<String,String> {
        val out=linkedMapOf<String,String>();var at=0
        while(at<bytes.size) {
            val start=at;while(at<bytes.size&&bytes[at]!=32.toByte())at++;require(at>start&&at-start<=6&&at<bytes.size)
            val raw=String(bytes,start,at-start,Charsets.US_ASCII);require(raw.matches(Regex("[1-9][0-9]{0,5}")));val n=raw.toInt();require(n>at-start+3&&n<=bytes.size-start&&bytes[start+n-1]==10.toByte())
            val value=utf8(bytes.copyOfRange(at+1,start+n-1));val equal=value.indexOf('=');require(equal>0);val key=value.substring(0,equal);val v=value.substring(equal+1)
            require(key in setOf("path","size","mtime","atime","ctime","uid","gid","uname","gname")&&out.put(key,v)==null&&v.toByteArray().size<=4096&&v.none{it.code<32||it.code==127});at=start+n
        };return out
    }
    /** Unlike GZIPInputStream, a concatenated member or any bytes after the trailer are rejected. */
    internal class SingleGzip(raw:InputStream,private val limit:Long,private val active:()->Unit={}):InputStream() {
        private val source=PushbackInputStream(raw,65536);private val inflater=Inflater(true);private val compressed=ByteArray(65536);private val crc=CRC32();private var total=0L;private var finished=false;private var lastInput=0
        init {
            val h=ByteArrayOutputStream()
            fun byte():Int { active();val v=source.read();require(v>=0);h.write(v);require(h.size()<=65536);return v }
            require(byte()==31&&byte()==139&&byte()==8);val flags=byte();require(flags and 224==0);repeat(6){byte()}
            if(flags and 4!=0){val n=byte() or(byte() shl 8);repeat(n){byte()}}
            if(flags and 8!=0)while(byte()!=0)Unit
            if(flags and 16!=0)while(byte()!=0)Unit
            if(flags and 2!=0){val sum=CRC32().apply{update(h.toByteArray())}.value.toInt() and 65535;val a=source.read();val b=source.read();require(a>=0&&b>=0&&(a or(b shl 8))==sum)}
        }
        override fun read():Int { val b=ByteArray(1);return if(read(b,0,1)<0)-1 else b[0].toInt() and 255 }
        override fun read(b:ByteArray,off:Int,len:Int):Int {
            require(off>=0&&len>=0&&off<=b.size-len);if(len==0)return 0;if(finished)return -1
            while(true) {
                active();if(inflater.needsInput()){lastInput=source.read(compressed);require(lastInput>0){"Truncated gzip"};inflater.setInput(compressed,0,lastInput)}
                val n=inflater.inflate(b,off,len)
                if(n>0){total=Math.addExact(total,n.toLong());require(total<=limit);crc.update(b,off,n);return n}
                if(inflater.finished()) {
                    val remaining=inflater.remaining;if(remaining>0)source.unread(compressed,lastInput-remaining,remaining)
                    fun uint():Long { var value=0L;repeat(4){val v=source.read();require(v>=0);value=value or(v.toLong() shl(it*8))};return value }
                    require(uint()==crc.value&&uint()==(total and 0xffffffffL)&&source.read()==-1){"Gzip checksum, size or extra payload rejected"};finished=true;return -1
                }
                require(!inflater.needsDictionary()&&inflater.needsInput()) { "Invalid compressed stream" }
            }
        }
        override fun close() { inflater.end() }
    }
    private fun utf8(bytes:ByteArray):String=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it.toInt() and 255)}
}
