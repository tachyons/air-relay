package `in`.aboobacker.airrelay.sync

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.MediaAction
import `in`.aboobacker.airrelay.protocol.MediaState
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.serialization.encodeToString

/**
 * Watches the phone's active media sessions via MediaSessionManager (which
 * reuses the notification-listener permission the app already requires) and
 * forwards now-playing state to the Mac. Executes transport controls sent
 * back from the Mac on the current session.
 */
class MediaMonitor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var started = false

    /** Last encoded album art, keyed by "title|artist" to avoid re-encoding. */
    private var lastArtKey: String? = null
    private var lastArtPng: String? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attach(controllers.orEmpty())
        }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            sendState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            sendState()
        }

        override fun onSessionDestroyed() {
            mainHandler.post { refreshSessions() }
        }
    }

    fun start() {
        if (started) return
        mainHandler.post {
            runCatching {
                val manager = context.getSystemService(MediaSessionManager::class.java)
                val listenerComponent =
                    ComponentName(context, NotificationRelayService::class.java)
                manager.addOnActiveSessionsChangedListener(
                    sessionsListener,
                    listenerComponent,
                    mainHandler,
                )
                this.manager = manager
                started = true
                attach(manager.getActiveSessions(listenerComponent))
                Log.i(TAG, "Media monitoring started")
            }.onFailure {
                // Notification access not granted yet; media sync stays off.
                Log.w(TAG, "Media monitoring unavailable: ${it.message}")
            }
        }
    }

    fun stop() {
        mainHandler.post {
            controller?.unregisterCallback(callback)
            controller = null
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
            manager = null
            started = false
        }
    }

    /** Re-sends the current state (used right after connecting to the Mac). */
    fun resend() {
        mainHandler.post { sendState() }
    }

    fun execute(action: MediaAction) {
        mainHandler.post {
            val transport = controller?.transportControls ?: return@post
            when (action.action) {
                "play" -> transport.play()
                "pause" -> transport.pause()
                "next" -> transport.skipToNext()
                "previous" -> transport.skipToPrevious()
                else -> Log.w(TAG, "Unknown media action: ${action.action}")
            }
        }
    }

    private fun refreshSessions() {
        val manager = manager ?: return
        runCatching {
            attach(
                manager.getActiveSessions(
                    ComponentName(context, NotificationRelayService::class.java),
                ),
            )
        }
    }

    private fun attach(controllers: List<MediaController>) {
        val best = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) {
            sendState()
            return
        }
        controller?.unregisterCallback(callback)
        controller = best
        best?.registerCallback(callback, mainHandler)
        sendState()
    }

    private fun sendState() {
        val controller = controller
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val payload = if (controller == null || title == null) {
            MediaState()
        } else {
            MediaState(
                packageName = controller.packageName,
                appName = appLabel(controller.packageName),
                title = title,
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                artPng = albumArtPng(metadata, title),
            )
        }
        SyncService.instance?.send(
            FrameType.MEDIA_STATE,
            ProtocolJson.encodeToString(payload).encodeToByteArray(),
        )
    }

    private fun albumArtPng(metadata: MediaMetadata, title: String): String? {
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val key = "$title|$artist"
        if (key == lastArtKey) return lastArtPng
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val encoded = art?.let { encode(it) }
        lastArtKey = key
        lastArtPng = encoded
        return encoded
    }

    private fun encode(source: Bitmap): String? = runCatching {
        val maxSize = 128
        val scale = minOf(
            1f,
            maxSize.toFloat() / source.width,
            maxSize.toFloat() / source.height,
        )
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true,
            )
        } else {
            source
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (bitmap !== source) bitmap.recycle()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun appLabel(packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0),
        ).toString()
    }.getOrDefault(packageName)

    companion object {
        private const val TAG = "MediaMonitor"
    }
}
