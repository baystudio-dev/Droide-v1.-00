package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProviderLocalFragmentTest {
 private fun normalize(pointer:String,defs:JsonObject):JsonObject {
  val input=buildJsonObject {
   put("type","object");put("$"+"defs",defs)
   putJsonObject("properties"){putJsonObject("value"){put("$"+"ref",pointer)}}
  }
  return ProviderToolSchema.parameters(input,"fixture")["properties"]!!.jsonObject["value"]!!.jsonObject
 }
 private fun defs(name:String)=buildJsonObject { putJsonObject(name){put("type","string");put("minLength",3)} }
 @Test fun encodedSpaceResolves() { assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/a%20b",defs("a b"))["minLength"]) }
 @Test fun encodedUnicodeResolves() { assertEquals(JsonPrimitive("string"),normalize("#/$"+"defs/%E2%82%AC",defs("€"))["type"]) }
 @Test fun encodedSeparatorIsDecodedBeforeTokenSplit() {
  val nested=buildJsonObject { putJsonObject("a"){putJsonObject("b"){put("type","string")}} }
  assertEquals(JsonPrimitive("string"),normalize("#/$"+"defs/a%2Fb",nested)["type"])
 }
 @Test fun encodedTildeIsUnescapedAfterDecoding() { assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/a%7E1b%7e0c",defs("a/b~c"))["minLength"]) }
 @Test fun encodedLeadingSlashWorks() { assertEquals(JsonPrimitive(3),normalize("#%2F$"+"defs/a",defs("a"))["minLength"]) }
 @Test fun literalPlusAndEncodedPercentRemainExact() {
  assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/a+b",defs("a+b"))["minLength"])
  assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/%2520",defs("%20"))["minLength"])
 }
 @Test fun malformedPercentAndUtf8AreRejected() {
  for(key in listOf("%","%2","%GG","%FF","%C0%AF","%E2%82","%４１"))
   assertThrows(IllegalArgumentException::class.java){normalize("#/$"+"defs/"+key,defs(key))}
 }
 @Test fun externalAndAnchorReferencesStayRejected() {
  for(ref in listOf("https://example.com/#/a","#anchor","%23/$"+"defs/a"))
   assertThrows(IllegalArgumentException::class.java){normalize(ref,defs("a"))}
 }
 @Test fun unencodedPointersAndTildeEscapesStillResolve() {
  assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/a~1b",defs("a/b"))["minLength"])
  assertEquals(JsonPrimitive(3),normalize("#/$"+"defs/normal",defs("normal"))["minLength"])
 }
}
