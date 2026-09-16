package `in`.aboobacker.airrelay.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import `in`.aboobacker.airrelay.R
import `in`.aboobacker.airrelay.net.DeviceIdentity
import `in`.aboobacker.airrelay.net.DiscoveryManager
import `in`.aboobacker.airrelay.net.PairingStore
import `in`.aboobacker.airrelay.net.TlsClient
import `in`.aboobacker.airrelay.protocol.ClipboardText
import `in`.aboobacker.airrelay.protocol.Frame
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.Hello
import `in`.aboobacker.airrelay.protocol.NotificationReply
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlin.random.Random

/**
 * Persistent foreground service owning the mTLS link to the Mac.
 * Handles discovery, pairing, connection, reconnection with exponential
 * backoff, keepalive, and dispatch of inbound/outbound frames.
 */
class SyncService : androidx.lifecycle.LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var identity: DeviceIdentity
    private lateinit var pairing: PairingStore
    private lateinit var discovery: DiscoveryManager
    private var client: TlsClient? = null
    private var keepaliveJob: Job? = null
    private var connectionJob: Job? = null
    private lateinit var callMonitor: CallMonitor
    private lateinit var features: FeaturePrefs
    private lateinit var statusReporter: DeviceStatusReporter
    private lateinit var phoneFinder: PhoneFinder
    private lateinit var mediaMonitor: MediaMonitor
    lateinit var fileTransfer: FileTransfer
        private set
    private var cameraStreamer: CameraStreamer? = null

    @Volatile
    private var awaitingPongs = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = DeviceIdentity.load()
        pairing = PairingStore(this)
        discovery = DiscoveryManager(this)
        features = FeaturePrefs(this)
        callMonitor = CallMonitor(this)
        callMonitor.start()
        statusReporter = DeviceStatusReporter(this)
        phoneFinder = PhoneFinder(this)
        mediaMonitor = MediaMonitor(this)
        mediaMonitor.start()
        fileTransfer = FileTransfer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAIR_ACCEPT -> resolvePairing(true)
            ACTION_PAIR_DECLINE -> resolvePairing(false)
            ACTION_FIND_PHONE_STOP -> phoneFinder.stop(notifyPeer = true)
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Waiting for Mac…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (connectionJob?.isActive != true) {
            connectionJob = scope.launch { connectionLoop() }
        }
        return START_STICKY
    }

    private suspend fun connectionLoop() {
        var attempt = 0
        while (scope.isActive) {
            try {
                val peer = discovery.discoverPeers().first()
                Log.i(TAG, "Found peer ${peer.name} at ${peer.host}:${peer.port}")
                val paired = pairing.isPaired
                if (!paired && _connectionState.value is ConnectionState.PairingDeclined) {
                    // Wait for the user to explicitly retry; don't hammer the Mac.
                    delay(5_000)
                    continue
                }
                val tls = TlsClient(
                    identity = identity,
                    pinnedFingerprint = pairing.peerFingerprint,
                    pairingMode = !paired,
                )
                tls.connect(peer.host, peer.port)
                client = tls
                if (!paired) {
                    if (!confirmPairing(tls, peer.name)) {
                        tls.close()
                        continue
                    }
                }
                attempt = 0
                _connectionState.value = ConnectionState.Connected(peer.name)
                updateNotification("Connected to ${peer.name}")
                sendHello()
                startKeepalive(tls)
                statusReporter.start(scope)
                mediaMonitor.resend()
                drainPendingShares()
                readLoop(tls)
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed: ${e.message}")
            } finally {
                statusReporter.stop()
                fileTransfer.reset()
                keepaliveJob?.cancel()
                client?.close()
                client = null
                val state = _connectionState.value
                if (state !is ConnectionState.Pairing && state !is ConnectionState.PairingDeclined) {
                    _connectionState.value = ConnectionState.Disconnected
                }
                updateNotification("Waiting for your Mac…")
            }
            val delayMs = min(60_000L, 1000L * (1L shl min(attempt, 6))) + Random.nextLong(500)
            attempt++
            delay(delayMs)
        }
    }

    /**
     * Waits until the user confirms the SAS code shown in the UI matches the
     * one on the Mac. Keeps answering keepalive PINGs while waiting so the
     * Mac does not drop the quarantined connection. Persists the peer
     * fingerprint on success.
     */
    private suspend fun confirmPairing(tls: TlsClient, peerName: String): Boolean {
        val sas = tls.sasCode() ?: return false
        val fingerprint = tls.peerFingerprint ?: return false
        val decision = CompletableDeferred<Boolean>()
        pairingDecision = decision
        _connectionState.value = ConnectionState.Pairing(peerName, sas)
        updateNotification("Pairing with $peerName — code $sas")

        val pingResponder = scope.launch {
            runCatching {
                while (tls.isConnected) {
                    val frame = try {
                        tls.receive()
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (frame.type == FrameType.PING) tls.send(Frame(FrameType.PONG, ByteArray(0)))
                }
            }
            decision.complete(false)
        }

        val accepted = try {
            kotlinx.coroutines.withTimeoutOrNull(PAIRING_TIMEOUT_MS) { decision.await() } ?: false
        } finally {
            pairingDecision = null
            pingResponder.cancel()
        }
        return if (accepted && tls.isConnected) {
            pairing.peerFingerprint = fingerprint
            pairing.peerName = peerName
            ShortcutPublisher.publishShareTarget(this, peerName)
            true
        } else {
            // Connection dropped while quarantined usually means the Mac
            // declined (or the code timed out). Pause pairing until the user
            // explicitly retries instead of re-prompting in a loop.
            _connectionState.value = if (accepted || !tls.isConnected) {
                ConnectionState.PairingDeclined(peerName)
            } else {
                ConnectionState.Disconnected
            }
            false
        }
    }

    private fun startKeepalive(tls: TlsClient) {
        awaitingPongs = 0
        keepaliveJob = scope.launch {
            while (isActive && tls.isConnected) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (awaitingPongs >= 2) {
                    Log.w(TAG, "Missed 2 PONGs; dropping connection")
                    tls.close()
                    return@launch
                }
                awaitingPongs++
                runCatching { tls.send(Frame(FrameType.PING, ByteArray(0))) }
                    .onFailure { tls.close() }
            }
        }
    }

    private fun sendHello() {
        val hello = Hello(
            deviceName = Build.MODEL,
            appVersion = `in`.aboobacker.airrelay.BuildConfig.APP_VERSION,
        )
        send(FrameType.HELLO, ProtocolJson.encodeToString(hello).encodeToByteArray())
    }

    private fun readLoop(tls: TlsClient) {
        while (tls.isConnected) {
            val frame = try {
                tls.receive()
            } catch (e: SocketTimeoutException) {
                continue
            }
            when (frame.type) {
                FrameType.PING -> send(FrameType.PONG, ByteArray(0))
                FrameType.PONG -> awaitingPongs = 0
                FrameType.CLIPBOARD_TEXT -> handleRemoteClipboard(frame)
                FrameType.OPEN_URL -> handleOpenUrl(frame)
                FrameType.NOTIF_REPLY -> if (features.notificationSync) handleNotifReply(frame)
                FrameType.NOTIF_ACTION -> if (features.notificationSync) handleNotifAction(frame)
                FrameType.NOTIF_DISMISS -> if (features.notificationSync) NotificationRelayService.dismiss(frame)
                FrameType.CALL_ACTION -> handleCallAction(frame)
                FrameType.MEDIA_ACTION -> handleMediaAction(frame)
                FrameType.FILE_OFFER -> fileTransfer.onOffer(frame)
                FrameType.FILE_ACCEPT -> fileTransfer.onAccept(frame, scope)
                FrameType.FILE_CHUNK -> fileTransfer.onChunk(frame)
                FrameType.FILE_DONE -> fileTransfer.onDone(frame)
                FrameType.CAMERA_START -> handleCameraStart(frame)
                FrameType.CAMERA_STOP -> handleCameraStop()
                FrameType.HOTSPOT_OPEN -> handleHotspotOpen()
                FrameType.FIND_PHONE -> phoneFinder.start()
                FrameType.FIND_PHONE_STOP -> phoneFinder.stop(notifyPeer = false)
                else -> Log.d(TAG, "Unhandled frame: ${frame.type}")
            }
        }
    }

    private fun handleRemoteClipboard(frame: Frame) {
        val payload = ProtocolJson.decodeFromString<ClipboardText>(frame.payload.decodeToString())
        ClipboardBridge.onRemoteClipboard(this, payload.text)
    }

    private fun handleOpenUrl(frame: Frame) {
        val payload = ProtocolJson.decodeFromString<`in`.aboobacker.airrelay.protocol.OpenUrl>(
            frame.payload.decodeToString(),
        )
        val uri = android.net.Uri.parse(payload.url)
        if (!uri.scheme.equals("http", ignoreCase = true) && !uri.scheme.equals("https", ignoreCase = true)) {
            Log.w(TAG, "Ignoring OPEN_URL with scheme ${uri.scheme}")
            return
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 10+ silently blocks activity starts from background
        // services, so always post a tap-to-open notification as well.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_URL_ID, "Links from Mac", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = Notification.Builder(this, CHANNEL_URL_ID)
            .setContentTitle("Link from your Mac")
            .setContentText(payload.url)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    4,
                    view,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(URL_NOTIFICATION_ID, notification)
        runCatching { startActivity(view) }
            .onFailure { Log.w(TAG, "Failed to open URL directly: ${it.message}") }
    }

    private fun handleCameraStart(frame: Frame) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "CAMERA permission not granted; ignoring CAMERA_START")
            return
        }
        val config = ProtocolJson.decodeFromString<`in`.aboobacker.airrelay.protocol.CameraStart>(
            frame.payload.decodeToString(),
        )
        mainExecutor.execute {
            cameraStreamer?.stop()
            cameraStreamer = CameraStreamer(this, config).also { it.start(this) }
        }
    }

    private fun handleCameraStop() {
        mainExecutor.execute {
            cameraStreamer?.stop()
            cameraStreamer = null
        }
    }

    /**
     * Third-party apps cannot enable the hotspot programmatically, so open
     * the tethering settings screen for the final tap. Android 10+ blocks
     * activity starts from background services, so a heads-up notification
     * carries the same intent as a reliable fallback.
     */
    private fun handleHotspotOpen() {
        val tether = Intent(Intent.ACTION_MAIN)
            .setClassName("com.android.settings", "com.android.settings.TetherSettings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent = if (packageManager.resolveActivity(tether, 0) != null) {
            tether
        } else {
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HOTSPOT_ID, "Hotspot requests", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = Notification.Builder(this, CHANNEL_HOTSPOT_ID)
            .setContentTitle("Your Mac wants to use your hotspot")
            .setContentText("Tap to open hotspot settings")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    5,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        manager.notify(HOTSPOT_NOTIFICATION_ID, notification)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Failed to open hotspot settings directly: ${it.message}") }
    }

    private fun handleMediaAction(frame: Frame) {
        val action = ProtocolJson.decodeFromString<`in`.aboobacker.airrelay.protocol.MediaAction>(
            frame.payload.decodeToString(),
        )
        mediaMonitor.execute(action)
    }

    private fun handleCallAction(frame: Frame) {
        val action = ProtocolJson.decodeFromString<`in`.aboobacker.airrelay.protocol.CallAction>(
            frame.payload.decodeToString(),
        )
        callMonitor.execute(action)
    }

    private fun drainPendingShares() {
        while (true) {
            val uri = pendingShares.poll() ?: break
            fileTransfer.offer(uri)
        }
    }

    private fun handleNotifReply(frame: Frame) {
        val reply = ProtocolJson.decodeFromString<NotificationReply>(frame.payload.decodeToString())
        NotificationRelayService.reply(reply)
    }

    private fun handleNotifAction(frame: Frame) {
        val action = ProtocolJson.decodeFromString<`in`.aboobacker.airrelay.protocol.NotificationAction>(
            frame.payload.decodeToString(),
        )
        NotificationRelayService.executeAction(action)
    }

    val isConnected: Boolean
        get() = client?.isConnected == true &&
            connectionState.value is ConnectionState.Connected

    fun send(type: FrameType, payload: ByteArray) {
        val tls = client ?: return
        scope.launch {
            runCatching { tls.send(Frame(type, payload)) }
                .onFailure { Log.w(TAG, "Send failed: ${it.message}") }
        }
    }

    /** Synchronous send with natural TCP backpressure (for file streaming). */
    fun sendBlocking(frame: Frame) {
        client?.send(frame)
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sync status", NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)

        val state = _connectionState.value
        if (state is ConnectionState.Pairing) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PAIR_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH),
            )
            builder.setChannelId(CHANNEL_PAIR_ID)
            builder.setFullScreenIntent(null, true) // make it heads-up

            val acceptIntent = Intent(this, SyncService::class.java).setAction(ACTION_PAIR_ACCEPT)
            val declineIntent = Intent(this, SyncService::class.java).setAction(ACTION_PAIR_DECLINE)

            builder.addAction(
                Notification.Action.Builder(null, "Accept",
                    android.app.PendingIntent.getService(this, 1, acceptIntent, android.app.PendingIntent.FLAG_IMMUTABLE))
                    .build()
            )
            builder.addAction(
                Notification.Action.Builder(null, "Decline",
                    android.app.PendingIntent.getService(this, 2, declineIntent, android.app.PendingIntent.FLAG_IMMUTABLE))
                    .build()
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        instance = null
        callMonitor.stop()
        statusReporter.stop()
        mediaMonitor.stop()
        phoneFinder.stop(notifyPeer = false)
        cameraStreamer?.stop()
        keepaliveJob?.cancel()
        client?.close()
        scope.cancel()
        _connectionState.value = ConnectionState.Disconnected
        super.onDestroy()
    }

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data class Pairing(val peerName: String, val sasCode: String) : ConnectionState
        data class PairingDeclined(val peerName: String) : ConnectionState
        data class Connected(val peerName: String) : ConnectionState
    }

    companion object {
        private const val TAG = "SyncService"
        private const val CHANNEL_ID = "sync_status"
        private const val CHANNEL_PAIR_ID = "pairing_requests"
        private const val CHANNEL_HOTSPOT_ID = "hotspot_requests"
        private const val CHANNEL_URL_ID = "links_from_mac"
        private const val NOTIFICATION_ID = 1
        private const val HOTSPOT_NOTIFICATION_ID = 5
        private const val URL_NOTIFICATION_ID = 4
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
        private const val PAIRING_TIMEOUT_MS = 120_000L

        private const val ACTION_PAIR_ACCEPT = "in.aboobacker.airrelay.PAIR_ACCEPT"
        private const val ACTION_PAIR_DECLINE = "in.aboobacker.airrelay.PAIR_DECLINE"
        const val ACTION_FIND_PHONE_STOP = "in.aboobacker.airrelay.FIND_PHONE_STOP"

        @Volatile
        var instance: SyncService? = null
            private set

        @Volatile
        private var pairingDecision: CompletableDeferred<Boolean>? = null

        /** Share intents received while disconnected, sent once the link is up. */
        private val pendingShares = java.util.concurrent.ConcurrentLinkedQueue<android.net.Uri>()

        fun queueShare(uri: android.net.Uri) {
            pendingShares.add(uri)
        }

        private val _connectionState =
            MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        fun resolvePairing(accepted: Boolean) {
            pairingDecision?.complete(accepted)
        }

        /** Clears the paused pairing-declined state so discovery retries. */
        fun retryPairing() {
            if (_connectionState.value is ConnectionState.PairingDeclined) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        /** Reconciles UI state after process death (onDestroy never ran). */
        fun reconcileState() {
            if (instance == null && _connectionState.value !is ConnectionState.Disconnected) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
