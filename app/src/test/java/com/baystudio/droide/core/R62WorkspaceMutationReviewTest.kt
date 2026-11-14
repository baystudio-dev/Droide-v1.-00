package com.baystudio.droide.core

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

/** Exact production snapshot and review helper; no Android/editor runtime is exercised. */
class R62WorkspaceMutationReviewTest {
    private fun baseline() = WorkspaceDocumentSnapshot(
        path = "src/main.kt", content = "alpha\nbeta", loaded = true,
        kind = WorkspaceDocumentKind.TEXT, dirty = true, editable = true,
        revision = 7, changeVersion = 11, selectionStart = 2, selectionEnd = 5,
        savedContentLength = 8, documentId = "review-document",
    )

    private fun assertConflict(changed: WorkspaceDocumentSnapshot) {
        val base = baseline()
        assertFalse(base.sameReviewedMutationState(changed))
        assertFalse(changed.sameReviewedMutationState(base))
        assertFalse(sameReviewedWorkspaceMutation(
            mapOf(base.path to "saved"), mapOf(base.path to base),
            mapOf(base.path to "saved"), mapOf(base.path to changed),
        ))
    }

    @Test fun identicalSnapshotsRemainEqual() {
        val base = baseline()
        assertTrue(base.sameReviewedMutationState(base.copy()))
        assertTrue(sameReviewedWorkspaceMutation(
            mapOf(base.path to "saved"), mapOf(base.path to base),
            mapOf(base.path to "saved"), mapOf(base.path to base.copy()),
        ))
    }

    @Test fun caretAndSelectionChangesDoNotInvalidateReview() {
        val base = baseline()
        for (start in 0..base.content.length) {
            for (end in 0..base.content.length) {
                val moved = base.copy(selectionStart = start, selectionEnd = end)
                assertTrue("selection $start:$end", base.sameReviewedMutationState(moved))
                assertTrue(moved.sameReviewedMutationState(base))
                assertTrue(sameReviewedWorkspaceMutation(
                    mapOf(base.path to "saved"), mapOf(base.path to base),
                    mapOf(base.path to "saved"), mapOf(base.path to moved),
                ))
            }
        }
    }

    @Test fun largeReadOnlySelectionsBeyondCapturedTextRemainPresentationOnly() {
        val base = baseline().copy(kind = WorkspaceDocumentKind.LARGE, editable = false)
        val moved = base.copy(selectionStart = 1_000_000, selectionEnd = 2_000_000)
        assertTrue(base.sameReviewedMutationState(moved))
    }

    @Test fun pathStillConflicts() = assertConflict(baseline().copy(path = "src/other.kt"))
    @Test fun contentStillConflicts() = assertConflict(baseline().copy(content = "ALPHA\nbeta"))
    @Test fun loadedStillConflicts() = assertConflict(baseline().copy(loaded = false))
    @Test fun kindStillConflicts() = assertConflict(baseline().copy(kind = WorkspaceDocumentKind.BINARY))
    @Test fun dirtyStillConflicts() = assertConflict(baseline().copy(dirty = false))
    @Test fun editableStillConflicts() = assertConflict(baseline().copy(editable = false))
    @Test fun revisionStillConflicts() = assertConflict(baseline().copy(revision = 8))
    @Test fun changeVersionStillConflicts() = assertConflict(baseline().copy(changeVersion = 12))
    @Test fun savedContentLengthStillConflicts() = assertConflict(baseline().copy(savedContentLength = 9))

    @Test fun documentIdentityStillConflicts() = assertConflict(baseline().copy(documentId = "replacement-document"))
    @Test fun missingDocumentIdentityCannotMatchScopedReview() = assertConflict(baseline().copy(documentId = null))

    @Test fun snapshotFieldInventoryMakesEveryNonCaretFieldExplicit() {
        val fields = WorkspaceDocumentSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        assertEquals(setOf(
            "path", "content", "loaded", "kind", "dirty", "editable", "revision",
            "changeVersion", "selectionStart", "selectionEnd", "savedContentLength", "documentId",
        ), fields)
    }

    @Test fun snapshotConstructorValidationIsTheRealProductionValidation() {
        assertThrows(IllegalArgumentException::class.java) { baseline().copy(path = " ") }
        assertThrows(IllegalArgumentException::class.java) { baseline().copy(revision = -1) }
        assertThrows(IllegalArgumentException::class.java) { baseline().copy(changeVersion = -1) }
        assertThrows(IllegalArgumentException::class.java) { baseline().copy(savedContentLength = -1) }
        assertThrows(IllegalArgumentException::class.java) { baseline().copy(selectionEnd = 99) }
        assertThrows(IllegalArgumentException::class.java) {
            baseline().copy(kind = WorkspaceDocumentKind.LARGE, editable = false, selectionStart = -1)
        }
    }

    @Test fun persistedBeforeContentStillConflicts() {
        val base = baseline()
        assertFalse(sameReviewedWorkspaceMutation(
            mapOf(base.path to "saved"), mapOf(base.path to base),
            mapOf(base.path to "changed on disk"), mapOf(base.path to base),
        ))
    }

    @Test fun absentNullAndEmptyPersistedBeforeEntriesRemainDistinct() {
        val live = mapOf(baseline().path to baseline())
        val values: List<Map<String, String?>> = listOf(emptyMap(), mapOf("new.kt" to null), mapOf("new.kt" to ""))
        for (left in values.indices) for (right in values.indices) {
            assertEquals(left == right, sameReviewedWorkspaceMutation(values[left], live, values[right], live))
        }
    }

    @Test fun addedRemovedAndRemappedLiveKeysStillConflict() {
        val base = baseline()
        val first = mapOf(base.path to base)
        val added = first + ("extra.kt" to base.copy(path = "extra.kt"))
        assertFalse(sameReviewedWorkspaceMutation(emptyMap(), first, emptyMap(), emptyMap()))
        assertFalse(sameReviewedWorkspaceMutation(emptyMap(), first, emptyMap(), added))
        assertFalse(sameReviewedWorkspaceMutation(emptyMap(), added, emptyMap(), first))
        assertFalse(sameReviewedWorkspaceMutation(emptyMap(), first, emptyMap(), mapOf("renamed.kt" to base)))
    }

    @Test fun laterLiveEntryCannotHideConflictAndMapOrderDoesNotMatter() {
        val base = baseline()
        val second = base.copy(path = "src/second.kt")
        val live = linkedMapOf(base.path to base, second.path to second)
        val reordered = linkedMapOf(second.path to second.copy(selectionStart = 0), base.path to base)
        assertTrue(sameReviewedWorkspaceMutation(emptyMap(), live, emptyMap(), reordered))
        assertFalse(sameReviewedWorkspaceMutation(
            emptyMap(), live, emptyMap(), reordered + (second.path to second.copy(revision = 8)),
        ))
    }

    @Test fun emptyReviewedWorkspacesAreEqual() {
        assertTrue(sameReviewedWorkspaceMutation(emptyMap(), emptyMap(), emptyMap(), emptyMap()))
    }
}
