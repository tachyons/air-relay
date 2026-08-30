package `in`.aboobacker.airrelay.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Self-signed P-256 identity used for mutual TLS, generated and stored in the
 * Android Keystore. The keystore issues a self-signed certificate automatically.
 */
class DeviceIdentity private constructor(private val keyStore: KeyStore) {

    val certificate: X509Certificate
        get() = keyStore.getCertificate(ALIAS) as X509Certificate

    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * AndroidKeyStore-backed keys are not extractable, so the default
     * KeyManagerFactory cannot use them; this key manager hands the SSL stack
     * the key reference and certificate chain directly.
     */
    fun keyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = ALIAS

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ) = ALIAS

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?,
        ) = ALIAS

        override fun getCertificateChain(alias: String): Array<X509Certificate> =
            arrayOf(certificate)

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
            arrayOf(ALIAS)

        override fun getPrivateKey(alias: String): PrivateKey =
            keyStore.getKey(ALIAS, null) as PrivateKey
    }

    companion object {
        // v2: DIGEST_NONE added; TLS 1.3 client auth signs pre-hashed digests
        // via NONEwithECDSA, which Keystore rejects unless DIGEST_NONE is allowed.
        private const val ALIAS = "airrelay-identity-v2"

        fun load(): DeviceIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) generate()
            return DeviceIdentity(store)
        }

        private fun generate() {
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=AirRelay Android"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotAfter(end.time)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(spec)
                generateKeyPair()
            }
        }
    }
}
