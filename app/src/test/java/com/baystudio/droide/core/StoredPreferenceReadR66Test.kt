package com.baystudio.droide.core
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class StoredPreferenceReadR66Test {
 private fun read(source:()->Flow<String>)=runBlocking{observeStoredPreferences("last",source).toList()}
 @Test fun successReady(){assertEquals(listOf(StoredPreferenceRead("one",true,false),StoredPreferenceRead("two",true,false)),read{flowOf("one","two")})}
 @Test fun ioPreservesInitial(){assertEquals(StoredPreferenceRead("last",false,true),read{flow{throw IOException()}}.single())}
 @Test fun typeErrorRecoverable(){assertTrue(read{flow{throw ClassCastException()}}.single().failed)}
 @Test fun latestValueRetainedOnFailure(){assertEquals(listOf(StoredPreferenceRead("new",true,false),StoredPreferenceRead("new",false,true)),read{flow{emit("new");throw IOException()}})}
 @Test fun factoryIoRecoverable(){assertTrue(read{throw IOException()}.single().failed)}
 @Test fun factoryTypeRecoverable(){assertTrue(read{throw ClassCastException()}.single().failed)}
 @Test fun explicitRetryCanRecover(){var count=0;val source={count++;if(count==1)flow<String>{throw IOException()}else flowOf("restored")};assertFalse(read(source).single().ready);assertEquals("restored",read(source).single().value);assertEquals(2,count)}
 @Test fun noAutomaticRetry(){var count=0;read{count++;flow{throw IOException()}};assertEquals(1,count)}
 @Test fun cancellationIdentity(){val f=CancellationException();assertSame(f,runCatching{read{flow{throw f}}}.exceptionOrNull())}
 @Test fun factoryCancellationIdentity(){val f=CancellationException();assertSame(f,runCatching{read{throw f}}.exceptionOrNull())}
 @Test fun unexpectedFailurePropagates(){val f=IllegalStateException();assertSame(f,runCatching{read{flow{throw f}}}.exceptionOrNull())}
 @Test fun fatalErrorPropagates(){val f=AssertionError();assertSame(f,runCatching{read{flow{throw f}}}.exceptionOrNull())}
 @Test fun downstreamIoNotReinterpreted(){val f=IOException();var count=0;val actual=runCatching{runBlocking{observeStoredPreferences("old"){flowOf("new")}.collect{count++;throw f}}}.exceptionOrNull();assertSame(f,actual);assertEquals(1,count)}
 @Test fun emptyFlowDoesNotInventReady(){assertTrue(read{emptyFlow()}.isEmpty())}
 @Test fun failurePayloadNotProjected(){assertFalse(read{flow{throw IOException("private fixture")}}.single().toString().contains("private fixture"))}
}
