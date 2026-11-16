# Sourced by debug-install.sh and package-release.sh so both bundles share one Info.plist.
# Usage: write_info_plist <path> <name> <bundle-id> <environment> <bonjour-type> <version> <build> <sparkle-keys-xml>
write_info_plist() {
  local out="$1" name="$2" bundle_id="$3" environment="$4" bonjour="$5" version="$6" build="$7" sparkle="$8"
  cat > "$out" <<PLIST
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
  <key>CFBundleDisplayName</key><string>$name</string>
  <key>CFBundleExecutable</key><string>BinderClip</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>CFBundleIdentifier</key><string>$bundle_id</string>
  <key>BinderClipEnvironment</key><string>$environment</string>
  <key>CFBundleName</key><string>$name</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$version</string>
  <key>CFBundleVersion</key><string>$build</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>BinderClip uses direct local-network or mesh-VPN connections to transfer clipboard text and images.</string>
  <key>NSAppleEventsUsageDescription</key><string>BinderClip reads the active tab URL from your web browser when sharing browser tabs.</string>
  <key>NSBluetoothAlwaysUsageDescription</key>
  <string>BinderClip syncs clipboard text over Bluetooth when Wi-Fi and mesh are unavailable.</string>
  <key>NSBonjourServices</key>
  <array>
    <string>$bonjour</string>
  </array>
  <key>NSServices</key>
  <array>
    <dict>
      <key>NSMenuItem</key>
      <dict>
        <key>default</key>
        <string>Send to Phones via $name</string>
      </dict>
      <key>NSMessage</key>
      <string>sendToPhones</string>
      <key>NSPortName</key>
      <string>$name</string>
      <key>NSSendTypes</key>
      <array>
        <string>NSStringPboardType</string>
        <string>NSFilenamesPboardType</string>
        <string>public.url</string>
        <string>public.plain-text</string>
        <string>public.image</string>
      </array>
    </dict>
  </array>
$sparkle
</dict></plist>
PLIST
}
