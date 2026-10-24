# Air Relay Wire Protocol v1

## Discovery

- Service type: `_syncbridge._tcp` (mDNS / DNS-SD)
- macOS publishes via `NWListener` (Bonjour); Android browses via `NsdManager`.
- TXT records: `v=1`, `name=<device name>`, `fp=<sha256 cert fingerprint prefix, 8 bytes hex>`

## Transport

- Mutual TLS 1.3 over TCP. Both peers use self-signed X.509 (P-256) certificates
  generated on first launch and persisted (Keychain on macOS, Android Keystore on Android).
- Trust is established by certificate pinning during pairing; no CA validation.

## Pairing (out-of-band)

1. macOS displays a QR code containing: `airrelay://pair?fp=<sha256(cert DER) hex>&port=<port>&name=<host>`
   plus a 6-digit SAS derived from `sha256(macCert || androidCert)` truncated mod 10^6, shown on both screens after handshake.
2. Android scans QR, connects, pins macOS cert fingerprint.
3. macOS prompts to accept the Android device; on accept, pins the Android client cert fingerprint.
4. Both persist the peer fingerprint. Subsequent connections require an exact match.

## Framing

All messages after the TLS handshake use length-prefixed frames:

```
+----------------+----------------+------------------+
| length: u32 BE | type: u8       | payload bytes    |
+----------------+----------------+------------------+
```

`length` covers `type + payload`. Max frame size: 16 MiB (larger transfers are chunked).

A frame type this build doesn't know is **skipped**, not fatal: the length
prefix keeps the stream in sync, so a peer running a newer protocol never
drops an otherwise healthy link. Structural faults (length < 1, length above
the max, truncated stream) close the connection. A payload that fails to
decode is dropped for that frame only; it never takes the link down.

### Frame types

| Type | Name            | Payload | Direction |
|------|-----------------|---------|-----------|
| 0x01 | HELLO           | JSON    | both      |
| 0x02 | PING            | empty   | both      |
| 0x03 | PONG            | empty   | both      |
| 0x10 | NOTIFICATION    | JSON    | android → mac |
| 0x11 | NOTIF_DISMISS   | JSON    | both      |
| 0x12 | NOTIF_REPLY     | JSON    | mac → android |
| 0x13 | NOTIF_ACTION    | JSON    | mac → android |
| 0x20 | CLIPBOARD_TEXT  | JSON    | both      |
| 0x21 | OPEN_URL        | JSON    | both      |
| 0x30 | CALL_STATE      | JSON    | android → mac |
| 0x31 | CALL_ACTION     | JSON    | mac → android |
| 0x40 | FILE_OFFER      | JSON    | both      |
| 0x41 | FILE_ACCEPT     | JSON    | both      |
| 0x42 | FILE_CHUNK      | binary  | both      |
| 0x43 | FILE_DONE       | JSON    | both      |
| 0x50 | CAMERA_START    | JSON    | mac → android |
| 0x51 | CAMERA_STOP     | empty   | mac → android |
| 0x52 | VIDEO_CONFIG    | binary  | android → mac (SPS/PPS) |
| 0x53 | VIDEO_FRAME     | binary  | android → mac |
| 0x60 | DEVICE_STATUS   | JSON    | android → mac |
| 0x61 | HOTSPOT_OPEN    | empty   | mac → android |
| 0x62 | FOCUS_STATE     | JSON    | both      |
| 0x63 | RINGER_ACTION   | JSON    | mac → android |
| 0x70 | FIND_PHONE      | empty   | mac → android |
| 0x71 | FIND_PHONE_STOP | empty   | both      |
| 0x80 | MEDIA_STATE     | JSON    | android → mac |
| 0x81 | MEDIA_ACTION    | JSON    | mac → android |

## JSON payload schemas

All JSON is UTF-8, camelCase keys.

### HELLO
```json
{ "protocolVersion": 1, "deviceName": "Pixel 9", "platform": "android", "appVersion": "0.1.0", "pairing": false }
```

Both sides send HELLO immediately after the handshake. `protocolVersion` is
the wire version this build speaks. A peer with a different version is not
tolerated: the connection is closed and the user is told which device needs
updating, rather than letting the link half-work.

`pairing` (optional, default `false`) is true while the sender is waiting for
the user to confirm a pairing code. HELLO is the one frame that may be sent
before the link is trusted, so an unverified peer learns that a confirmation
is pending and shows the same code. A paired device that receives
`"pairing": true` treats the stored pairing as reset, forgets it, and pairs
again — this is the recovery path after either side's certificate is
regenerated (app reinstall, keystore/keychain reset).

### NOTIFICATION
```json
{
  "key": "0|com.whatsapp|123|tag|10123",
  "packageName": "com.whatsapp",
  "appName": "WhatsApp",
  "title": "Alice",
  "text": "hey!",
  "postedAt": 1735500000000,
  "canReply": true,
  "actions": ["Reply", "Mark as read"],
  "iconPng": "<base64, optional>"
}
```

### NOTIF_REPLY
```json
{ "key": "…", "text": "on my way" }
```

### CLIPBOARD_TEXT
```json
{ "text": "…", "ts": 1735500000000 }
```

### OPEN_URL
```json
{ "url": "https://example.com/article" }
```

The receiver opens the URL in the default browser (`NSWorkspace.open` on
macOS, `Intent.ACTION_VIEW` on Android). Only http/https URLs are accepted;
anything else is ignored.

### CALL_STATE
```json
{ "callId": "uuid", "state": "ringing|active|ended", "displayName": "Alice", "number": "+1555…", "photoPng": "<base64, optional>" }
```

`photoPng` is the caller's contact photo thumbnail as base64 PNG, present
only when contacts permission is granted and the contact has a photo.
Thumbnails are small (a few KB); the phone caches the encoded photo per
number so state changes don't re-query or re-encode it.

### CALL_ACTION
```json
{ "callId": "uuid", "action": "answer|reject|hangup|speakerOn|speakerOff" }
```

Note: call **audio is not relayed**. Android forbids third-party capture of
cellular call audio (downlink is restricted to privileged system apps since
Android 10), and macOS cannot act as a Bluetooth HFP hands-free unit with
public APIs. Air Relay carries the control plane only; audio plays on the
phone (speakerphone is enabled automatically when a call is answered from the
Mac) or on earbuds paired to the phone.

### FILE_OFFER / FILE_ACCEPT / FILE_DONE
```json
{ "transferId": "uuid", "name": "photo.jpg", "size": 123456, "mime": "image/jpeg" }
```
FILE_CHUNK binary payload: `transferId (16 bytes uuid) || offset (u64 BE) || data`.

Offsets start at 0 and are strictly sequential; the receiver aborts the
transfer (and deletes the partial file) if a chunk arrives out of order,
duplicates data, or would exceed the offered `size`. FILE_DONE must arrive
with exactly `size` bytes written, otherwise the transfer is discarded rather
than published as a truncated file. `name` is reduced to its base name on the
receiving side so a peer can never write outside the downloads folder.

### CAMERA_START
```json
{ "width": 1280, "height": 720, "fps": 30, "lens": "back" }
```

### VIDEO_FRAME
Binary: `pts_us (u64 BE) || flags (u8, bit0 = keyframe) || Annex-B NAL units`.

### DEVICE_STATUS
```json
{ "battery": 87, "charging": false, "wifiSsid": "Home", "networkType": "wifi|cellular|none", "signalLevel": 3 }
```

`networkType` describes the phone's active default network. `signalLevel`
is 0–4 (cellular signal bars), present only when `networkType` is
`"cellular"`. Both fields are optional for backward compatibility.

### HOTSPOT_OPEN

Empty. Asks the phone to open its hotspot settings screen so the user can
flip the toggle — Android does not let third-party apps enable the hotspot
programmatically, so the final tap always happens on the phone.

### FOCUS_STATE

```json
{ "dnd": true }
```

Whether Do Not Disturb is active. Sent when either side toggles DND and the feature is enabled. The receiver applies the same DND state locally if permitted.

### RINGER_ACTION

```json
{ "mode": "normal|vibrate|silent" }
```

Sets the phone's ringer mode from the Mac. One-way (mac → android).

### FIND_PHONE / FIND_PHONE_STOP

Both empty. FIND_PHONE makes the phone ring at full volume on the alarm
stream (which bypasses mute) and post a full-screen "Found it" notification.
Ringing stops when the user taps the notification, after a 60 s timeout, or
when the phone sends FIND_PHONE_STOP so the Mac can update its UI. A stop
sent by the Mac is handled locally by the phone without an echo.

### MEDIA_STATE
```json
{
  "packageName": "com.spotify.music",
  "appName": "Spotify",
  "title": "Song",
  "artist": "Artist",
  "playing": true,
  "artPng": "<base64, optional>"
}
```

Sent when the phone's active media session changes (new track, play/pause,
session ended). A state with `"title": null` means nothing is playing and
the Mac hides its now-playing row. `artPng` is album art scaled to ≤128 px,
cached per track and included with the state while that track remains active
(including playback updates).

### MEDIA_ACTION
```json
{ "action": "play|pause|next|previous" }
```

Applied to the phone's most recent active media session.

## Keepalive & reconnection

- PING every 15 s; drop connection after 2 missed PONGs.
- Reconnect with exponential backoff + full jitter: `min(60s, 1s * 2^n + rand(0, 0.5s))`.
