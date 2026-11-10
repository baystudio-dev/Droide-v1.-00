package com.baystudio.droide.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class CSharpRuntimeClosureTest {
    @Test fun runtimeConfigRejectsGcOverrideProbingAndUnknownSchema() {
        val valid="""{"runtimeOptions":{"tfm":"net10.0","framework":{"name":"Microsoft.NETCore.App","version":"10.0.0"},"configProperties":{"System.GC.Server":false}}}"""
        CSharpRuntimeClosure.requireRuntimeConfig(Json.parseToJsonElement(valid).jsonObject)
        for(bad in listOf(valid.replace("false","true"),valid.replace("System.GC.Server","System.GC.HeapHardLimitSOH"),valid.replace("\"tfm\":","\"additionalProbingPaths\":[\"/tmp\"],\"tfm\":"),valid.replace("10.0.0","11.0.0")))reject{CSharpRuntimeClosure.requireRuntimeConfig(Json.parseToJsonElement(bad).jsonObject)}
        val old=valid.replace("net10.0","net8.0").replace("10.0.0","8.0.0")
        reject{CSharpRuntimeClosure.requireRuntimeConfig(Json.parseToJsonElement(old).jsonObject)}
        CSharpRuntimeClosure.requireRuntimeConfig(Json.parseToJsonElement(old.replace("\"tfm\":","\"rollForward\":\"LatestMajor\",\"tfm\":")).jsonObject)
    }
    @Test fun toolSettingsIsExactDataOnlyXml() {
        val good="""<DotNetCliTool Version="1"><Commands><Command Name="csharp-ls" EntryPoint="CSharpLanguageServer.dll" Runner="dotnet" /></Commands></DotNetCliTool>"""
        CSharpRuntimeClosure.requireToolSettings(good)
        for(bad in listOf(good.replace("dotnet","bash"),good.replace("CSharpLanguageServer.dll","../../x"),"<!DOCTYPE x>"+good))reject{CSharpRuntimeClosure.requireToolSettings(bad)}
    }
    @Test fun noticeCannotBorrowAnAdjacentComponentsLicense() {
        val terms="""Copyright (c) Authored Fixture
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""
        val raw="License notice for First/1.0.0\n--------------------\nOnly a link to somewhere else.\n\nLicense notice for Second/2.0.0\n--------------------\n$terms"
        val notices=CSharpRuntimeClosure.noticeSections(raw)
        assertFalse("First/1.0.0" in notices);assertTrue("Second/2.0.0" in notices)
        assertFalse("Second" in notices)
        assertTrue(CSharpRuntimeClosure.noticeSections(raw.replace("The above copyright notice and this permission notice shall be included in all", "Abridged" )).isEmpty())
        assertTrue(CSharpRuntimeClosure.noticeSections(raw.replace("Copyright (c) Authored Fixture", "")).isEmpty())
    }
    @Test fun finiteBindingDistinguishesFrameworkAndLocatorFromOrdinaryPackages() {
        CSharpRuntimeClosure.requireBinding("System.Runtime","8.0.0.0","10.0.0.0",false,true)
        CSharpRuntimeClosure.requireBinding("Microsoft.Build","15.1.0.0","15.1.0.0",true,false)
        reject{CSharpRuntimeClosure.requireBinding("Argu","6.0.0.0","7.0.0.0",false,false)}
        reject{CSharpRuntimeClosure.requireBinding("Other","8.0.0.0","10.0.0.0",false,true)}
        for(requested in listOf("10.1.0.0","10.0.1.0","10.0.0.1"))reject{CSharpRuntimeClosure.requireBinding("System.Runtime",requested,"10.0.0.0",false,true)}
    }
    @Test fun matchingNameAndVersionCannotBorrowAnotherStrongNameOrCulture() {
        val a=CSharpRuntimeClosure.Assembly("Authored","1.0.0.0",mapOf("Provider" to "1.0.0.0"),emptySet(),referenceKeys=mapOf("Provider" to "0123456789abcdef"),referenceCultures=mapOf("Provider" to ""))
        val p=CSharpRuntimeClosure.Assembly("Provider","1.0.0.0",emptyMap(),emptySet(),publicKeyToken="0123456789abcdef")
        CSharpRuntimeClosure.requireReferenceIdentity(a,"Provider",p)
        reject{CSharpRuntimeClosure.requireReferenceIdentity(a,"Provider",p.copy(publicKeyToken="fedcba9876543210"))}
        reject{CSharpRuntimeClosure.requireReferenceIdentity(a,"Provider",p.copy(culture="fr"))}
    }
    @Test fun parsesOnlyAuthoredPeMetadataWithoutLoadingAssembly() {
        val file=Files.createTempFile("csharp-managed-fixture",".data").toFile()
        try {file.writeBytes(managed());val value=CSharpRuntimeClosure.assembly(file);assertEquals("Fixture",value.name);assertEquals("0.28.0.0",value.version)
            val bad=managed();bad[512+16]=16;file.writeBytes(bad);reject{CSharpRuntimeClosure.assembly(file)}
        }finally{file.delete()}
    }
    @Test fun actualBashShellCannotBeSubstitutedByOnlyShProof() {
        val root=Files.createTempDirectory("csharp-shell-fixture").toFile()
        try {
            java.io.File(root,"bin").mkdir();val sh=java.io.File(root,"bin/sh");sh.writeBytes(symbolElf(true))
            reject{CSharpRuntimeClosure.launchShells(root)}
            val bash=java.io.File(root,"bin/bash");bash.writeBytes(symbolElf(true))
            reject{CSharpRuntimeClosure.launchShells(root)}
            val env=java.io.File(root,"usr/bin/env");env.parentFile.mkdirs();env.writeBytes(symbolElf(true))
            assertEquals(setOf(sh,bash,env),CSharpRuntimeClosure.launchShells(root).keys)
            env.writeBytes(byteArrayOf(1,2,3));reject{CSharpRuntimeClosure.launchShells(root)};env.writeBytes(symbolElf(true))
            bash.writeBytes(byteArrayOf(1,2,3));reject{CSharpRuntimeClosure.launchShells(root)}
        }finally{PathSecurity.deleteTreeNoFollow(root)}
    }
    internal fun elf(executable: Boolean, soname: String? = null, needed: List<String> = emptyList(), search: String? = null, versionNeed: Pair<String, String>? = null, versionDefinition: String? = null): ByteArray {
        val bytes = ByteArray(4096); val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); val base = 0x10000L
        b.putInt(0, 0x464c457f); b.put(4, 2); b.put(5, 1); b.put(6, 1); b.putShort(16, 3); b.putShort(18, 183); b.putInt(20, 1)
        b.putLong(24, if (executable) base + 0x280 else 0); b.putLong(32, 64); b.putShort(52, 64); b.putShort(54, 56); b.putShort(56, if (executable) 3 else 2)
        fun ph(index: Int, type: Int, flags: Int, offset: Long, size: Long) { val at = 64 + index * 56; b.putInt(at, type); b.putInt(at + 4, flags); b.putLong(at + 8, offset); b.putLong(at + 16, base + offset); b.putLong(at + 32, size); b.putLong(at + 40, size); b.putLong(at + 48, 1) }
        ph(0, 1, 5, 0, bytes.size.toLong())
        val strings = java.io.ByteArrayOutputStream(); strings.write(0)
        fun text(value: String): Long { val at = strings.size(); strings.write(value.toByteArray()); strings.write(0); return at.toLong() }
        val tags = mutableListOf(5L to base + 0x600, 10L to 0L)
        needed.forEach { tags += 1L to text(it) }; soname?.let { tags += 14L to text(it) }; search?.let { tags += 29L to text(it) }
        versionNeed?.let { (owner, version) ->
            val ownerIndex = text(owner); val versionIndex = text(version)
            tags += 0x6ffffffeL to base + 0x800; tags += 0x6fffffffL to 1L
            b.putShort(0x800, 1); b.putShort(0x802, 1); b.putInt(0x804, ownerIndex.toInt()); b.putInt(0x808, 16); b.putInt(0x80c, 0)
            b.putInt(0x818, versionIndex.toInt()); b.putInt(0x81c, 0)
        }
        versionDefinition?.let { version ->
            val versionIndex = text(version); tags += 0x6ffffffcL to base + 0x900; tags += 0x6ffffffdL to 1L
            b.putShort(0x900, 1); b.putShort(0x906, 1); b.putInt(0x90c, 20); b.putInt(0x910, 0); b.putInt(0x914, versionIndex.toInt()); b.putInt(0x918, 0)
        }
        tags[1] = 10L to strings.size().toLong(); tags += 0L to 0L
        tags.forEachIndexed { i, pair -> b.putLong(0x300 + i * 16, pair.first); b.putLong(0x308 + i * 16, pair.second) }
        ph(1, 2, 4, 0x300, tags.size * 16L)
        if (executable) { val interp = (LocalUbuntuPowerShellRuntimePolicy.INTERPRETER + '\u0000').toByteArray(); interp.copyInto(bytes, 0x200); ph(2, 3, 4, 0x200, interp.size.toLong()) }
        strings.toByteArray().copyInto(bytes, 0x600); return bytes
    }
    internal fun symbolElf(executable:Boolean,soname:String?=null,needed:List<String> = emptyList(),exports:List<String> = emptyList(),imports:List<String> = emptyList(),gnu:Boolean=false,versionNeed:Pair<String,String>?=null,versionDefinition:String?=null):ByteArray {
        val bytes=elf(executable,soname,needed,versionNeed=versionNeed,versionDefinition=versionDefinition).copyOf(32768);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val base=0x10000L
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
    private fun managed():ByteArray {
        val b=ByteArray(8192);val v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        b[0]=77;b[1]=90;v.putInt(60,128);v.putInt(128,0x4550);v.putShort(132,0x14c.toShort());v.putShort(134,1);v.putShort(148,224)
        val o=152;v.putShort(o,0x10b);v.putInt(o+92,16);v.putInt(o+96+14*8,8192);v.putInt(o+96+14*8+4,72)
        val section=o+224;v.putInt(section+12,8192);v.putInt(section+16,7680);v.putInt(section+20,512)
        v.putInt(512,72);v.putInt(520,8320);v.putInt(524,256);v.putInt(528,1)
        val m=640;v.putInt(m,0x424a5342);v.putInt(m+12,4);b[m+16]=118;v.putShort(m+22,3)
        fun stream(at:Int,offset:Int,size:Int,name:String){v.putInt(m+at,offset);v.putInt(m+at+4,size);(name+"\u0000").toByteArray().copyInto(b,m+at+8)}
        stream(24,80,50,"#~");stream(36,144,16,"#Strings");stream(56,160,1,"#Blob")
        val t=m+80;b[t+4]=2;v.putLong(t+8,1L shl 32);v.putInt(t+24,1);v.putInt(t+28,0x8004);v.putShort(t+32,0);v.putShort(t+34,28);v.putShort(t+46,1)
        "\u0000Fixture\u0000".toByteArray().copyInto(b,m+144)
        return b
    }
    private fun reject(block:()->Unit){assertTrue(runCatching(block).isFailure)}
}
