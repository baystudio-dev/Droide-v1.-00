package com.baystudio.droide.core
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

/** Authored ARM64 ELF data; never native executable fixtures. */
class NimRuntimeClosureTest {
    private val p get()=NimRuntimeClosure
    private val fixtures=LocalUbuntuPowerShellRuntimePolicyTest()
    private fun reject(block:()->Unit)=assertTrue(runCatching(block).isFailure)
    private fun temporary(block:(File)->Unit){val d=Files.createTempDirectory("nim-closure").toFile();try{block(d)}finally{d.deleteRecursively()}}
    private fun server(root:File,needed:List<String> = listOf("libc.so.6")):NimRuntimeClosure.Inventory {
        root.mkdirs();File(root,"nimlangserver").writeBytes(fixtures.elf(true,needed=needed));return LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver"))
    }
    private fun guest(root:File){val libs=File(root,"usr/lib/aarch64-linux-gnu").apply{mkdirs()};Files.createSymbolicLink(File(root,"lib").toPath(),Path.of("usr/lib"));Files.createSymbolicLink(File(root,"usr/lib/ld-linux-aarch64.so.1").toPath(),Path.of("aarch64-linux-gnu/ld-linux-aarch64.so.1"));for(name in listOf("libc.so.6","ld-linux-aarch64.so.1","libssl.so.3","libcrypto.so.3","libpcre.so.3"))File(libs,name).writeBytes(symbolElf(name=="libc.so.6",soname=name,exports=p.ordinaryMandatoryExports[name].orEmpty().toList()));File(root,"usr/bin").mkdirs();File(root,"usr/bin/dash").writeBytes(symbolElf(true,needed=listOf("libc.so.6")));Files.createSymbolicLink(File(root,"bin").toPath(),Path.of("usr/bin"));Files.createSymbolicLink(File(root,"usr/bin/sh").toPath(),Path.of("dash"))}
    @Test fun exactServerEnvelopeAndHiddenNativeRejected()=temporary { root->
        val inv=server(root);assertEquals(setOf("nimlangserver"),inv.files)
        File(root,"extra").writeText("notice");reject{LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver"))};File(root,"extra").delete()
        File(root,"nimlangserver").writeBytes(fixtures.elf(false));reject{LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver"))}
    }
    @Test fun libraryClosureHashesActualBytesAndRequiresSourceLoads()=temporary { root->
        val inv=server(File(root,"server"));val guest=File(root,"guest");guest(guest)
        val loads=setOf("libssl.so.3","libcrypto.so.3","libpcre.so.3")
        val a=p.requireExecutionClosure(listOf(inv),guest,loads);assertEquals(6,a.files.size)
        File(guest,"usr/lib/aarch64-linux-gnu/libpcre.so.3").delete();reject{p.requireExecutionClosure(listOf(inv),guest,loads)}
        assertEquals(3,p.requireExecutionClosure(listOf(inv),guest).files.size)
    }
    @Test fun guestRedirectPreloadUnknownArchitectureAndMissingEdgesRefused()=temporary { root->
        val inv=server(File(root,"server"));val g=File(root,"guest");guest(g)
        val etc=File(g,"etc").apply{mkdir()};File(etc,"ld.so.preload").writeText("/tmp/evil.so");reject{p.requireExecutionClosure(listOf(inv),g)};File(etc,"ld.so.preload").delete()
        val libc=File(g,"usr/lib/aarch64-linux-gnu/libc.so.6");val bytes=libc.readBytes();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(18,62);libc.writeBytes(bytes);reject{p.requireExecutionClosure(listOf(inv),g)}
    }
    @Test fun unknownNativeLoaderHookAndSourceTreeLinkRefused()=temporary { root->
        val f=File(root,"nimlangserver");val bytes=fixtures.elf(true);ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(0x320,0x6ffffefbL);f.writeBytes(bytes);reject{LocalUbuntuNimPolicy.requireTree(f)}
        f.delete();File(root,"data").writeText("data");Files.createSymbolicLink(f.toPath(),Path.of("data"));reject{LocalUbuntuNimPolicy.requireTree(f)}
    }
    @Test fun helperLinksRemainInOriginalTopOnly()=temporary { root->
        val top=File(root,"node-v22.23.3-linux-arm64").apply{mkdir()};File(top,"bin").mkdir();File(top,"bin/node").writeBytes(fixtures.elf(true));File(top,"data").writeText("data")
        Files.createSymbolicLink(File(top,"bin/npm").toPath(),Path.of("../data"))
        val inv=p.inspectTree(root,setOf("node-v22.23.3-linux-arm64/bin/node"),emptySet(),20,100000,top.name,true);assertEquals(1,inv.links.size)
        File(top,"bin/npm").delete();Files.createSymbolicLink(File(top,"bin/npm").toPath(),Path.of("../../../outside"));reject{p.inspectTree(root,setOf("node-v22.23.3-linux-arm64/bin/node"),emptySet(),20,100000,top.name,true)}
    }
    @Test fun defaultLoaderIdentityNeedsPositiveLoadSegmentEvidence()=temporary { root->
        val suffix="(.3|.1.1|.1.0.2|.1.0.1|.1.0.0|.0.9.9|.0.9.8|.48|.47|.46|.45|.44|.43|.41|.39|.38|.10|)"
        fun evidence(vararg strings:String):Map<String,String> {
            val bytes=fixtures.elf(true);var at=0xa00
            for(value in strings) { value.toByteArray().copyInto(bytes,at);at+=value.length+1 }
            File(root,"nimlangserver").writeBytes(bytes)
            val inv=LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver"))
            return p.requireDefaultLoaderEvidence(listOf(inv))
        }
        assertEquals(2,evidence("libssl.so$suffix","libcrypto.so$suffix").size)
        assertEquals(2,evidence("libssl.so.3","libcrypto.so.3").size)
        reject{evidence()};reject{evidence("libssl.so.3")}
        reject{evidence("libssl.so.1.1","libcrypto.so.1.1")}
        reject{evidence("libssl.so$suffix","libssl.so.3","libcrypto.so.3")}
        reject{evidence("libssl.so$suffix","libcrypto.so.3")}
        reject{evidence("libssl.so.99","libcrypto.so.99")}
        val outside=fixtures.elf(true)+"libssl.so.3\u0000libcrypto.so.3\u0000".toByteArray()
        File(root,"nimlangserver").writeBytes(outside)
        reject{p.requireDefaultLoaderEvidence(listOf(LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver"))))}
    }
    @Test fun boundedWorkAndCancellationAreEnforced()=temporary { root->
        server(root);reject{LocalUbuntuNimPolicy.requireTree(File(root,"nimlangserver")){error("cancel")}}
        reject{p.inspectTree(root,setOf("nimlangserver"),emptySet(),1,1)}
    }
    /** Authored symbol-bearing ELF data with real bounded loader hash chains; never executed. */
    internal fun symbolElf(executable:Boolean,soname:String?=null,needed:List<String> = emptyList(),exports:List<String> = emptyList(),imports:List<String> = emptyList(),gnu:Boolean=false,versionNeed:Pair<String,String>?=null,versionDefinition:String?=null):ByteArray {
        val bytes=fixtures.elf(executable,soname,needed,versionNeed=versionNeed,versionDefinition=versionDefinition).copyOf(32768);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val base=0x10000L
        b.putLong(64+32,bytes.size.toLong());b.putLong(64+40,bytes.size.toLong())
        val tags=mutableListOf<Pair<Long,Long>>();var at=0x300
        while(b.getLong(at)!=0L){tags+=b.getLong(at) to b.getLong(at+8);at+=16}
        val oldSize=tags.single{it.first==10L}.second.toInt();val strings=java.io.ByteArrayOutputStream();strings.write(bytes,0x600,oldSize)
        val names=listOf("")+imports+exports;val offsets=names.map { if(it.isEmpty())0 else strings.size().also{_->strings.write(it.toByteArray());strings.write(0)} }
        for(i in 1 until names.size){val a=0x2000+i*24;b.putInt(a,offsets[i]);b.put(a+4,0x12);b.putShort(a+6,if(i<=imports.size)0 else 1);b.putLong(a+8,if(i<=imports.size)0 else base+0x280);b.putLong(a+16,if(i<=imports.size)0 else 4)}
        tags.removeAll{it.first==5L||it.first==10L};tags+=5L to base+0x4000;tags+=10L to strings.size().toLong();tags+=6L to base+0x2000;tags+=11L to 24L
        if(gnu){
            tags+=0x6ffffef5L to base+0x1000;b.putInt(0x1000,1);b.putInt(0x1004,imports.size+1);b.putInt(0x1008,1);b.putInt(0x100c,5);b.putLong(0x1010,-1)
            b.putInt(0x1018,if(exports.isEmpty())0 else imports.size+1)
            exports.forEachIndexed { i,name -> var h=5381L;for(c in name)h=((h shl 5)+h+c.code) and 0xffffffffL;b.putInt(0x101c+i*4,((h and 0xfffffffeL) or if(i==exports.lastIndex)1 else 0).toInt()) }
        } else {
            tags+=4L to base+0x1000;b.putInt(0x1000,1);b.putInt(0x1004,names.size);b.putInt(0x1008,if(names.size>1)1 else 0)
            for(i in names.indices)b.putInt(0x100c+i*4,if(i>0 && i<names.lastIndex)i+1 else 0)
        }
        if(versionNeed!=null || versionDefinition!=null) {
            if(versionNeed!=null)b.putShort(0x816,2)
            if(versionDefinition!=null)b.putShort(0x904,2)
            tags+=0x6ffffff0L to base+0x1800
            for(i in 1 until names.size)b.putShort(0x1800+i*2,2)
        }
        tags+=0L to 0L;bytes.fill(0,0x300,0x600);tags.forEachIndexed{i,t->b.putLong(0x300+i*16,t.first);b.putLong(0x308+i*16,t.second)}
        b.putLong(64+56+32,tags.size*16L);b.putLong(64+56+40,tags.size*16L);strings.toByteArray().copyInto(bytes,0x4000);return bytes
    }
    @Test fun builtInExportChecksRejectEmptyHiddenUndefinedAndUnhashedProviders()=temporary { root ->
        val inv=server(File(root,"server"));val g=File(root,"guest");guest(g);val loads=p.ordinaryMandatoryExports.keys
        val ssl=File(g,"usr/lib/aarch64-linux-gnu/libssl.so.3");val good=ssl.readBytes()
        ssl.writeBytes(fixtures.elf(false,soname="libssl.so.3"));reject{p.requireExecutionClosure(listOf(inv),g,loads)}
        for(change in listOf<(ByteBuffer)->Unit>({it.put(0x201d,2)},{it.putShort(0x201e,0)},{it.putInt(0x1008,0)})){
            val bad=good.copyOf();change(ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN));ssl.writeBytes(bad);reject{p.requireExecutionClosure(listOf(inv),g,loads)}
        }
        ssl.writeBytes(good);p.requireExecutionClosure(listOf(inv),g,loads)
        File(g,"usr/bin/dash").delete();reject{p.requireExecutionClosure(listOf(inv),g,loads)}
    }
    @Test fun observedDirectWrapperAndStrongElfImportsRequireActualExports()=temporary { root ->
        val d=File(root,"server").apply{mkdir()};val g=File(root,"guest");guest(g)
        var bytes=symbolElf(true,needed=listOf("libc.so.6"),imports=listOf("fixture_call"));File(d,"nimlangserver").writeBytes(bytes)
        var inv=LocalUbuntuNimPolicy.requireTree(File(d,"nimlangserver"));reject{p.requireExecutionClosure(listOf(inv),g)}
        File(g,"usr/lib/aarch64-linux-gnu/libc.so.6").writeBytes(symbolElf(true,soname="libc.so.6",exports=listOf("fixture_call")))
        p.requireExecutionClosure(listOf(inv),g)
        bytes=fixtures.elf(true,needed=listOf("libc.so.6"));"SSL_shutdown\u0000".toByteArray().copyInto(bytes,0xa00);File(d,"nimlangserver").writeBytes(bytes)
        inv=LocalUbuntuNimPolicy.requireTree(File(d,"nimlangserver"));reject{p.requireExecutionClosure(listOf(inv),g,p.ordinaryMandatoryExports.keys)}
        File(g,"usr/lib/aarch64-linux-gnu/libssl.so.3").writeBytes(symbolElf(false,soname="libssl.so.3",exports=p.ordinaryMandatoryExports.getValue("libssl.so.3").toList()+"SSL_shutdown"))
        p.requireExecutionClosure(listOf(inv),g,p.ordinaryMandatoryExports.keys)
    }
    @Test fun gnuHashLookupAndSymbolBoundsAreVerified()=temporary { root ->
        val file=File(root,"provider");val bytes=symbolElf(false,soname="libssl.so.3",exports=listOf("SSL_new","SSL_free"),gnu=true)
        file.writeBytes(bytes);assertEquals(2,p.inspectElf(file,"libssl.so.3",NimRuntimeClosure.NativeRole.SHARED_LIBRARY).exports.size)
        val bad=bytes.copyOf();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putLong(0x1010,0);file.writeBytes(bad)
        reject{p.inspectElf(file,"libssl.so.3",NimRuntimeClosure.NativeRole.SHARED_LIBRARY)}
        file.writeBytes(bytes.copyOf(0x2010));reject{p.inspectElf(file,"libssl.so.3",NimRuntimeClosure.NativeRole.SHARED_LIBRARY)}
    }

    @Test fun symbolVersionLabelsDoNotSubstituteForMatchingExportedFunction()=temporary { root ->
        val d=File(root,"server").apply{mkdir()};val g=File(root,"guest");guest(g)
        File(d,"nimlangserver").writeBytes(symbolElf(true,needed=listOf("libc.so.6"),imports=listOf("fixture_call"),versionNeed="libc.so.6" to "FIXTURE_1"))
        val inv=LocalUbuntuNimPolicy.requireTree(File(d,"nimlangserver"));val libc=File(g,"usr/lib/aarch64-linux-gnu/libc.so.6")
        libc.writeBytes(symbolElf(true,soname="libc.so.6",exports=listOf("other_call"),versionDefinition="FIXTURE_1"));reject{p.requireExecutionClosure(listOf(inv),g)}
        libc.writeBytes(symbolElf(true,soname="libc.so.6",exports=listOf("fixture_call"),versionDefinition="FIXTURE_2"));reject{p.requireExecutionClosure(listOf(inv),g)}
        libc.writeBytes(symbolElf(true,soname="libc.so.6",exports=listOf("fixture_call"),versionDefinition="FIXTURE_1"));p.requireExecutionClosure(listOf(inv),g)
    }

    @Test fun gnuLookupCannotFallBackToValidSysvTable()=temporary { root ->
        val file=File(root,"provider");val bytes=symbolElf(false,soname="libssl.so.3",exports=listOf("SSL_new","SSL_free"),gnu=true)
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);var at=0x300
        while(b.getLong(at)!=0L)at+=16
        b.putLong(at,4);b.putLong(at+8,0x11200);b.putLong(at+16,0);b.putLong(at+24,0)
        val size=at+32-0x300;b.putLong(64+56+32,size.toLong());b.putLong(64+56+40,size.toLong())
        b.putInt(0x1200,1);b.putInt(0x1204,3);b.putInt(0x1208,1);b.putInt(0x120c,0);b.putInt(0x1210,2);b.putInt(0x1214,0)
        file.writeBytes(bytes);assertEquals(2,p.inspectElf(file,"libssl.so.3",NimRuntimeClosure.NativeRole.SHARED_LIBRARY).exports.size)
        b.putLong(0x1010,0);file.writeBytes(bytes);reject{p.inspectElf(file,"libssl.so.3",NimRuntimeClosure.NativeRole.SHARED_LIBRARY)}
    }

}
