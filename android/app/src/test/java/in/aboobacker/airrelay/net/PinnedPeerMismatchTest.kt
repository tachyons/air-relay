package `in`.aboobacker.airrelay.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

class PinnedPeerMismatchTest {

    @Test
    fun `detects a mismatch wrapped by the TLS stack`() {
        val wrapped = SSLHandshakeException("handshake failed").apply {
            initCause(PinnedPeerMismatch("Peer certificate does not match pinned fingerprint"))
        }
        assertTrue(wrapped.isPinnedPeerMismatch())
    }

    @Test
    fun `ignores unrelated failures`() {
        assertFalse(IOException("connection refused").isPinnedPeerMismatch())
        assertFalse(SSLHandshakeException("no cipher suites in common").isPinnedPeerMismatch())
    }
}
