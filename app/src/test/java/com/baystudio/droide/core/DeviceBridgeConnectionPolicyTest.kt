package com.baystudio.droide.core

import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import org.junit.Assert.*
import org.junit.Test

class DeviceBridgeConnectionPolicyTest {
    private val guid = "adb-serial-A1b2C3"
    private fun endpoint(name: String = guid, host: String = "192.0.2.1", port: Int = 37123,
        type: MdnsServiceType = MdnsServiceType.TLS_CONNECT) = MdnsEndpoint(name, host, port, type)

    @Test fun actualPairedGuidSurvivesHostAndPortChanges() {
        val moved = endpoint(host = "192.0.2.2", port = 40111)
        assertEquals(listOf(moved), DeviceBridgeConnectionPolicy.automaticCandidates(listOf(moved), guid))
    }
    @Test fun hostOnlyOrMissingPairingCannotEnableAutomaticSelection() {
        assertTrue(DeviceBridgeConnectionPolicy.automaticCandidates(listOf(endpoint()), null).isEmpty())
        assertTrue(DeviceBridgeConnectionPolicy.automaticCandidates(listOf(endpoint()), "192.0.2.1").isEmpty())
    }
    @Test fun wrongGuidSameHostAndLegacyAdvertisementAreNotEligible() {
        val values = listOf(endpoint(name = "adb-other-A1b2C3"), endpoint(type = MdnsServiceType.ADB))
        assertTrue(DeviceBridgeConnectionPolicy.automaticCandidates(values, guid).isEmpty())
    }
    @Test fun onlyExactDuplicatesCollapseAndAmbiguityRemains() {
        val original = endpoint()
        val other = endpoint(port = 40111)
        assertEquals(listOf(original, other), DeviceBridgeConnectionPolicy.automaticCandidates(listOf(original, original, other), guid))
    }
    @Test fun manualLegacyRemainsAllowedButCannotReuseAcrossProtocol() {
        val legacy = endpoint(type = MdnsServiceType.ADB)
        DeviceBridgeConnectionPolicy.requireEndpoint(legacy)
        assertFalse(DeviceBridgeConnectionPolicy.requiresTls(legacy))
        assertFalse(DeviceBridgeConnectionPolicy.reusable(legacy, endpoint()))
        assertFalse(DeviceBridgeConnectionPolicy.reusable(legacy, legacy.copy(port = 5555)))
    }
    @Test fun invalidEndpointsAndGuidAreRejected() {
        assertNull(DeviceBridgeConnectionPolicy.validGuid("bad\u0000guid"))
        assertNull(DeviceBridgeConnectionPolicy.validGuid(" "))
        assertTrue(runCatching { DeviceBridgeConnectionPolicy.requireEndpoint(endpoint(port = 0)) }.isFailure)
        assertTrue(runCatching { DeviceBridgeConnectionPolicy.requireEndpoint(endpoint(type = MdnsServiceType.TLS_PAIRING)) }.isFailure)
    }
}
