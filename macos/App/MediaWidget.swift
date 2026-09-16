import AirRelayKit
import SwiftUI

/// Separate menu bar icon for Now Playing. Lives outside the main
/// popover so the main popover stays simple (notifications + files).
/// Shows only when a media session is active.
struct MediaWidgetView: View {
    @EnvironmentObject private var engine: SyncEngine

    var body: some View {
        if let media = engine.mediaState {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    albumArt(for: media)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(media.title ?? "")
                            .font(.callout.weight(.medium))
                            .lineLimit(1)
                        Text(media.artist ?? media.appName ?? "")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer()
                }
                .padding(12)
                Divider()
                HStack(spacing: 12) {
                    Button { engine.sendMediaAction("previous") } label: {
                        Image(systemName: "backward.fill")
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Button { engine.sendMediaAction(media.playing ? "pause" : "play") } label: {
                        Image(systemName: media.playing ? "pause.fill" : "play.fill")
                            .font(.title3)
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Button { engine.sendMediaAction("next") } label: {
                        Image(systemName: "forward.fill")
                    }
                    .buttonStyle(.plain)
                }
                .font(.callout)
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .frame(width: 260)
        } else {
            Text("Nothing playing")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(12)
                .frame(width: 260)
        }
    }

    private func albumArt(for media: MediaState) -> some View {
        Group {
            if let base64 = media.artPng,
               let data = Data(base64Encoded: base64),
               let img = NSImage(data: data) {
                Image(nsImage: img)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "music.note")
                    .font(.title3)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(Color.primary.opacity(0.06))
        )
    }
}
