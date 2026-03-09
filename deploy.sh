#!/usr/bin/env zsh
set -e

SCRIPT_DIR="${0:A:h}"
ANDROID_DIR="$SCRIPT_DIR/android"
MAC_DIR="$SCRIPT_DIR/mac"
BUILD_DIR="/tmp/clipsync_build"
DESKTOP="$HOME/Desktop"
PACKAGE="com.bunty.clipsync"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

# ── Colors ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
step()  { echo "${CYAN}▶ $1${NC}" }
ok()    { echo "${GREEN}✅ $1${NC}" }
warn()  { echo "${YELLOW}⚠️  $1${NC}" }
fail()  { echo "${RED}❌ $1${NC}"; exit 1 }

echo ""
echo "╔══════════════════════════════════════╗"
echo "║       ClipSync — Full Deploy         ║"
echo "╚══════════════════════════════════════╝"
echo ""

# ── 1. Android build ──────────────────────────────────────────────────────────
step "Building Android APK..."
cd "$ANDROID_DIR"
./gradlew assembleDebug --quiet || fail "Android build failed"
ok "Android build succeeded → $APK"

# ── 2. macOS build ────────────────────────────────────────────────────────────
step "Building macOS app..."
cd "$MAC_DIR"
xcodebuild \
  -project ClipSync.xcodeproj \
  -scheme ClipSync \
  -configuration Release \
  -derivedDataPath "$BUILD_DIR" \
  build 2>&1 | grep -E "error:|BUILD (SUCCEEDED|FAILED)" || true

APP_SRC="$BUILD_DIR/Build/Products/Release/ClipSync.app"
[[ -d "$APP_SRC" ]] || fail "macOS build failed — ClipSync.app not found"
ok "macOS build succeeded"

# ── 3. Deploy macOS app to Desktop ───────────────────────────────────────────
step "Deploying ClipSync.app to Desktop..."
APP_DEST="$DESKTOP/ClipSync.app"

# Kill running instance if any
if pgrep -x "ClipSync" &>/dev/null; then
  warn "ClipSync is running — killing it first"
  pkill -x "ClipSync" || true
  sleep 1
fi

rm -rf "$APP_DEST"
cp -R "$APP_SRC" "$APP_DEST"
ENTITLEMENTS="$MAC_DIR/ClipSync/ClipSync.entitlements"
codesign --force --deep --sign - --entitlements "$ENTITLEMENTS" "$APP_DEST" 2>/dev/null
ok "ClipSync.app deployed to ~/Desktop (with entitlements)"

# ── 4. ADB: check device ──────────────────────────────────────────────────────
step "Checking ADB device..."
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [[ -z "$DEVICE" ]]; then
  warn "No ADB device found — skipping Android deploy"
  warn "Connect via: adb connect 10.200.34.66:5555"
  echo ""
  ok "macOS deploy complete. Android skipped."
  exit 0
fi
ok "Device found: $DEVICE"

# ── 5. Uninstall old APK ──────────────────────────────────────────────────────
step "Uninstalling $PACKAGE from device..."
if adb -s "$DEVICE" shell pm list packages | grep -q "$PACKAGE"; then
  adb -s "$DEVICE" uninstall "$PACKAGE" > /dev/null
  ok "Old app uninstalled"
else
  warn "App not installed yet — skipping uninstall"
fi

# ── 6. Install new APK ────────────────────────────────────────────────────────
step "Installing new APK..."
adb -s "$DEVICE" install "$APK" | grep -E "Success|Failure" || fail "ADB install failed"
ok "New APK installed on $DEVICE"
# ── 7. Grant runtime permissions ──────────────────────────────────────────────────────
step "Granting runtime permissions..."
SDK=$(adb -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$SDK" -ge 33 ]]; then
  adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.READ_MEDIA_IMAGES 2>/dev/null && \
    ok "READ_MEDIA_IMAGES granted" || warn "Could not grant READ_MEDIA_IMAGES"
else
  adb -s "$DEVICE" shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null && \
    ok "READ_EXTERNAL_STORAGE granted" || warn "Could not grant READ_EXTERNAL_STORAGE"
fi
# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════╗"
echo "║         Deploy complete ✅           ║"
echo "╚══════════════════════════════════════╝"
echo ""
echo "  Mac app : ~/Desktop/ClipSync.app"
echo "  Android : $PACKAGE"
echo ""
echo "  Logcat  : adb logcat -c && adb logcat -s \"ImageTransfer:D\" \"ClipSync_Service:D\" \"ClipboardGhost:D\" -v time"
echo "  Mac log : log stream --predicate 'subsystem == \"com.OP.ClipSync\"' --level debug"
echo ""
