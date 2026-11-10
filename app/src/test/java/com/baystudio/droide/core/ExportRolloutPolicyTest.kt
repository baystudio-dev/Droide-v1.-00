package com.baystudio.droide.core

import com.baystudio.droide.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class ExportRolloutPolicyTest {
    @Test fun policyUsesTheActualCompiledFlag() {
        assertEquals(BuildConfig.EXPLORER_EXPORT_EXPERIMENTAL, ExportRolloutPolicy.newExportsEnabled)
    }

    @Test fun newExportGuardMatchesTheActualCompiledFlag() {
        val failure = runCatching { ExportRolloutPolicy.requireNewExportEnabled() }.exceptionOrNull()
        if (BuildConfig.EXPLORER_EXPORT_EXPERIMENTAL) {
            assertNull(failure)
        } else {
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message.orEmpty().contains("disabled"))
            assertTrue(failure.message.orEmpty().contains("recovery remains available"))
        }
    }
}
