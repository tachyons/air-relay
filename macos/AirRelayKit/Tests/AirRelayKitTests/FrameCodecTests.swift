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

    func testCallStateDecodesSharedVectorWithPhoto() throws {
        let json = #"{"callId":"uuid","state":"ringing","displayName":"Alice","number":"+1555","photoPng":"aWNvbg=="}"#
        let call = try JSONDecoder().decode(CallState.self, from: Data(json.utf8))
        XCTAssertEqual(call.photoPng, "aWNvbg==")
        XCTAssertEqual(call.displayName, "Alice")
    }

    func testCallStateDecodesWithoutPhotoForBackwardCompatibility() throws {
        let json = #"{"callId":"uuid","state":"active"}"#
        let call = try JSONDecoder().decode(CallState.self, from: Data(json.utf8))
        XCTAssertNil(call.photoPng)
    }

    func testRejectsUnknownType() {
        var buffer = Data([0, 0, 0, 1, 0xFF])
        XCTAssertThrowsError(try FrameCodec.decode(from: &buffer))
    }
}
