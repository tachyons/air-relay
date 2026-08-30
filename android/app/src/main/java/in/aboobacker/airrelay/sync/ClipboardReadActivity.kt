package `in`.aboobacker.airrelay.sync

import android.app.Activity
import android.os.Bundle

/**
 * Transparent 1x1 activity that briefly gains window focus so the app is
 * allowed to read the clipboard on Android 10+, then finishes immediately.
 */
class ClipboardReadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ClipboardBridge.readAndSend(this)
            finish()
        }
    }
}
