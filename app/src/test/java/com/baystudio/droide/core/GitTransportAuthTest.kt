package com.baystudio.droide.core

import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.*
import org.junit.Test

class GitTransportAuthTest {
    @Test fun providerChecksActualUriAndNeverApprovesCertificateTrust() {
        InMemoryRepository(DfsRepositoryDescription("auth-fixture")).use { repo ->
            repo.config.setString("remote", "origin", "url", "https://github.com/fixture/repo.git")
            val provider = GitTransportAuth.forRemote(repo, "origin", false, "fixture", "synthetic-token")!!
            try {
                val user = CredentialItem.Username()
                val password = CredentialItem.Password()
                assertFalse(provider.get(URIish("https://github.com:8443/fixture/repo.git"), user, password))
                assertNull(password.value)
                assertFalse(provider.get(URIish("https://example.invalid/fixture/repo.git"), user, password))
                assertFalse(provider.get(URIish("http://github.com/fixture/repo.git"), user, password))
                val trust = CredentialItem.YesNoType("trust insecure certificate")
                assertFalse(provider.supports(trust))
                assertFalse(provider.get(URIish("https://github.com/fixture/repo.git"), trust))
                assertFalse(trust.value)
                assertTrue(provider.get(URIish("https://github.com/fixture/repo.git"), user, password))
                assertEquals("synthetic-token", String(requireNotNull(password.value)))
                password.clear()
            } finally { provider.clear() }
        }
    }

    @Test fun rewrittenAndEveryPushDestinationAreCheckedWithoutChangingConfig() {
        InMemoryRepository(DfsRepositoryDescription("rewrite-fixture")).use { repo ->
            val config = repo.config
            config.setString("remote", "origin", "url", "https://github.com/fixture/repo.git")
            config.setString("url", "https://example.invalid/", "insteadOf", "https://github.com/")
            val before = config.toText()
            assertFalse(GitTransportAuth.mayUseConnectedAccountForRemote(repo, "origin", false))
            assertThrows(IllegalArgumentException::class.java) {
                GitTransportAuth.forRemote(repo, "origin", false, token = "synthetic-token")
            }
            assertEquals(before, config.toText())
            config.unsetSection("url", "https://example.invalid/")
            config.setStringList("remote", "origin", "pushurl", listOf(
                "https://github.com/fixture/repo.git", "https://example.invalid/fixture/repo.git",
            ))
            assertFalse(GitTransportAuth.mayUseConnectedAccountForRemote(repo, "origin", true))
            config.setStringList("remote", "origin", "pushurl", emptyList())
            config.setString("url", "ssh://git@example.invalid/", "pushInsteadOf", "https://github.com/")
            assertTrue(GitTransportAuth.mayUseConnectedAccountForRemote(repo, "origin", false))
            assertFalse(GitTransportAuth.mayUseConnectedAccountForRemote(repo, "origin", true))
        }
    }
}
