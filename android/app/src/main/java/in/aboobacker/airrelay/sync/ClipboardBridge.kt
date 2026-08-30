package `in`.aboobacker.airrelay.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import `in`.aboobacker.airrelay.protocol.ClipboardText
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Android 10+ blocks background clipboard reads. Outbound sync is triggered by
 * user interaction (ClipboardReadActivity grabs focus briefly, reads, sends).
 * Inbound text from the Mac is applied to the system clipboard directly.
 */
object ClipboardBridge {

    fun onRemoteClipboard(context: Context, text: String) {
        if (!FeaturePrefs(context).clipboardSync) return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Air Relay", text))
    }

    fun readAndSend(context: Context) {
        if (!FeaturePrefs(context).clipboardSync) {
            Toast.makeText(context, "Clipboard sync is turned off in settings", Toast.LENGTH_SHORT).show()
            return
        }
        val service = SyncService.instance
        if (service == null || !service.isConnected) {
            Toast.makeText(context, "Not connected to your Mac", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = ClipboardText(text = text, ts = System.currentTimeMillis())
        service.send(
            FrameType.CLIPBOARD_TEXT,
            ProtocolJson.encodeToString(payload).encodeToByteArray(),
        )
        Toast.makeText(context, "Sent to Mac", Toast.LENGTH_SHORT).show()
    }

    fun launchReadActivity(context: Context) {
        context.startActivity(
            Intent(context, ClipboardReadActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
