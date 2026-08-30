import Foundation
import Network
import os

/// A framed mTLS connection to the Android peer.
public final class SyncConnection: @unchecked Sendable {
    private let log = Logger(subsystem: "dev.airrelay", category: "SyncConnection")
    private let connection: NWConnection
    private let queue: DispatchQueue
    private var decoder = FrameDecoder()
    private var keepaliveTimer: DispatchSourceTimer?
    private var awaitingPongs = 0

    public var onFrame: (@Sendable (Frame) -> Void)?
    /// Fires once the TLS handshake has completed and frames can flow.
    public var onReady: (@Sendable () -> Void)?
    public var onClose: (@Sendable () -> Void)?

    init(connection: NWConnection, queue: DispatchQueue) {
        self.connection = connection
        self.queue = queue
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.receiveLoop()
                self?.startKeepalive()
                self?.onReady?()
            case .failed, .cancelled:
                self?.stopKeepalive()
                self?.onClose?()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func startKeepalive() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            if self.awaitingPongs >= 2 {
                self.log.warning("Missed 2 PONGs; closing connection")
                self.close()
                return
            }
            self.awaitingPongs += 1
            self.send(Frame(type: .ping))
        }
        timer.resume()
        keepaliveTimer = timer
    }

    private func stopKeepalive() {
        keepaliveTimer?.cancel()
        keepaliveTimer = nil
    }

    public func send(_ frame: Frame) {
        connection.send(
            content: FrameCodec.encode(frame),
            completion: .contentProcessed { [weak self] error in
                if let error { self?.log.warning("Send failed: \(error)") }
            }
        )
    }

    public func close() {
        stopKeepalive()
        connection.cancel()
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) {
            [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data { self.drain(data) }
            if isComplete || error != nil {
                self.onClose?()
                return
            }
            self.receiveLoop()
        }
    }

    private func drain(_ data: Data) {
        decoder.append(data)
        do {
            while let frame = try decoder.next() {
                switch frame.type {
                case .ping:
                    send(Frame(type: .pong))
                case .pong:
                    awaitingPongs = 0
                default:
                    onFrame?(frame)
                }
            }
        } catch {
            log.error("Frame decode error: \(error)")
            close()
        }
    }
}
