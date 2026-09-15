import Foundation
import XCTest
@testable import AirRelayKit

final class FrameCodecTests: XCTestCase {
    func testRoundTrip() throws {
        let frame = Frame(type: .clipboardText, payload: Data("hello".utf8))
        var buffer = FrameCodec.encode(frame)
        let decoded = try FrameCodec.decode(from: &buffer)
        XCTAssertEqual(decoded, frame)
        XCTAssertTrue(buffer.isEmpty)
    }

    func testPartialFrameReturnsNil() throws {
        let frame = Frame(type: .ping)
        var buffer = FrameCodec.encode(frame).prefix(3) as Data
        XCTAssertNil(try FrameCodec.decode(from: &buffer))
    }

    func testMultipleFrames() throws {
        var buffer = FrameCodec.encode(Frame(type: .ping)) + FrameCodec.encode(Frame(type: .pong))
        XCTAssertEqual(try FrameCodec.decode(from: &buffer)?.type, .ping)
        XCTAssertEqual(try FrameCodec.decode(from: &buffer)?.type, .pong)
        XCTAssertNil(try FrameCodec.decode(from: &buffer))
    }

    func testFindPhoneWireFormatMatchesSpec() throws {
        XCTAssertEqual(FrameCodec.encode(Frame(type: .findPhone)), Data([0, 0, 0, 1, 0x70]))
        XCTAssertEqual(FrameCodec.encode(Frame(type: .findPhoneStop)), Data([0, 0, 0, 1, 0x71]))
        var buffer = Data([0, 0, 0, 1, 0x70])
        XCTAssertEqual(try FrameCodec.decode(from: &buffer)?.type, .findPhone)
    }

    func testRejectsUnknownType() {
        var buffer = Data([0, 0, 0, 1, 0xFF])
        XCTAssertThrowsError(try FrameCodec.decode(from: &buffer))
    }
}
