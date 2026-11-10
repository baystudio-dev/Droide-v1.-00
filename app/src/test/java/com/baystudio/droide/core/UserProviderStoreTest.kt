package com.baystudio.droide.core

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserProviderStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val provider = UserProviderDefinition("user.company", "Company", "https://api.example.com/v1")
    @After fun clear() { ProviderRegistry.installUserConfigured(emptyList()) }

    @Test fun createEditReloadAndRemovePreserveOtherProviders() {
        val file = File(temporary.root, "user-providers.json")
        val store = UserProviderStore(file)
        val first = store.save(provider, null)
        val other = store.save(provider.copy(id = "user.second"), null)
        val updated = store.save(first.copy(name = "Updated", models = listOf("one")), first)
        ProviderRegistry.installUserConfigured(emptyList())
        assertEquals(listOf(other, updated), UserProviderStore(file).load())
        assertEquals(updated.name, ProviderRegistry.findById(provider.id)!!.name)
        store.remove(updated)
        assertEquals(listOf(other), UserProviderStore(file).load())
    }

    @Test fun staleEditsAndDuplicateIdsNeverOverwriteNewerSettings() {
        val file = File(temporary.root, "providers.json")
        val store = UserProviderStore(file)
        store.save(provider, null)
        val next = provider.copy(baseUrl = "https://new.example/v1")
        store.save(next, provider)
        val bytes = file.readBytes()
        assertTrue(runCatching { store.save(provider, null) }.isFailure)
        assertTrue(runCatching { store.save(provider.copy(name = "Stale"), provider) }.isFailure)
        assertTrue(runCatching { store.remove(provider) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(next.baseUrl, ProviderRegistry.findById(provider.id)!!.baseUrl)
    }

    @Test fun corruptedOrFailedStorageIsPreservedAndNeverPublishesCandidate() {
        val file = temporary.newFile("providers.json").apply { writeText("{corrupt}") }
        val store = UserProviderStore(file)
        assertTrue(runCatching { store.save(provider, null) }.isFailure)
        assertEquals("{corrupt}", file.readText())
        assertNull(ProviderRegistry.findById(provider.id))
        val blocker = temporary.newFile("not-a-directory")
        assertTrue(runCatching { UserProviderStore(File(blocker, "providers.json")).save(provider, null) }.isFailure)
        assertNull(ProviderRegistry.findById(provider.id))
    }
}
