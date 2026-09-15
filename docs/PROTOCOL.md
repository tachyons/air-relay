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
| 0x70 | FIND_PHONE      | empty   | mac → android |
| 0x71 | FIND_PHONE_STOP | empty   | both      |

## JSON payload schemas

All JSON is UTF-8, camelCase keys.

### HELLO
```json
{ "protocolVersion": 1, "deviceName": "Pixel 9", "platform": "android", "appVersion": "0.1.0" }
```

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

### CALL_STATE
```json
{ "callId": "uuid", "state": "ringing|active|ended", "displayName": "Alice", "number": "+1555…" }
```

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

<`networkType` describes the phone's active default network. `signalLevel`
is 0–4 (cellular signal bars), present only when `networkType` is
`"cellular"`. Both fields are optional for backward compatibility.

### HOTSPOT_OPEN

Empty. Asks the phone to open its hotspot settings screen so the user can
flip the toggle — Android does not let third-party apps enable the hotspot
programmatically, so the final tap always happens on the phone.

### FIND_PHONE / FIND_PHONE_STOP

Both empty. FIND_PHONE makes the phone ring at full volume on the alarm
stream (which bypasses mute) and post a full-screen "Found it" notification.
Ringing stops when the user taps the notification, after a 60 s timeout, or
when the phone sends FIND_PHONE_STOP so the Mac can update its UI. A stop
sent by the Mac is handled locally by the phone without an echo.

## Keepalive & reconnection

- PING every 15 s; drop connection after 2 missed PONGs.
- Reconnect with exponential backoff + full jitter: `min(60s, 1s * 2^n + rand(0, 0.5s))`.
