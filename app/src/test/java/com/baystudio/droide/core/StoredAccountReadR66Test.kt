package com.baystudio.droide.core
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
class StoredAccountReadR66Test {
 private val types=mapOf("login" to StoredAccountFieldType.TEXT,"user_id" to StoredAccountFieldType.LONG,"credential" to StoredAccountFieldType.TEXT,"pending_oauth" to StoredAccountFieldType.TEXT)
 private fun read(values:Map<String,*>, action:()->String={"ready"})=readStoredAccount({validateStoredAccountFields(values,types)},{"unavailable"},action)
 @Test fun missingKeysRemainMissing(){assertEquals("ready",read(emptyMap<String,Any>()))}
 @Test fun validTypedDataAccepted(){assertEquals("ready",read(mapOf("login" to "fixture","user_id" to 42L,"credential" to "encrypted")))}
 @Test fun integerIsNotCoercedToLong(){assertEquals("unavailable",read(mapOf("user_id" to 42)))}
 @Test fun textIsNotCoercedToLong(){assertEquals("unavailable",read(mapOf("user_id" to "42")))}
 @Test fun invalidLoginTypeRejected(){assertEquals("unavailable",read(mapOf("login" to true)))}
 @Test fun invalidCiphertextTypeRejectedBeforeRead(){var calls=0;assertEquals("unavailable",read(mapOf("credential" to 7L)){calls++;"ready"});assertEquals(0,calls)}
 @Test fun invalidPendingTypeDoesNotRunCleanup(){var deletes=0;assertEquals("unavailable",read(mapOf("pending_oauth" to false)){deletes++;"ready"});assertEquals(0,deletes)}
 @Test fun entireInputPreserved(){val values=linkedMapOf<String,Any>("login" to 7,"pending_oauth" to "kept");val before=values.toMap();read(values);assertEquals(before,values)}
 @Test fun unknownFieldsNotReinterpreted(){assertEquals("ready",read(mapOf("future_schema" to listOf(1,2))))}
 @Test fun changedBetweenValidationAndReadStillFailsClosed(){assertEquals("unavailable",read(emptyMap<String,Any>()){throw ClassCastException()})}
 @Test fun unexpectedDefectPropagates(){val f=IllegalStateException();assertSame(f,runCatching{read(emptyMap<String,Any>()){throw f}}.exceptionOrNull())}
 @Test fun cancellationPropagates(){val f=CancellationException();assertSame(f,runCatching{read(emptyMap<String,Any>()){throw f}}.exceptionOrNull())}
 @Test fun repairedStoreCanBeRetriedWithoutWritingIt(){val data=mutableMapOf<String,Any>("user_id" to "invalid");assertEquals("unavailable",read(data));data["user_id"]=42L;assertEquals("ready",read(data))}
 @Test fun diagnosticsContainNoStoredValues(){val f=runCatching{validateStoredAccountFields(mapOf("login" to listOf("private fixture")),types)}.exceptionOrNull();assertTrue(f is ClassCastException);assertFalse(f.toString().contains("private fixture"))}
}
