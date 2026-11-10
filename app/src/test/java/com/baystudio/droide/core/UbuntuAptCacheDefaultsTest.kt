package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UbuntuAptCacheDefaultsTest {
    private fun fixture(block: (File, File) -> Unit) {
        val root = Files.createTempDirectory("droide-apt-defaults").toFile()
        try {
            val directory = File(root, "etc/apt/apt.conf.d").apply { mkdirs() }
            block(root, File(directory, "99droide-cache-defaults"))
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveDefaultsAreWrittenOnceWithoutReplacingUserOverrides() = fixture { root, target ->
        UbuntuAptCacheDefaults.install(root); assertEquals(UbuntuAptCacheDefaults.CONTENT, target.readText())
        target.writeText("APT::Keep-Downloaded-Packages \"true\";\n")
        UbuntuAptCacheDefaults.install(root); assertEquals("APT::Keep-Downloaded-Packages \"true\";\n", target.readText())
    }

    @Test fun settingDefaultsLeavesIndexesAndExistingArchivesUntouched() = fixture { root, _ ->
        val files = listOf("var/lib/apt/lists/ubuntu_InRelease", "var/cache/apt/archives/user.deb")
            .map { File(root, it).apply { parentFile!!.mkdirs(); writeText("keep") } }
        UbuntuAptCacheDefaults.install(root); files.forEach { assertEquals("keep", it.readText()) }
    }

    @Test fun symlinkedConfigurationCannotOverwriteFilesOutsideTheGuest() = fixture { root, target ->
        val outside = File(root, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(target.toPath(), outside.toPath())
        assertFalse(UbuntuAptCacheDefaults.install(root))
        assertEquals("keep", outside.readText())
    }

    @Test fun unavailableConfigCheckpointDoesNotBlockOpeningAnExistingGuest() = fixture { root, target ->
        val pending = File(target.parentFile, ".99droide-cache-defaults.next").apply { mkdir() }
        File(pending, "keep.txt").writeText("keep")
        assertFalse(UbuntuAptCacheDefaults.install(root)); assertFalse(target.exists())
        assertEquals("keep", File(pending, "keep.txt").readText())
    }

    @Test fun terminalDefaultsSkipTranslationsWithoutChangingRepositoryTrust() = fixture { root, target ->
        assertTrue(UbuntuAptCacheDefaults.install(root))
        val config = File(target.parentFile, UbuntuAptCacheDefaults.INDEX_FILE)
        assertEquals(UbuntuAptCacheDefaults.INDEX_CONTENT, config.readText())
        assertFalse(config.readText().contains("AllowUnauthenticated"))
        assertFalse(config.readText().contains("Check-Valid-Until"))
    }
    @Test fun existingFlatNestedOrMainLanguageSettingsArePreserved() = fixture { root, target ->
        for ((path, text) in listOf(
            "etc/apt/apt.conf.d/00user" to "Acquire::Languages { \"en\"; \"id\"; };\n",
            "etc/apt/apt.conf.d/90user" to "Acquire { Languages \"en\"; };\n",
            "etc/apt/apt.conf" to "Acquire::Languages \"id\";\n")) {
            val custom = File(root, path).apply { writeText(text) }
            UbuntuAptCacheDefaults.install(root)
            assertEquals(text, custom.readText())
            assertFalse(File(target.parentFile, UbuntuAptCacheDefaults.INDEX_FILE).exists())
            custom.delete()
        }
    }
    @Test fun laterUserLanguageSettingRemovesOnlyTheExactOwnedDefault() = fixture { root, target ->
        UbuntuAptCacheDefaults.install(root)
        val config = File(target.parentFile, UbuntuAptCacheDefaults.INDEX_FILE)
        File(target.parentFile, "20user").writeText("Acquire::Languages \"en\";\n")
        UbuntuAptCacheDefaults.install(root); assertFalse(config.exists())
        config.writeText("// my custom file\nAcquire::Languages \"id\";\n")
        UbuntuAptCacheDefaults.install(root); assertEquals("// my custom file\nAcquire::Languages \"id\";\n", config.readText())
    }
    @Test fun symlinkedOrUncertainExternalConfigDoesNotInstallAnIndexOverride() = fixture { root, target ->
        val outside = File(root, "keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(target.parentFile, "50user").toPath(), outside.toPath())
        UbuntuAptCacheDefaults.install(root)
        assertFalse(File(target.parentFile, UbuntuAptCacheDefaults.INDEX_FILE).exists())
        assertEquals("keep", outside.readText())
    }
}
