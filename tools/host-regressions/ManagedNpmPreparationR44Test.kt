package com.baystudio.droide.core

import java.io.File
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// @PRODUCTION_DECLARATIONS@

// Only external collaborators and enclosing Android/UI types are replaced.
data class Family(val id: String, val name: String = id)
data class Version(val version: String)
data class ExtensionVersionState(val family: Family, val version: Version)
data class VendorArtifactInstallRecipe(val version: String = "26.0.0", val assetSha256: String = "publisher-fixture")
sealed interface ExtensionInstallPlan {
    data class Recipe(val familyId: String, val familyName: String, val requestedVersion: String,
        val recipe: WorkstationInstallRecipe, val nodeDependency: VendorArtifactInstallRecipe?,
        val components: List<String>) : ExtensionInstallPlan
}
object PackageSourceCatalog { const val LATEST_OFFICIAL_VERSION = "Latest official" }
enum class Phase { IDLE }
data class State(val phase: Phase, val message: String, val family: String, val version: String)
class StateHolder { var value: State? = null }
data class HttpResponse(val code: Int, val body: String)
data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)
object Fixture {
    val requests = mutableListOf<Pair<String, String>>()
    val nodeCommands = mutableListOf<List<String>>()
    var npmProbes = 0
    var nodeResolutions = 0
    var ready = true
    var nodeVersion = "24.0.0"
    var npmVersion = "10.9.0"
    var nodeResult: ExecResult? = null
    var npmExit = 0
    var engine: String? = ">=22"
    var response: ((String, String) -> HttpResponse)? = null
    fun metadata(name: String, version: String = "1.2.3", engine: String? = this.engine): String = buildJsonObject {
        put("name", name); put("version", version)
        if (engine != null) putJsonObject("engines") { put("node", engine) }
        putJsonObject("dist") {
            put("integrity", "sha512-" + java.util.Base64.getEncoder().encodeToString(ByteArray(64)))
            put("tarball", "https://registry.npmjs.org/pkg/-/pkg-$version.tgz")
        }
        if (name == "typescript") putJsonObject("bin") { put("tsserver", "bin/tsserver") }
    }.toString()
    fun reset() {
        requests.clear(); nodeCommands.clear(); npmProbes = 0; nodeResolutions = 0
        ready = true; nodeVersion = "24.0.0"; npmVersion = "10.9.0"; nodeResult = null; npmExit = 0
        engine = ">=22"; response = null
    }
}
object SafeHttp {
    suspend fun get(url: String, accept: String, maxBytes: Int): HttpResponse {
        check(accept == "application/json" && maxBytes == 1_000_000)
        check(url.startsWith("https://registry.npmjs.org/"))
        val path = url.removePrefix("https://registry.npmjs.org/")
        val name = URLDecoder.decode(path.substringBeforeLast('/'), "UTF-8")
        val version = path.substringAfterLast('/')
        Fixture.requests.add(name to version)
        return Fixture.response?.invoke(name, version) ?: HttpResponse(200, Fixture.metadata(name))
    }
}
object LocalExecutionSubstrate {
    data class LinuxState(val ready: Boolean)
    class Launch {
        val environment = emptyMap<String, String>()
        fun command(command: List<String>) = command
    }
    fun inspectLinuxState() = LinuxState(Fixture.ready)
    fun linuxLaunchSpec(projectRoot: File) = Launch()
}
object LocalProcessSupervisor {
    suspend fun capture(command: List<String>, projectRoot: File, environment: Map<String, String>, maxOutputBytes: Int, timeoutMs: Int): ExecResult {
        check(maxOutputBytes == 4_000 && timeoutMs == 10_000)
        if (command == listOf("npm", "--version")) {
            Fixture.npmProbes++
            return ExecResult(Fixture.npmExit, Fixture.npmVersion)
        }
        check(command.take(2) == listOf("node", "-e"))
        Fixture.nodeCommands.add(command)
        Fixture.nodeResult?.let { return it }
        // Execute the production script with real npm semver and a deterministic runtime identity.
        val identity = "Object.defineProperty(process.versions,'node',{value:'${Fixture.nodeVersion}'});" +
            "Object.defineProperty(process,'arch',{value:'arm64'});"
        val process = ProcessBuilder(listOf("node", "-e", identity + command[2]) + command.drop(3)).redirectErrorStream(true).start()
        check(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { "Host runtime fixture timed out" }
        return ExecResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }
}
class PreparationHarness {
    private val projectRoot = File(".")
    private val _state = StateHolder()
    suspend fun prepare(item: ExtensionVersionState): ExtensionInstallPlan {
        // @CATALOG_BRANCH@
        error("Fixture supports only the production catalog branch")
    }
    // Both receipt and static-recipe call sites omit the prepared-metadata argument.
    suspend fun withoutPreparedMetadata(item: ExtensionVersionState, recipe: WorkstationInstallRecipe) = npmPlan(item, recipe)
    private suspend fun resolveNode(): VendorArtifactInstallRecipe {
        Fixture.nodeResolutions++
        return VendorArtifactInstallRecipe()
    }
    // @PRODUCTION_METHODS@
}

class ManagedNpmPreparationR44Test {
    private val expected = System.getProperty("droide.npm.expectedCatalogRequests", "1").toInt()
    private val item = ExtensionVersionState(Family("quality.prettier"), Version("1.2.3"))
    @Before fun reset() = Fixture.reset()
    private suspend fun plan(target: ExtensionVersionState = item) = PreparationHarness().prepare(target) as ExtensionInstallPlan.Recipe
    private fun assertRequests(count: Int = expected, name: String = "prettier") = assertEquals(count, Fixture.requests.count { it.first == name })

    @Test fun catalogPreparationFetchesMetadataOnlyAsOftenAsExpected() = runBlocking {
        val result = plan()
        assertRequests(); assertNull(result.nodeDependency); assertEquals(0, Fixture.nodeResolutions)
        assertEquals(listOf("22", ">=22"), Fixture.nodeCommands.single().takeLast(2))
        assertEquals(1, Fixture.npmProbes)
        assertEquals("1.2.3", result.recipe.version)
        println("catalog metadata requests=${Fixture.requests.size}; node probes=${Fixture.nodeCommands.size}; npm probes=${Fixture.npmProbes}")
    }
    @Test fun latestMetadataPinsExactRecipeWithoutRemovingValidation() = runBlocking {
        val result = plan(item.copy(version = Version("latest")))
        assertRequests(); assertEquals("latest", Fixture.requests.first().second)
        assertEquals("1.2.3", result.recipe.packageVersion)
        assertNotNull(result.recipe.registryIntegrity)
    }
    @Test fun engineMismatchStillSelectsNodeDependencyAndSkipsNpmProbe() = runBlocking {
        Fixture.engine = ">=26"
        assertNotNull(plan().nodeDependency); assertRequests()
        assertEquals(1, Fixture.nodeResolutions); assertEquals(0, Fixture.npmProbes)
    }
    @Test fun minimumMajorStillRejectsRuntimeEvenWhenPublisherAllowsIt() = runBlocking {
        Fixture.nodeVersion = "20.19.0"; Fixture.engine = ">=18"
        assertNotNull(plan().nodeDependency); assertRequests(); assertEquals(0, Fixture.npmProbes)
    }
    @Test fun missingEngineInPresentMetadataDoesNotTriggerAnotherLookup() = runBlocking {
        Fixture.engine = null
        assertNull(plan().nodeDependency); assertRequests()
        assertEquals("", Fixture.nodeCommands.single().last()); assertEquals(1, Fixture.npmProbes)
    }
    @Test fun omittedMetadataForStaticOrReceiptCallerStillFetchesOnce() = runBlocking {
        val entry = UbuntuNpmCatalog.find(item.family.id)!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "1.2.3", entry.packageName, "1.2.3", entry.command, 22, entry.provenance)
        Fixture.engine = ">=26"
        assertNotNull(PreparationHarness().withoutPreparedMetadata(item, recipe).nodeDependency)
        assertRequests(1); assertEquals(">=26", Fixture.nodeCommands.single().last())
    }
    @Test fun unavailableLinuxSkipsFallbackMetadataWhenNoObjectWasSupplied() = runBlocking {
        val entry = UbuntuNpmCatalog.find(item.family.id)!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "1.2.3", entry.packageName, "1.2.3", entry.command, 22, entry.provenance)
        Fixture.ready = false
        Fixture.response = { _, _ -> error("Fallback metadata must not be requested") }
        assertNotNull(PreparationHarness().withoutPreparedMetadata(item, recipe).nodeDependency)
        assertRequests(0); assertTrue(Fixture.nodeCommands.isEmpty()); assertEquals(1, Fixture.nodeResolutions)
    }
    @Test fun unavailableFallbackMetadataFailsClosed() = runBlocking {
        val entry = UbuntuNpmCatalog.find(item.family.id)!!
        val recipe = WorkstationInstallRecipe(entry.familyId, "1.2.3", entry.packageName, "1.2.3", entry.command, 22, entry.provenance)
        Fixture.response = { _, _ -> HttpResponse(503, "unavailable") }
        try { PreparationHarness().withoutPreparedMetadata(item, recipe); fail("Missing metadata admitted") }
        catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("HTTP 503")) }
        assertRequests(1); assertTrue(Fixture.nodeCommands.isEmpty()); assertEquals(0, Fixture.nodeResolutions)
    }
    @Test fun unavailableCatalogMetadataFailsBeforePlan() = runBlocking {
        Fixture.response = { _, _ -> HttpResponse(404, "missing") }
        try { plan(); fail("Missing metadata admitted") }
        catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("HTTP 404")) }
        assertRequests(1); assertTrue(Fixture.nodeCommands.isEmpty())
    }
    @Test fun wrongMetadataIdentityOrExactVersionFailsBeforeRuntimeProbe() = runBlocking {
        for ((name, version) in listOf("other" to "1.2.3", "prettier" to "9.9.9")) {
            reset(); Fixture.response = { _, _ -> HttpResponse(200, Fixture.metadata(name, version)) }
            try { plan(); fail("Wrong metadata admitted") } catch (_: IllegalArgumentException) { }
            assertRequests(1); assertTrue(Fixture.nodeCommands.isEmpty())
        }
    }
    @Test fun invalidPublisherEngineStillFailsClosed() = runBlocking {
        Fixture.engine = ">=22\n"
        try { plan(); fail("Invalid engine admitted") } catch (_: IllegalArgumentException) { }
        assertRequests(); assertTrue(Fixture.nodeCommands.isEmpty()); assertEquals(0, Fixture.nodeResolutions)
    }
    @Test fun noReadyLinuxStillSelectsNodeWithoutRuntimeProbeOrFallbackFetch() = runBlocking {
        Fixture.ready = false
        assertNotNull(plan().nodeDependency); assertRequests(1); assertTrue(Fixture.nodeCommands.isEmpty())
        assertEquals(1, Fixture.nodeResolutions)
    }
    @Test fun badNodeExitTimeoutAndMalformedOutputStillSelectDependency() = runBlocking {
        for (result in listOf(ExecResult(1, "failure"), ExecResult(0, "{}", true), ExecResult(0, "not json"))) {
            reset(); Fixture.nodeResult = result
            assertNotNull(plan().nodeDependency); assertRequests(); assertEquals(0, Fixture.npmProbes)
        }
    }
    @Test fun oldOrFailedNpmStillSelectsDependency() = runBlocking {
        for ((version, exit) in listOf("9.9.9" to 0, "10.9.0" to 1)) {
            reset(); Fixture.npmVersion = version; Fixture.npmExit = exit
            assertNotNull(plan().nodeDependency); assertRequests(); assertEquals(1, Fixture.npmProbes)
        }
    }
    @Test fun metadataReuseIsPerPreparationAndNeverAGlobalCache() = runBlocking {
        val harness = PreparationHarness()
        assertNull((harness.prepare(item) as ExtensionInstallPlan.Recipe).nodeDependency)
        Fixture.engine = ">=26"
        assertNotNull((harness.prepare(item) as ExtensionInstallPlan.Recipe).nodeDependency)
        assertRequests(expected * 2); assertEquals(2, Fixture.nodeCommands.size)
    }
    @Test fun typeScriptAdditionalDependencyResolutionIsUnchanged() = runBlocking {
        val result = plan(ExtensionVersionState(Family("lsp.typescript"), Version("1.2.3")))
        assertRequests(expected, "typescript-language-server"); assertRequests(1, "typescript")
        assertEquals(mapOf("typescript" to "1.2.3"), result.recipe.additionalDependencies)
    }
}
