package `in`.aboobacker.airrelay.sync

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import `in`.aboobacker.airrelay.protocol.Frame
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.NotificationDismiss
import `in`.aboobacker.airrelay.protocol.NotificationPayload
import `in`.aboobacker.airrelay.protocol.NotificationReply
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Intercepts posted notifications, forwards them to the Mac, and executes
 * remote replies via RemoteInput re-injection.
 */
class NotificationRelayService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    private val iconCache = mutableMapOf<String, String?>()

    private fun appIconPng(packageName: String): String? = iconCache.getOrPut(packageName) {
        runCatching {
            val drawable = packageManager.getApplicationIcon(packageName)
            val size = 64
            val bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }.getOrNull()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (!FeaturePrefs(this).notificationSync) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        Log.d(TAG, "Posted: ${sbn.packageName} title=$title sync=${SyncService.instance != null}")
        if (title == null) return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        }.getOrDefault(sbn.packageName)

        val replyAction = sbn.notification.findReplyAction()
        val payload = NotificationPayload(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postedAt = sbn.postTime,
            canReply = replyAction != null,
            actions = sbn.notification.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            iconPng = appIconPng(sbn.packageName),
        )
        SyncService.instance?.send(
            FrameType.NOTIFICATION,
            ProtocolJson.encodeToString(payload).encodeToByteArray(),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!FeaturePrefs(this).notificationSync) return
        SyncService.instance?.send(
            FrameType.NOTIF_DISMISS,
            ProtocolJson.encodeToString(NotificationDismiss(sbn.key)).encodeToByteArray(),
        )
    }

    private fun executeActionByTitle(key: String, title: String) {
        val sbn = activeNotifications.firstOrNull { it.key == key } ?: return
        val action = sbn.notification.actions
            ?.firstOrNull { it.title?.toString() == title && it.remoteInputs.isNullOrEmpty() }
            ?: return
        runCatching {
            action.actionIntent.send()
        }.onFailure { Log.w(TAG, "Action failed: ${it.message}") }
    }

    private fun executeReply(key: String, text: String) {
        val sbn = activeNotifications.firstOrNull { it.key == key }
        if (sbn == null) {
            Log.w(TAG, "Reply target gone: $key")
            return
        }
        val action = sbn.notification.findReplyAction()
        if (action == null) {
            Log.w(TAG, "No reply action on $key")
            return
        }
        val remoteInputs = action.remoteInputs ?: return
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val results = android.os.Bundle().apply {
            // Fill every free-form input with the reply text; apps read only
            // their own resultKey and some define more than one input.
            remoteInputs.forEach { input ->
                if (input.allowFreeFormInput) putCharSequence(input.resultKey, text)
            }
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        runCatching {
            action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "Reply sent to ${sbn.packageName}")
        }.onFailure { Log.w(TAG, "Reply failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "NotificationRelay"

        @Volatile
        private var instance: NotificationRelayService? = null

        fun reply(reply: NotificationReply) {
            instance?.executeReply(reply.key, reply.text)
        }

        fun executeAction(action: `in`.aboobacker.airrelay.protocol.NotificationAction) {
            instance?.executeActionByTitle(action.key, action.action)
        }

        fun dismiss(frame: Frame) {
            val payload =
                ProtocolJson.decodeFromString<NotificationDismiss>(frame.payload.decodeToString())
            instance?.cancelNotification(payload.key)
        }

        private fun Notification.findReplyAction(): Notification.Action? {
            actions?.firstOrNull { action ->
                action.remoteInputs?.any { it.allowFreeFormInput } == true
            }?.let { return it }
            // Many messaging apps expose their reply action only through the
            // wearable extender (built for watches; works identically).
            return Notification.WearableExtender(this).actions.firstOrNull { action ->
                action.remoteInputs?.any { it.allowFreeFormInput } == true
            }
        }
    }
}
