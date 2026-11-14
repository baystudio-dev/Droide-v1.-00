package com.baystudio.droide.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Host boundary controls; neither Android device nor native-runtime certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, shadows = [VueHostFileStat::class])
class VueHostFileStatTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryFilePreservesRealIdentityAndSingleLink() {
        val file = temporary.newFile("ordinary.txt").apply { writeText("original") }
        val stat = Os.lstat(file.path)
        assertEquals(1L, stat.st_nlink)
        assertTrue(OsConstants.S_ISREG(stat.st_mode))
        assertEquals((Files.getAttribute(file.toPath(), "unix:ino", NOFOLLOW_LINKS) as Number).toLong(), stat.st_ino)
        assertEquals(file.length(), stat.st_size)
        assertEquals("original", LocalUbuntuVuePolicy.read(file))
    }

    @Test fun hardLinksReportBothNamesAndPolicyRejectsAliasedOriginal() {
        val original = temporary.newFile("original.txt").apply { writeText("original") }
        val alias = temporary.root.toPath().resolve("alias.txt")
        Files.createLink(alias, original.toPath())
        val left = Os.lstat(original.path)
        val right = Os.lstat(alias.toString())
        assertTrue(left.st_nlink >= 2L)
        assertEquals(left.st_nlink, right.st_nlink)
        assertEquals(left.st_dev, right.st_dev)
        assertEquals(left.st_ino, right.st_ino)
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuVuePolicy.read(original) }
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuVuePolicy.read(alias.toFile()) }
    }

    @Test fun symlinkIsObservedWithoutFollowingItsTarget() {
        val original = temporary.newFile("target.txt").apply { writeText("original") }
        val link = temporary.root.toPath().resolve("link.txt")
        Files.createSymbolicLink(link, original.toPath())
        val stat = Os.lstat(link.toString())
        assertTrue(OsConstants.S_ISLNK(stat.st_mode))
        assertFalse(OsConstants.S_ISREG(stat.st_mode))
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuVuePolicy.read(link.toFile()) }
        assertEquals("original", original.readText())
    }

    @Test fun danglingSymlinkStillHasItsOwnStat() {
        val link = temporary.root.toPath().resolve("dangling")
        Files.createSymbolicLink(link, temporary.root.toPath().resolve("absent"))
        assertTrue(OsConstants.S_ISLNK(Os.lstat(link.toString()).st_mode))
        assertThrows(IllegalArgumentException::class.java) { LocalUbuntuVuePolicy.read(link.toFile()) }
    }

    @Test fun missingPathReportsEnoentInsteadOfSyntheticSuccess() {
        val missing = temporary.root.toPath().resolve("missing")
        val failure = assertThrows(ErrnoException::class.java) { Os.lstat(missing.toString()) }
        assertEquals(OsConstants.ENOENT, failure.errno)
        assertTrue(failure.cause is java.nio.file.NoSuchFileException)
    }
}
