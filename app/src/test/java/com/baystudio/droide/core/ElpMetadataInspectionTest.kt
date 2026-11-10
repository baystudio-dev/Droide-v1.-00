package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test

class ElpMetadataInspectionTest {
    private fun request(): ElpMetadataInspectionRequest {
        val file = ElpFileIdentity("/usr/bin/python3.12", "/usr/bin/python3.12", 10, "a".repeat(64), "fixture", emptyList())
        val python = ElpSelectedToolEntry("python", file, "/usr")
        val inspection = ElpSelectionInspection("/work", "/work/main.erl", "/work/main.erl", emptyList(), null, python, "b".repeat(64))
        val bytes = "[rebar]\nprofile = 'test'\n".toByteArray()
        return ElpMetadataInspectionRequest(inspection, python, "/work/.elp.toml", bytes, LspElpLaunchPolicy.sha(bytes), "attempt-1")
    }
    @Test fun metadataPermitIsOneAttemptAndCannotFollowChangedInput() {
        val request = request(); var current = true
        val permit = ElpMetadataInspectionPermit("permit", request.identity) { current }
        permit.claim(request)
        assertThrows(IllegalStateException::class.java) { permit.claim(request) }
        assertThrows(IllegalStateException::class.java) { permit.requireCurrent(request.copy(inputSha256 = "c".repeat(64))) }
        current = false
        assertThrows(IllegalStateException::class.java) { permit.requireCurrent(request) }
    }
    @Test fun helperIdentityAndProjectIdentityRemainDifferent() {
        val request = request()
        val metadata = ElpMetadataInspectionPermit("metadata", request.identity) { true }
        metadata.claim(request); metadata.invalidate(); assertFalse(metadata.isCurrent)
        assertTrue(ElpMetadataInspection.markerNames.containsAll(listOf(".elp.toml", ".elp_lint.toml", "build_info.json", "src", "include", "test")))
        assertFalse(ElpMetadataInspection.markerNames.contains("elp.json"))
    }
}
