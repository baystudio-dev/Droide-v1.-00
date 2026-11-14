package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileIconRegistryCoverageTest {
    @Test fun everyBuiltInLanguageExtensionAndFilenameHasItsArtwork() {
        val aliases = mapOf("cpp" to "cplusplus", "scss" to "sass", "jsonc" to "json",
            "dockerfile" to "docker", "batch" to "bat", "latex" to "tex", "proto" to "protobuf")
        val fileOverrides = mapOf("sample.h" to "cplusplus", "sample.svg" to "image")
        for (language in LanguageRegistry.all.filter { LanguageRegistry.isBuiltIn(it.id) }) {
            for (name in language.extensions.map { "sample.$it" } + language.filenames) {
                val expected = fileOverrides[name] ?: aliases[language.id] ?: language.id
                assertEquals("${language.id}: $name", expected, FileIconRegistry.iconNameFor("project/$name"))
            }
        }
    }

    @Test fun everyBuiltInLanguageServerFilePatternHasAnIcon() {
        for (server in LanguageServerRegistry.builtIns) {
            for (name in server.extensions.map { "sample.$it" } + server.filenames) {
                assertNotEquals("${server.id}: $name", "default_file", FileIconRegistry.iconNameFor(name))
            }
        }
    }

    @Test fun configurationIdentityWinsOverGenericLanguageWithoutMatchingParentFolders() {
        val examples = mapOf("package.json" to "nodejs", "tsconfig.node.json" to "tsconfig",
            "eslint.config.mjs" to "eslint", ".prettierrc.yaml" to "prettier",
            "vite.config.ts" to "vite", "astro.config.mjs" to "astro", "go.mod" to "gomod",
            "Cargo.toml" to "rust", "pyproject.toml" to "python", "Dockerfile.prod" to "docker",
            ".bashrc" to "shell", ".vimrc" to "vim", ".gitignore" to "git")
        for ((name, icon) in examples) {
            for (path in listOf(name, "root/$name", "C:\\project\\${name.uppercase()}")) {
                assertEquals(path, icon, FileIconRegistry.iconNameFor(path))
            }
            assertEquals(name, "text", FileIconRegistry.iconNameFor("$name/notes.txt"))
            assertEquals(name, "default_file", FileIconRegistry.iconNameFor("$name.tmp"))
        }
    }

    @Test fun compoundSuffixesAndCaseSensitiveCppConventionRemainPrecise() {
        val examples = mapOf("build.gradle.kts" to "gradle", "generated.d.mts" to "typescript",
            "Button.test.tsx" to "react", "data.tar.gz" to "zip", ".env.production.zip" to "zip",
            "src/main.c" to "c", "src/main.C" to "cplusplus", "src/main.H" to "cplusplus",
            "foo.C/file.py" to "python", "shell.envrc.sh" to "shell", "file.py.unknown" to "default_file")
        for ((path, expected) in examples) assertEquals(path, expected, FileIconRegistry.iconNameFor(path))
    }
}
