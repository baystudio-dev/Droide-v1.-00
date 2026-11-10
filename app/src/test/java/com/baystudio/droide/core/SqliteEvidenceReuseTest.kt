package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteEvidenceReuseTest {
    private class Fixture(val root: File, val source: File, val working: File, val directory: File) {
        fun authority(checkpoint: (String) -> Unit = {}) = MirrorMetadataAuthority(directory, source, working,
            processCheckpoint = { point ->
                check(point != "metadata-before-database-open") { "Recovery must never open the original SQLite" }
                checkpoint(point)
            })
        fun originals() = listOf("state.sqlite", "state.sqlite-journal").associateWith { directory.resolve(it).readBytes().toList() }
        fun captures() = directory.listFiles()!!.filter { it.name.startsWith("sqlite-review-") }.sortedBy { it.name }
        fun preserved() = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to hash(it.readBytes()) }
    }
    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("f159-sqlite-reuse-").toFile()
        val source = root.resolve("source").apply { mkdir() }
        val working = root.resolve("working")
        val directory = root.resolve(".working-sync-authority")
        MirrorMetadataAuthority.createForNewWorkingCopy(directory, source, working)
        val database = directory.resolve("state.sqlite").readBytes()
        val encoded = ((database[16].toInt() and 255) shl 8) or (database[17].toInt() and 255)
        val size = if (encoded == 1) 65536 else encoded
        val bytes = ByteArray(512 + size + 8)
        byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 5, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte()).copyInto(bytes)
        fun put(offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).putInt(value) }
        put(8, 1); put(12, 123); put(16, database.size / size); put(20, 512); put(24, size); put(512, 1)
        database.copyInto(bytes, 516, 0, size)
        var checksum = 123L; var index = size - 200
        while (index > 0) { checksum = (checksum + (database[index].toInt() and 255)) and 0xffffffffL; index -= 200 }
        put(516 + size, checksum.toInt())
        directory.resolve("state.sqlite-journal").writeBytes(bytes)
        println("PRESERVED_FIXTURE ${root.path}")
        for (type in listOf(MirrorMetadataAuthority::class.java, MirrorSqliteRollbackRecovery::class.java,
            MirrorSqliteForensicCandidate::class.java, MirrorInterruptedRecovery::class.java)) {
            val actual = type.getResource(type.simpleName + ".class")!!.toURI().path
            println("ORIGIN ${type.name} $actual")
            System.getProperty("f159.classes")?.let { expected ->
                assertEquals("Fresh production origin", expected + "/" + type.name.replace('.', '/') + ".class", actual)
            }
        }
        return Fixture(root, source, working, directory)
    }

    @Test fun firstBuildWorksAndOriginalSidecarsRemainBlockedControl() = runBlocking {
        val f = fixture(); val before = f.originals()
        val result = MirrorInterruptedRecovery(f.source, f.working).buildSqliteForensicCandidate {}
        assertEquals(before, f.originals())
        assertArrayEquals(f.directory.resolve("state.sqlite").readBytes(), result.directory.resolve("candidate.sqlite").readBytes())
        assertTrue(result.directory.resolve("report").readText().contains("ACTIVATION_AND_CLEANUP_DENIED"))
        assertNotNull(runCatching { MirrorMetadataAuthority(f.directory, f.source, f.working).load() }.exceptionOrNull())
        Unit
    }

    @Test fun firstBuildAtCaptureCapacityMustConsumeExistingCompleteCapture() = runBlocking {
        val f = fixture(); val before = f.originals()
        repeat(8) { f.authority().captureSqliteRollbackEvidence {} }
        val captures = f.captures(); val preserved = f.preserved()
        assertEquals(8, captures.size)
        val outcome = runCatching { MirrorInterruptedRecovery(f.source, f.working).buildSqliteForensicCandidate {} }
        assertEquals(before, f.originals())
        preserved.forEach { (path, value) -> assertEquals("Existing bytes preserved: $path", value, hash(f.directory.resolve(path).readBytes())) }
        println("CAPACITY_FIRST_BUILD ${outcome.exceptionOrNull()}")
        assertTrue("Eight complete preserved captures must not prevent the first forensic Build: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertTrue(outcome.getOrThrow().directory.parentFile in captures)
        assertEquals(8, f.captures().size)
        Unit
    }

    @Test fun lostReturnOfCompletedCandidateMustReopenAtCapacityWithoutNewCapture() {
        val f = fixture(); val before = f.originals()
        val captures = (1..8).map { f.authority().captureSqliteRollbackEvidence {} }
        val review = captures.last()
        val binding = review.evidenceDirectory.resolve("receipt").readLines()[1]
        val lost = runCatching { MirrorSqliteForensicCandidate.build(f.directory, binding, review, {},
            { if (it == "forensic-after-report-publication") error("Lost return after durable report") }) }
        assertTrue(lost.exceptionOrNull()?.message.orEmpty().contains("Lost return"))
        assertEquals(8, f.captures().size)
        val report = f.directory.walkTopDown().single { it.name == "report" }
        val publishedCandidate = report.parentFile
        val preserved = f.preserved()
        val retry = runCatching { f.authority().buildSqliteForensicCandidate {} }
        assertEquals(before, f.originals())
        assertEquals(preserved, f.preserved())
        println("CAPACITY_LOST_RETURN ${retry.exceptionOrNull()}")
        assertTrue("Completed candidate must remain reachable after lost return: ${retry.exceptionOrNull()}", retry.isSuccess)
        assertEquals(publishedCandidate, retry.getOrThrow().directory)
        assertEquals(8, f.captures().size)
    }
    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
