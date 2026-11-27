#!/bin/zsh

# Build BinderClip.app and a DMG for Apple Silicon.

set -euo pipefail

SCRIPT_DIR="${0:A:h}"
if [[ -z "${MACOS_SIGNING_IDENTITY:-}" ]]; then
  exec python3 "$SCRIPT_DIR/mac-signing.py" run -- "$SCRIPT_DIR/package-release.sh" "$@"
fi
PACKAGE_DIR="$SCRIPT_DIR/BinderClipMac"
source "$SCRIPT_DIR/info-plist.sh"
RELEASE_REF="${GITHUB_REF_NAME:-}"
GIT_TAG_VERSION="$(git describe --tags --abbrev=0 --match "v[0-9]*" --match "macos-v[0-9]*" 2>/dev/null | sed -E 's/^(macos-|android-)?v?//' || echo "1.0.0")"
GIT_COMMIT_COUNT="$(git rev-list --count HEAD 2>/dev/null || echo "1")"
VERSION="${VERSION:-$(echo "${RELEASE_REF}" | sed -E 's/^(macos-|android-)?v?//')}"
VERSION="${VERSION:-$GIT_TAG_VERSION}"
# Commit count on main is monotonic and matches local builds, so Sparkle and Android upgrades never go backwards.
BUILD_NUMBER="${BUILD_NUMBER:-$GIT_COMMIT_COUNT}"
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

ARM_EXECUTABLE="$BUILD_ROOT/arm64/release/BinderClip"
[[ -x "$ARM_EXECUTABLE" && "$(lipo -archs "$ARM_EXECUTABLE")" == arm64 ]] || {
  print -u2 "Expected an Apple Silicon BinderClip executable at $ARM_EXECUTABLE"
  exit 1
}

mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources" "$APP_PATH/Contents/Frameworks" "$OUTPUT_DIR"
cp "$ARM_EXECUTABLE" "$APP_PATH/Contents/MacOS/BinderClip"

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
cp "$PACKAGE_DIR/../AppIcon/Assets.car" "$APP_PATH/Contents/Resources/Assets.car"
cp "$PACKAGE_DIR/Resources/BinderClipMenuIcon.svg" "$APP_PATH/Contents/Resources/BinderClipMenuIcon.svg"
cp -R "$PACKAGE_DIR"/Resources/*.lproj "$APP_PATH/Contents/Resources/"
chmod 755 "$APP_PATH/Contents/MacOS/BinderClip"

write_info_plist "$APP_PATH/Contents/Info.plist" BinderClip net.wastu.binderclip production _binderclip._tcp \
  "$VERSION" "$BUILD_NUMBER" "  <key>SUFeedURL</key><string>$FEED_URL</string>
  <key>SUPublicEDKey</key><string>$SPARKLE_PUBLIC_ED_KEY</string>
  <key>SUEnableAutomaticChecks</key><true/>"

install_name_tool -add_rpath '@loader_path/../Frameworks' "$APP_PATH/Contents/MacOS/BinderClip"
identity="${MACOS_SIGNING_IDENTITY:--}"
signing_args=(--force --deep --sign "$identity")
if [[ "$identity" != - ]]; then
  signing_args+=(--timestamp=none)
  [[ -z "${MACOS_SIGNING_KEYCHAIN:-}" ]] || signing_args+=(--keychain "$MACOS_SIGNING_KEYCHAIN")
fi
codesign "${signing_args[@]}" "$APP_PATH"
codesign --verify --deep --strict "$APP_PATH"
if [[ "$identity" != - ]]; then
  codesign --verify -R "=certificate leaf = H\"$identity\"" "$APP_PATH"
fi
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "$OUTPUT_DIR/BinderClip-$VERSION.zip"

DMG_STAGING="$BUILD_ROOT/dmg-root"
mkdir -p "$DMG_STAGING"
cp -R "$APP_PATH" "$DMG_STAGING/BinderClip.app"
ln -s /Applications "$DMG_STAGING/Applications"
hdiutil create -volname "BinderClip $VERSION" -srcfolder "$DMG_STAGING" -ov -format UDZO "$DMG_PATH" >/dev/null
rm -rf "$APP_PATH"
print "Created $DMG_PATH"
