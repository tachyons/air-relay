package `in`.aboobacker.airrelay.sync

import android.content.Context
import androidx.core.content.edit

/**
 * Per-feature on/off switches. Everything defaults to on; toggles only exist
 * for users who want to keep something private (e.g. notifications).
 */
class FeaturePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("features", Context.MODE_PRIVATE)

    var notificationSync: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    var callSync: Boolean
        get() = prefs.getBoolean(KEY_CALLS, true)
        set(value) = prefs.edit { putBoolean(KEY_CALLS, value) }

    var clipboardSync: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD, true)
        set(value) = prefs.edit { putBoolean(KEY_CLIPBOARD, value) }

    var fileSharing: Boolean
        get() = prefs.getBoolean(KEY_FILES, true)
        set(value) = prefs.edit { putBoolean(KEY_FILES, value) }

    companion object {
        private const val KEY_NOTIFICATIONS = "notificationSync"
        private const val KEY_CALLS = "callSync"
        private const val KEY_CLIPBOARD = "clipboardSync"
        private const val KEY_FILES = "fileSharing"
    }
}
