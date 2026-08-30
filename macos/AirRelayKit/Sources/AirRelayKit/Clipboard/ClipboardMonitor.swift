import AppKit
import Foundation

/// Polls NSPasteboard.changeCount and reports new text copies.
@MainActor
public final class ClipboardMonitor {
    private var timer: Timer?
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var suppressedText: String?

    public var onCopy: ((String) -> Void)?

    public init() {}

    public func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// Applies remote text to the local pasteboard without echoing it back.
    public func applyRemote(text: String) {
        suppressedText = text
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)
        lastChangeCount = pasteboard.changeCount
    }

    private func poll() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != lastChangeCount else { return }
        lastChangeCount = pasteboard.changeCount
        guard let text = pasteboard.string(forType: .string) else { return }
        if text == suppressedText {
            suppressedText = nil
            return
        }
        onCopy?(text)
    }
}
