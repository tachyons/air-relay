package `in`.aboobacker.airrelay.sync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import `in`.aboobacker.airrelay.protocol.CallAction
import `in`.aboobacker.airrelay.protocol.CallState
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Tracks call state via the PHONE_STATE broadcast (which carries the incoming
 * number when READ_CALL_LOG is granted, unlike TelephonyCallback) and forwards
 * ringing/active/ended events to the Mac with caller ID resolved from contacts.
 *
 * Remote answer uses TelecomManager.acceptRingingCall and reject/hangup uses
 * TelecomManager.endCall (both work for non-default-dialer apps holding
 * ANSWER_PHONE_CALLS).
 */
class CallMonitor(private val context: Context) {

    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var currentCallId: String? = null
    private var currentNumber: String? = null
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE

    /** Set when the Mac triggered the answer, so we can auto-route audio. */
    private var answeredFromMac = false

    /** User preference: enable speakerphone when a call is answered from the Mac. */
    var autoSpeakerOnMacAnswer: Boolean
        get() = prefs.getBoolean("autoSpeaker", true)
        set(value) {
            prefs.edit { putBoolean("autoSpeaker", value) }
        }

    private val prefs = context.getSharedPreferences("call_settings", Context.MODE_PRIVATE)

    fun start() {
        if (receiver != null) return
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            Log.w(TAG, "READ_PHONE_STATE not granted; call sync disabled")
            return
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                handleStateChange(state, number)
            }
        }
        context.registerReceiver(r, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        receiver = r
        Log.i(TAG, "Call monitoring started")
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    private fun handleStateChange(state: String, number: String?) {
        if (!FeaturePrefs(context).callSync) return
        // The broadcast fires once per SIM/phone account with the same state;
        // also fires with a null number first, then again with the number.
        if (number != null) currentNumber = number
        val stateName = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (lastState == state) {
                    // Duplicate ringing broadcast — resend only if number just arrived
                    if (number == null) return
                    "ringing"
                } else {
                    currentCallId = UUID.randomUUID().toString()
                    "ringing"
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == state) return
                if (currentCallId == null) currentCallId = UUID.randomUUID().toString()
                if (answeredFromMac && autoSpeakerOnMacAnswer) {
                    // Delay slightly so telephony finishes routing before we override.
                    mainHandler.postDelayed({ setSpeaker(true) }, 500)
                }
                answeredFromMac = false
                "active"
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == TelephonyManager.EXTRA_STATE_IDLE) return
                answeredFromMac = false
                mainHandler.post { setSpeaker(false) }
                "ended"
            }
            else -> return
        }
        lastState = state
        val callId = currentCallId ?: return
        val callerNumber = currentNumber
        if (stateName == "ended") {
            currentCallId = null
            currentNumber = null
        }

        val contact = callerNumber?.let { lookupContact(it) }
        val payload = CallState(
            callId = callId,
            state = stateName,
            displayName = contact?.name,
            number = callerNumber,
            photoPng = contact?.photoPng,
        )
        Log.i(TAG, "Call state: $stateName number=${callerNumber != null}")
        SyncService.instance?.send(
            FrameType.CALL_STATE,
            ProtocolJson.encodeToString(payload).encodeToByteArray(),
        )
    }

    private data class Contact(val name: String?, val photoPng: String?)

    /** Cached per number so repeated state changes don't re-query or re-encode. */
    private val contactCache = HashMap<String, Contact?>()

    private fun lookupContact(number: String): Contact? = contactCache.getOrPut(number) {
        if (!granted(Manifest.permission.READ_CONTACTS)) return@getOrPut null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                Contact(
                    name = cursor.getString(0),
                    photoPng = cursor.getString(1)?.let { encodePhoto(Uri.parse(it)) },
                )
            }
        }.getOrNull()
    }

    /** Reads a contact photo thumbnail and re-encodes it as base64 PNG (same
     *  pattern as notification app icons). Thumbnails are already small. */
    private fun encodePhoto(photoUri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(photoUri)?.use { stream ->
            val bitmap = android.graphics.BitmapFactory.decodeStream(stream) ?: return@use null
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun execute(action: CallAction) {
        if (!FeaturePrefs(context).callSync) return
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) {
            Log.w(TAG, "ANSWER_PHONE_CALLS not granted; ignoring ${action.action}")
            return
        }
        Log.i(TAG, "Executing call action: ${action.action}")
        try {
            when (action.action) {
                "answer" -> {
                    answeredFromMac = true
                    telecom.acceptRingingCall()
                }
                "reject", "hangup" -> telecom.endCall()
                "speakerOn" -> setSpeaker(true)
                "speakerOff" -> setSpeaker(false)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to execute ${action.action}: ${e.message}")
        }
    }

    /**
     * Routes in-call audio to the built-in speaker (or back to earpiece).
     * If a Bluetooth headset is connected, telephony keeps it — earbuds paired
     * to the phone remain the preferred path for Continuity-like calls.
     */
    private fun setSpeaker(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val bt = audioManager.availableCommunicationDevices.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    }
                    if (bt) return // leave audio on the user's headset
                    audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
            }
            Log.i(TAG, "Speakerphone: $on")
        }.onFailure { Log.w(TAG, "Speaker routing failed: ${it.message}") }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CallMonitor"
    }
}
