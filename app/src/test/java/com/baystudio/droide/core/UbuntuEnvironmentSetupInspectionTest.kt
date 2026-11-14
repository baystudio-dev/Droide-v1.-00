package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UbuntuEnvironmentSetupInspectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun freshAndInterruptedStagingDoNotPretendToBeInstalled() {
        val files = temporary.newFolder()
        assertFalse(UbuntuEnvironmentSetup.existingEnvironment(files))
        files.resolve("guest/.staging/incomplete/rootfs").mkdirs()
        assertFalse(UbuntuEnvironmentSetup.existingEnvironment(files))
    }

    @Test fun retainedGuestIsNeverOfferedAsAFreshInstallEvenWithoutPreferences() {
        val files = temporary.newFolder()
        val guest = files.resolve("guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")
        guest.mkdirs(); guest.resolve("user-work.txt").writeText("keep me")
        repeat(10) { assertTrue(UbuntuEnvironmentSetup.existingEnvironment(files)) }
        assertEquals("keep me", guest.resolve("user-work.txt").readText())
    }

    @Test fun interruptedMigrationKeepsThePreviousGuest() {
        val files = temporary.newFolder()
        val previous = files.resolve("guest/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")
        previous.mkdirs(); previous.resolve("draft.txt").writeText("unsaved work")
        assertTrue(UbuntuEnvironmentSetup.existingEnvironment(files))
        assertEquals("unsaved work", previous.resolve("draft.txt").readText())
    }

    @Test fun unexpectedFilesAndSymlinksFailClosedWithoutFollowingOrRemovingThem() {
        for (relative in listOf("guest", "guest/.previous", "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")) {
            val files = temporary.newFolder()
            val path = files.resolve(relative); path.parentFile!!.mkdirs(); path.writeText("existing data")
            assertThrows(IllegalStateException::class.java) { UbuntuEnvironmentSetup.existingEnvironment(files) }
            assertEquals("existing data", path.readText())
            path.delete()
            val outside = temporary.newFolder()
            outside.resolve("keep.txt").writeText("private")
            Files.createSymbolicLink(path.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { UbuntuEnvironmentSetup.existingEnvironment(files) }
            assertTrue(Files.isSymbolicLink(path.toPath()))
            assertEquals("private", outside.resolve("keep.txt").readText())
        }
    }
}
