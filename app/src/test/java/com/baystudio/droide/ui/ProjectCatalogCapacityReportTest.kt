package com.baystudio.droide.ui

import com.baystudio.droide.core.CanonicalFileOperationJournal
import com.baystudio.droide.core.CanonicalJournalRetention
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCatalogCapacityReportTest {
    @Test fun historyReportsObservedBudgetsWithoutReleaseOrMigrationPermission() {
        val usage = CanonicalJournalRetention.Usage(128, 128, 500, 1_000, 900, 4_194_304, 524_288)
        val report = Json.parseToJsonElement(ProjectCatalogRecoveryReport.history(
            CanonicalFileOperationJournal.RecoveryMetadata(null, emptyList(), usage), 100)).jsonObject
        val observed = report.getValue("retention_usage").jsonObject
        assertEquals(128, observed.getValue("completed_entries").jsonPrimitive.int)
        assertEquals(500L, observed.getValue("retained_bytes").jsonPrimitive.long)
        assertEquals(524_288L, observed.getValue("next_record_reserve_bytes").jsonPrimitive.long)
        assertTrue(report.getValue("capacity_observation_only").jsonPrimitive.boolean)
        for (field in listOf("authorizes_release", "default_migration_ready", "authorizes_recovery", "file_observations_included"))
            assertFalse(report.getValue(field).jsonPrimitive.boolean)
    }

    @Test fun absentRetentionIsUnknownNotEmptyCapacity() {
        val report = Json.parseToJsonElement(ProjectCatalogRecoveryReport.history(
            CanonicalFileOperationJournal.RecoveryMetadata(null, emptyList()), 100)).jsonObject
        assertEquals(JsonNull, report.getValue("retention_usage"))
        assertFalse(report.getValue("default_migration_ready").jsonPrimitive.boolean)
    }
}
