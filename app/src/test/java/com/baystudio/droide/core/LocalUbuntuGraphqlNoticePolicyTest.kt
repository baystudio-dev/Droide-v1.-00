package com.baystudio.droide.core
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class LocalUbuntuGraphqlNoticePolicyTest {
    @Test fun exactSourceNoticeAssetsAndExplicitUnrunDistributionGate() {
        val base=sequenceOf(File("src/main/assets"),File("app/src/main/assets")).first{it.isDirectory}
        for((name,hash)in LocalUbuntuGraphqlNoticePolicy.hashes) {
            val file=File(base,if(name=="NOTICE-REQUIREMENTS.json")"lsp/graphql/$name"else"legal/licenses/$name")
            assertEquals(hash,LocalUbuntuGraphqlPolicy.sha256(file.readBytes()))
        }
        val text=File(base,"legal/licenses/GraphQL-5.7.0-NOTICES.txt").readText()
        assertTrue(text.contains("UNRUN"));assertTrue(text.contains("debounce-promise"));assertTrue(text.contains("vscode-languageserver-types"))
    }
}
