package com.baystudio.droide.core
import java.io.File
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

class ElpToolSelectionTest {
    @Test fun finiteCompatibilityHasNoNewerOrPartialFallback() {
        for (otp in listOf("27.3", "27.3.4.1", "27.4", "28.0", "28.1")) assertTrue(ElpToolPolicy.compatible(otp, null, false))
        for (otp in listOf("25.3", "27", "27.2", "27.3-rc1", "28", "29.0", "27.03", "garbage")) assertFalse(ElpToolPolicy.compatible(otp, "3.25.1", true))
        assertTrue(ElpToolPolicy.compatible("27.3", "3.24.0", true))
        assertTrue(ElpToolPolicy.compatible("28.0", "3.25.1", true))
        assertFalse(ElpToolPolicy.compatible("28.0", "3.24.0", true))
        for (rebar in listOf(null, "3.19.0", "3.27.3", "3.25.1-dev")) assertFalse(ElpToolPolicy.compatible("27.3", rebar, true))
    }
    @Test fun guestAbsoluteOtpAliasesAreResolvedInsideGuestWithoutExecution() {
        val root = Files.createTempDirectory("elp-authored-guest").toFile()
        val bin = java.io.File(root, "usr/bin").apply { mkdirs() }
        val otp = java.io.File(root, "usr/lib/erlang/bin").apply { mkdirs() }
        val erl = java.io.File(otp, "erl"); erl.writeText("#!/bin/sh\n# ERL_ROOTDIR\nexec erlexec\n")
        Files.setPosixFilePermissions(erl.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        Files.createSymbolicLink(java.io.File(bin, "erl").toPath(), java.nio.file.Path.of("/usr/lib/erlang/bin/erl"))
        val selected = ElpGuestFilesystem(root, null).candidate("erl", ElpDeadline.after(5000))!!
        assertEquals("/usr/bin/erl", selected.file.guestPath)
        assertEquals("/usr/lib/erlang/bin/erl", selected.file.canonicalGuestPath)
        assertEquals("discovered-unverified", selected.provenance)
        erl.appendText("# changed\n")
        assertThrows(IllegalArgumentException::class.java) { ElpGuestFilesystem(root, null).verify(selected, ElpDeadline.after(5000)) }
    }
    @Test fun dynamicVersionManagerShimDoesNotExecuteOrFallThrough() {
        val root = Files.createTempDirectory("elp-authored-shim").toFile()
        val shim = java.io.File(root, "root/.local/share/mise/shims/erl"); shim.parentFile.mkdirs(); shim.writeText("#!/bin/sh\nexit 0\n"); shim.setExecutable(true)
        assertThrows(IllegalArgumentException::class.java) { ElpGuestFilesystem(root, null).candidate("erl", ElpDeadline.after(5000)) }
    }
    @Test fun metadataReadRecordsAbsenceAndRejectsEscapingProjectAlias() {
        val root = Files.createTempDirectory("elp-authored-root").toFile()
        val workspace = Files.createTempDirectory("elp-authored-workspace").toFile()
        val files = ElpGuestFilesystem(root, workspace)
        assertEquals("absent", files.observe(workspace.absolutePath + "/.elp.toml", ElpDeadline.after(5000)).kind)
        val outside = java.io.File(root, "etc/config").apply { parentFile.mkdirs(); writeText("bad") }
        Files.createSymbolicLink(java.io.File(workspace, ".elp.toml").toPath(), java.nio.file.Path.of("/etc/config"))
        assertThrows(IllegalArgumentException::class.java) { files.observe(workspace.absolutePath + "/.elp.toml", ElpDeadline.after(5000)) }
        assertTrue(outside.exists())
    }

    @Test fun explicitBoundFileMustNotRequireAndroidParentsInsideGuestRootfs() {
        val guest = Files.createTempDirectory("elp-authored-empty-guest").toFile()
        val source = Files.createTempDirectory("elp-authored-source-bind").toFile()
        java.io.File(source, "module.erl").writeText("-module(module).\n")
        val seen = ElpGuestFilesystem(guest, source).observe(source.absolutePath + "/module.erl", ElpDeadline.after(5000))
        assertEquals("regular", seen.kind)
    }

    @Test fun absoluteGuestParentAliasCannotBeMisreportedAsAbsent() {
        val root = Files.createTempDirectory("elp-authored-alias-guest").toFile()
        val source = Files.createTempDirectory("elp-authored-alias-source").toFile()
        File(source,"nested").mkdir()
        File(source,"nested/.elp.toml").writeText("[rebar]\n")
        Files.createSymbolicLink(File(source,"alias").toPath(), Path.of("/workspace/nested"))
        val observed = ElpGuestFilesystem(root,source,"/workspace").observe("/workspace/alias/.elp.toml",ElpDeadline.after(5000))
        assertEquals("regular", observed.kind)
        assertEquals("/workspace/nested/.elp.toml", observed.identity!!.canonicalGuestPath)
    }

    @Test fun fixedMappingObservesNestedAndActualGuestAncestors() {
        val guest = Files.createTempDirectory("elp-authored-ancestor-guest").toFile()
        val source = Files.createTempDirectory("elp-authored-ancestor-source").toFile()
        val nested = File(source, "apps/sample").apply { mkdirs() }
        val module = File(nested, "sample.erl").apply { writeText("-module(sample).\n") }
        File(guest, ".elp.toml").writeText("[rebar]\n")
        val captured = ElpMetadataInspection.inspect(ElpGuestFilesystem(guest, source, "/workspace"), source.absolutePath,
            module.absolutePath, ElpDeadline.after(5000))
        assertEquals("/workspace/apps/sample/sample.erl", captured.guestPath)
        assertEquals(listOf("/workspace/apps/sample", "/workspace/apps", "/workspace", "/"), captured.ancestors.map { it.guestPath })
        assertEquals("regular", captured.ancestors.last().markers.getValue(".elp.toml").kind)
        assertFalse(captured.ancestors.any { it.guestPath.startsWith(source.absolutePath) })
        val otherGuest = Files.createTempDirectory("elp-authored-changed-mapping").toFile()
        assertNotEquals(ElpGuestFilesystem(guest, source, "/workspace").mappingIdentity,
            ElpGuestFilesystem(otherGuest, source, "/workspace").mappingIdentity)
    }

    @Test fun danglingSelectedEntryNeverFallsThroughToAnotherExecutable() {
        val guest = Files.createTempDirectory("elp-authored-dangling-tool").toFile()
        File(guest, "usr/bin").mkdirs()
        Files.createSymbolicLink(File(guest, "usr/bin/erl").toPath(), Path.of("/usr/lib/erlang/bin/missing"))
        assertThrows(IllegalArgumentException::class.java) { ElpGuestFilesystem(guest, null).candidate("erl", ElpDeadline.after(5000)) }
    }
}
