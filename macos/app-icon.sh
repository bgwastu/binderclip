#!/bin/zsh
# Recompiles macos/AppIcon/AppIcon.icon. Needs Xcode 26+, so the output is committed for CI.
set -euo pipefail
ROOT="${0:A:h}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
xcrun actool "$ROOT/AppIcon/AppIcon.icon" --compile "$OUT" --app-icon AppIcon --platform macosx \
  --minimum-deployment-target 13.0 --target-device mac --output-partial-info-plist "$OUT/partial.plist" >/dev/null
cp "$OUT/Assets.car" "$ROOT/AppIcon/Assets.car"

# actool generates AppIcon.icns only up to 256px. Extract the full 1024px representation
# from Assets.car into AppIcon.icns so Finder, Dock, and previews are ultra crisp.
swift - "$OUT/Assets.car" "$ROOT/BinderClipMac/Resources/AppIcon.icns" <<'SWIFT'
import AppKit

let args = CommandLine.arguments
let carURL = URL(fileURLWithPath: args[1]).absoluteURL
let outIcns = URL(fileURLWithPath: args[2]).absoluteURL
let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".bundle")
let resDir = tempDir.appendingPathComponent("Contents/Resources", isDirectory: true)
try! FileManager.default.createDirectory(at: resDir, withIntermediateDirectories: true)
try! FileManager.default.copyItem(at: carURL, to: resDir.appendingPathComponent("Assets.car"))

let plist = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>net.wastu.iconextract</string><key>CFBundleIconName</key><string>AppIcon</string></dict></plist>
"""
try! plist.write(to: tempDir.appendingPathComponent("Contents/Info.plist"), atomically: true, encoding: .utf8)

guard let bundle = Bundle(url: tempDir), let img = bundle.image(forResource: "AppIcon") else {
    fatalError("Could not load AppIcon from bundle")
}

let iconsetDir = tempDir.appendingPathComponent("AppIcon.iconset")
try! FileManager.default.createDirectory(at: iconsetDir, withIntermediateDirectories: true)

let sizes: [(String, Int, Int)] = [
    ("icon_16x16.png", 16, 16),
    ("icon_16x16@2x.png", 32, 32),
    ("icon_32x32.png", 32, 32),
    ("icon_32x32@2x.png", 64, 64),
    ("icon_128x128.png", 128, 128),
    ("icon_128x128@2x.png", 256, 256),
    ("icon_256x256.png", 256, 256),
    ("icon_256x256@2x.png", 512, 512),
    ("icon_512x512.png", 512, 512),
    ("icon_512x512@2x.png", 1024, 1024)
]

for (filename, w, h) in sizes {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let candidates = img.representations.filter { $0.pixelsWide >= w }
    let bestRep = (candidates.min(by: { $0.pixelsWide < $1.pixelsWide }) ?? img.representations.max(by: { $0.pixelsWide < $1.pixelsWide }))!
    bestRep.draw(in: NSRect(x: 0, y: 0, width: w, height: h))
    NSGraphicsContext.restoreGraphicsState()
    let png = rep.representation(using: .png, properties: [:])!
    try! png.write(to: iconsetDir.appendingPathComponent(filename))
}

let proc = Process()
proc.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
proc.arguments = ["-c", "icns", iconsetDir.path, "-o", outIcns.path]
try! proc.run()
proc.waitUntilExit()
try! FileManager.default.removeItem(at: tempDir)
SWIFT
