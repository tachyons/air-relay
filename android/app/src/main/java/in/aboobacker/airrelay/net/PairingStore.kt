package `in`.aboobacker.airrelay.net

import android.content.Context
import androidx.core.content.edit

/** Persists the paired Mac's certificate fingerprint and last known endpoint. */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    var peerFingerprint: String?
        get() = prefs.getString("peerFingerprint", null)
        set(value) = prefs.edit { putString("peerFingerprint", value) }

    var peerName: String?
        get() = prefs.getString("peerName", null)
        set(value) = prefs.edit { putString("peerName", value) }

    val isPaired: Boolean get() = peerFingerprint != null

    fun clear() = prefs.edit { clear() }
}
