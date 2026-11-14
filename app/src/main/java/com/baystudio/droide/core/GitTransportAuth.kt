package com.baystudio.droide.core

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish

 
data class GitHubTransportCredential(
    val username: String,
    val accessToken: String,
)








object GitTransportAuth {
    fun fetchRemote(repository: Repository): String = "origin"

    fun pullRemote(repository: Repository): String {
        val branch = repository.branch.orEmpty()
        return repository.config.getString("branch", branch, "remote")?.trim()?.takeIf(String::isNotEmpty) ?: "origin"
    }

    fun pushRemote(repository: Repository): String {
        val branch = repository.branch.orEmpty()
        val config = repository.config
        return config.getString("branch", branch, "pushRemote")?.trim()?.takeIf(String::isNotEmpty)
            ?: config.getString("remote", null, "pushDefault")?.trim()?.takeIf(String::isNotEmpty)
            ?: config.getString("branch", branch, "remote")?.trim()?.takeIf(String::isNotEmpty)
            ?: "origin"
    }

    fun forClone(url: String, username: String? = null, token: String? = null): UsernamePasswordCredentialsProvider? {
        if (token.isNullOrBlank()) return null
        requireGitHubHttps(GitTransportSecurity.cloneUri(url).toPrivateString())
        return provider(username, token)
    }

    fun forRemote(
        repository: Repository,
        remoteName: String,
        push: Boolean,
        username: String? = null,
        token: String? = null,
    ): UsernamePasswordCredentialsProvider? {
        if (token.isNullOrBlank()) return null
        val urls = remoteUrls(repository, remoteName, push)
        val blocked = urls.filterNot(GitHubGitTransportPolicy::isGitHubHttps)
        require(blocked.isEmpty()) {
            "GitHub credential is host-bound and will not be sent to non-GitHub or SSH remote '$remoteName': ${blocked.take(4).joinToString { GitRemoteSecurity.redact(it) }}"
        }
        return provider(username, token)
    }

    fun mayUseConnectedAccountForClone(url: String): Boolean =
        GitHubGitTransportPolicy.isGitHubHttps(GitTransportSecurity.cloneUri(url).toPrivateString())

    fun mayUseConnectedAccountForRemote(repository: Repository, remoteName: String, push: Boolean): Boolean =
        GitHubGitTransportPolicy.mayUseConnectedAccount(remoteUrls(repository, remoteName, push))

    fun isGitHubHttps(raw: String): Boolean = GitHubGitTransportPolicy.isGitHubHttps(raw)

    private fun remoteUrls(repository: Repository, remoteName: String, push: Boolean): List<String> {
        return GitTransportSecurity.remoteUris(repository, remoteName, push)
            .map(URIish::toPrivateString).distinct()
    }

    private fun requireGitHubHttps(raw: String) {
        require(GitHubGitTransportPolicy.isGitHubHttps(raw)) {
            "GitHub credential authentication is allowed only for clean HTTPS github.com remotes"
        }
    }

    private fun provider(username: String?, token: String): UsernamePasswordCredentialsProvider {
        val secret = token.trim()
        require(secret.length in 1..4_096 && secret.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Git credential is invalid" }
        val user = username?.trim()?.takeIf(String::isNotEmpty) ?: "x-access-token"
        require(user.length <= 256 && user.none { it == '\n' || it == '\r' || it == '\u0000' || it == ':' }) { "Git username is invalid" }
        return object : UsernamePasswordCredentialsProvider(user, secret) {
            override fun supports(vararg items: CredentialItem): Boolean =
                items.none { it is CredentialItem.YesNoType } && super.supports(*items)

            override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
                if (!GitHubGitTransportPolicy.isGitHubHttps(uri.toPrivateString()) || !supports(*items)) return false
                return super.get(uri, *items)
            }
        }
    }
}
