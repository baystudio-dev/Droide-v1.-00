package com.baystudio.droide.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentRuleCatalogLimitTest {
    @Test fun zeroAndBoundsAreExplicit() {
        val root = Files.createTempDirectory("droide-rule-limit-").toFile()
        try {
            val manager = AgentRuleManager(root)
            assertTrue(manager.modelVisible(0).isEmpty())
            assertTrue(manager.modelVisible(96).isEmpty())
            for (bad in listOf(-1, 97, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { manager.modelVisible(bad) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
