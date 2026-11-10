package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** Bounded original-byte inspection. No assembly loading, reflection, MSBuild, SDK execution or resolver. */
internal object CSharpRuntimeClosure {
    data class Assembly(val name: String, val version: String, val references: Map<String,String>, val nativeModules:Set<String>,
        val publicKeyToken:String="",val culture:String="",val referenceKeys:Map<String,String> = emptyMap(),val referenceCultures:Map<String,String> = emptyMap())
    data class Inventory(val recipe:CSharpInstallRecipe,val root:File,val members:List<LocalUbuntuCSharpArchive.Member>,
        val native:Map<String,NimRuntimeClosure.NativeImage>,val configs:Map<String,String>,val assemblies:Map<String,Assembly>,
        val dependencies:Set<String>,val notices:Map<String,String>) {
        val configSha256 get()=NimRuntimeClosure.sha256(configs.toSortedMap().entries.joinToString("\n"){"${it.key}\u0000${it.value}"})
        val treeSha256 get()=NimRuntimeClosure.sha256(LocalUbuntuCSharpArchive.encode(members))
    }
    private val nativeNames=setOf("libhostfxr.so","libhostpolicy.so","libclrgc.so","libclrjit.so","libcoreclr.so","libcoreclrtraceptprovider.so",
        "libmscordaccore.so","libmscordbi.so","libSystem.Globalization.Native.so","libSystem.IO.Compression.Native.so","libSystem.IO.Ports.Native.so",
        "libSystem.Native.so","libSystem.Net.Security.Native.so","libSystem.Security.Cryptography.Native.OpenSsl.so","libmsquic.so")
    private val guestNames=setOf("ld-linux-aarch64.so.1","libc.so.6","libm.so.6","libdl.so.2","libpthread.so.0","librt.so.1","libresolv.so.2",
        "libgcc_s.so.1","libstdc++.so.6","libtinfo.so.6","libz.so.1","libgssapi_krb5.so.2","libkrb5.so.3","libk5crypto.so.3","libcom_err.so.2",
        "libkrb5support.so.0","libkeyutils.so.1","libicuuc.so.74","libicui18n.so.74","libicudata.so.74","libssl.so.3","libcrypto.so.3")
    private val dynamicGuest=setOf("libicuuc.so.74","libicui18n.so.74","libicudata.so.74","libssl.so.3","libcrypto.so.3","libgssapi_krb5.so.2","libz.so.1")
    fun inspectTree(recipe:CSharpInstallRecipe,root:File,members:List<LocalUbuntuCSharpArchive.Member>,active:()->Unit={}):Inventory {
        LocalUbuntuCSharpArchive.verifyTree(recipe,root,members,active)
        val files=members.filter{it.kind=="file"}.associateBy{it.path}
        val native=linkedMapOf<String,NimRuntimeClosure.NativeImage>();val configs=linkedMapOf<String,String>();val assemblies=linkedMapOf<String,Assembly>()
        val dependencies=linkedSetOf<String>();val notices=linkedMapOf<String,String>()
        for((path,member)in files) {
            active();val file=File(root,path);val magic=ByteArray(4);file.inputStream().use{it.read(magic)}
            if(magic.contentEquals(byteArrayOf(127,69,76,70))) {
                val role=when(recipe.role) {
                    CSharpArtifactRole.PRIVATE_NODE_HELPER -> { require(path==LocalUbuntuCSharpHelperPolicy.ENTRY);NimRuntimeClosure.NativeRole.EXECUTABLE }
                    CSharpArtifactRole.MANAGED_NUPKG -> error("Unexpected native payload in original C# tool package")
                    CSharpArtifactRole.ORIGINAL_SDK -> {
                        require(path=="dotnet"||path.startsWith("shared/Microsoft.NETCore.App/10.0.13/")||path.startsWith("host/fxr/10.0.13/")||path.startsWith("sdk/10.0.402/"))
                        val name=path.substringAfterLast('/')
                        when { path=="dotnet"||name=="createdump" -> NimRuntimeClosure.NativeRole.EXECUTABLE
                            name in nativeNames -> NimRuntimeClosure.NativeRole.SHARED_LIBRARY
                            else -> error("Unsupported SDK native member: $path") }
                    }
                }
                native[path]=NimRuntimeClosure.inspectElf(file,path,role,active)
            }
            if(path.endsWith(".runtimeconfig.dev.json")) error("External development probing configuration is not admitted")
            if(path.endsWith(".runtimeconfig.json")) { requireRuntimeConfig(readObject(file,active));configs[path]=member.sha256 }
            if(path.endsWith(".deps.json")) { dependencies+=requireDeps(readObject(file,active),path,files.keys);configs[path]=member.sha256 }
            if(path.substringAfterLast('/').contains("license",true)||path.substringAfterLast('/').contains("notice",true)) {
                require(member.size in 1..8_388_608L);NimRuntimeClosure.readText(file,8_388_608,active);notices[path]=member.sha256
            }
        }
        when(recipe.role) {
            CSharpArtifactRole.PRIVATE_NODE_HELPER -> {
                require(native.keys==setOf(LocalUbuntuCSharpHelperPolicy.ENTRY))
                val config=LocalUbuntuCSharpHelperPolicy.CONFIG
                configs[config]=LocalUbuntuCSharpHelperPolicy.requireConfiguration(File(root,config),active)
                require(files.keys.containsAll(setOf("${LocalUbuntuCSharpHelperPolicy.TOP}/LICENSE","${LocalUbuntuCSharpHelperPolicy.TOP}/README.md",config)))
            }
            CSharpArtifactRole.ORIGINAL_SDK -> {
                require(files.keys.containsAll(LocalUbuntuCSharpSdkPolicy.required)) { "Original SDK/runtime/MSBuild/notice layout incomplete" }
                require(files.keys.none { it.startsWith("sdk/")&&!it.startsWith("sdk/10.0.402/") || it.startsWith("host/fxr/")&&!it.startsWith("host/fxr/10.0.13/") ||
                    it.startsWith("shared/Microsoft.NETCore.App/")&&!it.startsWith("shared/Microsoft.NETCore.App/10.0.13/") }) { "Additional SDK/runtime generation is not admitted" }
                require(files.keys.any{it.startsWith("packs/Microsoft.NETCore.App.Ref/10.0.13/")})
                require(native.keys.containsAll(setOf("dotnet","host/fxr/10.0.13/libhostfxr.so","shared/Microsoft.NETCore.App/10.0.13/libcoreclr.so","shared/Microsoft.NETCore.App/10.0.13/libhostpolicy.so")))
                for(path in setOf("sdk/10.0.402/MSBuild.dll","sdk/10.0.402/dotnet.dll","shared/Microsoft.NETCore.App/10.0.13/System.Private.CoreLib.dll")) assemblies[path]=assembly(File(root,path),active)
                require(assemblies.getValue("sdk/10.0.402/MSBuild.dll").name=="MSBuild")
            }
            CSharpArtifactRole.MANAGED_NUPKG -> {
                require(native.isEmpty() && files.keys==LocalUbuntuCSharpPolicy.catalogMembers.keys)
                val settings="tools/net10.0/any/DotnetToolSettings.xml"
                requireToolSettings(NimRuntimeClosure.readText(File(root,settings),65536,active));configs[settings]=files.getValue(settings).sha256
                for(path in files.keys.filter{it.endsWith(".dll")&&!it.contains("/BuildHost-net472/")&&!it.contains("/runtimes/win/")}) assemblies[path]=assembly(File(root,path),active)
                require(assemblies.getValue(LocalUbuntuCSharpPolicy.ENTRY).name=="CSharpLanguageServer")
                require(assemblies.getValue(LocalUbuntuCSharpPolicy.BUILDHOST).name=="Microsoft.CodeAnalysis.Workspaces.MSBuild.BuildHost")
                require(files.getValue(".signature.p7s").size==12985L) // Retained data, no signature-chain claim.
            }
        }
        return Inventory(recipe,root,members,native.toMap(),configs.toMap(),assemblies.toMap(),dependencies.toSet(),notices.toMap())
    }
    fun readObject(file:File,active:()->Unit={})=LocalUbuntuPowerShellRuntimePolicy.strictObject(NimRuntimeClosure.readText(file,4_194_304,active),4_194_304)
    private fun text(o:JsonObject,key:String)=LocalUbuntuPowerShellRuntimePolicy.string(o,key)
    internal fun requireRuntimeConfig(config:JsonObject) {
        require(config.keys==setOf("runtimeOptions"));val o=config.getValue("runtimeOptions") as? JsonObject?:error("Runtime options missing")
        require(o.keys.all{it in setOf("tfm","framework","frameworks","includedFrameworks","rollForward","applyPatches","configProperties")}) { "Unsupported runtime probing/configuration" }
        val tfm=text(o,"tfm");require(tfm.matches(Regex("net(?:[6-9]|10)\\.0")))
        val sets=listOfNotNull(o["framework"]?.let{listOf(it)},o["frameworks"]?.let{(it as? JsonArray)?.toList()?:error("Invalid frameworks")},o["includedFrameworks"]?.let{(it as? JsonArray)?.toList()?:error("Invalid included frameworks")})
        require(sets.size==1&&sets.single().size in 1..2)
        for(element in sets.single()) { val f=element as? JsonObject?:error("Framework must be object");require(f.keys==setOf("name","version"))
            require(text(f,"name") in setOf("Microsoft.NETCore.App","Microsoft.AspNetCore.App"));val version=text(f,"version")
            require(version.matches(Regex("(?:[6-9]|10)\\.0\\.[0-9]{1,3}")))
            if(!version.startsWith("10.0.")) require(o["rollForward"]==JsonPrimitive("LatestMajor")) { "Unsupported framework roll-forward requirement" }
            else require(version.substringAfterLast('.').toInt()<=13)
            if(o["includedFrameworks"]!=null) require(version=="10.0.13")
        }
        o["rollForward"]?.let{require(it is JsonPrimitive&&it.isString&&it.content in setOf("LatestPatch","Minor","LatestMinor","Major","LatestMajor","Disable"))}
        o["applyPatches"]?.let{require(it==JsonPrimitive(true))}
        val properties=o["configProperties"]?.let{it as? JsonObject?:error("Invalid runtime properties")}.orEmpty()
        val booleans=setOf("System.Reflection.Metadata.MetadataUpdater.IsSupported","System.Runtime.Serialization.EnableUnsafeBinaryFormatterSerialization","System.Runtime.Serialization.EnableUnsafeBinaryFormatterInDesigntimeLicenseContextSerialization",
            "System.Runtime.TieredCompilation","System.Runtime.TieredPGO","System.Globalization.Invariant","System.Globalization.PredefinedCulturesOnly","System.GC.Server","System.GC.Concurrent")
        properties.forEach { (name,value) ->
            require(name in booleans&&value is JsonPrimitive&&!value.isString&&value.content in setOf("true","false")) { "Unsupported or competing GC/runtime setting: $name" }
            if(name=="System.GC.Server")require(value==JsonPrimitive(false)) { "Server GC competes with fixed workstation profile" }
            if(name=="System.Globalization.Invariant")require(value==JsonPrimitive(false))
        }
    }
    internal fun requireDeps(deps:JsonObject,path:String,files:Set<String>):Set<String> {
        require(deps.keys.all{it in setOf("runtimeTarget","compilationOptions","targets","libraries","runtimes")})
        val rt=deps["runtimeTarget"] as? JsonObject?:error("Missing deps runtime target")
        require(rt.keys.all{it in setOf("name","signature")});val targetName=text(rt,"name")
        require(targetName.matches(Regex("\\.NETCoreApp,Version=v(?:[6-9]|10)\\.0(?:/linux-arm64)?")))
        val targets=deps["targets"] as? JsonObject?:error("Missing dependency targets");require(targets.size in 1..2&&targetName in targets)
        require(targets.keys.all{it==targetName||it==targetName.substringBefore('/')})
        val libraries=deps["libraries"] as? JsonObject?:error("Missing dependency libraries");require(libraries.size in 1..1024)
        val base=path.substringBeforeLast('/', "")
        val allIds=targets.values.flatMap{(it as? JsonObject?:error("Invalid deps target")).keys}.toSet()
        require(allIds==libraries.keys)
        for((id,value)in libraries) {
            require(id.matches(Regex("[A-Za-z0-9_.+-]{1,160}/[0-9][A-Za-z0-9_.+-]{0,79}")))
            val lib=value as? JsonObject?:error("Invalid library");require(lib.keys.all{it in setOf("type","serviceable","sha512","path","hashPath")})
            require(text(lib,"type") in setOf("package","project","reference"))
            for(key in listOf("path","hashPath"))lib[key]?.let{LocalUbuntuCSharpArchive.requirePath(it.jsonPrimitive.content)}
        }
        for((_,target)in targets) for((_,element)in target.jsonObject) {
            val sections=element as? JsonObject?:error("Invalid dependency object")
            require(sections.keys.all{it in setOf("dependencies","runtime","native","resources","runtimeTargets","compile")})
            (sections["dependencies"] as? JsonObject)?.forEach{(id,v)->require("$id/${v.jsonPrimitive.content}" in allIds)}
            for(kind in listOf("runtime","native","resources","runtimeTargets")) {
                val assets=sections[kind]?:continue;require(assets is JsonObject&&assets.size<=4096)
                for((asset,metadata)in assets) {
                    LocalUbuntuCSharpArchive.requirePath(asset);require(metadata is JsonObject)
                    if(kind=="runtimeTargets") {
                        require(metadata.keys.all{it in setOf("rid","assetType","assemblyVersion","fileVersion")})
                        val rid=text(metadata,"rid");require(rid in setOf("linux-arm64","win","win-x64","win-x86","win-arm64"))
                        require(text(metadata,"assetType") in setOf("native","runtime"))
                        require("$base/$asset".removePrefix("/") in files) { "Original RID asset missing" }
                        continue
                    }
                    require(metadata.keys.all{it in setOf("assemblyVersion","fileVersion","locale")})
                    val relative=if(kind=="resources")asset else asset.substringAfterLast('/')
                    require("$base/$relative".removePrefix("/") in files) { "Dependency asset is absent from authenticated original tree: $asset" }
                }
            }
        }
        deps["compilationOptions"]?.let{require(it is JsonObject&&it.isEmpty())}
        deps["runtimes"]?.let{require(it is JsonObject&&it.size<=256);it.forEach{(rid,fallbacks)->require(rid.matches(Regex("[A-Za-z0-9_.-]{1,80}"))&&fallbacks is JsonArray&&fallbacks.size<=32&&fallbacks.all{v->v is JsonPrimitive&&v.isString&&v.content.matches(Regex("[A-Za-z0-9_.-]{1,80}"))})}}
        return allIds
    }
    internal fun requireToolSettings(raw:String) {
        require(raw.length in 1..65536&&!raw.contains("<!"))
        val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.isNamespaceAware=false;factory.isExpandEntityReferences=false
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        val doc=factory.newDocumentBuilder().parse(java.io.ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        val root=doc.documentElement;require(root.tagName=="DotNetCliTool"&&root.attributes.length==1&&root.getAttribute("Version")=="1")
        fun children(e:org.w3c.dom.Element)=(0 until e.childNodes.length).map{e.childNodes.item(it)}.filter{it.nodeType==org.w3c.dom.Node.ELEMENT_NODE}.map{it as org.w3c.dom.Element}
        val commands=children(root).single();require(commands.tagName=="Commands"&&commands.attributes.length==0)
        val command=children(commands).single();require(command.tagName=="Command"&&command.attributes.length==3&&children(command).isEmpty())
        require(command.getAttribute("Name")=="csharp-ls"&&command.getAttribute("EntryPoint")=="CSharpLanguageServer.dll"&&command.getAttribute("Runner")=="dotnet")
    }
    /** ECMA-335/PE metadata as bytes only. Unknown tables, native entry points and malformed indices stop admission. */
    internal fun assembly(file:File,active:()->Unit={}):Assembly {
        val before=NimRuntimeClosure.measure(file,536_870_912L,active)
        RandomAccessFile(file,"r").use { r ->
            fun read(offset:Long,size:Int):ByteArray { active();require(offset>=0&&size>=0&&offset<=r.length()&&size<=r.length()-offset);r.seek(offset);return ByteArray(size).also(r::readFully) }
            fun u16(b:ByteArray,i:Int)=(b[i].toInt()and 255) or ((b[i+1].toInt()and 255)shl 8)
            fun u32(b:ByteArray,i:Int)=u16(b,i).toLong() or (u16(b,i+2).toLong()shl 16)
            val dos=read(0,64);require(dos[0]==77.toByte()&&dos[1]==90.toByte());val pe=u32(dos,60);val h=read(pe,24)
            require(h.take(4)==listOf<Byte>(80,69,0,0));val sections=u16(h,6);require(sections in 1..96)
            val optional=u16(h,20);require(optional in 224..512);val o=read(pe+24,optional);val magic=u16(o,0)
            val directory=when(magic){0x10b->96;0x20b->112;else->error("Unknown PE optional header")}
            require(o.size>=directory+15*8&&u32(o,directory-4)>=15)
            data class Section(val address:Long,val bytes:Long,val raw:Long)
            val ss=(0 until sections).map { i -> val s=read(pe+24+optional+i*40L,40);Section(u32(s,12),u32(s,16),u32(s,20)) }
            fun map(address:Long,size:Long):Long {require(size>0);val s=ss.singleOrNull{address>=it.address&&address-it.address<=it.bytes&&size<=it.bytes-(address-it.address)}?:error("Managed metadata RVA not uniquely file-backed");return s.raw+address-s.address}
            val cli=read(map(u32(o,directory+14*8),72),72);require(u32(cli,0)>=72&&u32(cli,16)and 0x10L==0L)
            val metadataSize=u32(cli,12);require(metadataSize in 24..33_554_432)
            val m=read(map(u32(cli,8),metadataSize),metadataSize.toInt());require(u32(m,0)==0x424a5342L)
            val versionSize=u32(m,12).toInt();require(versionSize in 1..256);var at=(16+versionSize+3)and -4
            require(at+4<=m.size);val streamCount=u16(m,at+2);at+=4;require(streamCount in 1..16)
            val streams=linkedMapOf<String,Pair<Int,Int>>()
            repeat(streamCount) { require(at+8<=m.size);val start=u32(m,at).toInt();val size=u32(m,at+4).toInt();at+=8
                val end=(at until minOf(at+32,m.size)).firstOrNull{m[it]==0.toByte()}?:error("Invalid metadata stream")
                val name=m.copyOfRange(at,end).toString(Charsets.US_ASCII);at=(end+4)and -4
                require(start>=0&&size>=0&&start<=m.size&&size<=m.size-start&&streams.put(name,start to size)==null)
            }
            require(streams.keys.all{it in setOf("#~","#-","#Strings","#Blob","#GUID","#US","#Pdb")})
            require(("#~" in streams) xor ("#-" in streams))
            val ordered=streams.values.filter{it.second>0}.sortedBy{it.first};ordered.zipWithNext().forEach{(a,b)->require(a.first+a.second<=b.first)}
            val strings=streams["#Strings"]?:error("Missing managed strings")
            fun str(index:Int):String { require(index in 0 until strings.second);val start=strings.first+index
                val end=(start until minOf(start+4096,strings.first+strings.second)).firstOrNull{m[it]==0.toByte()}?:error("Unterminated metadata string")
                return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(m,start,end-start)).toString().also{require(it.none{c->c.code<32})} }
            val table=streams["#~"]?:streams.getValue("#-");val t=m.copyOfRange(table.first,table.first+table.second);require(t.size>=24)
            val heap=t[6].toInt()and 255;require(heap and 0xf8==0);val stringWidth=if(heap and 1==0)2 else 4;val guidWidth=if(heap and 2==0)2 else 4;val blobWidth=if(heap and 4==0)2 else 4
            val valid=ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).getLong(8);require(valid ushr 45==0L)
            val counts=IntArray(45);var cursor=24
            for(i in 0..44)if(valid and (1L shl i)!=0L){require(cursor+4<=t.size);val n=u32(t,cursor);require(n<=1_000_000);counts[i]=n.toInt();cursor+=4}
            fun ix(i:Int)=if(counts[i]<65536)2 else 4
            fun coded(bits:Int,vararg tables:Int)=if(tables.maxOf{counts[it]}<(1 shl(16-bits)))2 else 4
            val td=coded(2,2,1,27);val resolution=coded(2,0,26,35,1);val constant=coded(2,4,8,23)
            val custom=coded(5,6,4,1,2,8,9,10,0,14,23,20,17,26,27,32,35,38,39,40,42,44,43)
            val member=coded(3,2,1,26,6,27);val semantics=coded(1,20,23);val method=coded(1,6,10)
            val rows=intArrayOf(2+stringWidth+guidWidth*3,resolution+stringWidth*2,4+stringWidth*2+td+ix(4)+ix(6),ix(4),2+stringWidth+blobWidth,ix(6),8+stringWidth+blobWidth+ix(8),ix(8),4+stringWidth,ix(2)+td,member+stringWidth+blobWidth,2+constant+blobWidth,custom+coded(3,6,10)+blobWidth,coded(1,4,8)+blobWidth,2+coded(2,2,6,32)+blobWidth,6+ix(2),4+ix(4),blobWidth,ix(2)+ix(20),ix(20),2+stringWidth+td,ix(2)+ix(23),ix(23),2+stringWidth+blobWidth,2+ix(6)+semantics,ix(2)+method*2,stringWidth,blobWidth,2+coded(1,4,6)+stringWidth+ix(26),4+ix(4),8,4,16+blobWidth+stringWidth*2,4,12,12+blobWidth*2+stringWidth*2,4+ix(35),12+ix(35),4+stringWidth+blobWidth,8+stringWidth*2+coded(2,38,35,39),8+stringWidth+coded(2,38,35,39),ix(2)*2,4+coded(1,2,6)+stringWidth,method+blobWidth,ix(42)+td)
            val offsets=IntArray(45)
            for(i in 0..44){offsets[i]=cursor;val size=counts[i].toLong()*rows[i];require(size<=t.size-cursor);cursor+=size.toInt()}
            require(counts[32]==1)
            fun index(offset:Int,width:Int):Int { val n=if(width==2)u16(t,offset).toLong() else u32(t,offset);require(n<=Int.MAX_VALUE);return n.toInt() }
            fun version(offset:Int)=(0..3).joinToString("."){u16(t,offset+it*2).toString()}
            val blobs=streams["#Blob"]?:error("Missing managed blob heap")
            fun blob(index:Int):ByteArray {
                if(index==0)return ByteArray(0)
                require(index in 1 until blobs.second);var at=blobs.first+index
                fun byte():Int {require(at<blobs.first+blobs.second);return m[at++].toInt()and 255}
                val first=byte();val size=when {first and 128==0->first;first and 192==128->((first and 63)shl 8)or byte();first and 224==192->((first and 31)shl 24)or(byte()shl 16)or(byte()shl 8)or byte();else->error("Invalid managed blob length")}
                require(size in 0..16384&&size<=blobs.first+blobs.second-at)
                return m.copyOfRange(at,at+size)
            }
            fun token(bytes:ByteArray,full:Boolean):String {
                if(bytes.isEmpty())return ""
                require(if(full)bytes.size in 16..16384 else bytes.size==8)
                // ECMA identity's SHA-1 public-key token is data, never an artifact authentication primitive.
                val value=if(full)java.security.MessageDigest.getInstance("SHA-1").digest(bytes).takeLast(8).reversed().toByteArray()else bytes
                return value.joinToString(""){"%02x".format(it.toInt()and 255)}
            }
            fun culture(value:String)=value.also{require(it.isEmpty()||it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,2}")))}
            val a=offsets[32];val name=str(index(a+16+blobWidth,stringWidth));require(name.matches(Regex("[A-Za-z0-9_.+-]{1,180}")))
            val flags=u32(t,a+12);require(flags and 0xffff3efeL==0L)
            val publicKey=token(blob(index(a+16,blobWidth)),flags and 1L!=0L)
            val ownCulture=culture(str(index(a+16+blobWidth+stringWidth,stringWidth)))
            val refs=linkedMapOf<String,String>();val keys=linkedMapOf<String,String>();val cultures=linkedMapOf<String,String>()
            repeat(counts[35]) { val x=offsets[35]+it*rows[35];val ref=str(index(x+12+blobWidth,stringWidth));require(ref.matches(Regex("[A-Za-z0-9_.+-]{1,180}")))
                require(refs.put(ref,version(x))==null)
                val flag=u32(t,x+8);require(flag and 0xffff3efeL==0L)
                keys[ref]=token(blob(index(x+12,blobWidth)),flag and 1L!=0L)
                cultures[ref]=culture(str(index(x+12+blobWidth+stringWidth,stringWidth)))
            }
            val modules=linkedSetOf<String>();repeat(counts[26]){val name=str(index(offsets[26]+it*rows[26],stringWidth));require(name.matches(Regex("[A-Za-z0-9_.+-]{1,180}")));modules+=name}
            require(NimRuntimeClosure.measure(file,536_870_912L,active)==before)
            return Assembly(name,version(a+4),refs.toMap(),modules.toSet(),publicKey,ownCulture,keys.toMap(),cultures.toMap())
        }
    }

    fun requireManagedClosure(inventories:List<Inventory>,active:()->Unit={}):Map<String,String> {
        require(inventories.size==3&&inventories.map{it.recipe.familyId}.toSet()==LocalUbuntuCSharpPolicy.families)
        val tool=inventories.single{it.recipe.role==CSharpArtifactRole.MANAGED_NUPKG};val sdk=inventories.single{it.recipe.role==CSharpArtifactRole.ORIGINAL_SDK}
        val runtimePrefix="shared/Microsoft.NETCore.App/10.0.13/"
        val runtime=sdk.members.filter{it.kind=="file"&&it.path.startsWith(runtimePrefix)&&it.path.removePrefix(runtimePrefix).indexOf('/')<0&&it.path.endsWith(".dll")}
            .associate{member->member.path to assembly(File(sdk.root,member.path),active)}
        val all=tool.assemblies+runtime
        require(all.size<=4096)
        val runtimeByName=runtime.values.associateBy{it.name};require(runtimeByName.size==runtime.size)
        val sdkBuildNames=setOf("Microsoft.Build","Microsoft.Build.Framework","Microsoft.Build.Tasks.Core","Microsoft.Build.Utilities.Core")
        val sdkBuild=sdkBuildNames.associateWith { name -> assembly(File(sdk.root,"sdk/10.0.402/$name.dll"),active).also { require(it.name==name) } }
        for((path,a)in tool.assemblies) {
            val directory=path.substringBeforeLast('/')
            val bindingDirectory=if(a.culture.isEmpty())directory else {
                require(a.name.endsWith(".resources")&&directory.substringAfterLast('/')==a.culture) { "Unreviewed satellite assembly location" }
                directory.substringBeforeLast('/')
            }
            val localCandidates=tool.assemblies.filterKeys{it.substringBeforeLast('/')==bindingDirectory}.values
            val local=localCandidates.associateBy{it.name};require(local.size==localCandidates.size)
            for((name,version)in a.references) {
                val sdkLoaded=name in sdkBuildNames && (path==LocalUbuntuCSharpPolicy.BUILDHOST || a.name in setOf("CSharpLanguageServer","Microsoft.CodeAnalysis.Workspaces.MSBuild","Microsoft.CodeAnalysis.Workspaces.MSBuild.Contracts"))
                val match=if(sdkLoaded) sdkBuild.getValue(name) else local[name]?:runtimeByName[name]?:error("Managed reference unresolved: ${a.name} -> $name/$version")
                requireBinding(name,version,match.version,sdkLoaded, name !in local && name in runtimeByName)
                requireReferenceIdentity(a,name,match)

            }
            require(a.nativeModules.all{it in setOf("libc","libc.so.6","libdl","libdl.so.2","libpthread","libpthread.so.0")||it in nativeNames||"lib$it.so" in nativeNames}) { "Unsupported managed P/Invoke module in ${a.name}" }
        }
        // Only explicit complete component sections count; nearby unrelated license words never
        // establish attribution.
        val noticeFiles=(tool.notices.mapKeys{tool.root to it.key}+sdk.notices.mapKeys{sdk.root to it.key})
        val records=linkedMapOf<String,String>()
        for((key,hash)in noticeFiles) {
            val raw=NimRuntimeClosure.readText(File(key.first,key.second),8_388_608,active)
            for((id,license)in noticeSections(raw)) {
                val evidence=NimRuntimeClosure.sha256("${key.second}\u0000$hash\u0000$license")
                require(records.put(id,evidence)==null) { "Ambiguous duplicated component attribution: $id" }
            }
        }
        val proof=linkedMapOf<String,String>()
        for(id in tool.dependencies.sorted()) proof[id]=records[id]?:records[id.substringBefore('/')]?:error("Complete shipped component notice is unresolved: $id")
        return proof.toMap()
    }
    internal fun requireReferenceIdentity(requester:Assembly,name:String,provider:Assembly) {
        require(requester.referenceKeys.getValue(name)==provider.publicKeyToken&&requester.referenceCultures.getValue(name)==provider.culture) { "Managed reference strong-name/culture mismatch: ${requester.name} -> $name" }
    }
    /** Reviewed MSBuildLocator API binding is not a claim of semantic compatibility. */

    internal fun requireBinding(name:String,requested:String,provided:String,sdkLoaded:Boolean,runtimeFramework:Boolean) {
        fun parts(v:String)=v.split('.').also{require(it.size==4&&it.all{p->p.matches(Regex("[0-9]{1,5}"))})}.map(String::toInt)
        val req=parts(requested);val actual=parts(provided)
        val nondecreasing=actual.zip(req).firstOrNull{it.first!=it.second}?.let{it.first>it.second}!=false
        when {
            sdkLoaded -> require(name in setOf("Microsoft.Build","Microsoft.Build.Framework","Microsoft.Build.Tasks.Core","Microsoft.Build.Utilities.Core") && req[0] in setOf(15,16,17,18) && actual[0] in setOf(15,16,17,18) && nondecreasing) { "Unreviewed SDK MSBuild binding" }
            runtimeFramework -> require((name.startsWith("System.")||name in setOf("System","netstandard","mscorlib","Microsoft.CSharp","Microsoft.VisualBasic","Microsoft.VisualBasic.Core")) && req[0] in 4..10 && actual[0] in req[0]..10 && nondecreasing) { "Unsupported reviewed framework roll-forward binding" }
            else -> require(requested==provided) { "Managed non-framework reference version mismatch: $name/$requested" }
        }
    }
    /** Exact component headings delimit their own notice text. */

    internal fun noticeSections(raw:String):Map<String,String> {
        require(raw.length<=8_388_608)
        val lines=raw.lines();val starts=lines.indices.filter { lines[it].startsWith("License notice for ") }
        require(starts.size<=1024);val out=linkedMapOf<String,String>()
        for((index,start)in starts.withIndex()) {
            val name=lines[start].removePrefix("License notice for ").trim()
            require(name.length in 1..240&&name.none{it.code<32}&&start+1<lines.size&&lines[start+1].matches(Regex("-{3,120}")))
            val end=starts.getOrNull(index+1)?:lines.size
            val terms=lines.subList(start+2,end).joinToString("\n").trim()
            require(terms.length<=262144)
            val normalized=terms.replace(Regex("\\s+")," ").trim()
            val mit=normalized.indexOf("Permission is hereby granted")
            val apache=normalized.indexOf("Apache License")
            val licenseEnd=normalized.indexOf("END OF TERMS AND CONDITIONS",apache.coerceAtLeast(0))
            // Exact complete normalized license bodies, not keywords or adjacent-component proximity. Body
            // fingerprints are local legal-template hashes, never publisher archive authentication.
            val bodyStart=listOf(mit,apache).filter{it>=0}.minOrNull()?:0
            val attribution=normalized.substring(0,bodyStart)
            val attributed=Regex("(?i)Copyright(?: \\(c\\)| ©)? +[A-Za-z0-9][^<>]{3,512}").containsMatchIn(attribution)
            val complete=attributed&&(
                mit>=0&&NimRuntimeClosure.sha256(normalized.substring(mit))=="fe2a9817987f862eaced948f0468c7f51d2fedfc48c5c505b246a49a3870e9a5" ||
                apache>=0&&licenseEnd>apache&&NimRuntimeClosure.sha256(normalized.substring(apache,licenseEnd+"END OF TERMS AND CONDITIONS".length))=="59d8f0ba87ad9a2f1a431123c8d16646e5b89ba53653e818f16d136d77263c99"
            )
            if(complete)require(out.put(name,NimRuntimeClosure.sha256(terms))==null) { "Duplicate component notice heading: $name" }
        }
        return out.toMap()
    }

    internal fun launchShells(guestRoot:File,active:()->Unit={}):Map<File,NimRuntimeClosure.NativeImage> = listOf("/bin/sh","/bin/bash","/usr/bin/env").associate { path ->
        val file=requireNotNull(NimRuntimeClosure.resolveGuestFile(guestRoot,path,active)) { "C# launch command is missing: $path" }
        file to NimRuntimeClosure.inspectElf(file,path,NimRuntimeClosure.NativeRole.EXECUTABLE,active).also { require(!it.originSearch) }
    }.also { require(it.size==3) { "C# env, bash and sh must have distinct original paths" } }

    /** C# native closure includes original-local providers plus bounded actual guest ELF/symbol/version evidence. */
    fun guestClosure(guestRoot:File,inventories:List<Inventory>,active:()->Unit={}):NimRuntimeClosure.Closure {
        require(inventories.size in 1..3)
        val helper=inventories.firstOrNull{it.recipe.role==CSharpArtifactRole.PRIVATE_NODE_HELPER}
        // Shared parser is pure data validation; this does not borrow Nim receipt/process/health
        // authority.
        val bootstrap=helper?.let{LocalUbuntuCSharpHelperPolicy.requireTree(File(it.root,LocalUbuntuCSharpHelperPolicy.ENTRY),active)}
        val native=inventories.flatMap{inv->inv.native.values.map{inv to it}}
        require(native.size<=512)
        val guest=linkedMapOf<String,NimRuntimeClosure.NativeImage>();val hashes=linkedMapOf<String,String>()
        if(bootstrap!=null)hashes.putAll(NimRuntimeClosure.requireExecutionClosure(listOf(bootstrap),guestRoot,emptySet(),active).files)
        fun resolveGuest(name:String):NimRuntimeClosure.NativeImage {
            require(name in guestNames);guest[name]?.let{return it};require(guest.size<64)
            val candidates=(if(name=="ld-linux-aarch64.so.1")listOf("/lib/ld-linux-aarch64.so.1","/usr/lib/aarch64-linux-gnu/$name","/lib/aarch64-linux-gnu/$name") else listOf("/usr/lib/aarch64-linux-gnu/$name","/lib/aarch64-linux-gnu/$name"))
                .mapNotNull{NimRuntimeClosure.resolveGuestFile(guestRoot,it,active)}.distinctBy{it.absolutePath}
            val file=candidates.singleOrNull()?:error("C# guest dependency missing or ambiguous: $name")
            val role=when(name){"ld-linux-aarch64.so.1"->NimRuntimeClosure.NativeRole.GUEST_LOADER;"libc.so.6"->NimRuntimeClosure.NativeRole.GUEST_LIBC;else->NimRuntimeClosure.NativeRole.SHARED_LIBRARY}
            val image=NimRuntimeClosure.inspectElf(file,name,role,active);require(image.soname==name&&!image.originSearch)
            hashes[file.relativeTo(guestRoot).invariantSeparatorsPath]=NimRuntimeClosure.measure(file,536_870_912,active).second
            guest[name]=image;return image
        }
        fun resolve(image:NimRuntimeClosure.NativeImage,name:String):NimRuntimeClosure.NativeImage {
            if(name in guestNames)return resolveGuest(name)
            require(name in nativeNames)
            val candidates=native.filter{(_,n)->n.relativePath.substringAfterLast('/')==name}
            val sibling=candidates.filter{(_,n)->n.relativePath.substringBeforeLast('/')==image.relativePath.substringBeforeLast('/')}
            return (sibling.singleOrNull()?:candidates.singleOrNull())?.second?:error("Ambiguous/missing original C# native provider: $name")
        }
        val visited=linkedMapOf<String,NimRuntimeClosure.NativeImage>()
        fun visit(image:NimRuntimeClosure.NativeImage) {
            active();if(visited.putIfAbsent(image.relativePath,image)!=null)return;require(visited.size<=576)
            require(image.versionNeeds.keys.all{it in image.needed})
            for(name in image.needed) { val provider=resolve(image,name);require(provider.versionDefinitions.containsAll(image.versionNeeds[name].orEmpty()));visit(provider) }
        }
        native.forEach{visit(it.second)};visit(resolveGuest("ld-linux-aarch64.so.1"))
        if(inventories.any{it.recipe.role==CSharpArtifactRole.ORIGINAL_SDK})dynamicGuest.forEach{visit(resolveGuest(it))}
        val shells=launchShells(guestRoot,active)
        for((file,shell)in shells) {
            hashes[file.relativeTo(guestRoot).invariantSeparatorsPath]=NimRuntimeClosure.measure(file,16_777_216,active).second
            visit(shell)
        }
        for(image in visited.values) {
            val providers=linkedMapOf<String,NimRuntimeClosure.NativeImage>()
            fun add(n:NimRuntimeClosure.NativeImage){if(providers.putIfAbsent(n.relativePath,n)==null)n.needed.forEach{add(resolve(n,it))}}
            add(image)
            for(symbol in image.imports.filterNot{it.weak})require(providers.values.any{provider->provider.exports.any{export->export.name==symbol.name&&(if(symbol.version==null)export.defaultVersion else export.version==symbol.version)&&(symbol.type==0||symbol.type==export.type||symbol.type==2&&export.type==10)}}){"Unresolved C# native import ${image.relativePath}:${symbol.name}"}
        }
        // SDK-only proof has no helper bootstrap: obtain identical data-only loader/cache checks via the
        // shell inventory.
        if(bootstrap==null) {
            val inventory=NimRuntimeClosure.Inventory(guestRoot,emptySet(),shells.values.associateBy{it.relativePath},emptyMap(),emptyMap())
            hashes.putAll(NimRuntimeClosure.requireExecutionClosure(listOf(inventory),guestRoot,emptySet(),active).files)
        }
        val material=hashes.toSortedMap().entries.joinToString("\n"){"${it.key}\u0000${it.value}"}
        return NimRuntimeClosure.Closure(hashes.toMap(),NimRuntimeClosure.sha256(material))
    }
}
