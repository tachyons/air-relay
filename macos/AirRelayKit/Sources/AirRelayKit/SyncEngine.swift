import AppKit
import CryptoKit
import Foundation
import Network
import ServiceManagement
import os

/// Central coordinator for the macOS side: owns the Bonjour listener,
/// active connection, clipboard monitor, pairing state, and video decoder.
///
/// Unpaired connections are quarantined: frames are ignored and nothing is
/// sent until the user confirms the SAS code matches the one on the phone.
@MainActor
public final class SyncEngine: ObservableObject {
    private let log = Logger(subsystem: "dev.airrelay", category: "SyncEngine")

    public struct PendingPairing: Equatable {
        public let fingerprint: String
        public let sasCode: String
    }

    @Published public private(set) var isConnected = false
    @Published public private(set) var isPaired = false
    @Published public private(set) var peerName: String?
    @Published public private(set) var deviceStatus: DeviceStatus?
    @Published public private(set) var mediaState: MediaState?
    @Published public private(set) var activeCall: CallState?
    @Published public private(set) var notifications: [NotificationPayload] = []
    @Published public private(set) var localFingerprint: String = ""
    @Published public private(set) var lastError: String?
    @Published public private(set) var pendingPairing: PendingPairing?
    @Published public private(set) var isFindingPhone = false

    private var listener: SyncListener?
    private var connection: SyncConnection?
    private var quarantined = false
    private var pathMonitor: NWPathMonitor?
    private var identity: TlsIdentity?
    private let clipboard = ClipboardMonitor()
    public let decoder = VideoFrameDecoder()
    private let mirror = NotificationMirror()
    public let fileTransfer = FileTransferManager()
    private let defaults = UserDefaults.standard
    private let json = JSONDecoder()

    public init() {}

    // MARK: Feature toggles

    public var notificationSyncEnabled: Bool {
        get { defaults.object(forKey: "feature.notifications") as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: "feature.notifications")
            objectWillChange.send()
        }
    }

    public var callSyncEnabled: Bool {
        get { defaults.object(forKey: "feature.calls") as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: "feature.calls")
            objectWillChange.send()
        }
    }

    public var clipboardSyncEnabled: Bool {
        get { defaults.object(forKey: "feature.clipboard") as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: "feature.clipboard")
            objectWillChange.send()
        }
    }

    public var fileSharingEnabled: Bool {
        get { defaults.object(forKey: "feature.files") as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: "feature.files")
            fileTransfer.isEnabled = newValue
            objectWillChange.send()
        }
    }

    public func start() {
        guard listener == nil else { return }
        do {
            let identity = try identity ?? TlsIdentity.loadOrCreate()
            self.identity = identity
            localFingerprint = identity.fingerprint
            let listener = SyncListener(tlsIdentity: identity)
            let pinned = defaults.string(forKey: "peerFingerprint")
            listener.pinnedPeerFingerprint = pinned
            listener.pairingMode = pinned == nil
            isPaired = pinned != nil
            listener.onConnection = { [weak self] connection, fingerprint in
                Task { @MainActor in self?.attach(connection, peerFingerprint: fingerprint) }
            }
            try listener.start(deviceName: Host.current().localizedName ?? "Mac")
            self.listener = listener

            clipboard.onCopy = { [weak self] text in
                guard let self, self.clipboardSyncEnabled else { return }
                self.sendClipboard(text)
            }
            clipboard.start()

            mirror.onReply = { [weak self] key, text in self?.sendReply(key: key, text: text) }
            mirror.onDismiss = { [weak self] key in
                self?.sendJSON(.notifDismiss, NotificationDismiss(key: key))
            }
            mirror.onAction = { [weak self] key, action in
                self?.sendJSON(.notifAction, NotificationAction(key: key, action: action))
            }
            mirror.onCallAction = { [weak self] callId, action in
                self?.sendCallAction(callId: callId, action: action)
            }
            mirror.activate()
            fileTransfer.sendFrame = { [weak self] frame in
                guard let self, !self.quarantined else { return }
                self.connection?.send(frame)
            }
            fileTransfer.isEnabled = fileSharingEnabled
            fileTransfer.onTransferComplete = { [weak self] name, inbound in
                self?.mirror.showTransferComplete(name: name, inbound: inbound)
            }
            fileTransfer.onTransferFailed = { [weak self] name in
                self?.mirror.showTransferFailed(name: name)
            }
            observeSystemEvents()
            log.info("Listening on port \(listener.port ?? 0)")
        } catch {
            log.error("Failed to start: \(error)")
            lastError = "Failed to start: \(error.localizedDescription)"
        }
    }

    public func stop() {
        clipboard.stop()
        connection?.close()
        listener?.stop()
        listener = nil
        isConnected = false
        deviceStatus = nil
    }

    /// Restarts the Bonjour listener after wake or network changes; a stale
    /// NWListener can silently stop advertising after sleep.
    private func restartListener() {
        log.info("Restarting listener")
        isFindingPhone = false
        connection?.close()
        connection = nil
        listener?.stop()
        listener = nil
        isConnected = false
        deviceStatus = nil
        start()
    }

    private var systemObserversInstalled = false

    private func observeSystemEvents() {
        guard !systemObserversInstalled else { return }
        systemObserversInstalled = true

        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.restartListener() }
        }

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self else { return }
                if path.status == .satisfied, self.listener == nil {
                    self.start()
                }
            }
        }
        monitor.start(queue: DispatchQueue(label: "in.aboobacker.airrelay.path"))
        pathMonitor = monitor
    }

    /// Payload for the pairing QR code scanned by the Android app.
    /// Format: airrelay://pair?fp=<sha256 hex>&port=<port>&name=<host>
    public var pairingURL: String? {
        guard !localFingerprint.isEmpty, let port = listener?.port else { return nil }
        let name = Host.current().localizedName ?? "Mac"
        var components = URLComponents()
        components.scheme = "airrelay"
        components.host = "pair"
        components.queryItems = [
            URLQueryItem(name: "fp", value: localFingerprint),
            URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "name", value: name),
        ]
        return components.string
    }

    // MARK: Login item

    public var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue {
                    try SMAppService.mainApp.register()
                } else {
                    try SMAppService.mainApp.unregister()
                }
                objectWillChange.send()
            } catch {
                log.error("Login item update failed: \(error)")
                lastError = "Login item: \(error.localizedDescription)"
            }
        }
    }

    public func confirmPairing() {
        guard let pending = pendingPairing else { return }
        defaults.set(pending.fingerprint, forKey: "peerFingerprint")
        listener?.pinnedPeerFingerprint = pending.fingerprint
        listener?.pairingMode = false
        pendingPairing = nil
        mirror.dismissPairingRequest()
        isPaired = true
        quarantined = false
        sendHello()
    }

    public func rejectPairing() {
        pendingPairing = nil
        mirror.dismissPairingRequest()
        connection?.close()
        connection = nil
        isConnected = false
        deviceStatus = nil
    }

    public func unpair() {
        defaults.removeObject(forKey: "peerFingerprint")
        listener?.pinnedPeerFingerprint = nil
        listener?.pairingMode = true
        isPaired = false
        connection?.close()
    }

    public func sendReply(key: String, text: String) {
        sendJSON(.notifReply, NotificationReply(key: key, text: text))
    }

    public func sendCallAction(callId: String, action: String) {
        sendJSON(.callAction, CallAction(callId: callId, action: action))
    }

    /// Controls the phone's current media session (play/pause/next/previous).
    public func sendMediaAction(_ action: String) {
        sendJSON(.mediaAction, MediaAction(action: action))
        // Optimistic toggle so the button doesn't lag the round trip.
        if action == "play" { mediaState?.playing = true }
        if action == "pause" { mediaState?.playing = false }
    }

    /// Opens an http(s) link in the phone's default browser.
    public func openOnPhone(url: URL) {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return }
        sendJSON(.openUrl, OpenUrl(url: url.absoluteString))
    }

    public func startCamera(config: CameraStart = CameraStart()) {
        sendJSON(.cameraStart, config)
    }

    public func stopCamera() {
        guard !quarantined else { return }
        connection?.send(Frame(type: .cameraStop))
        decoder.invalidate()
    }

    /// Asks the phone to open its hotspot settings — the user flips the
    /// toggle there (Android does not allow enabling it remotely).
    public func requestHotspot() {
        guard !quarantined else { return }
        connection?.send(Frame(type: .hotspotOpen))
    }

    public func findPhone() {
        guard !quarantined else { return }
        connection?.send(Frame(type: .findPhone))
        isFindingPhone = true
    }

    public func stopFindingPhone() {
        guard !quarantined else { return }
        connection?.send(Frame(type: .findPhoneStop))
        isFindingPhone = false
    }

    private func attach(_ connection: SyncConnection, peerFingerprint: String) {
        let previous = self.connection
        deviceStatus = nil
        isFindingPhone = false
        self.connection = connection
        previous?.onClose = nil
        previous?.close()
        connection.onFrame = { [weak self, weak connection] frame in
            Task { @MainActor in
                guard let self, self.connection === connection else { return }
                self.handle(frame)
            }
        }
        connection.onClose = { [weak self, weak connection] in
            Task { @MainActor in
                guard let self, self.connection === connection else { return }
                self.isConnected = false
                self.peerName = nil
                self.pendingPairing = nil
                self.deviceStatus = nil
                self.mediaState = nil
                self.isFindingPhone = false
                self.fileTransfer.reset()
            }
        }
        isConnected = true

        if isPaired {
            quarantined = false
            sendHello()
        } else {
            quarantined = true
            guard let listener, let peerDER = listener.lastPeerCertificate else {
                connection.close()
                return
            }
            let pending = PendingPairing(
                fingerprint: peerFingerprint,
                sasCode: Self.sasCode(peerCertificate: peerDER, localCertificate: listener.localCertificate)
            )
            pendingPairing = pending
            mirror.showPairingRequest(sasCode: pending.sasCode)
        }
    }

    /// Matches the Android derivation: sha256(macCert || androidCert), first
    /// 4 bytes as big-endian integer mod 10^6.
    static func sasCode(peerCertificate: Data, localCertificate: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: localCertificate)
        hasher.update(data: peerCertificate)
        let digest = Array(hasher.finalize())
        let value = (UInt64(digest[0]) << 24) | (UInt64(digest[1]) << 16)
            | (UInt64(digest[2]) << 8) | UInt64(digest[3])
        return String(format: "%06d", value % 1_000_000)
    }

    private func handle(_ frame: Frame) {
        guard !quarantined else { return }
        switch frame.type {
        case .hello:
            if let hello = try? json.decode(Hello.self, from: frame.payload) {
                peerName = hello.deviceName
            }
        case .notification:
            guard notificationSyncEnabled else { return }
            if let payload = try? json.decode(NotificationPayload.self, from: frame.payload) {
                notifications.removeAll { $0.key == payload.key }
                notifications.insert(payload, at: 0)
                if notifications.count > 50 { notifications.removeLast() }
                showUserNotification(payload)
            }
        case .notifDismiss:
            if let payload = try? json.decode(NotificationDismiss.self, from: frame.payload) {
                notifications.removeAll { $0.key == payload.key }
                mirror.dismiss(key: payload.key)
            }
        case .callState:
            guard callSyncEnabled else { return }
            if let call = try? json.decode(CallState.self, from: frame.payload) {
                activeCall = call.state == "ended" ? nil : call
                mirror.showCall(call)
            }
        case .clipboardText:
            guard clipboardSyncEnabled else { return }
            if let payload = try? json.decode(ClipboardText.self, from: frame.payload) {
                clipboard.applyRemote(text: payload.text)
            }
        case .openUrl:
            if let payload = try? json.decode(OpenUrl.self, from: frame.payload),
               let url = URL(string: payload.url),
               let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" {
                NSWorkspace.shared.open(url)
            }
        case .deviceStatus:
            deviceStatus = try? json.decode(DeviceStatus.self, from: frame.payload)
        case .mediaState:
            if let state = try? json.decode(MediaState.self, from: frame.payload) {
                mediaState = state.title == nil ? nil : state
            }
        case .fileOffer:
            fileTransfer.handleOffer(frame.payload)
        case .fileAccept:
            fileTransfer.handleAccept(frame.payload)
        case .fileChunk:
            fileTransfer.handleChunk(frame.payload)
        case .fileDone:
            fileTransfer.handleDone(frame.payload)
        case .findPhoneStop:
            isFindingPhone = false
        case .videoConfig:
            decoder.handleConfig(frame.payload)
        case .videoFrame:
            decoder.handleFrame(frame.payload)
        default:
            log.debug("Unhandled frame: \(String(describing: frame.type))")
        }
    }

    private func sendHello() {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "dev"
        let hello = Hello(deviceName: Host.current().localizedName ?? "Mac", appVersion: version)
        sendJSON(.hello, hello)
    }

    private func sendClipboard(_ text: String) {
        sendJSON(.clipboardText, ClipboardText(text: text, ts: Int64(Date().timeIntervalSince1970 * 1000)))
    }

    private func sendJSON<T: Encodable>(_ type: FrameType, _ value: T) {
        guard !quarantined else { return }
        guard let data = try? JSONEncoder().encode(value) else { return }
        connection?.send(Frame(type: type, payload: data))
    }

    private func showUserNotification(_ payload: NotificationPayload) {
        mirror.show(payload)
    }
}
