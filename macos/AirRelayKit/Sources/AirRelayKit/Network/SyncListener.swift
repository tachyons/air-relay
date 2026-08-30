import CryptoKit
import Foundation
import Network
import os

/// Publishes the `_syncbridge._tcp` Bonjour service and accepts a single
/// mTLS connection from the Android peer.
///
/// Trust policy:
/// - Paired (pinned fingerprint set): only that exact certificate is accepted.
/// - Unpaired: handshakes are rejected unless `pairingMode` is true, in which
///   case the connection is admitted provisionally and must be confirmed by
///   the user (SAS comparison) before any data is trusted.
public final class SyncListener: @unchecked Sendable {
    public static let serviceType = "_syncbridge._tcp"
    /// Fixed port so peers with stale mDNS resolutions still reach us after
    /// an app restart. Falls back to an ephemeral port if taken.
    public static let defaultPort: UInt16 = 42425

    private let log = Logger(subsystem: "dev.airrelay", category: "SyncListener")
    private let tlsIdentity: TlsIdentity
    private let queue = DispatchQueue(label: "dev.airrelay.listener")
    private var listener: NWListener?
    private let state = OSAllocatedUnfairLock(initialState: State())

    private struct State {
        var pinnedPeerFingerprint: String?
        var pairingMode = false
        var lastPeerCertificate: Data?
        /// Connections retained between accept and TLS-ready.
        var pending: [ObjectIdentifier: SyncConnection] = [:]
        /// Sliding window of recent pairing handshake attempts (rate limiting).
        var pairingAttempts: [Date] = []
    }

    /// Max unpaired handshakes admitted per minute while in pairing mode.
    private static let pairingRateLimit = 5

    public var pinnedPeerFingerprint: String? {
        get { state.withLock { $0.pinnedPeerFingerprint } }
        set { state.withLock { $0.pinnedPeerFingerprint = newValue } }
    }

    /// When true, unpinned peers are admitted provisionally for pairing.
    public var pairingMode: Bool {
        get { state.withLock { $0.pairingMode } }
        set { state.withLock { $0.pairingMode = newValue } }
    }

    /// DER bytes of the most recent peer certificate (for SAS computation).
    public var lastPeerCertificate: Data? {
        state.withLock { $0.lastPeerCertificate }
    }

    public var localCertificate: Data { tlsIdentity.certificateData }

    public var onConnection: (@Sendable (SyncConnection, _ peerFingerprint: String) -> Void)?

    public init(tlsIdentity: TlsIdentity) {
        self.tlsIdentity = tlsIdentity
    }

    public func start(deviceName: String) throws {
        let tlsOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tlsOptions.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_local_identity(
            tlsOptions.securityProtocolOptions,
            sec_identity_create(tlsIdentity.identity)!
        )
        sec_protocol_options_set_peer_authentication_required(tlsOptions.securityProtocolOptions, true)
        sec_protocol_options_set_verify_block(
            tlsOptions.securityProtocolOptions,
            { [weak self] _, secTrust, complete in
                guard let self else {
                    complete(false)
                    return
                }
                let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
                guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                      let leaf = chain.first
                else {
                    complete(false)
                    return
                }
                let der = SecCertificateCopyData(leaf) as Data
                let fp = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
                let allowed = self.state.withLock { state -> Bool in
                    if let pinned = state.pinnedPeerFingerprint {
                        guard fp == pinned else { return false }
                        state.lastPeerCertificate = der
                        return true
                    }
                    if state.pairingMode {
                        let cutoff = Date().addingTimeInterval(-60)
                        state.pairingAttempts.removeAll { $0 < cutoff }
                        guard state.pairingAttempts.count < Self.pairingRateLimit else {
                            return false
                        }
                        state.pairingAttempts.append(Date())
                        state.lastPeerCertificate = der
                        return true
                    }
                    return false
                }
                if !allowed {
                    self.log.warning("Rejected TLS peer \(fp.prefix(16))…")
                }
                complete(allowed)
            },
            queue
        )

        let parameters = NWParameters(tls: tlsOptions)
        parameters.includePeerToPeer = true
        parameters.allowLocalEndpointReuse = true

        let listener: NWListener
        do {
            listener = try NWListener(
                using: parameters,
                on: NWEndpoint.Port(rawValue: Self.defaultPort)!
            )
        } catch {
            log.warning("Port \(Self.defaultPort) unavailable, using ephemeral: \(error)")
            listener = try NWListener(using: parameters)
        }
        let fpPrefix = String(tlsIdentity.fingerprint.prefix(16))
        listener.service = NWListener.Service(
            name: deviceName,
            type: Self.serviceType,
            txtRecord: NWTXTRecord(["v": "1", "name": deviceName, "fp": fpPrefix])
        )
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            self.log.info("Incoming connection from \(String(describing: connection.endpoint))")
            let sync = SyncConnection(connection: connection, queue: self.queue)
            let id = ObjectIdentifier(sync)
            // Retain until the handshake resolves; the TLS verify block (which
            // captures the peer certificate) completes only at .ready.
            self.state.withLock { $0.pending[id] = sync }
            sync.onReady = { [weak self, weak sync] in
                guard let self, let sync else { return }
                self.state.withLock { _ = $0.pending.removeValue(forKey: id) }
                let der = self.lastPeerCertificate ?? Data()
                let fp = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
                self.onConnection?(sync, fp)
            }
            sync.onClose = { [weak self] in
                self?.state.withLock { _ = $0.pending.removeValue(forKey: id) }
            }
        }
        listener.stateUpdateHandler = { [weak self] state in
            self?.log.info("Listener state: \(String(describing: state))")
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    public var port: UInt16? { listener?.port?.rawValue }

    public func stop() {
        listener?.cancel()
        listener = nil
    }
}
