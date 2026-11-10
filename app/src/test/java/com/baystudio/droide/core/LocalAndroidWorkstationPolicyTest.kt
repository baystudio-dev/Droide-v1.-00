package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LocalAndroidWorkstationPolicyTest {
    private fun catalog(): AndroidLocalComponentCatalogDocument {
        val asset = listOf(File("src/main/assets/android-local-component-catalog.json"), File("app/src/main/assets/android-local-component-catalog.json"))
            .first { it.isFile }
        return Json.decodeFromString<AndroidLocalComponentCatalogDocument>(asset.readText()).also(AndroidLocalComponentCatalog::validate)
    }
    private val gradle = "/opt/droide/packages/build.gradle/8.13/arm64-v8a/payload/bin/gradle"

    @Test fun bundleRequiresBothActualCatalogComponentsAndTheirNativeOverlayPins() {
        val catalog = catalog()
        assertEquals(setOf("sdk.android", "build.android-tools"), LocalAndroidWorkstationPolicy.components(catalog).map { it.familyId }.toSet())
        val lock = LocalAndroidWorkstationPolicy.lock(catalog)
        val components = lock["sdkComponents"]!!.jsonArray.map { it.jsonObject }
        assertEquals(4, components.single { it["familyId"] == JsonPrimitive("build.android-tools") }["nativeTools"]!!.jsonObject.size)
        assertThrows(IllegalArgumentException::class.java) { LocalAndroidWorkstationPolicy.components(catalog.copy(entries = catalog.entries.take(1))) }
    }

    @Test fun replacingAComponentOrNativeOverlayChangesTheBundleReceiptIdentity() {
        val catalog = catalog()
        val original = LocalAndroidWorkstationPolicy.hash(LocalAndroidWorkstationPolicy.lock(catalog))
        val changed = catalog.copy(entries = catalog.entries.map { it.copy(sha256 = "a".repeat(64)) })
        assertNotEquals(original, LocalAndroidWorkstationPolicy.hash(LocalAndroidWorkstationPolicy.lock(changed)))
        val overlay = catalog.copy(entries = catalog.entries.map { e -> e.copy(nativeTools = e.nativeTools.map { it.copy(sha256 = "b".repeat(64)) }) })
        assertNotEquals(original, LocalAndroidWorkstationPolicy.hash(LocalAndroidWorkstationPolicy.lock(overlay)))
    }

    @Test fun doctorCannotSelectAnotherGradleRevisionOrEscapeOwnedPackages() {
        listOf(gradle.replace("8.13", "9.1"), gradle.replace("payload/bin", "../bin"), "/tmp/gradle", "$gradle\nexec /tmp/other")
            .forEach { path -> assertThrows(IllegalArgumentException::class.java) { LocalAndroidWorkstationPolicy.doctor(path) } }
    }

    @Test fun helpAndInvalidDoctorArgumentsDoNotStartAnyToolchainCommands() {
        val script = Files.createTempFile("droide-android-doctor-", ".sh").toFile()
        try {
            script.writeText(LocalAndroidWorkstationPolicy.doctor(gradle))
            fun invoke(vararg args: String): Pair<Int, String> {
                val process = ProcessBuilder(listOf("/bin/sh", script.path) + args).redirectErrorStream(true).start()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                return process.exitValue() to process.inputStream.bufferedReader().readText()
            }
            val help = invoke("--help")
            assertEquals(0, help.first); assertTrue(help.second.contains("Gradle 8.13"))
            assertEquals(2, invoke("unexpected").first)
            assertEquals(2, invoke("--droide-health", "unexpected").first)
        } finally { script.delete() }
    }

    @Test fun healthUsesTheSharedRuntimeProbeAndThePinnedGradle() {
        val script = LocalAndroidWorkstationPolicy.doctor(gradle)
        assertTrue(script.contains("-XshowSettings:properties"))
        assertTrue(script.contains("java.home"))
        assertTrue(script.contains("AAPT2 cannot execute"))
        assertTrue(script.contains("build-tools/36.0.0/aapt2"))
        assertTrue(script.endsWith("exec ${LocalExecutionSubstrate.shellQuote(gradle)} --version\n"))
        assertFalse(script.contains("assembleDebug"))
        assertEquals(4, LocalAndroidWorkstationPolicy.dependencyKeys.size)
    }
}
