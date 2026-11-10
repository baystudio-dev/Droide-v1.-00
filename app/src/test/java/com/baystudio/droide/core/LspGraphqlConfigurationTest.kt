package com.baystudio.droide.core
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlConfigurationTest {
    private fun parse(s:String)=LspGraphqlConfiguration.selection(s.toByteArray())
    @Test fun exactPathsOrderAndEmptyCompanions(){ assertEquals(listOf("base.GRAPHQL","folder/with space.gql"),parse("""{"schema":["base.GRAPHQL","folder/with space.gql"],"documents":[]}""").schema);assertTrue(parse("""{"schema":"s.gql"}""").fragments.isEmpty()) }
    @Test fun strictDataRejectsAmbiguity(){for(s in listOf("""{"schema":"a.gql","schema":"b.gql"}""","""{"schema":"a.gql","\u0073chema":"b.gql"}""","""{"schema":[]}""","""{"schema":"../s.gql"}""","""{"schema":"*.graphql"}""","""{"schema":"http://a/s.gql"}""","""{"schema":"s.gql","documents":["s.gql"]}""","""{"schema":"s.gql",}""","""{"schema":"s.gql","x":1}"""))assertTrue(s,runCatching{parse(s)}.isFailure)}
    @Test fun packageCompetingAndImportConventions(){assertTrue(runCatching{LspGraphqlConfiguration.admitPackage("""{"graphql":null}""".toByteArray())}.isFailure);assertTrue(runCatching{LspGraphqlConfiguration.admitSource(" # import foo")}.isFailure);LspGraphqlConfiguration.admitSource("# normal comment\nquery A { f }")}
}
