import AirRelayKit
import AppKit
import SwiftUI

/// Floating always-on-top call panel, shown while a call is ringing or active.
@MainActor
final class CallPanelController {
    private var panel: NSPanel?

    func update(call: CallState?, engine: SyncEngine) {
        guard let call else {
            close()
            return
        }
        if panel == nil {
            let panel = NSPanel(
                contentRect: NSRect(x: 0, y: 0, width: 320, height: 150),
                styleMask: [.nonactivatingPanel, .titled, .fullSizeContentView],
                backing: .buffered,
                defer: false
            )
            panel.level = .floating
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            panel.titleVisibility = .hidden
            panel.titlebarAppearsTransparent = true
            panel.isMovableByWindowBackground = true
            panel.hidesOnDeactivate = false
            panel.isReleasedWhenClosed = false
            self.panel = panel
            positionTopRight(panel)
        }
        panel?.contentView = NSHostingView(
            rootView: CallPanelView(call: call)
                .environmentObject(engine)
        )
        panel?.orderFrontRegardless()
    }

    func close() {
        panel?.orderOut(nil)
    }

    private func positionTopRight(_ panel: NSPanel) {
        guard let screen = NSScreen.main else { return }
        let frame = screen.visibleFrame
        panel.setFrameOrigin(NSPoint(
            x: frame.maxX - panel.frame.width - 20,
            y: frame.maxY - panel.frame.height - 20
        ))
    }
}

struct CallPanelView: View {
    let call: CallState
    @EnvironmentObject private var engine: SyncEngine
    @State private var speakerOn = true

    private var callerName: String {
        call.displayName ?? call.number ?? "Unknown caller"
    }

    var body: some View {
        VStack(spacing: 14) {
            VStack(spacing: 4) {
                Image(systemName: "phone.circle.fill")
                    .font(.system(size: 36))
                    .foregroundStyle(call.state == "ringing" ? .green : .blue)
                    .symbolEffect(.pulse, isActive: call.state == "ringing")
                Text(callerName)
                    .font(.title3.bold())
                    .lineLimit(1)
                if call.displayName != nil, let number = call.number {
                    Text(number)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(call.state == "ringing"
                    ? "Incoming call on your phone"
                    : "On call · audio plays on your phone")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 12) {
                if call.state == "ringing" {
                    Button {
                        engine.sendCallAction(callId: call.callId, action: "reject")
                    } label: {
                        Label("Decline", systemImage: "phone.down.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .tint(.red)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)

                    Button {
                        engine.sendCallAction(callId: call.callId, action: "answer")
                    } label: {
                        Label("Answer", systemImage: "phone.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .tint(.green)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                } else {
                    Button {
                        speakerOn.toggle()
                        engine.sendCallAction(
                            callId: call.callId,
                            action: speakerOn ? "speakerOn" : "speakerOff"
                        )
                    } label: {
                        Image(systemName: speakerOn
                            ? "speaker.wave.2.fill" : "speaker.slash.fill")
                            .frame(width: 24)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .help(speakerOn ? "Switch to earpiece" : "Switch to speakerphone")

                    Button {
                        engine.sendCallAction(callId: call.callId, action: "hangup")
                    } label: {
                        Label("Hang Up", systemImage: "phone.down.fill")
                            .frame(maxWidth: .infinity)
                    }
                    .tint(.red)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                }
            }
            if call.state == "ringing" {
                Text("Answering plays audio on the phone's speaker. Use earbuds paired to your phone for private calls.")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(20)
        .frame(width: 320)
    }
}
