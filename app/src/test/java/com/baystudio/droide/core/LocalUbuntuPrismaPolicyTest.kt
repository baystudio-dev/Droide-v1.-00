package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored registry-metadata fixtures. No npm resolution, downloaded package or project executes. */
class LocalUbuntuPrismaPolicyTest {
    private val recipe get() = WorkstationInstallRecipe(LocalUbuntuPrismaPolicy.FAMILY_ID, "31.13.0", "@prisma/language-server", "31.13.0",
        "prisma-language-server", 22, "https://github.com/prisma/language-tools", LocalUbuntuPrismaPolicy.INTEGRITY,
        additionalDependencies = LocalUbuntuPrismaPolicy.dependencies)
    private fun lock(): JsonObject = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/prisma-reviewed-lock.json")).bufferedReader().readText()).jsonObject
    private fun reject(value: JsonObject) = assertTrue(runCatching { LocalUbuntuNpmPolicy.validateLock(value.toString(), recipe) }.isFailure)
    private fun entries(value: JsonObject, mutation: (JsonObject) -> JsonObject) = JsonObject(value + ("packages" to mutation(value.getValue("packages").jsonObject)))

    @Test fun exactRecipeAndFortyFourPackageClosure() {
        assertEquals(43, recipe.additionalDependencies.size)
        recipe.validate(); assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        val good = lock(); assertEquals(good, LocalUbuntuNpmPolicy.validateLock(good.toString(), recipe))
        assertEquals(45, good.getValue("packages").jsonObject.size)
        assertEquals("31.13.0", UbuntuNpmCatalog.find(recipe.familyId)?.offeredVersion)
        assertEquals(mapOf("prisma-language-server" to listOf("prisma-language-server")), LocalUbuntuNpmPolicy.commands(recipe))
        assertEquals(listOf("--droide-health"), LocalUbuntuNpmPolicy.healthArgs(recipe))
    }
    @Test fun exceptionRequiresEveryExactRecipeField() {
        val wrong = listOf(recipe.copy(familyId="lsp.other"),recipe.copy(version="31.13.1"),recipe.copy(packageName="other"),
            recipe.copy(packageVersion="31.13.1"),recipe.copy(command="other"),recipe.copy(minimumNodeMajor=24),
            recipe.copy(provenanceUrl="https://example.com"),recipe.copy(registryIntegrity=null),
            recipe.copy(additionalDependencies=recipe.additionalDependencies-"magicast"),recipe.copy(additionalDependencies=recipe.additionalDependencies+("magicast" to "0.5.3")),
            recipe.copy(allowLifecycleScripts=true,lifecycleScriptReview="Unreviewed"))
        for (bad in wrong) { assertFalse(LocalUbuntuNpmPolicy.supports(bad)); assertTrue(runCatching { bad.validate() }.isFailure) }
        val generic = recipe.copy(familyId="lsp.generic",additionalDependencies=recipe.additionalDependencies.entries.take(4).associate { it.toPair() })
        generic.validate()
        assertTrue(runCatching { generic.copy(additionalDependencies=recipe.additionalDependencies.entries.take(5).associate { it.toPair() }).validate() }.isFailure)
    }
    @Test fun everyPackageArtifactEdgeAndEngineIsBound() {
        val good = lock()
        val p = good.getValue("packages").jsonObject
        for (key in p.keys.filter(String::isNotEmpty)) {
            for ((field, bad) in listOf("version" to JsonPrimitive("999.0.0"),"resolved" to JsonPrimitive("https://registry.npmjs.org/other/-/other-1.0.0.tgz"),
                "integrity" to JsonPrimitive(LocalUbuntuPrismaPolicy.INTEGRITY+"x"),"link" to JsonPrimitive(true),"hasInstallScript" to JsonPrimitive(true),
                "dev" to JsonPrimitive(true),"inBundle" to JsonPrimitive(true),"dependencies" to buildJsonObject { put("unreviewed","1.0.0") },
                "optionalDependencies" to buildJsonObject { put("unreviewed","1.0.0") },"peerDependencies" to buildJsonObject { put("unreviewed","1.0.0") },
                "peerDependenciesMeta" to buildJsonObject { put("unreviewed",true) },"engines" to buildJsonObject { put("node",">=1") })) {
                reject(entries(good) { JsonObject(it + (key to JsonObject(it.getValue(key).jsonObject + (field to bad)))) })
            }
            reject(entries(good) { JsonObject(it-key) })
        }
        reject(entries(good) { JsonObject(it+("node_modules/extra" to p.getValue("node_modules/magicast"))) })
        reject(entries(good) { JsonObject(it+("node_modules/c12/node_modules/magicast" to p.getValue("node_modules/magicast"))) })
    }
    @Test fun rootManifestLockVersionAndDuplicateKeysAreBound() {
        val good=lock();reject(JsonObject(good+("lockfileVersion" to JsonPrimitive(2))))
        reject(entries(good) { JsonObject(it+("" to JsonObject(it.getValue("").jsonObject+("dependencies" to buildJsonObject { put("@prisma/language-server","31.13.0");put("magicast","0.5.4") })))) })
        for (field in listOf("scripts","optionalDependencies","peerDependencies","overrides","workspaces","resolutions")) reject(entries(good) { JsonObject(it+("" to JsonObject(it.getValue("").jsonObject+(field to JsonObject(emptyMap()))))) })
        for (text in listOf("{\"x\":1,\"x\":2}","{\"x\":{\"y\":1,\"y\":2}}","{\"x\":1,\"\\u0078\":2}","{\"x\":01}","{\"x\":1,}","{\"x\": [1,]}","[]","{\"x\":"+"[".repeat(65)+"0"+"]".repeat(65)+"}")) assertTrue(text.take(60), runCatching { LocalUbuntuPrismaPolicy.strictObject(text) }.isFailure)
    }
    @Test fun exactProfileRejectsOldMissingExtraAndChangedSourceIdentity() {
        val profile=LocalUbuntuPrismaLaunchPolicy.launchProfile("/usr/bin/node","22.20.0",null)
        val review=buildJsonObject { put("launchProfile",profile) }
        assertTrue(LocalUbuntuPrismaLaunchPolicy.profileMatches(review,"/usr/bin/node","22.20.0",null))
        assertFalse(LocalUbuntuPrismaLaunchPolicy.profileMatches(JsonObject(emptyMap()),"/usr/bin/node","22.20.0",null))
        for (key in profile.keys) {
            assertFalse(LocalUbuntuPrismaLaunchPolicy.profileMatches(buildJsonObject { put("launchProfile",JsonObject(profile-key)) },"/usr/bin/node","22.20.0",null))
            assertFalse(LocalUbuntuPrismaLaunchPolicy.profileMatches(buildJsonObject { put("launchProfile",JsonObject(profile+(key to JsonPrimitive("changed")))) },"/usr/bin/node","22.20.0",null))
        }
        assertFalse(LocalUbuntuPrismaLaunchPolicy.profileMatches(buildJsonObject { put("launchProfile",JsonObject(profile+("extra" to JsonPrimitive(true)))) },"/usr/bin/node","22.20.0",null))
    }
    @Test fun compiledBytesAndShellModesAreNarrow() {
        assertEquals(LocalUbuntuPrismaPolicy.hash(LocalUbuntuPrismaLaunchPolicy.script),LocalUbuntuPrismaLaunchPolicy.SOURCE_SHA256)
        assertEquals(LocalUbuntuPrismaPolicy.hash(LocalUbuntuPrismaLspHealth.script),LocalUbuntuPrismaLspHealth.SOURCE_SHA256)
        val wrapper=LocalUbuntuPrismaLaunchPolicy.guestWrapper("/usr/bin/node","/opt/droide/packages/final")
        assertTrue(wrapper.startsWith("#!/bin/sh\n"))
        assertTrue(wrapper.contains("[ \"${'$'}#\" -eq 1 ]"));assertTrue(wrapper.contains("unset NODE_OPTIONS NODE_PATH LD_PRELOAD LD_LIBRARY_PATH"))
        assertTrue(wrapper.contains("/usr/bin/env -i"));assertFalse(wrapper.contains("node_modules/.bin"))
        assertEquals(1,Regex(Regex.escape("require('@prisma/language-server').startServer()")).findAll(LocalUbuntuPrismaLaunchPolicy.script).count())
    }
}
