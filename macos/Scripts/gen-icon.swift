// Renders the Air Relay app icon at a given size.
// Usage: swift gen-icon.swift <size> <output.png> [--android]
// macOS style: rounded-rect gradient tile with phone + radiowaves glyph.
// Android (--android): full-bleed square (adaptive icon foreground layer).
import AppKit

let args = CommandLine.arguments
guard args.count >= 3, let size = Int(args[1]) else {
    print("usage: swift gen-icon.swift <size> <output.png> [--android]")
    exit(1)
}
let outputPath = args[2]
let androidStyle = args.contains("--android")

let dimension = CGFloat(size)
let image = NSImage(size: NSSize(width: dimension, height: dimension))
image.lockFocus()

guard let context = NSGraphicsContext.current?.cgContext else { exit(1) }

// Background tile: macOS uses ~80% rounded rect on transparent; Android full bleed.
let tileRect: CGRect
let cornerRadius: CGFloat
if androidStyle {
    tileRect = CGRect(x: 0, y: 0, width: dimension, height: dimension)
    cornerRadius = 0
} else {
    let inset = dimension * 0.1
    tileRect = CGRect(x: inset, y: inset, width: dimension - 2 * inset, height: dimension - 2 * inset)
    cornerRadius = dimension * 0.18
}

let path = CGPath(
    roundedRect: tileRect,
    cornerWidth: cornerRadius,
    cornerHeight: cornerRadius,
    transform: nil
)
context.addPath(path)
context.clip()

let colors = [
    NSColor(calibratedRed: 0.15, green: 0.45, blue: 0.95, alpha: 1).cgColor,
    NSColor(calibratedRed: 0.35, green: 0.75, blue: 0.95, alpha: 1).cgColor,
]
let gradient = CGGradient(
    colorsSpace: CGColorSpaceCreateDeviceRGB(),
    colors: colors as CFArray,
    locations: [0, 1]
)!
context.drawLinearGradient(
    gradient,
    start: CGPoint(x: tileRect.minX, y: tileRect.maxY),
    end: CGPoint(x: tileRect.maxX, y: tileRect.minY),
    options: []
)

// Glyph
let symbolConfig = NSImage.SymbolConfiguration(
    pointSize: dimension * 0.42,
    weight: .medium
)
if let symbol = NSImage(
    systemSymbolName: "iphone.gen3.radiowaves.left.and.right",
    accessibilityDescription: nil
)?.withSymbolConfiguration(symbolConfig) {
    let tinted = NSImage(size: symbol.size)
    tinted.lockFocus()
    NSColor.white.set()
    let rect = NSRect(origin: .zero, size: symbol.size)
    symbol.draw(in: rect)
    rect.fill(using: .sourceAtop)
    tinted.unlockFocus()

    let glyphSize = tinted.size
    let origin = NSPoint(
        x: (dimension - glyphSize.width) / 2,
        y: (dimension - glyphSize.height) / 2
    )
    tinted.draw(at: origin, from: .zero, operation: .sourceOver, fraction: 1)
}

image.unlockFocus()

guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:])
else { exit(1) }
try! png.write(to: URL(fileURLWithPath: outputPath))
print("wrote \(outputPath)")
