package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlProfileTest {
 @Test fun exactStandaloneExtensions(){for(p in listOf("query.graphql","query.GQL","file.GRAPHQL"))assertTrue(LspGraphqlProfile.supportsPath(p));for(p in listOf("schema.graphqls","x.ts","x.vue","a.gqls","query"))assertFalse(LspGraphqlProfile.supportsPath(p))}
 @Test fun strictCanonicalFileUri(){assertEquals("/workspace/a b.gql",LspGraphqlProfile.requireOwnedUri("file:///workspace/a%20b.gql","/workspace"));for(u in listOf("file:///else/a.gql","file://host/workspace/a.gql","file:///workspace/../a.gql","file:///workspace/a.gql?x","file:///workspace/%61.gql"))assertTrue(runCatching{LspGraphqlProfile.requireOwnedUri(u,"/workspace")}.isFailure)}
}
