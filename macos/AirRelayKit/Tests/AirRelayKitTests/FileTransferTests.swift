import Foundation
import XCTest
@testable import AirRelayKit

/// Guards the inbound file-transfer validation: corrupt headers and unsafe
/// names must be rejected before they reach disk.
final class FileTransferTests: XCTestCase {
    private let transferId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private func chunkPayload(id: String, offset: UInt64, data: [UInt8]) -> Data {
        let uuid = UUID(uuidString: id)!
        var payload = withUnsafeBytes(of: uuid.uuid) { Data($0) }
        withUnsafeBytes(of: offset.bigEndian) { payload.append(contentsOf: $0) }
        payload.append(contentsOf: data)
        return payload
    }

    func testParsesChunkHeader() throws {
        let chunk = try XCTUnwrap(
            FileTransferManager.parseChunk(chunkPayload(id: transferId, offset: 262_144, data: [1, 2, 3]))
        )
        XCTAssertEqual(chunk.id, transferId)
        XCTAssertEqual(chunk.offset, 262_144)
        XCTAssertEqual(chunk.data, Data([1, 2, 3]))
    }

    func testRejectsTruncatedChunkHeader() {
        let payload = chunkPayload(id: transferId, offset: 0, data: []).prefix(23)
        XCTAssertNil(FileTransferManager.parseChunk(Data(payload)))
    }

    func testRejectsEmptyPayload() {
        XCTAssertNil(FileTransferManager.parseChunk(Data()))
    }

    func testSafeNameStripsPaths() {
        XCTAssertEqual(FileTransferManager.safeName("../../Library/evil.sh"), "evil.sh")
        XCTAssertEqual(FileTransferManager.safeName("/etc/passwd"), "passwd")
        XCTAssertEqual(FileTransferManager.safeName("photo.jpg"), "photo.jpg")
        XCTAssertNil(FileTransferManager.safeName(".."))
        XCTAssertNil(FileTransferManager.safeName(""))
    }
}
