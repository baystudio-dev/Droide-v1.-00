package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

/** Authored metadata/graph controls only. No original archive or runtime is executed. */
class LocalUbuntuVuePolicyTest {
    @Test fun frozenIdentitiesAndPhysicalEdges() {
        assertEquals(89,LocalUbuntuVuePolicy.artifacts.size)
        assertEquals(90,LocalUbuntuVuePolicy.placements.size)
        assertEquals(165,LocalUbuntuVuePolicy.data.getValue("resolutions").jsonArray.size)
        LocalUbuntuVuePolicy.requireGraph()
        assertEquals(2,LocalUbuntuVuePolicy.placements.values.count { it == "vscode-languageserver-types@3.17.5" })
    }
    @Test fun exactRecipeAndTwoIndependentFamilies() {
        val recipe=LocalUbuntuVuePolicy.recipe()
        assertTrue(LocalUbuntuVuePolicy.supports(recipe))
        assertFalse(LocalUbuntuVuePolicy.supports(recipe.copy(packageVersion="latest")))
        assertFalse(LocalUbuntuVuePolicy.supports(recipe.copy(allowLifecycleScripts=true)))
        assertFalse(LocalUbuntuVuePolicy.supports(recipe.copy(additionalDependencies=recipe.additionalDependencies+("typescript" to "7.0.0"))))
        assertEquals("runtime.vue-helper@22.23.3",LocalUbuntuVueHelperPolicy.KEY)
        assertFalse(LocalUbuntuVuePolicy.isFamily("runtime.nim-helper"))
        assertFalse(LocalUbuntuVuePolicy.isFamily("runtime.node"))
    }
    @Test fun everyMetadataIdentityIsExact() {
        LocalUbuntuVuePolicy.artifacts.forEach { (identity,a) ->
            val metadata=a.getValue("metadata").jsonObject
            LocalUbuntuVuePolicy.requirePackage(identity,metadata)
            assertThrows(IllegalArgumentException::class.java) {
                LocalUbuntuVuePolicy.requirePackage(identity,JsonObject(metadata+("version" to JsonPrimitive("unreviewed"))))
            }
        }
    }
    @Test fun bundledTslspDoesNotRequireInventedUnbundledFiles() {
        val a=LocalUbuntuVuePolicy.artifacts.getValue("typescript-language-server@6.0.2").getValue("metadata").jsonObject
        assertEquals(JsonNull,a["dependencies"])
        assertEquals(JsonPrimitive("lib/cli.mjs"),a.getValue("bin").jsonObject["typescript-language-server"])
        assertFalse(LocalUbuntuVuePolicy.placements.keys.any { it.startsWith("node_modules/typescript-language-server/node_modules/") })
    }
    @Test fun strictJsonAndPaths() {
        listOf("{\"x\":1,\"x\":2}","{\"x\":1} garbage","[]").forEach { text ->
            assertThrows(RuntimeException::class.java) { LocalUbuntuVuePolicy.strictObject(text) }
        }
        listOf("../x","/x","x//y","x/../y","x\\y","x:y","x\u0000y").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { LocalUbuntuVuePolicy.safeRelative(path) }
        }
    }
}
