package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class BrowserSessionApprovalScopeTest {
    @Test fun originUsesTheExactLiteralHostSchemeAndEffectivePortWithoutPathSecrets() {
        assertEquals("http://localhost:3000", BrowserSessionApprovalScope.canonicalOrigin("HTTP://LOCALHOST:3000/a?token=fixture#private"))
        assertEquals("http://localhost:80", BrowserSessionApprovalScope.canonicalOrigin("http://localhost/a"))
        assertEquals("http://localhost:80", BrowserSessionApprovalScope.canonicalOrigin("http://localhost:80/b"))
        assertEquals("https://localhost:443", BrowserSessionApprovalScope.canonicalOrigin("https://localhost/"))
        assertEquals("http://127.0.0.1:3000", BrowserSessionApprovalScope.canonicalOrigin("http://127.0.0.1:3000/"))
        assertEquals("http://[::1]:3000", BrowserSessionApprovalScope.canonicalOrigin("http://[::1]:3000/"))
    }

    @Test fun privateNetworksAliasesCredentialsAndMalformedOriginsCannotCreateLocalCapabilities() {
        for (url in listOf(
            "https://example.test/a", "http://192.168.1.2:3000/a", "http://10.0.0.1/a",
            "http://localhost.example.test/a", "http://localhost./a", "http://127.0.0.2/a",
            "http://[0:0:0:0:0:0:0:1]:3000/a", "http://[::ffff:127.0.0.1]:3000/a",
            "http://user:fixture@localhost:3000/a", "http://@localhost:3000/a",
            "http://%6cocalhost:3000/a", "http://local*host:3000/a", "http://localhost:*/a",
            "http://localhost:0/a", "http://localhost:65536/a", "http://localhost:-2/a",
            "file://localhost/a", "content://localhost/a", "javascript:http://localhost/a",
            "http://localhost:3000/a\n", "http://localhost:3000/a\u0000", "http://localhost:3000/" + "x".repeat(8_192),
        )) assertNull("No local origin authority for $url", BrowserSessionApprovalScope.canonicalOrigin(url))
    }

    @Test fun browserReadIdentityIsSeparateFromTheOrdinaryFetchUrlAndPublicFetchesStayExact() {
        assertEquals("browser-read-origin:http://localhost:3000", BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3000/a?q=1#part"))
        assertEquals(
            BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3000/a"),
            BrowserSessionApprovalScope.browserReadGrantResource("http://localhost:3000/b?new=2"),
        )
        for (url in listOf("https://example.test/a?private=fixture#part", "http://192.168.1.2:3000/a", "http://user@localhost:3000/a")) {
            assertEquals(url, BrowserSessionApprovalScope.browserReadGrantResource(url))
        }
    }

    @Test fun dialogResponseRemainsMutationWhileDialogInspectionRemainsReadOnly() {
        assertTrue(AgentRepairCompletionPolicy.browserInteracts("dialog_respond"))
        assertFalse(AgentRepairCompletionPolicy.browserInteracts("dialog"))
        assertFalse(AgentRepairCompletionPolicy.browserInteracts("console"))
        assertEquals("http://localhost:3000/actual", AgentRepairCompletionPolicy.browserPermissionTarget("dialog_respond", "https://unrelated.example.test", "http://localhost:3000/actual"))
    }
}
