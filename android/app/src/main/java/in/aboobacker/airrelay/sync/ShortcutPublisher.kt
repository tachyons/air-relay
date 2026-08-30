package `in`.aboobacker.airrelay.sync

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import `in`.aboobacker.airrelay.MainActivity
import `in`.aboobacker.airrelay.R

/**
 * Publishes the paired Mac as a Direct Share target so it appears at the top
 * of the system share sheet ("Send to <Mac name>").
 */
object ShortcutPublisher {

    private const val SHARE_TARGET_ID = "share_to_mac"
    private const val SHARE_CATEGORY = "in.aboobacker.airrelay.category.SHARE_TARGET"

    fun publishShareTarget(context: Context, macName: String) {
        val shortcut = ShortcutInfoCompat.Builder(context, SHARE_TARGET_ID)
            .setShortLabel(macName)
            .setLongLabel("Send to $macName")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground))
            .setCategories(setOf(SHARE_CATEGORY))
            .setIntent(
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
            )
            .setLongLived(true)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    fun removeShareTarget(context: Context) {
        ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHARE_TARGET_ID))
    }
}
