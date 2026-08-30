import Foundation
import os

/// File transfer over the sync link.
///
/// FILE_CHUNK binary payload: transferId (16 bytes uuid) || offset (u64 BE) || data.
/// Inbound files land in ~/Downloads/AirRelay. Outbound sends stream after
/// the peer's FILE_ACCEPT.
@MainActor
public final class FileTransferManager: ObservableObject {
    private let log = Logger(subsystem: "in.aboobacker.airrelay", category: "FileTransfer")

    public struct Progress: Identifiable {
        public let id: String
        public let name: String
        public let size: Int64
        public var transferred: Int64
        public let inbound: Bool

        public var fraction: Double {
            size > 0 ? Double(transferred) / Double(size) : 0
        }
    }

    @Published public private(set) var transfers: [Progress] = []

    /// Recently completed transfers, newest first (kept for the session).
    @Published public private(set) var recentFiles: [RecentFile] = []

    public struct RecentFile: Identifiable, Equatable {
        public let id: String
        public let name: String
        public let url: URL?
        public let inbound: Bool
        public let date: Date
    }

    /// Set from SyncEngine's file-sharing feature toggle.
    public var isEnabled = true

    /// Sends a frame over the active connection; injected by SyncEngine.
    public var sendFrame: ((Frame) -> Void)?

    /// Fired when a transfer finishes (name, inbound).
    public var onTransferComplete: ((String, Bool) -> Void)?

    /// Fired when a transfer fails or is interrupted (name).
    public var onTransferFailed: ((String) -> Void)?

    private struct Incoming {
        let meta: FileMeta
        let handle: FileHandle
        let url: URL
        var received: Int64 = 0
    }

    private var incoming: [String: Incoming] = [:]
    private var pendingOutgoing: [String: URL] = [:]

    public init() {}

    // MARK: Outbound

    public func offer(fileURL: URL) {
        guard isEnabled else { return }
        guard let size = try? FileManager.default
            .attributesOfItem(atPath: fileURL.path)[.size] as? Int64
        else { return }
        let meta = FileMeta(
            transferId: UUID().uuidString.lowercased(),
            name: fileURL.lastPathComponent,
            size: size,
            mime: "application/octet-stream"
        )
        pendingOutgoing[meta.transferId] = fileURL
        transfers.append(Progress(
            id: meta.transferId, name: meta.name, size: size, transferred: 0, inbound: false
        ))
        if let data = try? JSONEncoder().encode(meta) {
            sendFrame?(Frame(type: .fileOffer, payload: data))
        }
    }

    public func handleAccept(_ payload: Data) {
        guard let meta = try? JSONDecoder().decode(FileMeta.self, from: payload),
              let url = pendingOutgoing.removeValue(forKey: meta.transferId)
        else { return }
        Task.detached(priority: .utility) { [weak self] in
            await self?.stream(meta: meta, from: url)
        }
    }

    private func stream(meta: FileMeta, from url: URL) async {
        guard let handle = try? FileHandle(forReadingFrom: url),
              let idBytes = Self.uuidBytes(meta.transferId)
        else { return }
        defer { try? handle.close() }
        var offset: Int64 = 0
        while let chunk = try? handle.read(upToCount: Self.chunkSize), !chunk.isEmpty {
            var payload = Data(capacity: 24 + chunk.count)
            payload.append(idBytes)
            var offsetBE = UInt64(offset).bigEndian
            withUnsafeBytes(of: &offsetBE) { payload.append(contentsOf: $0) }
            payload.append(chunk)
            sendFrame?(Frame(type: .fileChunk, payload: payload))
            offset += Int64(chunk.count)
            updateProgress(id: meta.transferId, transferred: offset)
        }
        if let data = try? JSONEncoder().encode(meta) {
            sendFrame?(Frame(type: .fileDone, payload: data))
        }
        finish(id: meta.transferId)
        recordRecent(id: meta.transferId, name: meta.name, url: url, inbound: false)
        onTransferComplete?(meta.name, false)
        log.info("Sent \(meta.name) (\(offset) bytes)")
    }

    // MARK: Inbound

    public func handleOffer(_ payload: Data) {
        guard isEnabled else { return }
        guard let meta = try? JSONDecoder().decode(FileMeta.self, from: payload) else { return }
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AirRelay", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = uniqueURL(dir: dir, name: meta.name)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        incoming[meta.transferId] = Incoming(meta: meta, handle: handle, url: url)
        transfers.append(Progress(
            id: meta.transferId, name: meta.name, size: meta.size, transferred: 0, inbound: true
        ))
        // Auto-accept: paired peer is trusted.
        sendFrame?(Frame(type: .fileAccept, payload: payload))
        log.info("Receiving \(meta.name) -> \(url.path)")
    }

    public func handleChunk(_ payload: Data) {
        guard payload.count > 24 else { return }
        let idBytes = payload.prefix(16)
        guard let id = Self.uuidString(from: Data(idBytes)),
              var transfer = incoming[id]
        else { return }
        let chunk = payload.dropFirst(24)
        try? transfer.handle.write(contentsOf: chunk)
        transfer.received += Int64(chunk.count)
        incoming[id] = transfer
        updateProgress(id: id, transferred: transfer.received)
    }

    public func handleDone(_ payload: Data) {
        guard let meta = try? JSONDecoder().decode(FileMeta.self, from: payload),
              let transfer = incoming.removeValue(forKey: meta.transferId)
        else { return }
        try? transfer.handle.close()
        finish(id: meta.transferId)
        recordRecent(id: meta.transferId, name: meta.name, url: transfer.url, inbound: true)
        onTransferComplete?(meta.name, true)
        log.info("Received \(meta.name) (\(transfer.received) bytes)")
    }

    /// Aborts in-flight transfers and deletes partial downloads.
    public func reset() {
        for transfer in incoming.values {
            try? transfer.handle.close()
            try? FileManager.default.removeItem(at: transfer.url)
            log.info("Discarded partial download \(transfer.meta.name)")
            onTransferFailed?(transfer.meta.name)
        }
        incoming.removeAll()
        pendingOutgoing.removeAll()
        transfers.removeAll()
    }

    // MARK: Helpers

    private func updateProgress(id: String, transferred: Int64) {
        if let index = transfers.firstIndex(where: { $0.id == id }) {
            transfers[index].transferred = transferred
        }
    }

    private func recordRecent(id: String, name: String, url: URL?, inbound: Bool) {
        recentFiles.insert(RecentFile(id: id, name: name, url: url, inbound: inbound, date: Date()), at: 0)
        if recentFiles.count > 20 { recentFiles.removeLast() }
    }

    private func finish(id: String) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.transfers.removeAll { $0.id == id }
        }
    }

    private func uniqueURL(dir: URL, name: String) -> URL {
        var url = dir.appendingPathComponent(name)
        var counter = 1
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        while FileManager.default.fileExists(atPath: url.path) {
            let suffixed = ext.isEmpty ? "\(base) (\(counter))" : "\(base) (\(counter)).\(ext)"
            url = dir.appendingPathComponent(suffixed)
            counter += 1
        }
        return url
    }

    static let chunkSize = 256 * 1024

    static func uuidBytes(_ id: String) -> Data? {
        guard let uuid = UUID(uuidString: id) else { return nil }
        return withUnsafeBytes(of: uuid.uuid) { Data($0) }
    }

    static func uuidString(from data: Data) -> String? {
        guard data.count == 16 else { return nil }
        let bytes = [UInt8](data)
        let uuid = UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        ))
        return uuid.uuidString.lowercased()
    }
}
