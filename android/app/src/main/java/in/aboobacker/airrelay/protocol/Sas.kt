package `in`.aboobacker.airrelay.protocol

import java.security.MessageDigest

/**
 * Short Authentication String shown on both devices during pairing.
 * Derivation: sha256(macCert || androidCert), first 4 bytes as a big-endian
 * integer, mod 10^6, zero-padded to 6 digits. Must match the Swift
 * implementation in AirRelayKit exactly.
 */
object Sas {
    fun code(macCert: ByteArray, androidCert: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(macCert)
            update(androidCert)
            digest()
        }
        val value = ((digest[0].toLong() and 0xff) shl 24) or
            ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or
            (digest[3].toLong() and 0xff)
        return "%06d".format(value % 1_000_000)
    }
}
