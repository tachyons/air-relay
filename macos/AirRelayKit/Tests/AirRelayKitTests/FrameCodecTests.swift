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

    func testMediaFramesWireFormatMatchesSpec() {
        XCTAssertEqual(FrameCodec.encode(Frame(type: .mediaState))[4], 0x80)
        XCTAssertEqual(FrameCodec.encode(Frame(type: .mediaAction))[4], 0x81)
    }

    func testMediaStateDecodesSharedVector() throws {
        let json = #"{"packageName":"com.spotify.music","appName":"Spotify","title":"Song","artist":"Artist","playing":true,"artPng":"YXJ0"}"#
        let state = try JSONDecoder().decode(MediaState.self, from: Data(json.utf8))
        XCTAssertEqual(state.title, "Song")
        XCTAssertTrue(state.playing)
        XCTAssertEqual(state.artPng, "YXJ0")
    }

    func testMediaStateDecodesEmptySessionVector() throws {
        let state = try JSONDecoder().decode(MediaState.self, from: Data(#"{"playing":false}"#.utf8))
        XCTAssertNil(state.title)
        XCTAssertFalse(state.playing)
    }

    func testMediaActionEncodesSharedVector() throws {
        let data = try JSONEncoder().encode(MediaAction(action: "next"))
        let decoded = try JSONDecoder().decode(MediaAction.self, from: data)
        XCTAssertEqual(decoded.action, "next")
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

    func testOpenUrlWireFormatMatchesSpec() throws {
        let payload = Data(#"{"url":"https://example.com/article"}"#.utf8)
        var buffer = FrameCodec.encode(Frame(type: .openUrl, payload: payload))
        XCTAssertEqual(buffer[4], 0x21)
        let decoded = try XCTUnwrap(try FrameCodec.decode(from: &buffer))
        XCTAssertEqual(decoded.type, .openUrl)
        let url = try JSONDecoder().decode(OpenUrl.self, from: decoded.payload)
        XCTAssertEqual(url.url, "https://example.com/article")
    }

    func testRejectsUnknownType() {
        var buffer = Data([0, 0, 0, 1, 0xFF])
        XCTAssertThrowsError(try FrameCodec.decode(from: &buffer))
    }
}
