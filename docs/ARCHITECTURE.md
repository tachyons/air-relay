# Air Relay Architecture

## Overview

Two pure-native peers connected over LAN with mutual TLS 1.3:

- **macOS app** (Swift 6, SwiftUI): single-process menu bar app. Publishes the
  Bonjour service, accepts the mTLS connection, syncs NSPasteboard, decodes
  H.264 with VideoToolbox, and (when signed) feeds a CMIOExtension virtual camera.
- **Android app** (Kotlin): persistent foreground service hosting the socket
  link, NotificationListenerService, InCallService, clipboard bridge, and a
  CameraX + MediaCodec hardware encoding pipeline.

There is no relay server, daemon, or cloud component.

## macOS components (`macos/`)

| Module | Role |
|---|---|
| `App/` (Xcode app target) | `MenuBarExtra` UI, call panel, camera preview window, app lifecycle. Project generated from `project.yml` via xcodegen |
| `AirRelayKit/` (SwiftPM package) | `SyncEngine`, Bonjour `NWListener`, TLS identity management, frame codec, clipboard monitor, video decoder, file transfer |
| CMIOExtension virtual camera | Future target (requires paid Developer ID) |

Key decisions:
- `Network.framework` for the listener and connections (TLS via `sec_protocol_options`).
- Self-signed P-256 identity: certificate built with Apple's `swift-certificates`
  package (macOS has no native X.509 creation API; LibreSSL CLI is deprecated),
  key + cert persisted in the login Keychain as a `SecIdentity`.
- Clipboard sync polls `NSPasteboard.changeCount` (no push API on macOS).
- Power assertions (`IOPMAssertionCreateWithName`) held during transfers/streams.
- Decoded `CVPixelBuffer`s are handed to the camera extension over an app-group
  shared ring buffer once the extension is enabled.

## Android components (`android/`)

| Module | Role |
|---|---|
| `SyncService` | Foreground service (`connectedDevice|dataSync|camera` types), owns socket + reconnect loop |
| `DiscoveryManager` | `NsdManager` browse/resolve of `_syncbridge._tcp` |
| `TlsClient` | mTLS 1.3 socket, cert pinning against paired fingerprint |
| `NotificationRelayService` | `NotificationListenerService`; forwards posts/dismissals, executes `RemoteInput` replies |
| `CallMonitor` | PHONE_STATE broadcast + `TelecomManager`; forwards call state with caller ID, executes answer/reject/hangup and speaker routing |
| `ClipboardBridge` | Focus-grab 1×1 activity + notification action for Android 10+ clipboard reads |
| `CameraStreamer` | CameraX → `MediaCodec` H.264 low-latency encode → frame writer |
| `PairingActivity` | QR scan, fingerprint pinning, SAS confirmation |

## Security model

### Known tradeoff: Mac private key extractability

The Mac's TLS private key is created in the login keychain with
`kSecAttrIsExtractable = true`. This is required because the self-signed
certificate is produced by swift-certificates in-process (the key material
must be readable once to sign the cert). Keychain ACLs still gate access to
the app; a hardened alternative (SEP-backed key + CSR-style flow) is planned
for the notarized release build.


- Trust = pinned peer certificate fingerprint, established once via QR + SAS.
- All traffic (control + media) inside a single mTLS session.
- mDNS metadata is treated as untrusted hints only.

## Known platform constraints

- **Call audio cannot be relayed.** Android restricts capture of cellular call
  audio (uplink+downlink) to privileged pre-installed apps since Android 10;
  an accessibility service can at best capture the local mic, never the remote
  party. macOS additionally cannot act as a Bluetooth HFP hands-free unit with
  public APIs (IOBluetooth exposes RFCOMM, not SCO audio), so the "route via
  Mac over HFP" approach used by Windows Phone Link is not implementable.
  Air Relay therefore handles the call control plane only:
  - Answering from the Mac auto-enables the phone's speakerphone (toggleable).
  - A speaker/earpiece toggle is available on the Mac call panel during calls.
  - For private calls, users should pair earbuds with the phone; telephony
    routes to them automatically and Air Relay leaves that routing untouched.
- Android 10+ background clipboard reads require focus → QS tile / notification
  tap trick or optional AccessibilityService.
- CMIOExtension requires notarized, Developer ID-signed builds and user approval
  in System Settings → General → Login Items & Extensions.
