package com.baystudio.droide.core

import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType

internal object DeviceBridgeConnectionPolicy {
    const val PAIR_ONCE_MESSAGE = "Connect manually, or pair once to enable automatic Wireless Debugging reconnection."

    fun validGuid(value: String?): String? = value?.takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}"))
    }

    fun requireEndpoint(endpoint: MdnsEndpoint) {
        require(endpoint.serviceType == MdnsServiceType.TLS_CONNECT || endpoint.serviceType == MdnsServiceType.ADB) {
            "Not a connect endpoint"
        }
        require(endpoint.host.isNotBlank() && endpoint.host.length <= 253 && endpoint.port in 1..65535 &&
            endpoint.host.none { it.isWhitespace() || it == '\u0000' }) { "Invalid device endpoint" }
    }

    fun requiresTls(endpoint: MdnsEndpoint) = endpoint.serviceType == MdnsServiceType.TLS_CONNECT

    fun reusable(current: MdnsEndpoint, requested: MdnsEndpoint): Boolean =
        current.serviceType == requested.serviceType && if (requiresTls(current)) {
            validGuid(current.name) != null && current.name == requested.name
        } else current.host == requested.host && current.port == requested.port

    fun automaticCandidates(endpoints: List<MdnsEndpoint>, pairedGuid: String?): List<MdnsEndpoint> {
        val guid = validGuid(pairedGuid) ?: return emptyList()
        return endpoints.filter {
            it.serviceType == MdnsServiceType.TLS_CONNECT && it.name == guid &&
                runCatching { requireEndpoint(it) }.isSuccess
        }.distinctBy { listOf(it.name, it.host, it.port.toString(), it.serviceType.name) }
    }
}
