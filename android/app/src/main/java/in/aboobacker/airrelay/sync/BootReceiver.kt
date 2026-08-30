package `in`.aboobacker.airrelay.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.aboobacker.airrelay.net.PairingStore

/** Restarts the sync service after boot or app update if a Mac is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> if (PairingStore(context).isPaired) {
                runCatching { SyncService.start(context) }
            }
        }
    }
}
