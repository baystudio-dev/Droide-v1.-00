package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object LspMdxProfile {
    const val ID = "mdx-language-server"
    const val FAMILY = "lsp.mdx-language-server"
    fun matches(spec: LanguageServerSpec) = spec.source == "built-in" && spec.id == ID

    fun initializationOptions(executable: String): JsonObject {
        val suffix = "/payload/bin/$ID"
        require(executable.startsWith("/opt/droide/packages/") && executable.endsWith(suffix) &&
            executable.split('/').none { it == ".." || it == "." } && executable.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "Install the MDX language server through Extensions to provide its matching TypeScript SDK."
        }
        return buildJsonObject {
            put("typescript", buildJsonObject {
                put("tsdk", executable.removeSuffix(suffix) + "/payload/npm/node_modules/typescript/lib")
                put("enabled", false)
            })
        }
    }

    val healthScript: String by lazy {
        val marker = "capabilities: { general: { positionEncodings: ['utf-16'] },"
        val original = LocalUbuntuNpmLspHealth.script
        val position = original.indexOf(marker)
        check(position >= 0 && original.indexOf(marker, position + marker.length) == -1)
        original.replace(marker, "initializationOptions: { typescript: { tsdk: require('node:path').join(__dirname, 'npm/node_modules/typescript/lib'), enabled: false } },\n            $marker")
    }
}
