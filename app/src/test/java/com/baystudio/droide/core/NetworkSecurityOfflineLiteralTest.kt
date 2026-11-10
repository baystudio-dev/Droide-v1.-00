package com.baystudio.droide.core

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NetworkSecurityOfflineLiteralTest {
    @Test fun ambiguousOrMalformedNumericHostsAreRejectedBeforeAnyResolverCallback() {
        var parseCalls = 0
        for (host in listOf("123.", "123", "999999999999999", "1.2", "1.2.3", "1.2.3.", "127.1", "2130706433", "1.2.3.4.",
            "1.2.3.256", "1.2.3.4.5", "1.2..4")) {
            try {
                NetworkSecurity.validatePublicHttpsUrl("https://$host/archive.bin") {
                    parseCalls++
                    throw AssertionError("Numeric host reached a resolver callback: $it")
                }
                fail("Ambiguous numeric host was admitted: $host")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, parseCalls)
    }

    @Test fun publicIpv4AndUnresolvedDomainRemainOfflineWhilePrivateLiteralsAreRejected() {
        var parseCalls = 0
        val unexpectedParser: (String) -> InetAddress = {
            parseCalls++
            throw AssertionError("Unexpected resolver callback: $it")
        }
        for (host in listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "offline-cache.invalid")) {
            assertEquals(host, NetworkSecurity.validatePublicHttpsUrl("https://$host/archive.bin", unexpectedParser).host)
        }
        for (host in listOf("127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "100.64.0.1", "169.254.0.1", "224.0.0.1")) {
            try {
                NetworkSecurity.validatePublicHttpsUrl("https://$host/archive.bin", unexpectedParser)
                fail("Private/local literal was admitted: $host")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("Private/local"))
            }
        }
        assertEquals(0, parseCalls)
    }

    @Test fun canonicalIpv6StillUsesTheExistingLiteralAndPrivateAddressCheck() {
        var parseCalls = 0
        try {
            NetworkSecurity.validatePublicHttpsUrl("https://[::1]/archive.bin") { literal ->
                parseCalls++
                assertEquals("::1", literal)
                InetAddress.getByAddress(ByteArray(16).apply { this[15] = 1 })
            }
            fail("IPv6 loopback was admitted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Private/local"))
        }
        assertEquals(1, parseCalls)
        assertNotNull(NetworkSecurity.validatePublicHttpsUrl("https://[2606:4700:4700::1111]/archive.bin"))
    }
}
