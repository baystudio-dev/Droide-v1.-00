package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Host format/failure tests. Success is speculative bytes, never live recovery or coherence proof. */
class MirrorSqliteForensicCandidateTest {
    private val binding = "a".repeat(64)
    private class Interrupted : RuntimeException()
    private val magic = byteArrayOf(0xd9.toByte(),0xd5.toByte(),5,0xf9.toByte(),0x20,0xa1.toByte(),0x63,0xd7.toByte())
    private class Fixture(val root: File, val original: ByteArray, val database: File, val journal: File)
    private fun fixture(action: Fixture.() -> Unit) {
        val root = Files.createTempDirectory("forensic-format-").toFile()
        try {
            val old = ByteArray(3 * 512) { (it % 239).toByte() }
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(old)
            old[16] = 2; old[17] = 0; old[18] = 1; old[19] = 1
            put(old, 28, 3)
            val current = old.copyOf(4 * 512)
            put(current, 28, 4); current[612] = 42
            val journal = ByteArray(512 + 2 * 520)
            magic.copyInto(journal); put(journal,8,2); put(journal,12,0xfedcba98L)
            put(journal,16,3); put(journal,20,512); put(journal,24,512)
            for (record in 0..1) {
                val offset = 512 + record * 520
                put(journal,offset,(record + 1).toLong())
                val page = old.copyOfRange(record * 512, (record + 1) * 512)
                page.copyInto(journal,offset + 4)
                put(journal,offset + 516,checksum(page,0xfedcba98L))
            }
            val database = root.resolve("state.sqlite").apply { writeBytes(current) }
            val sidecar = root.resolve("state.sqlite-journal").apply { writeBytes(journal) }
            Fixture(root,old,database,sidecar).action()
        } finally { root.deleteRecursively() }
    }
    private fun capture(f: Fixture) = MirrorSqliteRollbackRecovery.capture(f.root,binding,{})
    private fun build(f: Fixture, review: MirrorSqliteRollbackRecovery.Review = capture(f), checkpoint: (String) -> Unit = {}) =
        MirrorSqliteForensicCandidate.build(f.root,binding,review,{},checkpoint)
    private fun payloads(root: File) = root.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes().toList() }
    private fun put(bytes: ByteArray, offset: Int, value: Long) { ByteBuffer.wrap(bytes,offset,4).putInt(value.toInt()) }
    private fun checksum(page: ByteArray, nonce: Long): Long {
        var result=nonce; var i=page.size-200
        while(i>0) { result=(result+(page[i].toInt() and 255)) and 0xffffffffL; i-=200 }
        return result
    }

    @Test fun exactSegmentCopiesOriginalPageImagesIntoSeparateSpeculativeCandidate() = fixture {
        val before=payloads(root); val review=capture(this); val evidence=payloads(review.evidenceDirectory)
        val result=build(this,review)
        assertArrayEquals(original,result.directory.resolve("candidate.sqlite").readBytes())
        assertEquals(1536,result.bytes); assertEquals(2,result.restoredPages); assertEquals(512,result.pageSize)
        assertEquals(before,payloads(root)); assertEquals(evidence,payloads(review.evidenceDirectory))
        assertTrue(result.directory.resolve("report").readText().contains("UNVERIFIED_SPECULATIVE_BYTES"))
        assertTrue(result.directory.resolve("report").readText().contains("ACTIVATION_AND_CLEANUP_DENIED"))
        assertEquals(MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) },result.sha256)
        assertFalse(result.directory.resolve("candidate.sqlite-journal").exists())
    }

    @Test fun supportedPageSizesAndUnsignedChecksumWrapStayBounded() {
        for(size in listOf(512,1024,2048,4096,8192,16384,32768,65536)) fixture {
            val originalBytes=ByteArray(size*2) { (it%251).toByte() }
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(originalBytes)
            val encoded=if(size==65536)1 else size
            originalBytes[16]=(encoded ushr 8).toByte(); originalBytes[17]=encoded.toByte()
            originalBytes[18]=1; originalBytes[19]=1; put(originalBytes,28,2)
            database.writeBytes(originalBytes.copyOf().also { it[100]=(it[100].toInt() xor 1).toByte() })
            val sector=if(size==512)65536 else 512
            val bytes=ByteArray(sector+size+8)
            magic.copyInto(bytes); put(bytes,8,1); put(bytes,12,0xffffffffL); put(bytes,16,2)
            put(bytes,20,sector.toLong()); put(bytes,24,size.toLong()); put(bytes,sector,1)
            originalBytes.copyInto(bytes,sector+4,0,size)
            put(bytes,sector+4+size,checksum(originalBytes.copyOf(size),0xffffffffL))
            journal.writeBytes(bytes)
            val result=build(this)
            assertArrayEquals(originalBytes,result.directory.resolve("candidate.sqlite").readBytes())
            assertEquals(size,result.pageSize)
        }
    }

    @Test fun malformedCountsAndSizesFailBeforeCandidateCreation() {
        for ((offset,value) in listOf(8 to 0L,8 to 0xffffffffL,8 to 4L,16 to 0L,16 to 0xffffffffL,
            20 to 0L,20 to 768L,24 to 0L,24 to 131072L)) fixture {
            journal.writeBytes(journal.readBytes().also { put(it,offset,value) })
            val review=capture(this)
            assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
            assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
        }
    }

    @Test fun missingPrefixPagesCannotBeFilledWithInventedZeros() = fixture {
        database.writeBytes(database.readBytes().copyOf(1024))
        val review=capture(this)
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
    }

    @Test fun externalTrailerAndAdditionalSegmentsAreRejectedWithoutTouchingTarget() = fixture {
        val canary=root.resolve("external-super-journal").apply { writeText("must remain") }
        journal.appendBytes(canary.absolutePath.toByteArray() + ByteArray(8) + magic)
        val before=payloads(root); val review=capture(this)
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertEquals(before,payloads(root)); assertEquals("must remain",canary.readText())
        assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
    }

    @Test fun arbitraryTrailingByteIsNotIgnored() = fixture {
        journal.appendBytes(byteArrayOf(0)); val review=capture(this)
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
    }

    @Test fun invalidPageNumbersDuplicatesAndChecksumFailuresNeverPublishReport() {
        for ((offset,value) in listOf(512 to 0L,512 to 4L,512 to (0x40000000L/512+1),1032 to 1L,1028 to 7L)) fixture {
            journal.writeBytes(journal.readBytes().also { put(it,offset,value) })
            val before=payloads(root); val review=capture(this)
            assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
            assertEquals(before,payloads(root)); assertFalse(review.evidenceDirectory.walkTopDown().any { it.name=="report" })
        }
    }

    @Test fun nonzeroHeaderPaddingIsRejected() = fixture {
        journal.writeBytes(journal.readBytes().also { it[100]=9 })
        val review=capture(this)
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertFalse(review.evidenceDirectory.walkTopDown().any { it.name=="report" })
    }

    @Test fun capturedAndRestoredPageOneFormatsMustBothMatch() {
        for (modifyCaptured in listOf(true,false)) fixture {
            if(modifyCaptured) database.writeBytes(database.readBytes().also { it[18]=2 })
            else journal.writeBytes(journal.readBytes().also { bytes ->
                bytes[512+4+18]=2
                put(bytes,1028,checksum(bytes.copyOfRange(516,1028),0xfedcba98L))
            })
            val review=capture(this)
            assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
            assertFalse(review.evidenceDirectory.walkTopDown().any { it.name=="report" })
        }
    }

    @Test fun changedCapturedBytesAndReceiptFailClosed() {
        for (name in listOf("state.sqlite","state.sqlite-journal","receipt")) fixture {
            val review=capture(this); review.evidenceDirectory.resolve(name).appendText("changed")
            assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
            assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
        }
    }

    @Test fun evidenceFromOtherAuthorityAndReplacedDirectoryAreRejected() = fixture {
        val review=capture(this)
        val other=root.resolve("other").apply { mkdir() }
        assertNotNull(runCatching { MirrorSqliteForensicCandidate.build(other,binding,review,{}) }.exceptionOrNull())
        val moved=root.resolve("preserved-evidence")
        Files.move(review.evidenceDirectory.toPath(),moved.toPath())
        review.evidenceDirectory.mkdir()
        moved.listFiles()!!.forEach { it.copyTo(review.evidenceDirectory.resolve(it.name)) }
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertFalse(review.evidenceDirectory.listFiles()!!.any { it.isDirectory })
    }

    @Test fun interruptedAttemptsRemainAndFreshRetryDoesNotOverwriteThem() {
        for(boundary in listOf("forensic-after-database-copy","forensic-after-page","forensic-before-candidate-force",
            "forensic-before-report-publication","forensic-after-report-publication")) fixture {
            val before=payloads(root); val review=capture(this)
            assertTrue(runCatching { build(this,review) { if(it==boundary) throw Interrupted() } }.exceptionOrNull() is Interrupted)
            val failed=review.evidenceDirectory.listFiles()!!.single { it.isDirectory }; val evidence=payloads(failed)
            val retry=build(this,review)
            assertNotEquals(failed,retry.directory); assertEquals(evidence,payloads(failed)); assertEquals(before,payloads(root))
            assertArrayEquals(original,retry.directory.resolve("candidate.sqlite").readBytes())
        }
    }

    @Test fun boundedRetriesNeverRemoveAnOlderCandidate() = fixture {
        val review=capture(this); val results=(1..4).map { build(this,review) }
        assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
        assertEquals(4,review.evidenceDirectory.listFiles()!!.count { it.isDirectory })
        results.forEach { assertArrayEquals(original,it.directory.resolve("candidate.sqlite").readBytes()) }
    }

    @Test fun sparseChecksumDoesNotPretendToAuthenticateOrProveCoherence() = fixture {
        // This byte is deliberately not covered by SQLite's sparse checksum.
        journal.writeBytes(journal.readBytes().also { it[516+100]=(it[516+100].toInt() xor 1).toByte() })
        val result=build(this)
        assertFalse(original.contentEquals(result.directory.resolve("candidate.sqlite").readBytes()))
        assertTrue(result.directory.resolve("report").readText().contains("COHERENCE_AND_WRITER_EXCLUSION_UNPROVEN"))
    }
    @Test fun emptyTrailingHeaderRequiresExactGeometryAndZeroPadding() {
        for (mutation in listOf("none","padding","magic","count","pages","sector","size","reserved")) fixture {
            val originalJournal=journal.readBytes()
            val aligned=((originalJournal.size+511)/512)*512
            val extended=originalJournal.copyOf(aligned+512)
            put(extended,aligned+12,123); put(extended,aligned+16,3); put(extended,aligned+20,512); put(extended,aligned+24,512)
            when(mutation) {
                "padding" -> extended[originalJournal.size]=1
                "magic" -> extended[aligned]=1
                "count" -> put(extended,aligned+8,1)
                "pages" -> put(extended,aligned+16,4)
                "sector" -> put(extended,aligned+20,1024)
                "size" -> put(extended,aligned+24,1024)
                "reserved" -> extended[aligned+40]=1
            }
            journal.writeBytes(extended)
            val review=capture(this)
            if(mutation=="none") {
                val result=build(this,review)
                assertArrayEquals(original,result.directory.resolve("candidate.sqlite").readBytes())
                assertEquals(MirrorSqliteForensicCandidate.JournalShape.SINGLE_SEGMENT_EMPTY_HEADER,result.journalShape)
            } else {
                assertNotNull(runCatching { build(this,review) }.exceptionOrNull())
                assertFalse(review.evidenceDirectory.walkTopDown().any { it.name=="report" })
            }
        }
    }

    @Test fun candidateMutationOrReplacementAtPublicationNeverReturnsStaleResult() {
        for(boundary in listOf("forensic-before-candidate-force","forensic-before-report-publication","forensic-after-report-publication"))
            for(replace in listOf(false,true)) fixture {
                val before=payloads(root); val review=capture(this)
                var result: MirrorSqliteForensicCandidate.Result? = null
                assertNotNull(runCatching {
                    result=build(this,review) {
                        if(it==boundary) {
                            val candidate=review.evidenceDirectory.listFiles()!!.single { entry -> entry.isDirectory }.resolve("candidate.sqlite")
                            if(replace) {
                                val prior=candidate.resolveSibling("preserved-candidate")
                                Files.move(candidate.toPath(),prior.toPath()); candidate.writeBytes(prior.readBytes())
                            } else candidate.writeBytes(candidate.readBytes().also { bytes -> bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte() })
                        }
                    }
                }.exceptionOrNull())
                assertNull(result); assertEquals(before,payloads(root))
            }
    }

    private fun actualInterruptedDatabase(f: Fixture, committed: Boolean) {
        Files.delete(f.database.toPath()); Files.delete(f.journal.toPath())
        val script = """
import ctypes, ctypes.util, pathlib, sys, time
root = pathlib.Path(sys.argv[1])
lib = ctypes.CDLL(ctypes.util.find_library('sqlite3'))
handle = ctypes.c_void_p()
lib.sqlite3_open.argtypes = [ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
lib.sqlite3_exec.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
lib.sqlite3_db_cacheflush.argtypes = [ctypes.c_void_p]
assert lib.sqlite3_open(str(root / 'state.sqlite').encode(), ctypes.byref(handle)) == 0
def sql(text):
    assert lib.sqlite3_exec(handle, text.encode(), None, None, None) == 0
sql('PRAGMA journal_mode=DELETE')
sql('PRAGMA synchronous=EXTRA')
sql('CREATE TABLE demo(id INTEGER PRIMARY KEY, value TEXT)')
sql("INSERT INTO demo VALUES(1, 'original')")
(root / 'pre-transaction.sqlite').write_bytes((root / 'state.sqlite').read_bytes())
sql("BEGIN IMMEDIATE; UPDATE demo SET value='changed' WHERE id=1")
assert lib.sqlite3_db_cacheflush(handle) == 0
if sys.argv[2] == 'commit':
    sql('COMMIT')
print('READY', flush=True)
while True:
    time.sleep(1)
""".trimIndent()
        val child = ProcessBuilder("python3", "-c", script, f.root.path, if(committed) "commit" else "before")
            .redirectErrorStream(true).start()
        try {
            assertEquals("READY", child.inputStream.bufferedReader().readLine())
            child.destroyForcibly()
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally { if(child.isAlive) child.destroyForcibly().waitFor() }
    }

    @Test(timeout = 30000) fun actualKilledSqliteWithEmptyHeaderProducesOnlySpeculativePriorBytes() = fixture {
        actualInterruptedDatabase(this, committed=false)
        val before=payloads(root); val review=capture(this)
        val candidate=build(this,review)
        assertArrayEquals(root.resolve("pre-transaction.sqlite").readBytes(),candidate.directory.resolve("candidate.sqlite").readBytes())
        assertEquals(MirrorSqliteForensicCandidate.JournalShape.SINGLE_SEGMENT_EMPTY_HEADER,candidate.journalShape)
        assertEquals(before,payloads(root))
        assertTrue(candidate.directory.resolve("report").readText().contains("COHERENCE_AND_WRITER_EXCLUSION_UNPROVEN"))
    }

    @Test(timeout = 30000) fun actualKillAfterCommitDoesNotInventRollbackCandidate() = fixture {
        actualInterruptedDatabase(this, committed=true)
        val before=payloads(root)
        assertFalse(journal.exists())
        assertNotNull(runCatching { build(this) }.exceptionOrNull())
        assertEquals(before,payloads(root))
        assertFalse(root.listFiles()!!.any { it.isDirectory })
    }

}
