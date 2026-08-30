import AirRelayKit
import AppKit
import AVFoundation
import CoreMedia
import SwiftUI

/// Preview window for the phone camera stream. Displays decoded frames via
/// AVSampleBufferDisplayLayer until the CMIOExtension virtual camera ships.
@MainActor
final class CameraPreviewController {
    private var window: NSWindow?
    private let displayLayer = AVSampleBufferDisplayLayer()

    func show() {
        if window == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 640, height: 360),
                styleMask: [.titled, .closable, .resizable],
                backing: .buffered,
                defer: false
            )
            window.title = "Phone Camera"
            window.isReleasedWhenClosed = false
            let view = NSView()
            view.wantsLayer = true
            displayLayer.videoGravity = .resizeAspect
            displayLayer.frame = view.bounds
            displayLayer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
            view.layer?.addSublayer(displayLayer)
            window.contentView = view
            window.center()
            self.window = window
        }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func hide() {
        window?.orderOut(nil)
        displayLayer.flushAndRemoveImage()
    }

    nonisolated func enqueue(pixelBuffer: CVPixelBuffer, pts: CMTime) {
        var formatDesc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDesc
        )
        guard let formatDesc else { return }
        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: pts, decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDesc,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        guard let sampleBuffer else { return }
        nonisolated(unsafe) let buffer = sampleBuffer
        Task { @MainActor in
            if self.displayLayer.status == .failed {
                self.displayLayer.flush()
            }
            self.displayLayer.enqueue(buffer)
        }
    }
}
