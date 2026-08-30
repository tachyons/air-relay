package `in`.aboobacker.airrelay.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SasTest {

    /**
     * Golden vector shared with the Swift test suite
     * (macos/Tests/AirRelayKitTests/SasTests.swift). If this changes, pairing
     * between platforms breaks.
     */
    @Test
    fun `matches cross-platform vector`() {
        val macCert = ByteArray(32) { (it + 1).toByte() }
        val androidCert = ByteArray(32) { (it + 33).toByte() }
        assertEquals("875972", Sas.code(macCert, androidCert))
    }

    @Test
    fun `is order sensitive`() {
        val a = ByteArray(16) { 1 }
        val b = ByteArray(16) { 2 }
        assert(Sas.code(a, b) != Sas.code(b, a))
    }

    @Test
    fun `always six digits`() {
        repeat(50) { seed ->
            val code = Sas.code(ByteArray(8) { seed.toByte() }, ByteArray(8) { (seed + 1).toByte() })
            assertEquals(6, code.length)
        }
    }
}
