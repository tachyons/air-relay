package `in`.aboobacker.airrelay.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import `in`.aboobacker.airrelay.protocol.ClipboardText
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Handles ACTION_PROCESS_TEXT ("Send to Mac" in the text-selection toolbar).
 * Sends the selected text to the Mac clipboard and finishes immediately.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val service = SyncService.instance
        when {
            text.isNullOrBlank() -> Unit
            service == null ->
                Toast.makeText(this, "Not connected to Mac", Toast.LENGTH_SHORT).show()
            else -> {
                val payload = ClipboardText(text = text, ts = System.currentTimeMillis())
                service.send(
                    FrameType.CLIPBOARD_TEXT,
                    ProtocolJson.encodeToString(payload).encodeToByteArray(),
                )
                Toast.makeText(this, "Sent to Mac", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
