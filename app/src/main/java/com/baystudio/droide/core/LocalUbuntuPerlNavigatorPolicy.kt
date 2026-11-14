package com.baystudio.droide.core

import kotlinx.serialization.json.*

/** Published registry manifests/files reviewed 2026-10-18; no package code is rewritten. */
internal object LocalUbuntuPerlNavigatorPolicy {
    const val FAMILY_ID = "lsp.perlnavigator"
    private const val ROOT_PACKAGE = "perlnavigator-server"
    private data class Artifact(val version: String, val url: String, val integrity: String)
    // Each URL is the exact dist.tarball in registry.npmjs.org/<name>/<version>.
    // This is a closed JS/WASM production set; scripts remain disabled at install.
    private val artifacts = linkedMapOf(
        "perlnavigator-server" to Artifact("0.8.20", "https://registry.npmjs.org/perlnavigator-server/-/perlnavigator-server-0.8.20.tgz", "sha512-whXoAEQwavV2GF/KuzzJ/yg5x9q3XUccMNrA06N0kvmi53BcySburu3Y/YEhPpNjJe0NNxpK7kOj80PE9vRu/g=="),
        "lru-cache" to Artifact("6.0.0", "https://registry.npmjs.org/lru-cache/-/lru-cache-6.0.0.tgz", "sha512-Jo6dJ04CmSjuznwJSS3pUeWmd/H0ffTlkXXgwZi+eq1UCmqQwCh+eLsYOYCwY991i2Fah4h1BEMCx4qThGbsiA=="),
        "nanoid" to Artifact("3.3.20", "https://registry.npmjs.org/nanoid/-/nanoid-3.3.20.tgz", "sha512-uKdg2G3GNCKQn9byYOpxbGqrT2fGO5KRt5J/8b3pok8rT6qxGWF6hxMyJiEYtAf+FVyYuD9hRaDqX5uPFYJ4ZQ=="),
        "vscode-uri" to Artifact("1.0.8", "https://registry.npmjs.org/vscode-uri/-/vscode-uri-1.0.8.tgz", "sha512-obtSWTlbJ+a+TFRYGaUumtVwb+InIUVI0Lu0VBUAPmj2cU5JutEXg3xUE0c2J5Tcy7h2DEKVJBFi+Y9ZSFzzPQ=="),
        "vscode-textmate" to Artifact("9.3.2", "https://registry.npmjs.org/vscode-textmate/-/vscode-textmate-9.3.2.tgz", "sha512-n2uGbUcrjhUEBH16uGA0TvUfhWwliFZ1e3+pTjrkim1Mt7ydB41lV08aUvsi70OlzDWp6X7Bx3w/x3fAXIsN0Q=="),
        "vscode-oniguruma" to Artifact("2.0.1", "https://registry.npmjs.org/vscode-oniguruma/-/vscode-oniguruma-2.0.1.tgz", "sha512-poJU8iHIWnC3vgphJnrLZyI3YdqRlR27xzqDmpPXYzA93R4Gk8z7T6oqDzDoHjoikA2aS82crdXFkjELCdJsjQ=="),
        "vscode-languageserver" to Artifact("7.0.0", "https://registry.npmjs.org/vscode-languageserver/-/vscode-languageserver-7.0.0.tgz", "sha512-60HTx5ID+fLRcgdHfmz0LDZAXYEV68fzwG0JWwEPBode9NuMYTIxuYXPg4ngO8i8+Ou0lM7y6GzaYWbiDL0drw=="),
        "vscode-languageserver-textdocument" to Artifact("1.0.15", "https://registry.npmjs.org/vscode-languageserver-textdocument/-/vscode-languageserver-textdocument-1.0.15.tgz", "sha512-Gsfr9S8LU3S6885cS+NaleasJ0n3V5y/SOTqHCifQiXWFw3G9w9/YItDfwHrlCz+dSZWWZ8ilYVMcJe7Sz7Sxw=="),
        "yallist" to Artifact("4.0.0", "https://registry.npmjs.org/yallist/-/yallist-4.0.0.tgz", "sha512-3wdGidZyq5PB084XLES5TpOSRA3wjXAlIWMhum2kRcv/41Sn2emQ0dycQW4uZXLejwKvg6EsvbdlVL+FYEct7A=="),
        "vscode-languageserver-protocol" to Artifact("3.16.0", "https://registry.npmjs.org/vscode-languageserver-protocol/-/vscode-languageserver-protocol-3.16.0.tgz", "sha512-sdeUoAawceQdgIfTI+sdcwkiK2KU+2cbEYA0agzM2uqaUy2UpnnGHtWTHVEtS0ES4zHU0eMFRGN+oQgDxlD66A=="),
        "vscode-languageserver-types" to Artifact("3.16.0", "https://registry.npmjs.org/vscode-languageserver-types/-/vscode-languageserver-types-3.16.0.tgz", "sha512-k8luDIWJWyenLc5ToFQQMaSrqCHiLwyKPHKPQZ5zz21vM+vIVUSvsRpcbiECH4WR88K2XZqc4ScRcZ7nk/jbeA=="),
        "vscode-jsonrpc" to Artifact("6.0.0", "https://registry.npmjs.org/vscode-jsonrpc/-/vscode-jsonrpc-6.0.0.tgz", "sha512-wnJA4BnEjOSyFMvjZdpiOwhSq9uDoK8e/kpRJDTaMYzwlkrhG1fwDIZI94CLsLzlCK5cIbMMtFlJlfR57Lavmg=="),
    )
    val dependencies: Map<String, String> = artifacts.filterKeys { it != ROOT_PACKAGE }.mapValues { it.value.version }

    fun validateLock(entries: JsonObject) {
        require(entries.keys == setOf("") + artifacts.keys.map { "node_modules/$it" }) {
            "PerlNavigator dependency closure differs from its reviewed exact package set"
        }
        for ((name, expected) in artifacts) {
            val actual = entries.getValue("node_modules/$name").jsonObject
            require(actual["version"]?.jsonPrimitive?.content == expected.version &&
                actual["resolved"]?.jsonPrimitive?.content == expected.url &&
                actual["integrity"]?.jsonPrimitive?.content == expected.integrity) {
                "Reviewed PerlNavigator dependency identity changed: $name"
            }
            require(actual["hasInstallScript"]?.jsonPrimitive?.booleanOrNull != true) {
                "Reviewed PerlNavigator dependency unexpectedly requires install scripts: $name"
            }
        }
    }
}
