package com.baystudio.droide.core

internal class InstallerProgressLog(private val limit: Int = 8_192) {
    private var value = ""
    private var rawTail = ""
    init { require(limit in 256..65_536) }
    @Synchronized fun clear() { value = ""; rawTail = "" }
    @Synchronized fun append(chunk: String): String {
        val plain = chunk.replace(ANSI_PATTERN, "")
            .filter { it == '\n' || it == '\t' || it.code >= 32 && it.code != 127 }
        rawTail = (rawTail + plain).takeLast(limit + 8_192)
        value = redact(rawTail).takeLast(limit)
        return value
    }
    private fun redact(text: String): String = text
        .replace(URL_CREDENTIAL_PATTERN, "$1[redacted]@")
        .replace(AUTHORIZATION_PATTERN, "$1[redacted]")
        .replace(KEY_VALUE_SECRET_PATTERN, "$1[redacted]")
        .replace(TOKEN_PATTERN, "[redacted]")

    private companion object {
        val ANSI_PATTERN = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        val URL_CREDENTIAL_PATTERN = Regex("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@")
        val AUTHORIZATION_PATTERN = Regex("(?im)(authorization[ \\t]*[:=][ \\t]*)[^\\r\\n]*")
        val KEY_VALUE_SECRET_PATTERN = Regex("(?i)((?:api[_-]?key|access[_-]?token|_authToken|password)\\s*[:=]\\s*)[^\\s,;]+")
        val TOKEN_PATTERN = Regex("(?:sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{8,}|github_pat_[A-Za-z0-9_]{8,})")
    }
}
