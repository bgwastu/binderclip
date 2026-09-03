#!/bin/zsh

# Build an ad-hoc signed, universal direct-only BinderClip.app and DMG.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
PACKAGE_DIR="$SCRIPT_DIR/BinderClipMac"
RELEASE_REF="${GITHUB_REF_NAME:-}"
GIT_TAG_VERSION="$(git describe --tags --abbrev=0 2>/dev/null | sed -E 's/^(macos-|android-)?v?//' || echo "1.0.0")"
GIT_COMMIT_COUNT="$(git rev-list --count HEAD 2>/dev/null || echo "1")"
VERSION="${VERSION:-$(echo "${RELEASE_REF}" | sed -E 's/^(macos-|android-)?v?//')}"
VERSION="${VERSION:-$GIT_TAG_VERSION}"
BUILD_NUMBER="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-$GIT_COMMIT_COUNT}}"
APP_PATH="$PWD/BinderClip.app"
OUTPUT_DIR="${OUTPUT_DIR:-$PWD/dist}"
DMG_PATH="$OUTPUT_DIR/BinderClip-$VERSION.dmg"
BUILD_ROOT="$PACKAGE_DIR/.build/release-package"

[[ "$VERSION" =~ '^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$' ]] || { print -u2 "VERSION must be semantic, for example 1.0.0"; exit 2; }
[[ "$BUILD_NUMBER" =~ '^[1-9][0-9]*$' ]] || { print -u2 "BUILD_NUMBER must be positive"; exit 2; }

SPARKLE_PUBLIC_ED_KEY="${SPARKLE_PUBLIC_ED_KEY:-}"
[[ -n "$SPARKLE_PUBLIC_ED_KEY" ]] || {
  print -u2 "SPARKLE_PUBLIC_ED_KEY is required for a production build"
  exit 2
}
FEED_URL="${SPARKLE_FEED_URL:-https://github.com/${GITHUB_REPOSITORY:-bgwastu/BinderClip}/releases/latest/download/appcast.xml}"

rm -rf "$BUILD_ROOT" "$APP_PATH" "$OUTPUT_DIR"
print "Building BinderClip $VERSION..."
swift build --package-path "$PACKAGE_DIR" --configuration release --triple arm64-apple-macosx13.0 --scratch-path "$BUILD_ROOT/arm64"
swift build --package-path "$PACKAGE_DIR" --configuration release --triple x86_64-apple-macosx13.0 --scratch-path "$BUILD_ROOT/x86_64"

ARM_EXECUTABLE="$BUILD_ROOT/arm64/release/BinderClip"
INTEL_EXECUTABLE="$BUILD_ROOT/x86_64/release/BinderClip"
[[ -x "$ARM_EXECUTABLE" && -x "$INTEL_EXECUTABLE" ]] || { print -u2 "Universal build did not produce both architectures"; exit 1; }

mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources" "$APP_PATH/Contents/Frameworks" "$OUTPUT_DIR"
lipo -create "$ARM_EXECUTABLE" "$INTEL_EXECUTABLE" -output "$APP_PATH/Contents/MacOS/BinderClip"

SPARKLE_FRAMEWORK="$BUILD_ROOT/arm64/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework"
if [[ ! -d "$SPARKLE_FRAMEWORK" ]]; then
  SPARKLE_FRAMEWORK="$PACKAGE_DIR/.build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework"
fi
[[ -d "$SPARKLE_FRAMEWORK" ]] || {
  print -u2 "Sparkle.framework was not produced at $SPARKLE_FRAMEWORK"
  exit 1
}
ditto "$SPARKLE_FRAMEWORK" "$APP_PATH/Contents/Frameworks/Sparkle.framework"
cp "$PACKAGE_DIR/Resources/AppIcon.icns" "$APP_PATH/Contents/Resources/AppIcon.icns"
cp "$PACKAGE_DIR/Resources/BinderClipMenuIcon.svg" "$APP_PATH/Contents/Resources/BinderClipMenuIcon.svg"
cp -R "$PACKAGE_DIR"/Resources/*.lproj "$APP_PATH/Contents/Resources/"
chmod 755 "$APP_PATH/Contents/MacOS/BinderClip"

cat > "$APP_PATH/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleLocalizations</key>
  <array>
    <string>en</string>
    <string>id</string>
    <string>es</string>
    <string>zh-Hans</string>
    <string>ja</string>
    <string>de</string>
    <string>fr</string>
    <string>pt-BR</string>
    <string>ru</string>
  </array>
  <key>CFBundleDisplayName</key><string>BinderClip</string>
  <key>CFBundleExecutable</key><string>BinderClip</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIdentifier</key><string>net.wastu.binderclip</string>
  <key>CFBundleName</key><string>BinderClip</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD_NUMBER</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>BinderClip uses direct local-network or mesh-VPN connections to transfer clipboard text and images.</string>
  <key>NSAppleEventsUsageDescription</key><string>BinderClip reads the active tab URL from your web browser when sharing browser tabs.</string>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>BinderClip syncs clipboard text over Bluetooth when Wi-Fi and mesh are unavailable.</string>
  <key>NSBonjourServices</key>
  <array>
    <string>_binderclip._tcp</string>
  </array>
  <key>SUFeedURL</key><string>$FEED_URL</string>
  <key>SUPublicEDKey</key><string>$SPARKLE_PUBLIC_ED_KEY</string>
  <key>SUEnableAutomaticChecks</key><true/>
</dict></plist>
PLIST

install_name_tool -add_rpath '@loader_path/../Frameworks' "$APP_PATH/Contents/MacOS/BinderClip"
codesign --force --deep --sign - "$APP_PATH"
codesign --verify --deep --strict "$APP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$OUTPUT_DIR/BinderClip-$VERSION.zip"

DMG_STAGING="$BUILD_ROOT/dmg-root"
mkdir -p "$DMG_STAGING"
cp -R "$APP_PATH" "$DMG_STAGING/BinderClip.app"
ln -s /Applications "$DMG_STAGING/Applications"
hdiutil create -volname "BinderClip $VERSION" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" >/dev/null
rm -rf "$APP_PATH"
print "Created $DMG_PATH"
