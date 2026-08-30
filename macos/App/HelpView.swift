import SwiftUI

struct HelpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("What Air Relay does")
                    .font(.title2.bold())
                Text("Everything happens directly between your phone and your Mac on your Wi-Fi. Nothing goes through the internet.")
                    .foregroundStyle(.secondary)

                helpRow(
                    icon: "bell.badge",
                    title: "Notifications",
                    body: "Your phone's notifications appear here. Reply to messages and tap actions without picking up the phone."
                )
                helpRow(
                    icon: "phone.arrow.down.left",
                    title: "Phone calls",
                    body: "See who's calling and answer or decline from your Mac. The phone switches to speakerphone so you can talk hands-free."
                )
                helpRow(
                    icon: "doc.on.clipboard",
                    title: "Clipboard",
                    body: "Anything you copy on the Mac lands on your phone automatically. Use the tile or button on the phone to send its clipboard here."
                )
                helpRow(
                    icon: "folder",
                    title: "Files",
                    body: "Drop files on the menu bar icon, use Send Files…, or right-click a file in Finder and choose Send to Phone. Files from your phone arrive in Downloads › AirRelay."
                )
                helpRow(
                    icon: "video",
                    title: "Camera",
                    body: "Open a live preview of your phone's camera from the menu bar."
                )

                Text("You can turn features off any time in Settings.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(width: 440, height: 480)
    }

    private func helpRow(icon: String, title: String, body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Color.accentColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(body)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
