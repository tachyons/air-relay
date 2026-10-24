package `in`.aboobacker.airrelay.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Wire protocol this build speaks. Bump together with docs/PROTOCOL.md; a
 * mismatch is shown to the user instead of half-working.
 */
const val PROTOCOL_VERSION = 1

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Hello(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val platform: String = "android",
    val appVersion: String,
    /** True while this side waits for the user to confirm a pairing code. */
    val pairing: Boolean = false,
)

@Serializable
data class NotificationPayload(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val canReply: Boolean,
    val actions: List<String> = emptyList(),
    val iconPng: String? = null,
)

@Serializable
data class NotificationDismiss(val key: String)

@Serializable
data class NotificationReply(val key: String, val text: String)

@Serializable
data class NotificationAction(val key: String, val action: String)

@Serializable
data class ClipboardText(val text: String, val ts: Long)

@Serializable
data class OpenUrl(val url: String)

@Serializable
data class CallState(
    val callId: String,
    val state: String,
    val displayName: String? = null,
    val number: String? = null,
    val photoPng: String? = null,
)

@Serializable
data class CallAction(val callId: String, val action: String)

@Serializable
data class FileMeta(
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
)

@Serializable
data class CameraStart(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val lens: String = "back",
)

@Serializable
data class MediaState(
    val packageName: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val artPng: String? = null,
)

@Serializable
data class MediaAction(val action: String)

@Serializable
data class DeviceStatus(
    val battery: Int,
    val charging: Boolean,
    val wifiSsid: String? = null,
    val networkType: String? = null,
    val signalLevel: Int? = null,
)

@Serializable
data class FocusState(val dnd: Boolean)

@Serializable
data class RingerAction(val mode: String)

/**
 * FILE_CHUNK binary payload: transferId (16 bytes) || offset (u64 BE) || data.
 * Parsing is defensive: a short or malformed header returns null so the
 * read loop can drop it instead of failing the connection.
 */
class FileChunk private constructor(
    val transferId: String,
    val offset: Long,
    val data: ByteArray,
) {
    companion object {
        fun parse(payload: ByteArray): FileChunk? {
            if (payload.size < 24) return null
            val buffer = ByteBuffer.wrap(payload)
            val id = UUID(buffer.long, buffer.long).toString()
            val offset = buffer.long
            if (offset < 0) return null
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return FileChunk(id, offset, data)
        }
    }
}
