package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManagedPackageRemovalPlanTest {
    private fun record(version: String, active: Boolean, root: String = "/app/packages/runtime.node/$version/arm64-v8a", installed: Long = 1) =
        ManagedPackageRecord("runtime.node", version, "LOCAL_LINUX_ARM64", root, installed, active)

    @Test fun staleInactiveSelectionRemovesCurrentActiveReceiptAndActivatesFallback() {
        val selected = record("22", active = false, root = "/stale/path")
        val current = record("22", active = true)
        val old = record("20", active = false)
        val plan = ManagedPackageRemovalPlan.resolve(listOf(current, old), selected.familyId, selected.version)
        assertEquals(current, plan.current)
        assertTrue(plan.remaining.single().active)
    }

    @Test fun staleActiveSelectionCannotReplaceCurrentlyActiveDifferentVersion() {
        val selected = record("20", active = true)
        val old = record("20", active = false)
        val current = record("22", active = true)
        val plan = ManagedPackageRemovalPlan.resolve(listOf(old, current), selected.familyId, selected.version)
        assertEquals(old, plan.current)
        assertEquals(current, plan.remaining.single())
    }

    @Test fun sameFamilyDependencyPreventsRemoval() {
        val current = record("20", true)
        val newer = record("22", false).copy(dependencies = listOf("runtime.node@20"))
        assertTrue(runCatching { ManagedPackageRemovalPlan.resolve(listOf(current, newer), current.familyId, current.version) }.isFailure)
    }

    @Test fun failedFallbackAdmissionLeavesBothPayloadsAndReceiptsUntouched() = runBlocking {
        val root = Files.createTempDirectory("droide-fallback-admission-").toFile()
        try {
            val activeTree = File(root, "22").apply { mkdirs(); File(this, "payload").writeText("active") }
            val oldTree = File(root, "20").apply { mkdirs(); File(this, "payload").writeText("broken") }
            val before = listOf(record("22", true, activeTree.path), record("20", false, oldTree.path))
            val plan = ManagedPackageRemovalPlan.resolve(before, "runtime.node", "22")
            val failure = runCatching {
                ManagedPackageRemovalPlan.requireHealthyFallback(plan) { fallback ->
                    File(fallback.installRoot, "payload").readText() == "healthy"
                }
                PathSecurity.deleteTreeNoFollow(File(plan.current.installRoot))
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("repair"))
            assertEquals("active", File(activeTree, "payload").readText())
            assertEquals("broken", File(oldTree, "payload").readText())
            assertTrue(before.first().active)
            assertFalse(before.last().active)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
