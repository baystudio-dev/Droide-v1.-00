package com.baystudio.droide.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fixed primary-registry test vectors, independently copied from the reviewed metadata snapshot. */
class LocalUbuntuPerlNavigatorPolicyTest {
    private val records = """
perlnavigator-server|0.8.20|https://registry.npmjs.org/perlnavigator-server/-/perlnavigator-server-0.8.20.tgz|sha512-whXoAEQwavV2GF/KuzzJ/yg5x9q3XUccMNrA06N0kvmi53BcySburu3Y/YEhPpNjJe0NNxpK7kOj80PE9vRu/g==
lru-cache|6.0.0|https://registry.npmjs.org/lru-cache/-/lru-cache-6.0.0.tgz|sha512-Jo6dJ04CmSjuznwJSS3pUeWmd/H0ffTlkXXgwZi+eq1UCmqQwCh+eLsYOYCwY991i2Fah4h1BEMCx4qThGbsiA==
nanoid|3.3.20|https://registry.npmjs.org/nanoid/-/nanoid-3.3.20.tgz|sha512-uKdg2G3GNCKQn9byYOpxbGqrT2fGO5KRt5J/8b3pok8rT6qxGWF6hxMyJiEYtAf+FVyYuD9hRaDqX5uPFYJ4ZQ==
vscode-uri|1.0.8|https://registry.npmjs.org/vscode-uri/-/vscode-uri-1.0.8.tgz|sha512-obtSWTlbJ+a+TFRYGaUumtVwb+InIUVI0Lu0VBUAPmj2cU5JutEXg3xUE0c2J5Tcy7h2DEKVJBFi+Y9ZSFzzPQ==
vscode-textmate|9.3.2|https://registry.npmjs.org/vscode-textmate/-/vscode-textmate-9.3.2.tgz|sha512-n2uGbUcrjhUEBH16uGA0TvUfhWwliFZ1e3+pTjrkim1Mt7ydB41lV08aUvsi70OlzDWp6X7Bx3w/x3fAXIsN0Q==
vscode-oniguruma|2.0.1|https://registry.npmjs.org/vscode-oniguruma/-/vscode-oniguruma-2.0.1.tgz|sha512-poJU8iHIWnC3vgphJnrLZyI3YdqRlR27xzqDmpPXYzA93R4Gk8z7T6oqDzDoHjoikA2aS82crdXFkjELCdJsjQ==
vscode-languageserver|7.0.0|https://registry.npmjs.org/vscode-languageserver/-/vscode-languageserver-7.0.0.tgz|sha512-60HTx5ID+fLRcgdHfmz0LDZAXYEV68fzwG0JWwEPBode9NuMYTIxuYXPg4ngO8i8+Ou0lM7y6GzaYWbiDL0drw==
vscode-languageserver-textdocument|1.0.15|https://registry.npmjs.org/vscode-languageserver-textdocument/-/vscode-languageserver-textdocument-1.0.15.tgz|sha512-Gsfr9S8LU3S6885cS+NaleasJ0n3V5y/SOTqHCifQiXWFw3G9w9/YItDfwHrlCz+dSZWWZ8ilYVMcJe7Sz7Sxw==
yallist|4.0.0|https://registry.npmjs.org/yallist/-/yallist-4.0.0.tgz|sha512-3wdGidZyq5PB084XLES5TpOSRA3wjXAlIWMhum2kRcv/41Sn2emQ0dycQW4uZXLejwKvg6EsvbdlVL+FYEct7A==
vscode-languageserver-protocol|3.16.0|https://registry.npmjs.org/vscode-languageserver-protocol/-/vscode-languageserver-protocol-3.16.0.tgz|sha512-sdeUoAawceQdgIfTI+sdcwkiK2KU+2cbEYA0agzM2uqaUy2UpnnGHtWTHVEtS0ES4zHU0eMFRGN+oQgDxlD66A==
vscode-languageserver-types|3.16.0|https://registry.npmjs.org/vscode-languageserver-types/-/vscode-languageserver-types-3.16.0.tgz|sha512-k8luDIWJWyenLc5ToFQQMaSrqCHiLwyKPHKPQZ5zz21vM+vIVUSvsRpcbiECH4WR88K2XZqc4ScRcZ7nk/jbeA==
vscode-jsonrpc|6.0.0|https://registry.npmjs.org/vscode-jsonrpc/-/vscode-jsonrpc-6.0.0.tgz|sha512-wnJA4BnEjOSyFMvjZdpiOwhSq9uDoK8e/kpRJDTaMYzwlkrhG1fwDIZI94CLsLzlCK5cIbMMtFlJlfR57Lavmg==
    """.trimIndent().lines().map { it.split('|') }
    private val entry = UbuntuNpmCatalog.find("lsp.perlnavigator")!!
    private val recipe get() = WorkstationInstallRecipe(entry.familyId, "0.8.20", entry.packageName, "0.8.20", entry.command,
        22, entry.provenance, registryIntegrity = entry.reviewedIntegrity,
        additionalDependencies = LocalUbuntuPerlNavigatorPolicy.dependencies)
    private fun packages(): JsonObject = buildJsonObject {
        put("", buildJsonObject { put("name", "droide-managed-agent") })
        for ((name, version, url, integrity) in records) put("node_modules/$name", buildJsonObject {
            put("version", version); put("resolved", url); put("integrity", integrity)
        })
    }
    private fun lock(entries: JsonObject): String = buildJsonObject { put("lockfileVersion", 3); put("packages", entries) }.toString()
    private fun reject(entries: JsonObject) = assertTrue(runCatching { LocalUbuntuNpmPolicy.validateLock(lock(entries), recipe) }.isFailure)

    @Test fun exactRootAndElevenDependenciesUseOriginalStdioAndMetadata() {
        assertEquals(12, records.size)
        assertEquals(records.drop(1).associate { it[0] to it[1] }, LocalUbuntuPerlNavigatorPolicy.dependencies)
        assertTrue(LocalUbuntuNpmPolicy.supports(recipe))
        LocalUbuntuNpmPolicy.validateLock(lock(packages()), recipe)
        assertEquals(listOf("--stdio"), LocalUbuntuNpmPolicy.languageServerArgs(recipe))
        assertEquals(UbuntuNpmCatalog.VERSION, entry.offeredVersion)
        assertEquals(setOf("node", "perl"), ManagedLanguageServerPlan.forServer("perlnavigator")!!.requiredCommands)
    }

    @Test fun missingRootMissingDependencyAndExtraDependencyAreRejected() {
        val good = packages()
        for (key in good.keys) reject(JsonObject(good - key))
        reject(JsonObject(good + ("node_modules/unreviewed" to good.getValue("node_modules/nanoid"))))
        val missingRootPlusExtra = JsonObject((good - "") + ("node_modules/unreviewed" to good.getValue("node_modules/nanoid")))
        assertEquals(good.size, missingRootPlusExtra.size)
        reject(missingRootPlusExtra)
    }

    @Test fun everyPackageVersionUrlIntegrityAndUnexpectedScriptAreBound() {
        val good = packages()
        for (key in good.keys.filter { it.isNotEmpty() }) {
            for ((field, replacement) in listOf("version" to JsonPrimitive("999.0.0"), "resolved" to JsonPrimitive("https://registry.npmjs.org/other/-/other-1.0.0.tgz"),
                "integrity" to JsonPrimitive(records.first()[3]+"changed"), "hasInstallScript" to JsonPrimitive(true))) {
                reject(JsonObject(good + (key to JsonObject(good.getValue(key).jsonObject + (field to replacement)))))
            }
        }
    }

    @Test fun recipeCannotChangeClosureOrEnableScripts() {
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(additionalDependencies = emptyMap())))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(additionalDependencies = recipe.additionalDependencies + ("nanoid" to "3.3.19"))))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(packageVersion = "0.8.21", version = "0.8.21")))
        assertFalse(LocalUbuntuNpmPolicy.supports(recipe.copy(allowLifecycleScripts = true, lifecycleScriptReview = "not-reviewed")))
    }
}
