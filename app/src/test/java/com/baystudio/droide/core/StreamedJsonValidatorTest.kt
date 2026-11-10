package com.baystudio.droide.core
import java.io.*
import org.junit.Assert.*
import org.junit.Test
class StreamedJsonValidatorTest {
 @Test fun validObjectsAndEscapedUnicode() {
   for(value in listOf("{}","{\"x\":[null,true,false,-12.34e+9,{\"y\":\"🌏\\uD83C\\uDF0F\"}]}"," {\"x\":\"\\b\\n\\t\\/\\\\\\\"\"} \n"))
     StreamedJsonValidator.requireObject(ByteArrayInputStream(value.toByteArray()))
 }
 @Test fun oneCharacterReaderBoundariesPreserveUnicodeAndExplicitDuplicateKeyScope() {
   val value="{\"x\":\"🌏\\uD83C\\uDF0F\",\"n\":-1.2e-10,\"x\":true}"
   val reader=object:Reader(){
     var i=0
     override fun close()=Unit
     override fun read(buffer:CharArray,offset:Int,length:Int):Int {
       if(i==value.length)return -1
       buffer[offset]=value[i++];return 1
     }
   }
   // Structural admission deliberately does not select a duplicate member winner.
   StreamedJsonValidator.validate(reader)
 }
 @Test fun malformedOrNonObjectFailsClosed() {
   for(value in listOf("[]","null","{}{}","{\"a\":01}","{\"a\":1.}","{\"a\":1e}","{\"a\":NaN}","{\"a\":true,}","{\"a\":[1,]}","{\"a\":\"\\uD800\"}","{\"a\":\"\\uDC00\"}","{\"a\":\"\\q\"}","{\"a\":\"\n\"}"))
     assertThrows(RuntimeException::class.java){StreamedJsonValidator.requireObject(ByteArrayInputStream(value.toByteArray()))}
 }
 @Test fun deepJsonIsRejectedBeforeUnboundedRecursion() {
   val input="{\"a\":"+"[".repeat(150)+"0"+"]".repeat(150)+"}"
   assertThrows(IllegalStateException::class.java){StreamedJsonValidator.requireObject(ByteArrayInputStream(input.toByteArray()))}
 }
 @Test fun hugeScalarIsReadWithBoundedRequestsAndNoStringConstruction() {
   val prefix="{\"content\":\"";val suffix="\"}"
   val count=12_000_000L
   var index=0L;var largest=0
   val reader=object:Reader(){
     override fun close()=Unit
     override fun read(buffer:CharArray,offset:Int,length:Int):Int {
       largest=maxOf(largest,length)
       val total=prefix.length+count+suffix.length
       if(index==total)return -1
       val n=minOf(length.toLong(),total-index).toInt()
       repeat(n){ val p=index++;buffer[offset+it]=when {p<prefix.length->prefix[p.toInt()];p<prefix.length+count->'x';else->suffix[(p-prefix.length-count).toInt()]} }
       return n
     }
   }
   StreamedJsonValidator.validate(reader)
   assertEquals(prefix.length+count+suffix.length,index);assertTrue(largest<=4096)
 }
 @Test fun completedLargeToolDocumentCanBeValidatedDirectlyFromOwnedSpool() {
   val root=java.nio.file.Files.createTempDirectory("owned-json-admission")
   try {
   val admission=OwnedResponseContentStore.HeadroomAdmission({ root.toFile().usableSpace },1024*1024)
   val store=OwnedResponseContentStore(root,admission,inlineThreshold=1024)
   val session=store.authorize(OwnedResponseContentStore.Owner("session","run","call"))
   session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {writer->
     writer.append("{\"content\":\"")
     repeat(300){writer.append("x".repeat(8192))}
     writer.append("\"}")
     val sealed=writer.sealCompleted { StreamedJsonValidator.requireObject(it) }
     assertTrue(sealed.content is OwnedResponseContentStore.Content.FileBacked)
     assertTrue(sealed.content.byteLength>2_000_000)
     session.openBytes(sealed.content.handle).use { StreamedJsonValidator.requireObject(it) }
   }
   } finally { java.nio.file.Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) } }
 }
 @Test fun cancellationAndIncompleteUtf8FailBeforeAdmission() {
   assertThrows(java.util.concurrent.CancellationException::class.java){StreamedJsonValidator.validate(StringReader("{}"),checkCancelled={throw java.util.concurrent.CancellationException()})}
   assertThrows(java.nio.charset.CharacterCodingException::class.java){StreamedJsonValidator.requireObject(ByteArrayInputStream(byteArrayOf(123,34,120,34,58,34,0xf0.toByte(),0x9f.toByte())))}
 }
}
