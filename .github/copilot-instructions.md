# ClipSync Development Guide

## Project Overview

ClipSync is a real-time cross-platform clipboard synchronization application with three main components:

- **Android**: Kotlin + Jetpack Compose UI, Accessibility Service for clipboard detection
- **macOS**: SwiftUI native application
- **Cloud Backend**: Firebase Cloud Functions for cleanup tasks and Firestore for data sync

Data flows bidirectionally: accessibility services monitor clipboard changes, encrypt content, and sync to Firestore. Paired devices receive push notifications (FCM) and update their local clipboard in real-time.

## Build & Test Commands

### Android (Gradle)

```bash
# Build the app
cd android && ./gradlew build

# Run tests
./gradlew test

# Run a single test
./gradlew testDebugUnitTest --tests "com.bunty.clipsync.YourTestClass"

# Build for release (minified)
./gradlew assemble Release

# View available tasks
./gradlew tasks
```

### macOS (Xcode)

The project is configured as an Xcode project. Build and test via Xcode IDE or:

```bash
cd mac
xcodebuild build
xcodebuild test
```

### Firebase Cloud Functions (Node.js)

```bash
# Navigate to functions directory
cd functions

# Install/update dependencies
npm install

# Lint code (Google ESLint config)
npm run lint

# Fix linting errors automatically
npx eslint . --fix

# Start local emulator
npm run serve

# Deploy to Firebase
npm run deploy

# View production logs
npm run logs
```

## High-Level Architecture

### Android (`android/app/src/main/java/com/bunty/clipsync/`)

**Core Detection Engine:**
- `ClipboardAccessibilityService.kt` — Listens to system accessibility events to detect clipboard changes. Uses a multi-stage detection strategy:
  1. ACTION_COPY action ID (language-independent)
  2. Toast notifications containing localized "copied" words (22 languages)
  3. Click/window events with copy-related text
  4. DFS traversal of accessibility node tree as fallback
- Implements echo-loop prevention via `ignoreNextChange` flag when Mac→Android writes occur
- Encrypts clipboard content before uploading to Firestore

**Sync & Communication:**
- `Connection.kt` — UI screen for QR-code pairing; connects Android to paired Mac via Firestore device ID
- `ClipboardAccessibilityService` watches real-time Firestore listener for inbound clipboard items from the Mac
- `FCMTokenManager.kt` — Manages FCM token registration for push notifications
- `MyFirebaseMessagingService.kt` — Handles incoming push notifications from the Mac

**UI Components (Compose):**
- `MainActivity.kt` — Main entry point with NavHost for screen routing
- `LandingScreen.kt` — Initial pairing interface
- `Permission.kt` — Runtime permissions request screen
- `HelperUtils.kt`, `MeshBackground.kt`, `CameraQRScanner.kt` — Shared utilities

**Services:**
- `OTPNotificationService.kt` — OTP extraction and display (for 2FA via email/SMS)
- `EmailOTPListenerService.kt` — Monitors accessibility events for email-based OTPs

### macOS (`mac/ClipSync/`)

**Core Clipboard Monitoring:**
- `ClipboardManager.swift` — Polls system clipboard in a background thread (no accessibility service needed on macOS)
- Detects changes, encrypts, and pushes to Firestore
- Implements inbound listener to apply Mac→Android writes

**Device & Pairing:**
- `DeviceManager.swift` — Generates/persists stable device UUID and derives friendly name (e.g. "John's Mac")
- `PairingManager.swift` — Manages pairing state: pending → connected → setup → done
- `FCMTokenManager.swift` — Registers FCM token for push notifications from Android

**UI (SwiftUI):**
- `ClipSyncApp.swift` — Main app entry point
- `ContentView.swift` — Navigation routing logic
- `LandingScreen.swift` — Initial pairing screen with QR code generation
- `ConnectedScreen.swift` — Confirmation after successful pairing
- `Final.swift` — Permissions request screen (Accessibility, Notifications)
- `HomeScreen.swift` — Primary UI showing paired device and sync status
- `MenuBarView.swift` — Menu bar icon and controls

**Notifications & OTP:**
- `OTPNotificationManager.swift` — Displays OTP bubbles when 2FA codes are copied
- `UpdateNotificationManager.swift` — App update notifications

**Utilities:**
- `FirebaseManager.swift` — Firestore setup and shared references
- `QRGen.swift` / `QRCodeGenerator.swift` — QR code generation for pairing
- `Color+Hex.swift` — Color utilities

### Firebase Cloud Functions (`functions/index.js`)

**Scheduled Cleanup:**
- `cleanupClipboardItems` — Runs every 60 minutes, deletes clipboard items older than 8 hours (preventing storage bloat)
- `cleanupNotifications` — Runs every 60 minutes, deletes notifications older than 8 hours
- Both use batch deletion in groups of 10 to avoid memory overruns

**Firestore Schema:**
- Collections: `clipboardItems`, `notifications`, `devices`
- Cleanup functions recursively delete documents and their subcollections
- Cloud Functions initialized with 512 MiB memory, 540-second timeout

## Key Conventions

### Code Organization

- **Service/Manager pattern**: Platform-specific logic is isolated in Manager classes (e.g., `DeviceManager`, `ClipboardManager`, `FirebaseManager`)
- **Accessibility Service (Android only)**: Runs continuously as a system service; avoid spawning long-lived background threads within it
- **Real-time Firestore listeners**: Always clean up listeners when components destroy to prevent memory leaks
- **Echo-loop prevention**: When a device writes clipboard content from a remote source, set a flag to ignore the next detected change

### Kotlin (Android)

- **Jetpack Compose**: All UI is Compose-based; use `@Composable` for screens and components
- **Coroutines**: Use `rememberCoroutineScope()`, `LaunchedEffect`, `DisposableEffect` for lifecycle-aware async work
- **State management**: Use `remember { mutableStateOf(...) }` for local UI state; avoid ViewModel pattern (not in use)
- **Imports**: Use explicit Android imports; Firebase Admin SDK is not available (mobile client SDKs only)

### Swift (macOS)

- **SwiftUI only**: No Storyboards; all UI via SwiftUI
- **Property wrappers**: Use `@State`, `@StateObject`, `@ObservedObject` for UI state
- **Singleton patterns**: Manager classes use `static let shared = ManagerClass()` for shared instances
- **UserDefaults**: Persistent simple values (device ID, pairing status) stored in UserDefaults
- **String extensions**: Prefer extension methods on String (e.g., `String(firstWord)`) over utility functions

### JavaScript (Cloud Functions)

- **ESLint rules** (Google config):
  - Double quotes for strings (except template literals)
  - Prefer arrow callbacks
  - No restricted globals like `name`, `length`
  - JSDoc comments required for exported functions
- **Error handling**: Use try/catch for async operations; log errors before returning
- **Firestore operations**: Always set `disallowLegacyRuntimeConfig: true` in `firebase.json`
- **Batch operations**: Limit batch deletes to ~10 documents at a time to prevent out-of-memory errors

### Cross-Platform Conventions

- **Device IDs**: Unique UUID per device; persisted in platform-specific storage (SharedPreferences on Android, UserDefaults on macOS)
- **Firestore document structure**: Use device ID as the parent collection key; clipboard items nested under each device
- **Encryption**: Clipboard content is encrypted before Firestore upload (implementation in respective platforms)
- **Multilingual support (Android)**: Copy-detection words hardcoded for 22 languages; no network calls for translation
- **Comments**: Only comment non-obvious logic (e.g., multi-stage detection strategies, echo-loop prevention)

## Testing

### Android Unit Tests

Tests are in `android/app/src/test/java/com/bunty/clipsync/`. Framework is junit (4.13.2) with Espresso for UI.

```bash
# All tests
cd android && ./gradlew test

# Single test class
./gradlew testDebugUnitTest --tests "com.bunty.clipsync.YourTest"

# Single test method
./gradlew testDebugUnitTest --tests "com.bunty.clipsync.YourTest#testMethod"
```

### Firebase Emulator

Local testing of Cloud Functions:

```bash
cd functions && npm run serve
# Emulator UI available at localhost:4000
```

## GitHub Integration

This repository uses the **GitHub MCP (Model Context Protocol) server** to provide Copilot with direct access to:

- **Issues & Pull Requests** — Search, read, and analyze open/closed issues and PRs
- **Commits & History** — Review recent commits, branches, and code changes
- **Repository Metadata** — Access README, contributing guides, and configuration files
- **Workflows & Actions** — Check CI/CD status, logs, and workflow runs
- **Code Search** — Search for symbols, patterns, and implementations across the repo

Use this in your Copilot queries to ask questions like:
- "What's the latest status of issue #42?"
- "Show me recent commits to the Android module"
- "Search for all Firebase Firestore references"
- "Check if there are any failing CI checks"

## Important Notes

- **Android minimum SDK**: 31 (Android 12+)
- **macOS target**: Requires appropriate Swift version and Xcode; Catalyst compatibility not configured
- **Firebase setup**: `google-services.json` (Android) and `GoogleService-Info.plist` (macOS) required; not in repo
- **Accessibility service permissions**: Critical on Android; users must manually enable in system settings
- **Memory constraints**: Cloud Functions run with 512 MiB; batch operations carefully to avoid OOM
