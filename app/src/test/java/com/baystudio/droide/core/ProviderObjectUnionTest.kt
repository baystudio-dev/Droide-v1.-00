package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProviderObjectUnionTest {
 private val json=Json
 private fun schema(type:JsonElement)=buildJsonObject{
  put("type",type);putJsonObject("properties"){putJsonObject("path"){put("type","string")}}
  putJsonArray("required"){add("path")};put("additionalProperties",false)
 }
 @Test fun singletonObjectUnionEqualsScalarObject(){
  assertEquals(ProviderToolSchema.parameters(schema(JsonPrimitive("object")),"test"),ProviderToolSchema.parameters(schema(JsonArray(listOf(JsonPrimitive("object")))),"test"))
 }
 @Test fun actualRequestRetainsOtherToolsAndSchemaAssertions(){
  val request=buildJsonObject {put("model","fixture");putJsonArray("tools"){
   for(name in listOf("first","second"))add(buildJsonObject{put("type","function");putJsonObject("function"){
    put("name",name);put("parameters",schema(if(name=="first")JsonArray(listOf(JsonPrimitive("object"))) else JsonPrimitive("object")))
   }})
  }}
  val normalized=json.parseToJsonElement(ProviderToolSchema.openAiRequest(request.toString(),json)).jsonObject
  val tools=normalized["tools"]!!.jsonArray;assertEquals(2,tools.size)
  for(tool in tools){val p=tool.jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject;assertEquals(JsonPrimitive("object"),p["type"]);assertEquals(JsonPrimitive(false),p["additionalProperties"]);assertEquals(JsonArray(listOf(JsonPrimitive("path"))),p["required"])}
 }
 @Test fun referencedSingletonObjectUnionIsSupported(){
  val s=buildJsonObject {putJsonObject("\$defs"){put("Input",schema(JsonArray(listOf(JsonPrimitive("object")))))};put("\$ref","#/\$defs/Input")}
  assertEquals(ProviderToolSchema.parameters(schema(JsonPrimitive("object")),"test"),ProviderToolSchema.parameters(s,"test"))
 }
 @Test fun otherRootTypesAndBroaderUnionsRemainRejected(){
  for(type in listOf(JsonPrimitive("array"),JsonArray(listOf(JsonPrimitive("string"))),JsonArray(listOf(JsonPrimitive("object"),JsonPrimitive("null"))),JsonArray(emptyList()),JsonArray(listOf(JsonPrimitive("object"),JsonPrimitive("object"))))){
   assertThrows(IllegalArgumentException::class.java){ProviderToolSchema.parameters(schema(type),"test")}
  }
 }

 @Test fun normalizedSchemaStillEnforcesRequiredAndClosedProperties(){
  val normalized=ProviderToolSchema.parameters(schema(JsonArray(listOf(JsonPrimitive("object")))),"test")
  assertTrue(AgentPluginJsonSchema.schemaSupported(normalized))
  AgentPluginJsonSchema.requireValid(normalized,buildJsonObject{put("path","ok")})
  assertThrows(IllegalArgumentException::class.java){AgentPluginJsonSchema.requireValid(normalized,buildJsonObject{})}
  assertThrows(IllegalArgumentException::class.java){AgentPluginJsonSchema.requireValid(normalized,buildJsonObject{put("path","ok");put("extra",true)})}
 }
 @Test fun nonStringArrayMembersAreStillRejected(){
  for(value in listOf(JsonPrimitive(1),JsonPrimitive(false),JsonNull)){
   assertThrows(IllegalArgumentException::class.java){ProviderToolSchema.parameters(schema(JsonArray(listOf(value))),"test")}
  }
 }
 @Test fun absentTypeAndNestedUnionsKeepExistingBehavior(){
  val source=buildJsonObject {putJsonObject("properties"){putJsonObject("value"){put("type",JsonArray(listOf(JsonPrimitive("string"),JsonPrimitive("null"))))}}}
  val result=ProviderToolSchema.parameters(source,"test")
  assertEquals(JsonPrimitive("object"),result["type"])
  assertEquals(source["properties"],result["properties"])
 }
}
