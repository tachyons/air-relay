import AirRelayKit
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var engine: SyncEngine
    @State private var showUnpairConfirm = false

    var body: some View {
        Form {
            Section("General") {
                Toggle("Launch at login", isOn: Binding(
                    get: { engine.launchAtLogin },
                    set: { engine.launchAtLogin = $0 }
                ))
                if let error = engine.lastError {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            Section("Sync") {
                Toggle("Notifications", isOn: Binding(
                    get: { engine.notificationSyncEnabled },
                    set: { engine.notificationSyncEnabled = $0 }
                ))
                Toggle("Phone calls", isOn: Binding(
                    get: { engine.callSyncEnabled },
                    set: { engine.callSyncEnabled = $0 }
                ))
                Toggle("Clipboard", isOn: Binding(
                    get: { engine.clipboardSyncEnabled },
                    set: { engine.clipboardSyncEnabled = $0 }
                ))
                Toggle("Files", isOn: Binding(
                    get: { engine.fileSharingEnabled },
                    set: { engine.fileSharingEnabled = $0 }
                ))
            }
            Section("Phone") {
                if engine.isPaired {
                    LabeledContent("Paired with") {
                        Text(engine.peerName ?? "your phone")
                    }
                    Button("Unpair…", role: .destructive) { showUnpairConfirm = true }
                        .confirmationDialog(
                            "Unpair from your phone?",
                            isPresented: $showUnpairConfirm
                        ) {
                            Button("Unpair", role: .destructive) { engine.unpair() }
                        } message: {
                            Text("Notifications, calls, and files will stop syncing until you pair again.")
                        }
                } else if let url = engine.pairingURL {
                    VStack(spacing: 8) {
                        QRCodeView(payload: url)
                            .frame(width: 160, height: 160)
                        Text("Scan with the Air Relay app on your phone")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 420, height: 460)
    }
}
