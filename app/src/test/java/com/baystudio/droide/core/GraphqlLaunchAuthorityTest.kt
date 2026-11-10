package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class GraphqlLaunchAuthorityTest {
    @Test fun independentExactRoots(){assertEquals("/opt/droide/packages/lsp.graphql-library/5.7.0/arm64-v8a",LspGraphqlLaunchPolicy.ROOT);assertEquals("/opt/droide/packages/runtime.graphql-node/22.23.3/arm64-v8a",LspGraphqlLaunchPolicy.NODE_ROOT);assertFalse(LspGraphqlLaunchPolicy.NODE.contains("runtime.node/"))}
    @Test fun directWrapperDenies(){val wrapper=LocalUbuntuGraphqlLaunchAuthority.guestWrapper("lsp.graphql-library",LspGraphqlLaunchPolicy.ROOT);assertTrue(wrapper.contains("exit 64"));assertFalse(wrapper.contains("exec node"))}
    @Test fun allAuthoredHelpersBound(){assertEquals(setOf(LspGraphqlLaunchPolicy.ADAPTER,LspGraphqlLaunchPolicy.WIRE,LspGraphqlLaunchPolicy.BOOTSTRAP,LspGraphqlLaunchPolicy.HEALTH,LspGraphqlLaunchPolicy.NODE_HEALTH),LocalUbuntuGraphqlLaunchAuthority.expectedHelperHashes.keys);assertTrue(LocalUbuntuGraphqlLaunchAuthority.expectedHelperHashes.values.all{it.matches(Regex("[0-9a-f]{64}"))})}
}
