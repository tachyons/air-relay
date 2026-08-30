import Foundation

public enum FrameType: UInt8, Sendable {
    case hello = 0x01
    case ping = 0x02
    case pong = 0x03
    case notification = 0x10
    case notifDismiss = 0x11
    case notifReply = 0x12
    case notifAction = 0x13
    case clipboardText = 0x20
    case callState = 0x30
    case callAction = 0x31
    case fileOffer = 0x40
    case fileAccept = 0x41
    case fileChunk = 0x42
    case fileDone = 0x43
    case cameraStart = 0x50
    case cameraStop = 0x51
    case videoConfig = 0x52
    case videoFrame = 0x53
    case deviceStatus = 0x60
    case mediaState = 0x80
    case mediaAction = 0x81
}

public struct Frame: Sendable, Equatable {
    public let type: FrameType
    public let payload: Data

    public init(type: FrameType, payload: Data = Data()) {
        self.type = type
        self.payload = payload
    }
}

public enum FrameCodecError: Error {
    case invalidLength(Int)
    case unknownType(UInt8)
}

public enum FrameCodec {
    public static let maxFrameSize = 16 * 1024 * 1024

    public static func encode(_ frame: Frame) -> Data {
        var data = Data(capacity: 5 + frame.payload.count)
        var length = UInt32(1 + frame.payload.count).bigEndian
        withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
        data.append(frame.type.rawValue)
        data.append(frame.payload)
        return data
    }

    /// Attempts to decode a single frame from the front of `buffer`.
    /// Returns nil if more bytes are needed; consumes decoded bytes on success.
    public static func decode(from buffer: inout Data) throws -> Frame? {
        guard buffer.count >= 4 else { return nil }
        let length = buffer.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard length >= 1, length <= maxFrameSize else {
            throw FrameCodecError.invalidLength(length)
        }
        guard buffer.count >= 4 + length else { return nil }
        let typeByte = buffer[buffer.index(buffer.startIndex, offsetBy: 4)]
        guard let type = FrameType(rawValue: typeByte) else {
            throw FrameCodecError.unknownType(typeByte)
        }
        let payloadStart = buffer.index(buffer.startIndex, offsetBy: 5)
        let payloadEnd = buffer.index(buffer.startIndex, offsetBy: 4 + length)
        let payload = Data(buffer[payloadStart..<payloadEnd])
        buffer.removeFirst(4 + length)
        return Frame(type: type, payload: payload)
    }
}

/// Incremental frame decoder that avoids repeated `removeFirst` copies.
/// Suitable for high-throughput streams (e.g. 30 fps video frames): consumed
/// bytes are dropped only when the buffer's read offset crosses a threshold.
public struct FrameDecoder: Sendable {
    private var buffer = Data()
    private var offset = 0

    public init() {}

    public mutating func append(_ data: Data) {
        buffer.append(data)
    }

    public mutating func next() throws -> Frame? {
        let available = buffer.count - offset
        guard available >= 4 else { compactIfNeeded(); return nil }
        let base = buffer.startIndex + offset
        let length = (Int(buffer[base]) << 24) | (Int(buffer[base + 1]) << 16)
            | (Int(buffer[base + 2]) << 8) | Int(buffer[base + 3])
        guard length >= 1, length <= FrameCodec.maxFrameSize else {
            throw FrameCodecError.invalidLength(length)
        }
        guard available >= 4 + length else { compactIfNeeded(); return nil }
        let typeByte = buffer[base + 4]
        guard let type = FrameType(rawValue: typeByte) else {
            throw FrameCodecError.unknownType(typeByte)
        }
        let payload = Data(buffer[(base + 5)..<(base + 4 + length)])
        offset += 4 + length
        compactIfNeeded()
        return Frame(type: type, payload: payload)
    }

    private mutating func compactIfNeeded() {
        guard offset > 0 else { return }
        if offset == buffer.count {
            buffer.removeAll(keepingCapacity: true)
            offset = 0
        } else if offset > 1 << 20 {
            buffer.removeFirst(offset)
            offset = 0
        }
    }
}
