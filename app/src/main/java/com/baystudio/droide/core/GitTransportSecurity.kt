package com.baystudio.droide.core

import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.DroideTransportConfig
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UrlConfig
import org.eclipse.jgit.util.SystemReader

internal object GitTransportSecurity {
    fun cloneUri(raw: String, config: Config = SystemReader.getInstance().userConfig): URIish =
        URIish(UrlConfig(config).replace(raw))

    fun remoteUris(repository: Repository, remoteName: String, push: Boolean): List<URIish> {
        require(remoteName.matches(Regex("[A-Za-z0-9._/-]{1,200}"))) { "Invalid Git remote name" }
        val remote = RemoteConfig(repository.config, remoteName)
        // RemoteConfig applies insteadOf and pushInsteadOf exactly as pinned JGit does. In particular,
        // explicit pushurl entries are not subjected to another hand-written rewrite.
        val uris = if (push && remote.pushURIs.isNotEmpty()) remote.pushURIs else remote.urIs
        require(uris.isNotEmpty()) { "Git remote has no URL" }
        return uris.toList()
    }

    fun operationUris(repository: Repository, remoteName: String, push: Boolean): List<URIish> =
        remoteUris(repository, remoteName, push).let { if (push) it else it.take(1) }

    fun requireAvailable(uris: Collection<URIish>, sshAvailable: Boolean? = null) {
        for (uri in uris) {
            require(GitRemoteSecurity.isAllowed(uri.toPrivateString())) {
                "Effective Git remote blocked by security policy"
            }
            if (isSsh(uri) && !(sshAvailable ?: (SshSessionFactory.getInstance() != null))) {
                throw IllegalArgumentException("SSH transport is unavailable in this Android build; configure an HTTPS remote")
            }
        }
    }

    fun callback(hasAppCredential: Boolean): TransportConfigCallback = TransportConfigCallback { transport ->
        require(GitRemoteSecurity.isAllowed(transport.uri.toPrivateString())) {
            "Effective Git remote blocked by security policy"
        }
        if (transport is SshTransport) {
            require(transport.sshSessionFactory != null) {
                "SSH transport is unavailable in this Android build; configure an HTTPS remote"
            }
        }
        if (hasAppCredential) {
            require(transport is TransportHttp) { "GitHub credentials require an HTTPS transport" }
            GitCredentialConnectionFactory.requireSafeHeaders(
                additionalHeaders(transport).map { (key, value) -> "$key: $value" },
            )
            transport.httpConnectionFactory = GitCredentialConnectionFactory(
                transport.httpConnectionFactory, DroideTransportConfig.get(transport), transport.uri,
            )
        }
    }

    private fun additionalHeaders(transport: TransportHttp): Map<String, String> = try {
        // Pinned JGit 6.10.1 wraps its initially null headers field in unmodifiableMap, which throws
        // instead of returning an empty map.
        transport.additionalHeaders
    } catch (_: NullPointerException) {
        emptyMap()
    }

    private fun isSsh(uri: URIish): Boolean = uri.scheme.equals("ssh", true) ||
        (uri.scheme == null && uri.host != null)
}
