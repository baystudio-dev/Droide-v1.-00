package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.FetchConnection
import org.eclipse.jgit.transport.PushConnection
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import org.junit.Assert.*
import org.junit.Test

class GitTransportAvailabilityTest {
    @Test fun unsupportedCloneDoesNotInitializeRepositoryOrReadConnectedCredential() = runBlocking {
        assertNull("This Android core dependency has no SSH provider", SshSessionFactory.getInstance())
        val root = Files.createTempDirectory("ssh-preflight-").toFile()
        var credentialReads = 0
        try {
            val manager = GitManager(root, githubCredentialResolver = {
                credentialReads++
                GitHubTransportCredential("fixture", "synthetic-token")
            })
            var blocked = false
            try { manager.clone("ssh://git@example.invalid/repo.git") }
            catch (expected: IllegalArgumentException) { blocked = expected.message!!.contains("SSH transport is unavailable") }
            assertTrue(blocked)
            assertFalse(root.resolve(".git").exists())
            assertEquals(0, credentialReads)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun sshIsValidConfigurationButOperationRequiresProvider() {
        for (raw in listOf("ssh://git@example.invalid/repo.git", "git@example.invalid:repo.git")) {
            assertTrue(GitRemoteSecurity.isAllowed(raw))
            assertThrows(IllegalArgumentException::class.java) {
                GitTransportSecurity.requireAvailable(listOf(URIish(raw)), sshAvailable = false)
            }
            GitTransportSecurity.requireAvailable(listOf(URIish(raw)), sshAvailable = true)
        }
    }

    @Test fun selectedHttpsFetchIgnoresUnusedSshWhileMultiPushIsPreflighted() {
        InMemoryRepository(DfsRepositoryDescription("availability-fixture")).use { repo ->
            repo.config.setStringList("remote", "origin", "url", listOf(
                "https://example.invalid/repo.git", "git@example.invalid:unused.git",
            ))
            repo.config.setString("remote", "other", "url", "git@example.invalid:other.git")
            val before = repo.config.toText()
            val fetch = GitTransportSecurity.operationUris(repo, "origin", false)
            assertEquals(1, fetch.size)
            GitTransportSecurity.requireAvailable(fetch, sshAvailable = false)
            assertThrows(IllegalArgumentException::class.java) {
                GitTransportSecurity.requireAvailable(GitTransportSecurity.operationUris(repo, "origin", true), sshAvailable = false)
            }
            assertEquals(before, repo.config.toText())
        }
    }

    @Test fun callbackPreservesExplicitSshFactoryWithoutOpeningSession() {
        InMemoryRepository(DfsRepositoryDescription("factory-fixture")).use { repo ->
            val transport = object : SshTransport(repo, URIish("ssh://git@example.invalid/repo.git")) {
                override fun openFetch(): FetchConnection = error("No network allowed")
                override fun openPush(): PushConnection = error("No network allowed")
            }
            val factory = object : SshSessionFactory() {
                override fun getType(): String = "synthetic"
                override fun getSession(uri: URIish, provider: CredentialsProvider?, fs: FS, timeout: Int): RemoteSession =
                    error("No network allowed")
            }
            transport.sshSessionFactory = factory
            try {
                GitTransportSecurity.callback(false).configure(transport)
                assertSame(factory, transport.sshSessionFactory)
            } finally { transport.close() }
        }
    }
}
