package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolIdentityTest {
    @Test fun existingAsciiFunctionNameRemainsIdentical() {
        assertEquals("mcp__github-main__search_issues__8a189f8b0a03",
            McpToolIdentity.functionName("github-main", "search_issues", "github-main@cfg:123", """{"type":"object"}"""))
    }

    @Test fun unicodeNamesProduceNonemptyAsciiSlugsWithoutIdentityCollisions() {
        val inputs = listOf(
            "服务器" to "搜索资源",
            "服务器✨" to "搜索资源",
            "服务器" to "搜索资源😀",
            "naïve-服务" to "读取１２٣",
        )
        val names = inputs.map { (server, tool) ->
            McpToolIdentity.functionName(server, tool, "fixture@cfg:sha", """{"type":"object"}""")
        }
        names.forEach { name ->
            assertTrue(name, name.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            assertTrue(name, name.matches(Regex("mcp__[A-Za-z0-9_-]+__[A-Za-z0-9_-]+__[0-9a-f]{12}")))
            assertTrue(McpToolIdentity.isFirstClassName(name))
        }
        assertTrue(names.first().startsWith("mcp__tool__tool__"))
        assertEquals(names.size, names.toSet().size)
        assertEquals(names.first(), McpToolIdentity.functionName(inputs.first().first, inputs.first().second,
            "fixture@cfg:sha", """{"type":"object"}"""))
    }

    @Test fun longNamesRemainBoundedAndHashesDistinguishTruncatedSlugs() {
        val first = McpToolIdentity.functionName("server".repeat(20), "tool".repeat(30), "runtime", "schema")
        val second = McpToolIdentity.functionName("server".repeat(20) + "other", "tool".repeat(30), "runtime", "schema")
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertTrue(second.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        assertEquals(first.substringBeforeLast("__"), second.substringBeforeLast("__"))
        assertTrue(first != second)
    }
}
