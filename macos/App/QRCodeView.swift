import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

/// Renders an `airrelay://pair?...` payload as a QR code via CoreImage.
struct QRCodeView: View {
    let payload: String

    var body: some View {
        if let image = Self.generate(from: payload) {
            Image(nsImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: "qrcode")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
        }
    }

    static func generate(from string: String, scale: CGFloat = 8) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let transformed = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: transformed)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

#Preview("QR code") {
    QRCodeView(payload: "airrelay://pair?fp=abc123&port=52431&name=Preview%20Mac")
        .frame(width: 160, height: 160)
        .padding()
}
