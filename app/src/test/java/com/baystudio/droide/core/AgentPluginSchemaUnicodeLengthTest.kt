package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentPluginSchemaUnicodeLengthTest {
    private fun schema(min: Int?=null,max: Int?=null) = buildJsonObject {
        put("type","object"); putJsonObject("properties") {
            putJsonObject("text") { put("type","string"); min?.let { put("minLength",it) }; max?.let { put("maxLength",it) } }
        }
    }
    private fun input(text: String)=buildJsonObject { put("text",text) }
    private fun accepts(text:String,min:Int?=null,max:Int?=null) {
        val s=schema(min,max); assertTrue(AgentPluginJsonSchema.schemaSupported(s)); AgentPluginJsonSchema.requireValid(s,input(text))
    }
    private fun rejects(text:String,min:Int?=null,max:Int?=null) {
        assertThrows(IllegalArgumentException::class.java) { AgentPluginJsonSchema.requireValid(schema(min,max),input(text)) }
    }
    @Test fun supplementaryCharacterFitsMaxOne() { accepts("🙂",max=1) }
    @Test fun supplementaryCharacterFailsMinTwo() { rejects("🙂",min=2) }
    @Test fun mixedCharactersFitExactCodePointCount() { accepts("a🙂字",min=3,max=3) }
    @Test fun twoSupplementaryCharactersFitTwo() { accepts("🙂🚀",min=2,max=2) }
    @Test fun asciiBoundariesUnchanged() { accepts("ab",min=2,max=2);rejects("a",min=2);rejects("abc",max=2) }
    @Test fun emptyBoundariesUnchanged() { accepts("",max=0);rejects("",min=1) }
    @Test fun combiningSequenceCountsCodePointsNotGraphemes() { accepts("e\u0301",min=2,max=2);rejects("e\u0301",max=1) }
    @Test fun zwjSequenceCountsAllCodePoints() { accepts("👩‍💻",min=3,max=3);rejects("👩‍💻",max=1) }
}
