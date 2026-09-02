#!/bin/zsh

# Build the same LSUIElement menu-bar app as production, then install it
# beside production (different bundle id / path) so it cannot replace
# /Applications/BinderClip.app.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
PACKAGE_DIR="$SCRIPT_DIR/BinderClipMac"
APP_NAME="BinderClip Debug.app"
BUNDLE_ID="net.wastu.binderclip.debug.mb"
PROD_BUNDLE_ID="net.wastu.binderclip"
OLD_DEBUG_BUNDLE_IDS=(
  "net.wastu.binderclip.debug"
  "net.wastu.binderclip.debug.v2"
)
INSTALL_DIR="$HOME/Applications"
INSTALL_PATH="$INSTALL_DIR/$APP_NAME"
STAGING_PATH="$INSTALL_DIR/.BinderClip Debug.app.new"
BACKUP_PATH="$INSTALL_DIR/.BinderClip Debug.app.previous"
VERSION="$(git describe --tags --always 2>/dev/null | sed 's/^v//' || echo "0.0.0-debug")"
BUILD_NUMBER="$(git rev-list --count HEAD 2>/dev/null || date +%s)"

usage() {
  print "Usage: $0 [--no-launch]"
  print "Build and replace ~/Applications/$APP_NAME as a menu-bar extra, then launch it."
}

launch=true
if [[ "${1:-}" == "--no-launch" ]]; then
  launch=false
elif [[ $# -gt 0 ]]; then
  usage >&2
  exit 2
fi

quit_bundle() {
  local bundle="$1"
  if osascript -e "tell application id \"$bundle\" to quit" >/dev/null 2>&1; then
    sleep 1
  fi
}

print "Building BinderClip debug executable..."
swift build --package-path "$PACKAGE_DIR" --configuration debug

EXECUTABLE="$PACKAGE_DIR/.build/debug/BinderClip"
[[ -x "$EXECUTABLE" ]] || { print -u2 "Build did not produce $EXECUTABLE"; exit 1; }

SPARKLE_FRAMEWORK="$PACKAGE_DIR/.build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework"
if [[ ! -d "$SPARKLE_FRAMEWORK" ]]; then
  SPARKLE_FRAMEWORK="$(find "$PACKAGE_DIR/.build" -name "Sparkle.framework" -type d | head -n 1)"
fi
[[ -d "$SPARKLE_FRAMEWORK" ]] || {
  print -u2 "Sparkle.framework was not produced"
  exit 1
}

mkdir -p "$INSTALL_DIR"
rm -rf "$STAGING_PATH"
mkdir -p "$STAGING_PATH/Contents/MacOS" "$STAGING_PATH/Contents/Resources" "$STAGING_PATH/Contents/Frameworks"

cp "$EXECUTABLE" "$STAGING_PATH/Contents/MacOS/BinderClip"
chmod 755 "$STAGING_PATH/Contents/MacOS/BinderClip"
ditto "$SPARKLE_FRAMEWORK" "$STAGING_PATH/Contents/Frameworks/Sparkle.framework"
cp "$PACKAGE_DIR/Resources/AppIcon.icns" "$STAGING_PATH/Contents/Resources/AppIcon.icns"
cp "$PACKAGE_DIR/Resources/BinderClipMenuIcon.svg" "$STAGING_PATH/Contents/Resources/BinderClipMenuIcon.svg"
cp -R "$PACKAGE_DIR"/Resources/*.lproj "$STAGING_PATH/Contents/Resources/"
print -n 'APPL????' > "$STAGING_PATH/Contents/PkgInfo"
install_name_tool -add_rpath '@loader_path/../Frameworks' "$STAGING_PATH/Contents/MacOS/BinderClip" 2>/dev/null || true

cat > "$STAGING_PATH/Contents/Info.plist" <<PLIST
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
  <key>CFBundleDisplayName</key><string>BinderClip Debug</string>
  <key>CFBundleExecutable</key><string>BinderClip</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
  <key>CFBundleName</key><string>BinderClip Debug</string>
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
  <key>SUEnableAutomaticChecks</key><false/>
</dict></plist>
PLIST

codesign --force --deep --sign - "$STAGING_PATH" >/dev/null

print "Stopping production BinderClip and the previous debug app..."
quit_bundle "$PROD_BUNDLE_ID"
quit_bundle "$BUNDLE_ID"
for old_id in "${OLD_DEBUG_BUNDLE_IDS[@]}"; do
  quit_bundle "$old_id"
done
if pgrep -f "$INSTALL_PATH/Contents/MacOS/BinderClip" >/dev/null 2>&1; then
  pkill -f "$INSTALL_PATH/Contents/MacOS/BinderClip" >/dev/null 2>&1 || true
  sleep 1
fi

rm -rf "$BACKUP_PATH"
if [[ -d "$INSTALL_PATH" ]]; then
  mv "$INSTALL_PATH" "$BACKUP_PATH"
fi
if ! mv "$STAGING_PATH" "$INSTALL_PATH"; then
  [[ -d "$BACKUP_PATH" ]] && mv "$BACKUP_PATH" "$INSTALL_PATH"
  exit 1
fi
rm -rf "$BACKUP_PATH"

print "Installed: $INSTALL_PATH"
# Do not keep a login LaunchAgent; it would start debug on every login.
if [[ -f "$HOME/Library/LaunchAgents/net.wastu.binderclip.debug.plist" ]]; then
  launchctl bootout "gui/$(id -u)/net.wastu.binderclip.debug" >/dev/null 2>&1 || true
  rm -f "$HOME/Library/LaunchAgents/net.wastu.binderclip.debug.plist"
fi
if $launch; then
  # Launch via Finder so Control Center does not attribute the extra to Cursor.
  osascript -e "tell application \"Finder\" to open POSIX file \"$INSTALL_PATH\"" >/dev/null
  print "Launched BinderClip Debug (menu extra)."
fi
