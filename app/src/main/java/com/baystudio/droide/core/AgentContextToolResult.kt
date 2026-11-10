package com.baystudio.droide.core

import java.security.MessageDigest

internal object AgentContextToolResult {
    fun loaded(name: String, description: String, source: String, body: String, activation: String? = null): String = buildString {
        append(if (activation == null) "# " else "# Rule ")
        append(shorten(name, 256, "metadata"))
        append(": ").append(shorten(description, 1_024, "metadata"))
        append("\nSource: ").append(shorten(source, 256, "metadata"))
        if (activation != null) append("\nActivation: ").append(activation)
        val end = prefixEnd(body, 10_000)
        append("\n\n").append(body, 0, end)
        if (end < body.length) append("\n\n[Instructions truncated; read source file.]")
    }

    fun diagnostic(result: String): String =
        if (result.startsWith("ERROR:") || result.startsWith("DENIED")) shorten(result, 2_048, "diagnostic") else result

    fun identifier(name: String): String = shorten(name, 256, "metadata")

    private fun shorten(value: String, limit: Int, label: String): String {
        if (value.length <= limit) return value
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return value.substring(0, prefixEnd(value, limit)) + "… [$label display shortened; sha256=$digest]"
    }

    private fun prefixEnd(value: String, limit: Int): Int {
        var end = minOf(value.length, limit)
        if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return end
    }
}
