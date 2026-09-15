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

<    func testHotspotOpenWireFormatMatchesSpec() {
        XCTAssertEqual(FrameCodec.encode(Frame(type: .hotspotOpen)), Data([0, 0, 0, 1, 0x61]))
    }

    func testDeviceStatusDecodesSharedVectorWithConnectivity() throws {
        let json = #"{"battery":87,"charging":false,"wifiSsid":"Home","networkType":"cellular","signalLevel":3}"#
        let status = try JSONDecoder().decode(DeviceStatus.self, from: Data(json.utf8))
        XCTAssertEqual(status.networkType, "cellular")
        XCTAssertEqual(status.signalLevel, 3)
    }

    func testDeviceStatusDecodesWithoutConnectivityForBackwardCompatibility() throws {
        let json = #"{"battery":87,"charging":false}"#
        let status = try JSONDecoder().decode(DeviceStatus.self, from: Data(json.utf8))
        XCTAssertNil(status.networkType)
        XCTAssertNil(status.signalLevel)
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
