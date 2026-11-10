package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuCSharpLaunchAuthorityTest {
    @Test fun allFourAuthoredHelpersAreBoundAndIndependent() {
        val hashes=LocalUbuntuCSharpLaunchAuthority.expectedHelperHashes
        assertEquals(4,hashes.size)
        assertEquals(LocalUbuntuCSharpLaunchAuthority.helperPaths.toSet(),hashes.keys)
        assertTrue(hashes.values.all {it.matches(Regex("[0-9a-f]{64}"))})
        assertTrue(LspCSharpLaunchPolicy.HELPER.contains("runtime.csharp-helper"))
        assertFalse(LspCSharpLaunchPolicy.HELPER.contains("vue"))
        assertFalse(LspCSharpLaunchPolicy.HELPER.contains("nim"))
    }
    @Test fun standaloneWrappersCannotStartAnalysis() {
        val wrapper=LocalUbuntuCSharpLaunchAuthority.guestWrapper("runtime.csharp-sdk",LspCSharpLaunchPolicy.SDK_ROOT.removeSuffix("/payload/tree"))
        assertTrue(wrapper.contains("exit 64"));assertFalse(wrapper.contains("--health ]"))
        assertTrue(runCatching { LocalUbuntuCSharpLaunchAuthority.guestWrapper("runtime.vue-helper","/other") }.isFailure)
    }
}
