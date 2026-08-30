# Air Relay — Agent Guide

Android ↔ macOS sync bridge (notifications, calls, clipboard, files, camera)
for **regular consumers**, mimicking Apple Continuity. Android app in
`android/` (Kotlin, Compose, Material 3 Expressive), macOS app in `macos/`
(Swift 6, SwiftUI menu bar app + SwiftPM library `AirRelayKit`).

## Build & test

Everything runs through the root `Makefile`:

```sh
make build          # both apps (debug)
make test           # Kotlin unit tests + Swift package tests
make android        # Android debug APK
make macos          # macOS app via xcodebuild
make run-macos      # build + launch menu bar app
make run-android    # build + deploy via `android` CLI
make xcodeproj      # regenerate AirRelay.xcodeproj after editing macos/project.yml
make doctor         # toolchain check
```

- `AirRelay.xcodeproj` is **generated** from `macos/project.yml` (xcodegen) — never edit the project file directly.
- JDK: Android Studio's bundled JDK is auto-selected by the Makefile.
- Always run `make test` after protocol or SAS changes — the Kotlin and Swift
  test suites share golden vectors (`SasTest.kt` / `SasTests.swift`) that keep
  the two implementations compatible. Breaking one breaks pairing.

## Design philosophy

**Less is more. We build for normal people, not power users.** KDE Connect
exposes every knob; we deliberately do not. When in doubt, cut the feature,
hide the option, or make it automatic.

### Principles

1. **Zero configuration.** Pairing is scan-a-QR or compare-a-code. Discovery,
   reconnection, and recovery are automatic. If the app needs the user to
   configure something, that's a bug.
2. **No jargon in user-facing strings.** Never show: fingerprint, mTLS, TLS,
   SAS, certificate, mDNS, Bonjour, daemon, socket. Say "code", "pair",
   "connect". Error text says what the user can *do*, not what went wrong
   internally ("Make sure your Mac is awake and on the same Wi-Fi").
3. **Every user action gets feedback.** Sent a clipboard? Toast. Sent a file?
   Notification. Replied from the Mac? Inline "Sent ✓". Silent success is
   indistinguishable from silent failure.
4. **Errors are actionable or invisible.** Show a problem only with a
   one-tap fix attached (the Android "fix-it rows": grant permission, allow
   battery). Transient network hiccups auto-recover quietly — never alarm the
   user with "Reconnecting…"; say "Waiting for your Mac…".
5. **A healthy app shows nothing.** No status matrices of features that are
   "on". The connected screen is: device name, Connected, and the one or two
   actions that matter. Settings contain only what users change (launch at
   login, unpair).
6. **Destructive actions are confirmed.** Unpair always asks first, and states
   the consequence in plain words.
7. **Fail clean.** Interrupted file transfers delete their partial files and
   tell the user to retry — never leave invisible half-files. Pairing declined
   stops retrying until the user says "Try again" — never loop prompts.
8. **Native over custom.** macOS notifications via UNUserNotificationCenter,
   Finder Services for file sending, Android share sheet and Quick Settings
   tiles — meet users in the OS surfaces they already know. The menu bar
   popover / phone app are dashboards, not the primary path.
9. **Privacy is structural, not a setting.** Peer-to-peer only, mutual TLS,
   certificate pinning after one explicit pairing step. No cloud, no accounts,
   no telemetry. There is deliberately no toggle for any of this.

### Platform constraints to respect (documented, don't fight them)

- Cellular call audio **cannot** be relayed (Android privileged-API + no macOS
  HFP unit role). We do call *control* + auto-speakerphone. See docs/ARCHITECTURE.md.
- Android 10+ blocks background clipboard reads → focus-grab activity, QS tile.
- CMIOExtension virtual camera needs a paid Developer ID (not yet wired).

### Code conventions

- Kotlin package root: `` `in`.aboobacker.airrelay `` (note backticked keyword).
- Wire protocol changes require: docs/PROTOCOL.md update + both codecs +
  golden-vector tests.
- User-visible strings live with the UI (extraction to resources pending);
  keep them short, sentence-case, and free of technical vocabulary.
- Never commit `AirRelay.xcodeproj`, `keystore.properties`, or `android/.artifacts/`.

## More docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — system design, module map, security model
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — wire protocol and pairing flow
