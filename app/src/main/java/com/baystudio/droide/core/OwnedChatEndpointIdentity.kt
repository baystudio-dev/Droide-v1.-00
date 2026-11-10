package com.baystudio.droide.core

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Identity only, not URL validation or authentication. Never persist raw endpoint credentials. */
internal object OwnedChatEndpointIdentity {
    fun fromValidatedUrl(url: String): String {
        val canonical = url.toHttpUrl().toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "endpoint-sha256:" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
