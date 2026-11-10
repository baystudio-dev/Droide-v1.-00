package com.baystudio.droide.core

/** Separate exact-family probe: never changes previously sealed families' helper bytes. */
internal object LocalUbuntuTexlabLspHealth {
    const val FILE_NAME = "texlab-lsp-health.cjs"
    val script: String = run {
        val marker = "processId: process.pid, rootUri: null, workspaceFolders: null,"
        val original = LocalUbuntuNpmLspHealth.script
        require(original.indexOf(marker) >= 0 && original.indexOf(marker) == original.lastIndexOf(marker)) {
            "The reviewed Texlab health initialization boundary changed"
        }
        original.replace(marker, marker + "\n            initializationOptions: { skipDistro: true },")
    }
}
