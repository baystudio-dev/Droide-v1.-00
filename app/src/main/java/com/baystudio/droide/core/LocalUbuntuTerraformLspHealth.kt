package com.baystudio.droide.core

/** Exact-family derivative; previously sealed shared/Texlab helper bytes stay unchanged. */
internal object LocalUbuntuTerraformLspHealth {
    const val FILE_NAME = "terraform-ls-health.cjs"
    val script: String = run {
        fun replaceOnce(text: String, marker: String, replacement: String): String {
            require(text.indexOf(marker) >= 0 && text.indexOf(marker) == text.lastIndexOf(marker)) {
                "The reviewed Terraform LS health boundary changed"
            }
            return text.replace(marker, replacement)
        }
        val init = "processId: process.pid, rootUri: null, workspaceFolders: null,"
        val checked = "if (!object(message.result) || !object(message.result.capabilities)) throw new Error('initialize capabilities are missing');"
        val options = replaceOnce(LocalUbuntuNpmLspHealth.script, init,
            "processId: process.pid, rootUri: null, workspaceFolders: [], initializationOptions: { ignoreSingleFileWarning: true },")
        replaceOnce(options, checked, checked + "\n            if (!object(message.result.serverInfo) || message.result.serverInfo.name !== 'terraform-ls' || message.result.serverInfo.version !== '0.39.0') throw new Error('unexpected Terraform LS health identity');")
    }
}
