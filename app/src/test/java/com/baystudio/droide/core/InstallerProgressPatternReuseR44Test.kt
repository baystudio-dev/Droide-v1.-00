package com.baystudio.droide.core

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class InstallerProgressPatternReuseR44Test {
    @Test fun redactionPatternsAreCompiledOnceForAllLogInstances() {
        val fields = InstallerProgressLog::class.java.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && it.type == Regex::class.java
        }
        assertEquals("Five immutable patterns must be reused rather than recompiled per chunk", 5, fields.size)
        val before = fields.map { it.isAccessible = true; it.get(null) }
        repeat(8) { InstallerProgressLog().append("Authorization: Bearer test-value\n") }
        fields.zip(before).forEach { (field, pattern) -> assertSame(pattern, field.get(null)) }
    }

    @Test fun sharedPatternsNeverShareLogStateOrCredentialFragments() {
        val first = InstallerProgressLog(256)
        val second = InstallerProgressLog(512)
        first.append("Authorization: Bearer ")
        assertEquals("second operation\n", second.append("second operation\n"))
        val redacted = first.append("first-private-token\nfirst operation\n")
        assertFalse(redacted.contains("first-private-token"))
        assertFalse(redacted.contains("second operation"))
        first.clear()
        assertEquals("fresh\n", first.append("fresh\n"))
        assertEquals("second operation\ncontinued\n", second.append("continued\n"))
    }

    @Test fun smallChunkRedactionAndTailBoundsStayUnchanged() {
        val log = InstallerProgressLog(256)
        val chunks = listOf("\u001b[32mprogress\u001b[0m\n", "https://name:", "private@example.com/file\n",
            "api_key=", "sensitive\n", "Authorization: Basic ", "private-encoded\n", "ghp_1234", "5678secret\n")
        var out = ""
        chunks.forEach { out = log.append(it) }
        for (secret in listOf("private@example", "sensitive", "private-encoded", "ghp_12345678secret")) {
            assertFalse(secret, out.contains(secret))
        }
        assertTrue(out.contains("progress"))
        assertEquals(256, log.append("x".repeat(40_000)).length)
    }
}
