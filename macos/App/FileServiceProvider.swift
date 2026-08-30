import AirRelayKit
import AppKit

/// Handles the Finder Services menu item "Send to Phone" (right-click a file
/// in Finder → Services → Send to Phone). Registered via NSServices in
/// Info.plist and NSApp.servicesProvider.
@MainActor
final class FileServiceProvider: NSObject {
    private let engine: SyncEngine

    init(engine: SyncEngine) {
        self.engine = engine
    }

    @objc func sendToPhone(
        _ pasteboard: NSPasteboard,
        userData: String,
        error: AutoreleasingUnsafeMutablePointer<NSString>
    ) {
        guard engine.isConnected else {
            error.pointee = "Air Relay is not connected to your phone." as NSString
            return
        }
        let urls = pasteboard.readObjects(
            forClasses: [NSURL.self],
            options: [.urlReadingFileURLsOnly: true]
        ) as? [URL] ?? []
        guard !urls.isEmpty else {
            error.pointee = "No files to send." as NSString
            return
        }
        for url in urls {
            engine.fileTransfer.offer(fileURL: url)
        }
    }
}
