package com.baystudio.droide.core
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class TomlWasmDataAdmissionTest {
    private fun denied(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun section(id:Int,bytes:ByteArray)=byteArrayOf(id.toByte(),bytes.size.toByte())+bytes
    private fun text(s:String)=byteArrayOf(s.length.toByte())+s.toByteArray()
    private fun module(importModule:String="wbg",memoryFlags:Int=0)=byteArrayOf(0,97,115,109,1,0,0,0)+section(1,byteArrayOf(1,0x60,0,0))+
        section(2,byteArrayOf(1)+text(importModule)+text("__wbg_authored")+byteArrayOf(0,0))+
        section(5,byteArrayOf(1,memoryFlags.toByte(),1))+
        section(7,byteArrayOf(1)+text("memory")+byteArrayOf(2,0))
    @Test fun authoredInlineModuleAndNoMaximumAreAdmittedAsData(){val m=module();val literal=Base64.getEncoder().encodeToString(m);assertArrayEquals(m,TomlWasmDataAdmission.inlineModule("const wasm='$literal';"));val data=TomlWasmDataAdmission.inspectModule(m);assertEquals(JsonNull,data["memories"]!!.jsonArray[0].jsonObject["maximum"]);assertEquals("wbg",data["imports"]!!.jsonArray[0].jsonObject["module"]!!.jsonPrimitive.content)}
    @Test fun wasiSharedMalformedAndAmbiguousAreConcreteFailures(){denied{TomlWasmDataAdmission.inspectModule(module("wasi_snapshot_preview1"))};denied{TomlWasmDataAdmission.inspectModule(module(memoryFlags=2))};denied{TomlWasmDataAdmission.inspectModule(module().dropLast(1).toByteArray())};val b=Base64.getEncoder().encodeToString(module());denied{TomlWasmDataAdmission.inlineModule("'$b';\"$b\"")};denied{TomlWasmDataAdmission.inlineModule("'AGFzbQ'+'EAAAA=';")}}
    @Test fun externalPackagesAndExternalWasmAreUnsupported(){assertEquals(listOf("@taplo/core","node:buffer"),TomlWasmDataAdmission.literalReferences("require('@taplo/core');require('node:buffer')"));denied{TomlWasmDataAdmission.literalReferences("require('project-plugin')")};denied{TomlWasmDataAdmission.literalReferences("import('./module.wasm')")}}
    @Test fun boundedLiteralsAndModuleSectionsRejectOverflow(){denied{TomlWasmDataAdmission.inspectModule(byteArrayOf(0,97,115,109,1,0,0,0,5,-1,-1,-1,-1,127))};denied{TomlWasmDataAdmission.inlineModule("'AGFzbQEAAAA===';")};denied{TomlWasmDataAdmission.inspectModule(module()+section(5,byteArrayOf(1,0,1)))}}
}
