import CryptoKit
import Foundation
@preconcurrency import Security
import SwiftASN1
import X509

/// Self-signed P-256 TLS identity for the Mac peer.
///
/// The private key is generated directly in the login keychain via
/// `SecKeyCreateRandomKey` (the file keychain rejects `SecItemAdd` of external
/// key material, and the data-protection keychain requires entitlements that
/// ad-hoc signed dev builds lack). The certificate is built natively with
/// Apple's swift-certificates package and stored alongside, forming a
/// `SecIdentity`.
public final class TlsIdentity: Sendable {
    public let identity: SecIdentity
    public let certificateData: Data

    public var fingerprint: String {
        SHA256.hash(data: certificateData).map { String(format: "%02x", $0) }.joined()
    }

    /// User-visible Keychain label. This is the string macOS shows in the
    /// "Air Relay wants to sign using key \"...\" in your keychain" prompt,
    /// so it must be plain language with no jargon (no "Identity", "Certificate", etc.).
    private static let label = "Air Relay"

    public static func loadOrCreate() throws -> TlsIdentity {
        if let existing = try? loadFromKeychain() {
            return existing
        }
        return try createAndStore()
    }

    private init(identity: SecIdentity) throws {
        var certificate: SecCertificate?
        SecIdentityCopyCertificate(identity, &certificate)
        guard let certificate else {
            throw TlsIdentityError.missingCertificate
        }
        self.identity = identity
        self.certificateData = SecCertificateCopyData(certificate) as Data
    }

    private static func loadFromKeychain() throws -> TlsIdentity {
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrLabel as String: label,
            kSecReturnRef as String: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let result else {
            throw TlsIdentityError.keychain(status)
        }
        return try TlsIdentity(identity: result as! SecIdentity)
    }

    private static func createAndStore() throws -> TlsIdentity {
        var loginKeychain: SecKeychain?
        SecKeychainCopyDefault(&loginKeychain)
        guard let loginKeychain else {
            throw TlsIdentityError.keychain(errSecNoDefaultKeychain)
        }

        // No .userPresence — we don't want a Touch ID / password prompt
        // on every TLS handshake; access is gated by the login keychain
        // being unlocked and the app's ACL. "Always Allow" will then persist.
        let accessControl = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            .privateKeyUsage,
            nil
        )
        var privateKeyAttrs: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrIsExtractable as String: true,
            // Label MUST be inside privateKeyAttrs — this is the "key" macOS
            // shows in the "wants to sign using key \"...\"" prompt. Top-level
            // kSecAttrLabel alone is not applied to the private-key item and
            // results in the literal "<key>" placeholder seen in the screenshot.
            kSecAttrLabel as String: label,
        ]
        if let accessControl {
            privateKeyAttrs[kSecAttrAccessControl as String] = accessControl
        }

        let keyAttrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecUseKeychain as String: loginKeychain,
            kSecAttrLabel as String: label,
            kSecPrivateKeyAttrs as String: privateKeyAttrs,
        ]
        var error: Unmanaged<CFError>?
        guard let secKey = SecKeyCreateRandomKey(keyAttrs as CFDictionary, &error) else {
            throw error?.takeRetainedValue() ?? TlsIdentityError.keyEncoding
        }
        guard let x963 = SecKeyCopyExternalRepresentation(secKey, &error) as Data? else {
            throw error?.takeRetainedValue() ?? TlsIdentityError.keyEncoding
        }
        let signingKey = try P256.Signing.PrivateKey(x963Representation: x963)

        let der = try makeSelfSignedCertificate(for: signingKey)
        guard let secCertificate = SecCertificateCreateWithData(nil, der as CFData) else {
            throw TlsIdentityError.certificateEncoding
        }

        let certAttrs: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: secCertificate,
            kSecUseKeychain as String: loginKeychain,
            kSecAttrLabel as String: label,
        ]
        let status = SecItemAdd(certAttrs as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw TlsIdentityError.keychain(status)
        }

        var identity: SecIdentity?
        let idStatus = SecIdentityCreateWithCertificate(loginKeychain, secCertificate, &identity)
        guard idStatus == errSecSuccess, let identity else {
            throw TlsIdentityError.keychain(idStatus)
        }
        return try TlsIdentity(identity: identity)
    }

    private static func makeSelfSignedCertificate(for key: P256.Signing.PrivateKey) throws -> Data {
        let name = try DistinguishedName {
            CommonName("AirRelay Mac")
        }
        let certificate = try Certificate(
            version: .v3,
            serialNumber: Certificate.SerialNumber(),
            publicKey: Certificate.PublicKey(key.publicKey),
            notValidBefore: Date(),
            notValidAfter: Date().addingTimeInterval(10 * 365 * 24 * 3600),
            issuer: name,
            subject: name,
            signatureAlgorithm: .ecdsaWithSHA256,
            extensions: Certificate.Extensions(),
            issuerPrivateKey: Certificate.PrivateKey(key)
        )
        var serializer = DER.Serializer()
        try serializer.serialize(certificate)
        return Data(serializer.serializedBytes)
    }
}

public enum TlsIdentityError: Error {
    case keychain(OSStatus)
    case missingCertificate
    case certificateEncoding
    case keyEncoding
}
