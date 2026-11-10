package com.baystudio.droide.core
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuGraphqlPolicyTest {
    private fun denied(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {} }
    @Test fun exactFivePackagesAndOneConfiguration() {
        assertEquals(5,LocalUbuntuGraphqlPolicy.artifacts.size);assertEquals(5,LocalUbuntuGraphqlPolicy.placements.size)
        assertEquals(706,LocalUbuntuGraphqlPolicy.artifacts.values.sumOf{it.getValue("dist").jsonObject.getValue("fileCount").jsonPrimitive.int})
        assertEquals(2_713_721L,LocalUbuntuGraphqlPolicy.artifacts.values.sumOf{it.getValue("dist").jsonObject.getValue("unpackedSize").jsonPrimitive.long})
        assertEquals("graphql-local-schema",LocalUbuntuGraphqlPolicy.CONFIGURATION_ID)
        assertTrue(LocalUbuntuGraphqlPolicy.supports(LocalUbuntuGraphqlPolicy.recipe()))
        assertFalse(LocalUbuntuGraphqlPolicy.supports(LocalUbuntuGraphqlPolicy.recipe().copy(allowLifecycleScripts=true)))
        assertFalse(LocalUbuntuGraphqlPolicy.supports(LocalUbuntuGraphqlPolicy.recipe().copy(version="latest")))
        for(family in listOf("runtime.node","runtime.taplo-node","runtime.vue-helper","lsp.graphql-language-service-cli"))assertFalse(LocalUbuntuGraphqlPolicy.isFamily(family))
    }
    @Test fun manifestAbsenceAndNullRemainDistinct() {
        val id="graphql-language-service@5.7.0";val value=LocalUbuntuGraphqlPolicy.artifacts.getValue(id)
        LocalUbuntuGraphqlPolicy.requirePackage(id,value)
        denied{LocalUbuntuGraphqlPolicy.requirePackage(id,JsonObject(value+("type" to JsonNull)))}
        denied{LocalUbuntuGraphqlPolicy.requirePackage(id,JsonObject(value+("version" to JsonPrimitive("5.8.0"))))}
    }
    @Test fun exactIndependentNodePublication() {
        val def=requireNotNull(LocalUbuntuVendorCatalog.find(LocalUbuntuGraphqlNodePolicy.FAMILY))
        val checksum="${LocalUbuntuGraphqlNodePolicy.SHA256}  ${LocalUbuntuGraphqlNodePolicy.ASSET}"
        assertEquals("runtime.graphql-node",LocalUbuntuGraphqlNodePolicy.releaseRecipe(def,"22.23.3",checksum).familyId)
        denied{LocalUbuntuGraphqlNodePolicy.releaseRecipe(def,"latest",checksum)};denied{LocalUbuntuGraphqlNodePolicy.releaseRecipe(def,"22.23.3",checksum+"\n"+checksum)}
        assertFalse(def.supportsPublisherVersionSelection)
    }
}
