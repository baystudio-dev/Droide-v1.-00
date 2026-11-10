package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalCommandProjectionSnapshotTest {
    private fun fixture(block: (java.io.File, Map<String, String>) -> Unit) {
        val directory = Files.createTempDirectory("droide-command-snapshot-").toFile()
        val expected = mapOf("tool" to "#!/bin/sh\nexec '/package/tool' \"\$@\"\n")
        expected.forEach { (name, text) -> directory.resolve(name).apply { writeText(text); check(setExecutable(true)) } }
        try { block(directory, expected) } finally { PathSecurity.deleteTreeNoFollow(directory) }
    }

    @Test fun exactProjectionMatchesWithoutRewritingAnyInode() = fixture { directory, expected ->
        val file = directory.resolve("tool")
        val before = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        repeat(100) { assertTrue(LocalCommandProjectionSnapshot.matches(directory, expected)) }
        val after = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
    }

    @Test fun modifiedMissingExtraAndNonexecutableShimsDoNotSkipRecovery() = fixture { directory, expected ->
        val file = directory.resolve("tool")
        file.writeText("broken"); assertFalse(LocalCommandProjectionSnapshot.matches(directory, expected))
        file.writeText(expected.getValue("tool"))
        check(file.setExecutable(false, false)); assertFalse(LocalCommandProjectionSnapshot.matches(directory, expected))
        check(file.setExecutable(true))
        directory.resolve("obsolete").writeText("extra")
        assertFalse(LocalCommandProjectionSnapshot.matches(directory, expected))
        directory.resolve("obsolete").delete(); file.delete()
        assertFalse(LocalCommandProjectionSnapshot.matches(directory, expected))
    }

    @Test fun symlinkedShimIsNotAdmittedEvenWhenItHasTheExpectedBytes() = fixture { directory, expected ->
        val outside = Files.createTempFile("droide-preserved-shim-", ".sh").toFile()
        try {
            outside.writeText(expected.getValue("tool")); outside.setExecutable(true)
            directory.resolve("tool").delete()
            Files.createSymbolicLink(directory.resolve("tool").toPath(), outside.toPath())
            assertFalse(LocalCommandProjectionSnapshot.matches(directory, expected))
            assertEquals(expected.getValue("tool"), outside.readText())
        } finally { outside.delete() }
    }
}
