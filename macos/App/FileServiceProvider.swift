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
        guard engine.isConnected && engine.isPaired else {
            error.pointee = "Air Relay is not connected to a paired phone." as NSString
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

    /// Services menu "Open on Phone": sends a selected link (or a URL found
    /// in selected text) to the phone's default browser.
    @objc func openOnPhone(
        _ pasteboard: NSPasteboard,
        userData: String,
        error: AutoreleasingUnsafeMutablePointer<NSString>
    ) {
        guard engine.isConnected && engine.isPaired else {
            error.pointee = "Air Relay is not connected to a paired phone." as NSString
            return
        }
        guard let url = webURL(from: pasteboard) else {
            error.pointee = "No link found in the selection." as NSString
            return
        }
        engine.openOnPhone(url: url)
    }

    private func webURL(from pasteboard: NSPasteboard) -> URL? {
        if let urls = pasteboard.readObjects(forClasses: [NSURL.self]) as? [URL],
           let url = urls.first(where: { $0.scheme?.lowercased() == "http" || $0.scheme?.lowercased() == "https" }) {
            return url
        }
        guard let text = pasteboard.string(forType: .string) else { return nil }
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(text.startIndex..., in: text)
        return detector?.matches(in: text, range: range)
            .compactMap(\.url)
            .first { $0.scheme?.lowercased() == "http" || $0.scheme?.lowercased() == "https" }
    }
}
