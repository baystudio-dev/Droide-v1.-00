package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MirrorSqliteForensicAuthorityTest {
    private fun fixture(action: (File, File, File) -> Unit) {
        val root=Files.createTempDirectory("forensic-authority-").toFile()
        try {
            val source=root.resolve("source").apply { mkdir() }; val working=root.resolve("working")
            val directory=root.resolve("authority")
            MirrorMetadataAuthority.createForNewWorkingCopy(directory,source,working)
            action(source,working,directory)
        } finally { root.deleteRecursively() }
    }
    private fun journal(database: ByteArray): ByteArray {
        val encoded=((database[16].toInt() and 255) shl 8) or (database[17].toInt() and 255)
        val size=if(encoded==1)65536 else encoded
        val bytes=ByteArray(512+size+8)
        byteArrayOf(0xd9.toByte(),0xd5.toByte(),5,0xf9.toByte(),0x20,0xa1.toByte(),0x63,0xd7.toByte()).copyInto(bytes)
        fun put(offset:Int,value:Int) { ByteBuffer.wrap(bytes,offset,4).putInt(value) }
        put(8,1); put(12,123); put(16,database.size/size); put(20,512); put(24,size); put(512,1)
        database.copyInto(bytes,516,0,size)
        var checksum=123L; var index=size-200
        while(index>0) { checksum=(checksum+(database[index].toInt() and 255)) and 0xffffffffL; index-=200 }
        put(516+size,checksum.toInt())
        return bytes
    }

    @Test fun explicitAuthorityActionNeverOpensOriginalSQLiteAndCannotResumeWorkspace() = fixture { source,working,directory ->
        val database=directory.resolve("state.sqlite"); val before=database.readBytes()
        val sidecar=directory.resolve("state.sqlite-journal").apply { writeBytes(journal(before)) }
        val journalBefore=sidecar.readBytes()
        val authority=MirrorMetadataAuthority(directory,source,working,processCheckpoint={
            check(it!="metadata-before-database-open") { "Forensic path must never open SQLite" }
        })
        val result=authority.buildSqliteForensicCandidate {}
        assertArrayEquals(before,result.directory.resolve("candidate.sqlite").readBytes())
        assertArrayEquals(before,database.readBytes()); assertArrayEquals(journalBefore,sidecar.readBytes())
        assertNotNull(runCatching { MirrorMetadataAuthority(directory,source,working).load() }.exceptionOrNull())
        assertTrue(result.directory.resolve("report").readText().contains("ACTIVATION_AND_CLEANUP_DENIED"))
    }

    @Test fun failurePreservesAuthorityAndRetryUsesFreshCandidateInSameCapture() = fixture { source,working,directory ->
        val database=directory.resolve("state.sqlite"); val before=database.readBytes()
        directory.resolve("state.sqlite-journal").writeBytes(journal(before))
        val interrupted=MirrorMetadataAuthority(directory,source,working,processCheckpoint={
            if(it=="forensic-after-page") error("Injected interruption")
        })
        assertNotNull(runCatching { interrupted.buildSqliteForensicCandidate {} }.exceptionOrNull())
        val failed=directory.listFiles()!!.single { it.name.startsWith("sqlite-review-") }
        val partial=failed.walkTopDown().filter { it.isFile }.associate { it.relativeTo(failed).path to it.readBytes().toList() }
        val failedCandidate=failed.listFiles()!!.single { it.name.startsWith("candidate-") }
        val result=MirrorMetadataAuthority(directory,source,working).buildSqliteForensicCandidate {}
        assertEquals(failed,result.directory.parentFile)
        assertNotEquals(failedCandidate,result.directory)
        assertEquals(1,directory.listFiles()!!.count { it.name.startsWith("sqlite-review-") })
        partial.forEach { (path, bytes) -> assertEquals("Interrupted bytes preserved: $path", bytes, failed.resolve(path).readBytes().toList()) }
        assertArrayEquals(before,database.readBytes())
    }
}
