package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PackagedEngineAdmissionCacheTest {
    private fun fixture(block: (java.io.File, List<java.io.File>) -> Unit) {
        val directory = Files.createTempDirectory("droide-engine-admission-").toFile()
        val files = listOf("proot", "loader", "loader32").map { name ->
            directory.resolve(name).apply { writeText("reviewed-$name"); check(setExecutable(true)) }
        }
        try { block(directory, files) } finally { PathSecurity.deleteTreeNoFollow(directory) }
    }

    @Test fun warmConcurrentCallersValidateOneNativeGenerationOnce() = fixture { _, files ->
        val cache = PackagedEngineAdmissionCache()
        val validations = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = (1..40).map {
                pool.submit<java.io.File> { start.await(); cache.require(files) { validations.incrementAndGet(); files.first() } }
            }
            start.countDown()
            results.forEach { assertEquals(files.first(), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, validations.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun replacementAndPermissionChangesRequireFreshValidation() = fixture { root, files ->
        val cache = PackagedEngineAdmissionCache(); var validations = 0
        fun validate() = cache.require(files) { validations++; check(files.all { it.canExecute() }); files.first() }
        validate(); validate(); assertEquals(1, validations)
        val replacement = root.resolve("new-loader").apply { writeText("reviewed-loader"); check(setExecutable(true)) }
        Files.move(replacement.toPath(), files[1].toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        validate(); assertEquals(2, validations)
        check(files[1].setExecutable(false, false))
        try { validate(); fail("Non-executable library must fail") } catch (_: IllegalStateException) { }
        check(files[1].setExecutable(true))
        validate(); assertEquals(4, validations)
    }

    @Test fun restoredMtimeDoesNotHideChangedBytesAndFailuresAreNotCached() = fixture { _, files ->
        val cache = PackagedEngineAdmissionCache(); var validations = 0
        val original = files[1].readText()
        fun validate() = cache.require(files) { validations++; check(files[1].readText() == original); files.first() }
        validate()
        val modified = Files.getLastModifiedTime(files[1].toPath())
        files[1].writeText("x".repeat(original.length))
        Files.setLastModifiedTime(files[1].toPath(), modified)
        repeat(2) { try { validate(); fail("Changed library must fail") } catch (_: IllegalStateException) { } }
        assertEquals(3, validations)
        files[1].writeText(original); validate(); validate()
        assertEquals(4, validations)
    }

    @Test fun mutationDuringValidationNeverCreatesAnAdmission() = fixture { _, files ->
        val cache = PackagedEngineAdmissionCache()
        try {
            cache.require(files) { files[2].appendText("changed"); files.first() }
            fail("Racing engine mutation must invalidate admission")
        } catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("changed during validation")) }
        var validated = false
        cache.require(files) { validated = true; files.first() }
        assertTrue(validated)
    }
}
