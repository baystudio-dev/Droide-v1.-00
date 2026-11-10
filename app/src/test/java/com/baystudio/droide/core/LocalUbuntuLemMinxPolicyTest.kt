package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalUbuntuLemMinxPolicyTest {
    private val family = "lsp.lemminx"
    private val version = "0.31.2"
    private val sha = "f4fde164e785c635e5f86361dbf4b993bfe2c5f83fdb52891d058b7dfc9bcfc8"
    private val asset = "org.eclipse.lemminx-0.31.2-uber.jar"
    private val url = "https://repo.eclipse.org/repository/lemminx-maven2-releases/org/eclipse/lemminx/org.eclipse.lemminx/0.31.2/$asset"
    private val metadata get() = """{"id":"bGVtbWlueC1tYXZlbjItcmVsZWFzZXM6MWQ0Y2YwYTE","repository":"lemminx-maven2-releases","format":"maven2","contentType":"application/java-archive","path":"/org/eclipse/lemminx/org.eclipse.lemminx/0.31.2/$asset","downloadUrl":"$url","fileSize":8780504,"checksum":{"sha256":"$sha"},"maven2":{"groupId":"org.eclipse.lemminx","artifactId":"org.eclipse.lemminx","version":"0.31.2","classifier":"uber","extension":"jar"}}"""
    private val manifest = "Manifest-Version: 1.0\r\nMain-Class: org.eclipse.lemminx.XMLServerLauncher\r\n\r\n"
    private val launcher = "org/eclipse/lemminx/XMLServerLauncher.class"
    private suspend fun recipe() = LocalUbuntuVendorResolver { requested ->
        assertEquals("https://repo.eclipse.org/service/rest/v1/assets/bGVtbWlueC1tYXZlbjItcmVsZWFzZXM6MWQ0Y2YwYTE", requested)
        metadata
    }.resolve(family, version)
    private fun extension() = ExtensionFamily(family, "LemMinX", ExtensionCategory.LANGUAGE_SERVERS,
        "XML language server", listOf(ExtensionVersion(version, installKind = ExtensionInstallKind.REVIEWED_RECIPE,
            provides = setOf("lemminx"))), "xml")

    @Test fun exactNexusMetadataPreservesOriginalJarRecipeAndManagedJava() = runBlocking<Unit> {
        val r = recipe()
        r.validate()
        assertEquals(url, r.assetUrl)
        assertEquals(version, r.releaseTag)
        assertEquals(sha, r.assetSha256)
        assertEquals(8_780_504L, r.assetSize)
        assertEquals(asset, r.entryPoint)
        assertEquals("lemminx", r.command)
        assertEquals(LocalUbuntuVendorFormat.RAW, r.definition.format)
        assertEquals(LocalUbuntuVendorAdmission.INTERPRETED, r.definition.admission)
        assertEquals(1, r.definition.maxFiles)
        assertEquals(8_780_504L, r.definition.maxUnpackedBytes)
        assertEquals(8_780_504L, r.definition.maxAssetBytes)
        assertEquals(listOf("ca-certificates", "openjdk-21-jdk-headless", "nodejs"), r.requiredPackages)
        assertEquals(listOf("/usr/lib/jvm/java-21-openjdk-arm64/bin/java", "-jar"), r.definition.interpreter)
        assertTrue(r.healthArgs.isEmpty())
        assertTrue(r.definition.auxiliaryCommands.isEmpty())
        assertTrue(r.definition.lsp)
        assertFalse(r.definition.supportsPublisherVersionSelection)
        assertEquals(version, r.definition.offeredVersion)
    }

    @Test fun missingChangedMalformedAndWrongCoordinatesCannotResolve() = runBlocking<Unit> {
        val bodies = listOf("[]", "{}", "null", "{", metadata.replace("lemminx-maven2-releases", "lemminx-maven2-snapshots"),
            metadata.replace("\"0.31.2\"", "\"0.31.3\""), metadata.replace(sha, "a".repeat(64)),
            metadata.replace(url, "https://download.eclipse.org/lemminx/releases/0.31.2/org.eclipse.lemminx-uber.jar"),
            metadata.replace("8780504", "8780505"), metadata.replace("8780504", "\"8780504\""),
            metadata.replace("8780504", "8780504.0"), metadata.replace("8780504", "null"),
            metadata.replace("\"sha256\":\"$sha\"", "\"sha256\":null"),
            metadata.replace("\"classifier\":\"uber\"", "\"classifier\":\"sources\""),
            metadata.replace("\"extension\":\"jar\"", "\"extension\":\"zip\""),
            metadata.replace("\"groupId\":\"org.eclipse.lemminx\"", "\"groupId\":\"other\""),
            metadata.replace("\"artifactId\":\"org.eclipse.lemminx\"", "\"artifactId\":true"),
            metadata.replace("\"format\":\"maven2\"", "\"format\":\"raw\""),
            metadata.replace("\"contentType\":\"application/java-archive\"", "\"contentType\":{}"),
            metadata.replace("\"downloadUrl\":\"$url\",", ""),
            metadata.replace("MWQ0Y2YwYTE", "other"), " ".repeat(1_000_001))
        for (body in bodies) assertTrue(runCatching { LocalUbuntuVendorResolver { body }.resolve(family, version) }.isFailure)
    }

    @Test fun unreviewedRequestsNeverFetchOrSubstitute() = runBlocking<Unit> {
        var calls = 0
        for (v in listOf("", "latest", "Latest official", "Reviewed Ubuntu release", "0.31.1", "0.31.3", "v0.31.2")) {
            assertTrue(runCatching { LocalUbuntuVendorResolver { calls++; metadata }.resolve(family, v) }.isFailure)
        }
        assertEquals(0, calls)
    }

    @Test fun forgedRecipeCannotAlterReviewedIdentityOrReuseFixedInterpreter() = runBlocking<Unit> {
        val r = recipe()
        for (bad in listOf(r.copy(version = "0.31.3"), r.copy(releaseTag = "v0.31.2"),
            r.copy(assetSha256 = "a".repeat(64)), r.copy(assetSize = null), r.copy(assetSize = 8_780_503),
            r.copy(assetName = "org.eclipse.lemminx-uber.jar"), r.copy(assetUrl = "https://example.com/lemminx.jar"),
            r.copy(provenanceUrl = LocalUbuntuLemMinxPolicy.SEARCH_URL),
            r.copy(definition = r.definition.copy(packages = emptyList())),
            r.copy(definition = r.definition.copy(interpreter = listOf("/usr/bin/java", "-jar"))),
            r.copy(definition = r.definition.copy(familyId = "quality.ktlint")))) {
            assertTrue(runCatching { bad.validate() }.isFailure)
        }
        val old = requireNotNull(LocalUbuntuVendorCatalog.find("quality.ktlint"))
        assertEquals(listOf("/usr/bin/java", "-jar"), old.interpreter)
        assertTrue(old.supportsPublisherVersionSelection)
    }

    @Test fun pickerAndGenericUpdaterCannotBypassExactRecipe() {
        val f = extension()
        val offered = ExtensionVersionState(f, f.versions.single(), ExtensionState.AVAILABLE, "reviewed")
        assertEquals(offered, ToolVersionRequests.select(listOf(offered), f.id, version))
        for (v in listOf("latest", "0.31.1", "0.31.3", "99.0.0")) {
            val selected = requireNotNull(ToolVersionRequests.select(listOf(offered), f.id, v))
            assertEquals(ExtensionState.UNAVAILABLE, selected.state)
            assertEquals(v, selected.version.version)
            assertTrue(selected.detail.contains("no version was substituted"))
        }
        for (v in listOf(version, "latest", "99.0.0")) assertTrue(runCatching { ToolUpdatePlanner.recipe(f, v, null) }.isFailure)
        assertTrue(ToolUpdatePlanner.recipe(DevelopmentExtensionCatalog.byId("package.bun")!!, "99.0.0", null).command.contains("99.0.0"))
    }

    @Test fun structuralPositiveAndFoldedMainClassNeverAdmitSyntheticPublisherBytes() = runBlocking<Unit> {
        val r = recipe()
        withRoot { root ->
            for (text in listOf(manifest, manifest.replace("XMLServerLauncher", "XMLServer\r\n Launcher"),
                manifest.replace("\r\n\r\n", "\r\nClass-Path: \r\n\r\n"))) {
                val file = jar(root, text)
                LocalUbuntuLemMinxPolicy.requireJarStructure(file)
                assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireArtifact(file) }.isFailure)
                assertTrue(runCatching { LocalUbuntuVendorPolicy.admit(r, file) }.isFailure)
                RandomAccessFile(file, "rw").use { it.setLength(8_780_504) }
                val failure = runCatching { LocalUbuntuVendorPolicy.admit(r, file) }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains("digest"))
            }
        }
    }

    @Test fun manifestMissingWrongMainClasspathAmbiguityAndMalformedInputFailClosed() = withRoot { root ->
        for (text in listOf(manifest.replace("Main-Class", "Other-Class"), manifest.replace("XMLServerLauncher", "Other"),
            manifest.replace("\r\n\r\n", "\r\nClass-Path: project.jar\r\n\r\n"),
            manifest.replace("\r\n\r\n", "\r\nClass-Path:  \r\n\r\n"),
            manifest.replace("\r\n\r\n", "\r\nClass-Path: \r\n ../evil.jar\r\n\r\n"),
            manifest.replace("\r\n\r\n", "\r\nmain-class: org.eclipse.lemminx.XMLServerLauncher\r\n\r\n"),
            " orphan\r\n" + manifest, manifest.replace("Main-Class: ", "Main-Class:"),
            manifest.replace("Main-Class", "Main\u0000Class"), manifest.trimEnd(),
            manifest + "Name: entry\r\nClass-Path: project.jar\r\n\r\n")) {
            assertTrue(text, runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, text)) }.isFailure)
        }
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, includeLauncher = false)) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, name = "OTHER.MF")) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, extra = "meta-inf/manifest.mf")) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, extra = "../escape")) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, extra = "/absolute")) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, manifest, extra = "back\\slash")) }.isFailure)
        val file = jar(root, manifest)
        file.writeText("not a ZIP archive")
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(file) }.isFailure)
    }

    @Test fun manifestBoundAndSymlinkGuardsAreIndependentOfPayloadHash() = withRoot { root ->
        val header = "Manifest-Version: 1.0\nMain-Class: org.eclipse.lemminx.XMLServerLauncher\nX: "
        val exact = header + "a".repeat(65_536 - header.length - 2) + "\n\n"
        val file = jar(root, exact)
        LocalUbuntuLemMinxPolicy.requireJarStructure(file)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(jar(root, exact.replace("\n\n", "a\n\n"))) }.isFailure)
        val link = File(root, "linked.jar")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(link) }.isFailure)
        assertTrue(runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(root) }.isFailure)
    }

    @Test fun unavailableHardwareStillReportsUnreviewedRequestWithoutSubstitution() {
        val f = extension()
        val blocked = ExtensionVersionState(f, f.versions.single(), ExtensionState.UNAVAILABLE, "Device unavailable")
        assertEquals(blocked, ToolVersionRequests.select(listOf(blocked), f.id, version))
        val requested = requireNotNull(ToolVersionRequests.select(listOf(blocked), f.id, "99.0.0"))
        assertEquals(ExtensionState.UNAVAILABLE, requested.state)
        assertEquals("99.0.0", requested.version.version)
        assertTrue(requested.detail.contains("not a reviewed LemMinX integration"))
        assertTrue(requested.detail.contains("no version was substituted"))
    }

    @Test fun manifestUnixLinkDirectoryAndSpecialTypesCannotPassStructuralAdmission() = withRoot { root ->
        for (kind in listOf(0xa000, 0x4000, 0x1000, 0x2000, 0x6000, 0xc000)) {
            val file = jar(root, manifest)
            val bytes = file.readBytes()
            val central = (0..bytes.size - 46).first { i -> bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
            bytes[central + 5] = 3
            bytes[central + 40] = (kind and 255).toByte()
            bytes[central + 41] = (kind ushr 8).toByte()
            file.writeBytes(bytes)
            assertTrue("ZIP entry kind $kind", runCatching { LocalUbuntuLemMinxPolicy.requireJarStructure(file) }.isFailure)
        }
    }

    @Test fun existingRawProjectionKeepsOriginalBytesThenProductionAdmissionRejectsSyntheticJar() = runBlocking<Unit> {
        val r = recipe()
        val root = Files.createTempDirectory("lemminx-raw-").toFile()
        try {
            val file = jar(root, manifest)
            val original = file.readBytes()
            LocalUbuntuVendorArchive.project(r, file, "/unused").use { projection ->
                assertEquals(1, projection.files.size)
                assertTrue(projection.symlinks.isEmpty())
                assertEquals(asset, projection.files.single().relativePath)
                assertArrayEquals(original, projection.files.single().file.readBytes())
                assertTrue(runCatching { LocalUbuntuVendorPolicy.admit(r, projection.files.single().file) }.isFailure)
            }
            assertEquals(listOf(asset), root.listFiles()!!.map { it.name })
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun jar(root: File, text: String, name: String = "META-INF/MANIFEST.MF", includeLauncher: Boolean = true, extra: String? = null): File {
        val file = File(root, asset)
        ZipOutputStream(file.outputStream()).use { output ->
            fun entry(path: String, bytes: ByteArray) { output.putNextEntry(ZipEntry(path)); output.write(bytes); output.closeEntry() }
            entry(name, text.toByteArray(Charsets.UTF_8))
            if (includeLauncher) entry(launcher, "inert authored class-name fixture; never executable".toByteArray())
            extra?.let { entry(it, "inert".toByteArray()) }
        }
        return file
    }
    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("lemminx-policy-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
