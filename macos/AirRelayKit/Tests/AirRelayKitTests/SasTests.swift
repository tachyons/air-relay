import Foundation
import XCTest
@testable import AirRelayKit

final class SasTests: XCTestCase {

    /// Golden vector shared with the Kotlin test suite
    /// (android/app/src/test/.../SasTest.kt). If this changes, pairing
    /// between platforms breaks.
    @MainActor
    func testMatchesCrossPlatformVector() {
        let macCert = Data((1...32).map { UInt8($0) })
        let androidCert = Data((33...64).map { UInt8($0) })
        XCTAssertEqual(
            SyncEngine.sasCode(peerCertificate: androidCert, localCertificate: macCert),
            "875972"
        )
    }

    @MainActor
    func testOrderSensitive() {
        let a = Data(repeating: 1, count: 16)
        let b = Data(repeating: 2, count: 16)
        XCTAssertNotEqual(
            SyncEngine.sasCode(peerCertificate: a, localCertificate: b),
            SyncEngine.sasCode(peerCertificate: b, localCertificate: a)
        )
    }
}
