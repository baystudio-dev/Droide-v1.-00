package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProviderPointerIndexTest {
 private fun normalize(index:String, objectMap:Boolean=false):JsonObject {
  val target=buildJsonObject { put("description","Target") }
  val input=buildJsonObject {
   put("type","object")
   put("$"+"defs",buildJsonObject { putJsonObject("branch"){put(if(objectMap) "properties" else "anyOf",if(objectMap) JsonObject(mapOf(index to target)) else JsonArray(listOf(target,target)))} })
   putJsonObject("properties"){putJsonObject("value"){put("$"+"ref","#/$"+"defs/branch/"+(if(objectMap) "properties/" else "anyOf/")+index)}}
  }
  return ProviderToolSchema.parameters(input,"fixture")["properties"]!!.jsonObject["value"]!!.jsonObject
 }
 @Test fun canonicalZeroAndPositiveIndexResolve() { for(index in listOf("0","1")) assertEquals(JsonPrimitive("Target"),normalize(index)["description"]) }
 @Test fun leadingZeroArrayIndicesDoNotAliasValidSlots() { for(index in listOf("00","01","0001")) assertThrows(IllegalArgumentException::class.java){normalize(index)} }
 @Test fun signedArrayIndicesDoNotAliasValidSlots() { for(index in listOf("+1","-0","+0")) assertThrows(IllegalArgumentException::class.java){normalize(index)} }
 @Test fun unicodeDigitsAreNotArrayIndices() { for(index in listOf("١","１")) assertThrows(IllegalArgumentException::class.java){normalize(index)} }
 @Test fun nonCanonicalNumericObjectKeysRemainLiteral() { for(index in listOf("01","+1","-0","١","１")) assertEquals(JsonPrimitive("Target"),normalize(index,true)["description"]) }
 @Test fun invalidOrOutOfRangeIndicesRemainRejected() { for(index in listOf("","-","-1","2","2147483648","1.0","1e0")) assertThrows(IllegalArgumentException::class.java){normalize(index)} }
}
