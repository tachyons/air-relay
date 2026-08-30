import AirRelayKit
import SwiftUI

struct MenuBarView: View {
    @EnvironmentObject private var engine: SyncEngine
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(12)
            if let error = engine.lastError {
                Divider()
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
            }
            Divider()
            if let pending = engine.pendingPairing {
                pairingPrompt(pending)
                    .padding(12)
                Divider()
            }
            if !engine.fileTransfer.transfers.isEmpty {
                transferList
                    .padding(12)
                Divider()
            }
            if !engine.fileTransfer.recentFiles.isEmpty {
                recentFilesList
                    .padding(12)
                Divider()
            }
            notificationList
            Divider()
            dropZone
            Divider()
            footer
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
        }
        .frame(width: 340)
    }

    // MARK: Header

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: engine.isConnected
                ? "iphone.gen3.radiowaves.left.and.right" : "iphone.gen3.slash")
                .font(.title2)
                .foregroundStyle(engine.isConnected ? Color.accentColor : .secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(engine.peerName ?? (engine.isPaired ? "Waiting for phone…" : "No device paired"))
                    .font(.headline)
                HStack(spacing: 6) {
                    Circle()
                        .fill(engine.isConnected ? .green : .orange)
                        .frame(width: 7, height: 7)
                    Text(engine.isConnected ? "Connected" : "Searching")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if let status = engine.deviceStatus {
                        Text("·")
                            .foregroundStyle(.secondary)
                        Label("\(status.battery)%", systemImage: status.charging
                            ? "battery.100.bolt" : "battery.75")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let network = networkLabel(status) {
                            Text("·")
                                .foregroundStyle(.secondary)
                            Label(network.text, systemImage: network.icon)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }
            Spacer()
            if engine.isConnected, engine.deviceStatus?.networkType == "cellular" {
                Button {
                    engine.requestHotspot()
                } label: {
                    Image(systemName: "personalhotspot")
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .help("Use the phone's hotspot — opens hotspot settings on the phone")
            }
        }
    }

    private func networkLabel(_ status: DeviceStatus) -> (text: String, icon: String)? {
        switch status.networkType {
        case "wifi":
            return (status.wifiSsid ?? "Wi-Fi", "wifi")
        case "cellular":
            let bars = status.signalLevel.map { "\($0)/4" } ?? ""
            return (bars.isEmpty ? "Cellular" : "Cellular \(bars)", "antenna.radiowaves.left.and.right")
        case "none":
            return ("Offline", "wifi.slash")
        default:
            return nil
        }
    }

    // MARK: Pairing

    private func pairingPrompt(_ pending: SyncEngine.PendingPairing) -> some View {
        VStack(spacing: 10) {
            Label("Pairing request", systemImage: "link.badge.plus")
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(pending.sasCode)
                .font(.system(size: 32, weight: .bold, design: .monospaced))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(
                    RoundedRectangle(cornerRadius: 10)
                        .fill(Color.accentColor.opacity(0.12))
                )
            Text("Confirm this code matches the one shown on your phone.")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("Reject", role: .destructive) { engine.rejectPairing() }
                Spacer()
                Button("Codes Match") { engine.confirmPairing() }
                    .buttonStyle(.borderedProminent)
            }
        }
    }

    // MARK: Notifications

    private var notificationList: some View {
        Group {
            if engine.notifications.isEmpty {
                VStack(spacing: 6) {
                    Image(systemName: "bell.slash")
                        .font(.title3)
                        .foregroundStyle(.tertiary)
                    Text("No notifications")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(engine.notifications.prefix(15)) { item in
                            NotificationRow(item: item) { text in
                                engine.sendReply(key: item.key, text: text)
                            }
                            if item.key != engine.notifications.prefix(15).last?.key {
                                Divider()
                                    .padding(.leading, 12)
                            }
                        }
                    }
                }
                .frame(maxHeight: 260)
            }
        }
    }

    // MARK: File transfer

    private var transferList: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(engine.fileTransfer.transfers) { transfer in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Image(systemName: transfer.inbound
                            ? "arrow.down.circle" : "arrow.up.circle")
                            .foregroundStyle(.secondary)
                        Text(transfer.name)
                            .font(.caption)
                            .lineLimit(1)
                        Spacer()
                        Text("\(Int(transfer.fraction * 100))%")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    ProgressView(value: transfer.fraction)
                        .controlSize(.small)
                }
            }
        }
    }

    private var recentFilesList: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Recent files")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            ForEach(engine.fileTransfer.recentFiles.prefix(5)) { file in
                Button {
                    if let url = file.url {
                        NSWorkspace.shared.activateFileViewerSelecting([url])
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: file.inbound
                            ? "arrow.down.doc" : "arrow.up.doc")
                            .foregroundStyle(.secondary)
                        Text(file.name)
                            .font(.caption)
                            .lineLimit(1)
                        Spacer()
                        Text(file.date, style: .relative)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(file.inbound ? "Show in Finder" : "Sent to phone")
            }
        }
    }

    @State private var dropTargeted = false

    private var dropZone: some View {
        HStack(spacing: 8) {
            Button {
                pickAndSendFiles()
            } label: {
                Label("Send Files…", systemImage: "square.and.arrow.up")
                    .font(.caption)
            }
            .disabled(!engine.isConnected)
            .help("You can also right-click a file in Finder → Services → Send to Phone, or drop files here")
            if !engine.isConnected {
                Text("Connect to send files")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            Spacer()
        }
        .padding(10)
        .background(dropTargeted ? Color.accentColor.opacity(0.1) : .clear)
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            guard engine.isConnected else { return false }
            for provider in providers {
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    Task { @MainActor in
                        engine.fileTransfer.offer(fileURL: url)
                    }
                }
            }
            return true
        }
    }

    private func pickAndSendFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.message = "Choose files to send to your phone"
        panel.prompt = "Send"
        // The popover closes when it loses focus; run the panel as a
        // standalone key window so the selection flow survives.
        NSApp.activate(ignoringOtherApps: true)
        panel.begin { response in
            guard response == .OK else { return }
            Task { @MainActor in
                for url in panel.urls {
                    engine.fileTransfer.offer(fileURL: url)
                }
            }
        }
    }

    // MARK: Footer

    private var footer: some View {
        HStack {
            SettingsLink {
                Image(systemName: "gearshape")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            Button {
                openWindow(id: "help")
                NSApp.activate(ignoringOtherApps: true)
            } label: {
                Image(systemName: "questionmark.circle")
            }
            .buttonStyle(.plain)
            .foregroundStyle(.secondary)
            .help("What Air Relay does")
            Button {
                cameraActive.toggle()
                if cameraActive {
                    cameraPreview.show()
                    engine.decoder.onDecodedFrame = { [weak cameraPreview] buffer, pts in
                        cameraPreview?.enqueue(pixelBuffer: buffer, pts: pts)
                    }
                    engine.startCamera()
                } else {
                    engine.stopCamera()
                    cameraPreview.hide()
                }
            } label: {
                Image(systemName: cameraActive ? "video.fill" : "video")
            }
            .buttonStyle(.plain)
            .foregroundStyle(cameraActive ? Color.accentColor : .secondary)
            .disabled(!engine.isConnected)
            .help("Phone camera preview")
            Spacer()
            Button("Quit") { NSApp.terminate(nil) }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
        }
        .font(.callout)
    }

    @State private var cameraActive = false
    private static let sharedCameraPreview = CameraPreviewController()
    private var cameraPreview: CameraPreviewController { Self.sharedCameraPreview }
}

#Preview("Menu bar") {
    MenuBarView()
        .environmentObject(SyncEngine())
}

private struct NotificationRow: View {
    let item: NotificationPayload
    let onReply: (String) -> Void
    @State private var hovering = false
    @State private var replying = false
    @State private var replyText = ""
    @State private var justSent = false

    private var appIcon: NSImage? {
        guard let base64 = item.iconPng,
              let data = Data(base64Encoded: base64)
        else { return nil }
        return NSImage(data: data)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 10) {
                if let icon = appIcon {
                    Image(nsImage: icon)
                        .resizable()
                        .frame(width: 24, height: 24)
                        .clipShape(RoundedRectangle(cornerRadius: 5))
                } else {
                    Image(systemName: "app.badge.fill")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .frame(width: 24)
                }
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(item.title)
                            .font(.callout.weight(.semibold))
                            .lineLimit(1)
                        Spacer()
                        Text(relativeTime(item.postedAt))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                        if item.canReply, hovering || replying {
                            Button {
                                replying.toggle()
                            } label: {
                                Image(systemName: "arrowshape.turn.up.left")
                                    .font(.caption)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                        }
                    }
                    Text(item.text)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    Text(item.appName)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
            }
            if replying {
                HStack(spacing: 6) {
                    TextField("Reply…", text: $replyText)
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.small)
                        .onSubmit(sendReply)
                    Button("Send", action: sendReply)
                        .controlSize(.small)
                        .disabled(replyText.isEmpty)
                }
                .padding(.leading, 34)
            } else if justSent {
                Label("Sent", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.green)
                    .padding(.leading, 34)
                    .transition(.opacity)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(hovering ? Color.primary.opacity(0.05) : .clear)
        .onHover { hovering = $0 }
    }

    private func sendReply() {
        guard !replyText.isEmpty else { return }
        onReply(replyText)
        replyText = ""
        replying = false
        withAnimation { justSent = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            withAnimation { justSent = false }
        }
    }

    private func relativeTime(_ epochMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMs) / 1000)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
