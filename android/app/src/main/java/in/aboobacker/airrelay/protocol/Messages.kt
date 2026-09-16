package `in`.aboobacker.airrelay.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Hello(
    val protocolVersion: Int = 1,
    val deviceName: String,
    val platform: String = "android",
    val appVersion: String,
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
