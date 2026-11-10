package com.baystudio.droide.core

/** Caret/selection movement is presentation state, not a new edit proposal. */
internal fun WorkspaceDocumentSnapshot.sameReviewedMutationState(other: WorkspaceDocumentSnapshot): Boolean =
    copy(selectionStart = 0, selectionEnd = 0) == other.copy(selectionStart = 0, selectionEnd = 0)

internal fun sameReviewedWorkspaceMutation(
    before: Map<String, String?>,
    live: Map<String, WorkspaceDocumentSnapshot>,
    otherBefore: Map<String, String?>,
    otherLive: Map<String, WorkspaceDocumentSnapshot>,
): Boolean = before == otherBefore && live.keys == otherLive.keys &&
    live.all { (path, snapshot) -> snapshot.sameReviewedMutationState(otherLive.getValue(path)) }
