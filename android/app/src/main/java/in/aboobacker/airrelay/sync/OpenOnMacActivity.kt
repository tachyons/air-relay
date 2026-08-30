package `in`.aboobacker.airrelay.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.OpenUrl
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Share-sheet target "Open on Mac": receives a shared link and opens it in
 * the Mac's default browser. Finishes immediately with a toast.
 */
class OpenOnMacActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractUrl(text)
        val service = SyncService.instance
        when {
            url == null ->
                Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            service == null || !service.isConnected ->
                Toast.makeText(this, "Not connected to Mac", Toast.LENGTH_SHORT).show()
            else -> {
                service.send(
                    FrameType.OPEN_URL,
                    ProtocolJson.encodeToString(OpenUrl(url)).encodeToByteArray(),
                )
                Toast.makeText(this, "Opening on Mac", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private fun extractUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val match = matcher.group()
            if (match.startsWith("http://") || match.startsWith("https://")) return match
        }
        return null
    }
}
