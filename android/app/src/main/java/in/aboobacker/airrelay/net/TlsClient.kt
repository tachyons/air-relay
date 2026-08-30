package `in`.aboobacker.airrelay.net

import `in`.aboobacker.airrelay.protocol.Frame
import `in`.aboobacker.airrelay.protocol.FrameCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * mTLS 1.3 connection to the macOS peer. Trust is a pinned SHA-256 certificate
 * fingerprint captured during pairing; no CA validation is performed.
 *
 * When [pinnedFingerprint] is null the connection is only permitted in
 * [pairingMode]; the observed certificate is exposed via [peerFingerprint] and
 * must be confirmed by the user (QR / SAS) before being persisted.
 */
class TlsClient(
    private val identity: DeviceIdentity,
    private val pinnedFingerprint: String?,
    private val pairingMode: Boolean = false,
) {
    private var socket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    /** SHA-256 fingerprint of the peer certificate from the last handshake. */
    @Volatile
    var peerFingerprint: String? = null
        private set

    /** DER bytes of the peer certificate, used for SAS computation. */
    @Volatile
    var peerCertificate: ByteArray? = null
        private set

    /**
     * 6-digit Short Authentication String derived from both certificates.
     * Must match the code shown on the Mac before pairing is confirmed.
     */
    fun sasCode(): String? {
        val peer = peerCertificate ?: return null
        return `in`.aboobacker.airrelay.protocol.Sas.code(peer, identity.certificate.encoded)
    }

    @Synchronized
    fun connect(host: String, port: Int, timeoutMs: Int = 10_000) {
        close()
        // We use a custom X509TrustManager for manual certificate pinning to the macOS peer.
        // The fingerprint is confirmed during pairing and persisted.
        val trustManager = @android.annotation.SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client trust not applicable")

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val encoded = chain[0].encoded
                val fp = MessageDigest.getInstance("SHA-256")
                    .digest(encoded)
                    .joinToString("") { "%02x".format(it) }
                peerFingerprint = fp
                peerCertificate = encoded
                when {
                    pinnedFingerprint != null -> {
                        if (fp != pinnedFingerprint) {
                            throw CertificateException("Peer certificate does not match pinned fingerprint")
                        }
                    }
                    pairingMode -> Unit
                    else -> throw CertificateException("Not paired; refusing unpinned peer")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(identity.keyManager()), arrayOf(trustManager), null)
        }
        val s = context.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = READ_TIMEOUT_MS
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.startHandshake()
        socket = s
        input = DataInputStream(s.inputStream.buffered())
        output = DataOutputStream(s.outputStream.buffered())
    }

    fun send(frame: Frame) {
        val out = output ?: error("Not connected")
        synchronized(out) { FrameCodec.write(out, frame) }
    }

    fun receive(): Frame {
        val inp = input ?: error("Not connected")
        return FrameCodec.read(inp)
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    @Synchronized
    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        /** Must exceed the 15s keepalive interval so PINGs keep the read alive. */
        private const val READ_TIMEOUT_MS = 45_000
    }
}
