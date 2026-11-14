package com.baystudio.droide.ui

import com.baystudio.droide.core.AgentFileChanges
import org.junit.Assert.*
import org.junit.Test

class AgentLegacyDiffIdentityTest {
    @Test fun patchWithoutInputPathUsesActualRenameTargetAndPreviousPath() {
        val diff = AgentFileChanges.preview("new.tsx", "old\n", "new\n", previousPath = "old.py").diff
        val change = requireNotNull(legacyFileChange(diff, ""))
        assertEquals("new.tsx", change.path)
        assertEquals("old.py", change.previousPath)
    }

    @Test fun deletionKeepsItsOldFileIdentity() {
        val diff = AgentFileChanges.preview("removed.py", "old\n", null).diff
        assertEquals("removed.py", requireNotNull(legacyFileChange(diff, "")).path)
    }

    @Test fun multipleFilesDoNotInventASingleIdentityOrDiscardTheirHeaders() {
        val diff = AgentFileChanges.preview("one.py", null, "first\n").diff +
            AgentFileChanges.preview("two.tsx", null, "second\n").diff
        val change = requireNotNull(legacyFileChange(diff, ""))
        assertEquals("", change.path)
        assertEquals(diff, change.diff)
    }
}
