package `in`.aboobacker.airrelay.sync

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import `in`.aboobacker.airrelay.protocol.FocusState
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Syncs Do Not Disturb between phone and Mac.
 * Uses NotificationManager interruption filter as DND signal.
 * Requires ACCESS_NOTIFICATION_POLICY; gracefully no-ops if denied.
 */
class FocusMonitor(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var lastSentDnd: Boolean? = null
    private var lastReceivedAt = 0L
    private var lastSentAt = 0L

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                    handleLocalChange()
                }
            }
        }
        context.registerReceiver(r, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED))
        receiver = r
        // Send initial state if enabled
        handleLocalChange()
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun handleLocalChange() {
        if (!FeaturePrefs(context).focusSync) return
        if (!manager.isNotificationPolicyAccessGranted) return
        val dnd = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        // loop prevention: ignore change shortly after we applied a remote DND
        if (System.currentTimeMillis() - lastReceivedAt < 2000) return
        if (dnd == lastSentDnd) return
        lastSentDnd = dnd
        lastSentAt = System.currentTimeMillis()
        Log.i(TAG, "Sending focusState dnd=$dnd")
        SyncService.instance?.send(
            FrameType.FOCUS_STATE,
            ProtocolJson.encodeToString(FocusState(dnd)).encodeToByteArray()
        )
    }

    fun onRemoteState(dnd: Boolean) {
        if (!FeaturePrefs(context).focusSync) return
        if (!manager.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "No DND policy access, ignoring remote focus")
            return
        }
        if (System.currentTimeMillis() - lastSentAt < 2000 && dnd == lastSentDnd) return
        lastReceivedAt = System.currentTimeMillis()
        lastSentDnd = dnd
        val filter = if (dnd) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
        runCatching { manager.setInterruptionFilter(filter) }
            .onFailure { Log.w(TAG, "Failed to set DND: ${it.message}") }
        Log.i(TAG, "Applied remote focus dnd=$dnd")
    }

    fun isDndOn(): Boolean {
        return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    companion object {
        private const val TAG = "FocusMonitor"
    }
}
