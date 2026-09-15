package `in`.aboobacker.airrelay.sync

import android.app.Activity
import android.os.Bundle

/**
 * Full-screen stop UI for "Find my phone". Used as the activity
 * PendingIntent for Notification.Builder.setFullScreenIntent so the
 * system can display an interrupting UI without routing through
 * [SyncService] (service PendingIntents don't show UI when launched
 * as a full-screen intent).
 */
class FindPhoneActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stop ringing immediately — user has seen the phone.
        // Route through the service to ensure volume restoration and
        // FIND_PHONE_STOP peer notification.
        startService(
            android.content.Intent(this, SyncService::class.java)
                .setAction(SyncService.ACTION_FIND_PHONE_STOP),
        )
        finish()
    }
}
