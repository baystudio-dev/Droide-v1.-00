package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class CatalogReleasePreflightTest {
    private val cleanEditor = CatalogReleasePreflight.EditorEvidence(emptyList(), emptyList(), emptyList(), emptyList())
    private val cleanProcesses = CatalogReleasePreflight.ProcessEvidence(emptyList(), emptyList(), emptyList())
    private fun version(id: String = "inode") = CanonicalFileSnapshot.Version(id, 1, "a".repeat(64), 420)
    private fun record(id: String = "one", bytes: Long = 3) = CatalogReleasePreflight.Record(id, "owner", "REPLACE", "raw-$id", bytes,
        listOf("before", "after", "retired").map { CatalogReleasePreflight.Custody("$id.$it", version("$id-$it").copy(size = if (it == "after") bytes else 0)) })
    private fun snapshot(records: List<CatalogReleasePreflight.Record> = listOf(record())) = CatalogReleasePreflight.Snapshot(
        CatalogReleasePreflight.Scope("owner", "authority-inode", "/root", "root-inode", "generation"), version("catalog-inode"),
        CanonicalJournalRetention.Policy(128, 1000), records)
    private fun evaluate(a: CatalogReleasePreflight.Snapshot = snapshot(), b: CatalogReleasePreflight.Snapshot = a,
                         ids: List<String> = listOf("one")) = CatalogReleasePreflight.evaluate(a,b,ids,cleanEditor,cleanProcesses)
    private fun blocked(result: CatalogReleasePreflight.Result, vararg reasons: CatalogReleasePreflight.Blocker) {
        assertTrue(result.toString(), result is CatalogReleasePreflight.Result.Blocked)
        val actual = (result as CatalogReleasePreflight.Result.Blocked).blockers
        reasons.forEach { assertTrue("Missing $it in $actual", it in actual) }
    }
    @Test fun cleanIsObservationNeverRelease() {
        val result = evaluate() as CatalogReleasePreflight.Result.ObservedForReview
        assertEquals(CatalogReleasePreflight.LIMITATION, result.limitation)
        assertEquals(snapshot().scope, result.observation.scope)
        assertEquals(snapshot().catalog, result.observation.catalog)
        assertEquals(snapshot().policy, result.observation.policy)
        assertEquals(3, result.observation.selected.single().custody.size)
        assertEquals(64, result.observation.historyDigest.length)
    }
    @Test fun orderingCanonicalized() {
        val a = snapshot(listOf(record("two"), record()))
        val b = a.copy(records = a.records.reversed().map { it.copy(custody = it.custody.reversed()) })
        assertEquals(evaluate(a,b,listOf("two","one")), evaluate(b,a,listOf("one","two")))
    }
    @Test fun changedUnselectedMembershipSameGenerationBlocks() {
        val a = snapshot(listOf(record(),record("two")))
        blocked(evaluate(a,a.copy(records=listOf(record(),record("three")))), CatalogReleasePreflight.Blocker.HISTORY_CHANGED)
    }
    @Test fun rawRecordChangesBlock() { val a=snapshot(); blocked(evaluate(a,a.copy(records=listOf(record().copy(encoded="different")))), CatalogReleasePreflight.Blocker.HISTORY_CHANGED) }
    @Test fun samePathReplacedInodeBlocks() {
        val a=snapshot(); val r=record(); val c=r.custody.toMutableList(); c[0]=c[0].copy(version=c[0].version.copy(identity="replacement"))
        blocked(evaluate(a,a.copy(records=listOf(r.copy(custody=c)))),CatalogReleasePreflight.Blocker.CUSTODY_CHANGED)
    }
    @Test fun custodyContentChangesBlock() {
        val a=snapshot(); val r=record(); val c=r.custody.toMutableList(); c[1]=c[1].copy(version=c[1].version.copy(sha256="b".repeat(64)))
        blocked(evaluate(a,a.copy(records=listOf(r.copy(custody=c)))),CatalogReleasePreflight.Blocker.CUSTODY_CHANGED)
    }
    @Test fun scopeIdentitiesAllBound() {
        val a=snapshot(); val s=a.scope
        listOf(s.copy(owner="other"),s.copy(authority="other"),s.copy(root="/other"),s.copy(rootIdentity="other"),s.copy(generation="other")).forEach {
            val b=a.copy(scope=it,records=a.records.map { r -> r.copy(owner=it.owner) })
            blocked(evaluate(a,b),CatalogReleasePreflight.Blocker.IDENTITY_CHANGED)
        }
    }
    @Test fun catalogIdentityAndContentBound() {
        val a=snapshot()
        listOf(a.catalog.copy(identity="other"),a.catalog.copy(sha256="b".repeat(64)),a.catalog.copy(size=2),a.catalog.copy(mode=384)).forEach {
            blocked(evaluate(a,a.copy(catalog=it)),CatalogReleasePreflight.Blocker.CATALOG_CHANGED)
        }
    }
    @Test fun duplicateSelectionsBlocked() { blocked(evaluate(ids=listOf("one","one")),CatalogReleasePreflight.Blocker.DUPLICATE_ID) }
    @Test fun duplicateHistoryBlocked() { blocked(evaluate(snapshot(listOf(record(),record()))),CatalogReleasePreflight.Blocker.DUPLICATE_ID) }
    @Test fun unknownSelectionBlocked() { blocked(evaluate(ids=listOf("missing")),CatalogReleasePreflight.Blocker.UNKNOWN_SELECTION) }
    @Test fun emptySelectionBlocked() { blocked(evaluate(ids=emptyList()),CatalogReleasePreflight.Blocker.INVALID_INPUT) }
    @Test fun ownerMismatchBlocked() { blocked(evaluate(snapshot(listOf(record().copy(owner="other")))),CatalogReleasePreflight.Blocker.WRONG_OWNER) }
    @Test fun unsupportedKindAndPathBlocked() {
        listOf(record().copy(kind="CREATE"),record().copy(path="foreign.json")).forEach {
            blocked(evaluate(snapshot(listOf(it))),CatalogReleasePreflight.Blocker.UNSUPPORTED_RECORD_KIND)
        }
    }
    @Test fun allThreeRetainedRolesRequired() {
        val r=record()
        listOf(r.copy(custody=r.custody.dropLast(1)),r.copy(custody=r.custody.map { it.copy(name=it.name.replace("retired","publish")) })).forEach {
            blocked(evaluate(snapshot(listOf(it))),CatalogReleasePreflight.Blocker.INVALID_INPUT)
        }
    }
    @Test fun duplicateCustodyBlocked() { val r=record(); blocked(evaluate(snapshot(listOf(r.copy(custody=listOf(r.custody[0],r.custody[0],r.custody[2]))))),CatalogReleasePreflight.Blocker.DUPLICATE_ID) }
    @Test fun unavailableSubsystemsFailClosed() {
        blocked(CatalogReleasePreflight.evaluate(snapshot(),snapshot(),listOf("one"),null,null),
            CatalogReleasePreflight.Blocker.EDITOR_EVIDENCE_UNAVAILABLE,CatalogReleasePreflight.Blocker.PROCESS_EVIDENCE_UNAVAILABLE)
        blocked(CatalogReleasePreflight.evaluate(null,null,listOf("one"),cleanEditor,cleanProcesses),CatalogReleasePreflight.Blocker.SNAPSHOT_UNAVAILABLE)
    }
    @Test fun editorIssuesRemainSeparate() {
        blocked(CatalogReleasePreflight.evaluate(snapshot(),snapshot(),listOf("one"),
            CatalogReleasePreflight.EditorEvidence(listOf("buffer"),listOf("save1","save2"),listOf("sync"),listOf("handoff")),cleanProcesses),
            CatalogReleasePreflight.Blocker.DIRTY_BUFFERS,CatalogReleasePreflight.Blocker.PENDING_SAVES,
            CatalogReleasePreflight.Blocker.PENDING_SYNC,CatalogReleasePreflight.Blocker.UNRESOLVED_HANDOFF)
    }
    @Test fun processIssuesRemainSeparate() {
        blocked(CatalogReleasePreflight.evaluate(snapshot(),snapshot(),listOf("one"),cleanEditor,
            CatalogReleasePreflight.ProcessEvidence(listOf("pid"),listOf("child"),listOf("fd"))),
            CatalogReleasePreflight.Blocker.LIVE_PROCESS_OWNERS,CatalogReleasePreflight.Blocker.UNKNOWN_DESCENDANT_OWNERSHIP,
            CatalogReleasePreflight.Blocker.UNKNOWN_DESCRIPTOR_OWNERSHIP)
    }
    @Test fun pendingOperationBlocks() { blocked(evaluate(snapshot().copy(pendingOperation="pending")),CatalogReleasePreflight.Blocker.PENDING_CATALOG_OPERATION) }
    @Test fun policyCannotBeRelaxed() { val a=snapshot(); blocked(evaluate(a,a.copy(policy=CanonicalJournalRetention.Policy(128,2000))),CatalogReleasePreflight.Blocker.HISTORY_CHANGED) }
    @Test fun retainedCapacityExactBoundary() {
        assertTrue(evaluate(snapshot(listOf(record(bytes=999)))) is CatalogReleasePreflight.Result.ObservedForReview)
        blocked(evaluate(snapshot(listOf(record(bytes=1000)))),CatalogReleasePreflight.Blocker.CAPACITY_EXHAUSTED)
        blocked(evaluate(snapshot(listOf(record(bytes=1001)))),CatalogReleasePreflight.Blocker.INVALID_INPUT)
    }
    @Test fun entryCapacityExactBoundary() {
        val a=snapshot().copy(policy=CanonicalJournalRetention.Policy(2,1000))
        assertTrue(evaluate(a) is CatalogReleasePreflight.Result.ObservedForReview)
        blocked(evaluate(a.copy(records=listOf(record(),record("two")))),CatalogReleasePreflight.Blocker.CAPACITY_EXHAUSTED)
    }
    @Test fun metadataReserveExactBoundary() {
        val records=(0..6).map { record(it.toString()).copy(encoded="x".repeat(524288)) }
        val a=snapshot(records)
        assertTrue(evaluate(a,ids=listOf("0")) is CatalogReleasePreflight.Result.ObservedForReview)
        blocked(evaluate(a.copy(records=records+record("seven")),ids=listOf("0")),CatalogReleasePreflight.Blocker.METADATA_CAPACITY_EXHAUSTED)
    }
    @Test fun overflowFailsClosed() {
        val a=snapshot(listOf(record(bytes=Long.MAX_VALUE),record("two",1))).copy(policy=CanonicalJournalRetention.Policy(128,Long.MAX_VALUE))
        blocked(evaluate(a),CatalogReleasePreflight.Blocker.OVERFLOW)
    }
    @Test fun custodyUnderchargeFailsClosed() { blocked(evaluate(snapshot(listOf(record().copy(retainedBytes=0)))),CatalogReleasePreflight.Blocker.INVALID_INPUT) }
    @Test fun custodyChargeOverflowFailsClosed() {
        val r=record(); val c=r.custody.map { it.copy(version=it.version.copy(size=Long.MAX_VALUE)) }
        blocked(evaluate(snapshot(listOf(r.copy(custody=c)))),CatalogReleasePreflight.Blocker.OVERFLOW)
    }
    @Test fun negativeAccountingFailsClosed() { blocked(evaluate(snapshot(listOf(record(bytes=-1)))),CatalogReleasePreflight.Blocker.INVALID_INPUT) }
    @Test fun malformedVersionFailsClosed() { blocked(evaluate(snapshot().copy(catalog=version().copy(sha256="bad"))),CatalogReleasePreflight.Blocker.INVALID_INPUT) }
    @Test fun resultDoesNotAliasInputCollections() {
        val custody=record().custody.toMutableList(); val records=mutableListOf(record().copy(custody=custody)); val ids=mutableListOf("one")
        val a=snapshot(records); val result=evaluate(a,ids=ids) as CatalogReleasePreflight.Result.ObservedForReview
        custody.clear(); records.clear(); ids.clear()
        assertEquals(3,result.observation.selected.single().custody.size)
        assertEquals("one",result.observation.selected.single().id)
    }
    @Test fun capturedHistoryCannotBeRewrittenThroughSourceList() {
        val records=mutableListOf(record()); val a=snapshot(records)
        records.add(record("two")); val b=snapshot(records)
        assertEquals(1,a.records.size)
        blocked(evaluate(a,b),CatalogReleasePreflight.Blocker.HISTORY_CHANGED)
    }
    @Test fun capturedCustodyCannotBeRewrittenThroughSourceList() {
        val custody=record().custody.toMutableList(); val a=snapshot(listOf(record().copy(custody=custody)))
        custody[0]=custody[0].copy(version=custody[0].version.copy(identity="replacement"))
        val b=snapshot(listOf(record().copy(custody=custody)))
        blocked(evaluate(a,b),CatalogReleasePreflight.Blocker.CUSTODY_CHANGED)
    }
    @Test fun snapshotAndRecordCollectionsRejectMutation() {
        val a=snapshot()
        try { (a.records as MutableList).clear(); fail("mutable records") } catch (_: UnsupportedOperationException) {}
        try { (a.records[0].custody as MutableList).clear(); fail("mutable custody") } catch (_: UnsupportedOperationException) {}
    }
    @Test fun repeatedReviewDoesNotMutateInputs() {
        val a=snapshot(); val copy=a.copy(records=a.records.toList())
        assertEquals(evaluate(a),evaluate(a)); assertEquals(copy.records,a.records); assertEquals(copy.scope,a.scope)
    }
}
