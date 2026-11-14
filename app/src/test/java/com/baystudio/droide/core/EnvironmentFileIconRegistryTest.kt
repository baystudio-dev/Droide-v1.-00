package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentFileIconRegistryTest {
    @Test fun environmentVariantsAndNamedEnvFilesShareAnIdentity() {
        for (path in listOf(".env", ".env.local", ".env.production", ".env.development.local",
            ".env.production.local", ".env.test", ".env.test.local", ".env.example", ".env.sample",
            ".env.template", ".env.production.example", ".env.test.example", "service.env")) {
            assertEquals(path, "env", FileIconRegistry.iconNameFor(path))
            assertEquals(path, "ENV", FileIconRegistry.fallbackToken(path))
            assertTrue(path, FileIconRegistry.isTextFile(path))
            assertFalse(path, FileIconRegistry.isImageFile(path))
        }
    }

    @Test fun onlyTheBasenameDeterminesEnvironmentIdentity() {
        for (path in listOf("/project/.env.local", "C:\\project\\.ENV.PRODUCTION.LOCAL", "config/APP.ENV")) {
            assertEquals(path, "env", FileIconRegistry.iconNameFor(path))
        }
        assertEquals("kotlin", FileIconRegistry.iconNameFor(".env.production/Main.kt"))
    }

    @Test fun similarlyNamedCodeImagesAndArchivesKeepTheirOwnTypes() {
        for ((path, expected) in mapOf("env.ts" to "typescript", ".env.schema.json" to "json",
            ".env.local.js" to "javascript", ".env.example.md" to "markdown",
            ".env.production.zip" to "zip", ".env.local.tar.gz" to "zip", ".env.test.png" to "image",
            ".envrc" to "shell", ".environment" to "default_file")) {
            assertEquals(path, expected, FileIconRegistry.iconNameFor(path))
        }
        assertFalse(FileIconRegistry.isTextFile(".env.test.png"))
        assertTrue(FileIconRegistry.isImageFile(".env.test.png"))
    }
}
