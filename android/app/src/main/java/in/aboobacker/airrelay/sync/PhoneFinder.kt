package `in`.aboobacker.airrelay.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Rings the phone at full volume when the Mac asks "where's my phone?".
 * Uses the alarm stream so it sounds even when the ringer is muted or in
 * do-not-disturb (alarms are exempt by default). A high-priority
 * notification offers one tap to stop; ringing also stops automatically
 * after a timeout.
 */
class PhoneFinder(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var previousAlarmVolume: Int? = null
    private val timeoutRunnable = Runnable { stop(notifyPeer = true) }

    val isRinging: Boolean
        get() = ringtone?.isPlaying == true

    fun start() {
        mainHandler.post {
            if (isRinging) return@post
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ?: return@post
                val tone = RingtoneManager.getRingtone(context, uri) ?: return@post
                tone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                tone.isLooping = true
                previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0,
                )
                tone.play()
                ringtone = tone
                showNotification()
                mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                Log.i(TAG, "Ringing for find-my-phone")
            }.onFailure {
                Log.w(TAG, "Failed to ring: ${it.message}")
                previousAlarmVolume?.let { saved ->
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
                }
                previousAlarmVolume = null
            }
        }
    }

    fun stop(notifyPeer: Boolean) {
        mainHandler.post {
            mainHandler.removeCallbacks(timeoutRunnable)
            val tone = ringtone ?: return@post
            ringtone = null
            runCatching { tone.stop() }
            previousAlarmVolume?.let {
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
            }
            previousAlarmVolume = null
            context.getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
            if (notifyPeer) {
                SyncService.instance?.send(
                    `in`.aboobacker.airrelay.protocol.FrameType.FIND_PHONE_STOP,
                    ByteArray(0),
                )
            }
            Log.i(TAG, "Stopped ringing")
        }
    }

    private fun showNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Find my phone",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        val stopIntent = Intent(context, SyncService::class.java)
            .setAction(SyncService.ACTION_FIND_PHONE_STOP)
        val stopPending = PendingIntent.getService(
            context,
            3,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val fullScreenIntent = Intent(context, FindPhoneActivity::class.java)
        val fullScreenPending = PendingIntent.getActivity(
            context,
            4,
            fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Ringing so you can find this phone")
            .setContentText("Tap to stop")
            .setSmallIcon(`in`.aboobacker.airrelay.R.drawable.ic_launcher_foreground)
            .setContentIntent(stopPending)
            .setFullScreenIntent(fullScreenPending, true)
            .setOngoing(true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "PhoneFinder"
        private const val CHANNEL_ID = "find_phone"
        private const val NOTIFICATION_ID = 3
        private const val TIMEOUT_MS = 60_000L
    }
}
