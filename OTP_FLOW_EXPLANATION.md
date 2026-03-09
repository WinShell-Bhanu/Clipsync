# How OTP Detection Works in ClipSync Mac App

## Complete Flow Diagram

```
Android Phone (OTP detected)
       ↓
Firebase Firestore Collection: "notifications"
       ↓
Mac App Firebase Listener (OTPNotificationManager)
       ↓
Decrypt OTP → Copy to Clipboard
       ↓
Show OTP Bubble in Menu Bar
```

---

## Step-by-Step Breakdown

### 1. **App Launch** (ClipSyncApp.swift)
When the Mac app starts:
- Firebase is initialized via `FirebaseManager.shared`
- Pairing state is restored from UserDefaults
- **IF the device is already paired**, clipboard monitoring starts

### 2. **OTP Listener Activation** (ClipSyncApp.swift line 184)
When pairing is successful, the app observes the pairing state:
```swift
PairingManager.shared.$isPaired
    .sink { paired in
        if paired {
            OTPNotificationManager.shared.startListening()  // ← Starts here
        } else {
            OTPNotificationManager.shared.stopListening()
        }
    }
```

### 3. **Firebase Listener Setup** (OTPNotificationManager.swift)
`startListening()` creates a real-time Firebase listener:

```swift
listener = FirebaseManager.shared.db
    .collection("notifications")                    // Collection name
    .whereField("pairingId", isEqualTo: pairingId) // Filter by your pairing ID
    .whereField("type", isEqualTo: "OTP_NOTIFICATION") // Only OTP notifications
    .addSnapshotListener { snapshot, error in
        // Listen for new documents in real-time
    }
```

**What it does:**
- Monitors the `notifications` collection in Firestore
- Only looks for documents where:
  - `pairingId` matches your device pair
  - `type` equals "OTP_NOTIFICATION"
- Triggers automatically when Android phone adds a new OTP notification

### 4. **When Android Detects OTP**
The Android app:
1. Detects OTP from SMS/notification
2. Encrypts the OTP code using AES-GCM encryption
3. Creates a document in Firebase `notifications` collection:
   ```json
   {
     "pairingId": "your-pairing-id",
     "type": "OTP_NOTIFICATION",
     "encryptedOTP": "base64-encrypted-otp-code",
     "timestamp": "2026-02-14T10:30:00Z"
   }
   ```

### 5. **Mac App Receives Notification**
The Firebase listener instantly detects the new document:

**Age Check (OTPNotificationManager.swift line 71):**
```swift
let age = Date().timeIntervalSince(timestamp.dateValue())
if age < 30 {  // Only process OTPs less than 30 seconds old
    // Process OTP
}
```

**Decryption (line 75-77):**
```swift
if let encryptedOTP = data["encryptedOTP"] as? String,
   let decryptedOTP = self.decrypt(encryptedOTP),  // Decrypt using AES-GCM
   self.lastOTPCode != decryptedOTP {              // Avoid duplicates
    self.handleOTPDetected(otpCode: decryptedOTP)
}
```

### 6. **Handle OTP Detected** (line 86-105)
When a new OTP is received:

```swift
func handleOTPDetected(otpCode: String) {
    // 1. Copy to Mac clipboard
    let pasteboard = NSPasteboard.general
    pasteboard.setString(otpCode, forType: .string)
    
    // 2. Store OTP for 60 seconds
    self.lastOTPCode = otpCode
    self.lastOTPTime = Date()
    
    // 3. Show visual indicator
    self.showOTPIndicator = true
    
    // 4. Show bubble in menu bar
    self.pingMenuBar(with: otpCode)
    
    // 5. Play sound
    NSSound(named: "Tink")?.play()
    
    // 6. Show macOS notification
    self.showNotification(otpCode: otpCode)
}
```

### 7. **Show OTP Bubble** (pingMenuBar method, line 113-143)

**Menu Bar Icon Animation:**
```swift
// 1. Flash menu bar icon green for 0.5 seconds
button.contentTintColor = .systemGreen

// 2. Bounce animation
let animation = CAKeyframeAnimation(keyPath: "transform.scale")
animation.values = [1.0, 1.2, 0.9, 1.1, 1.0]
button.layer?.add(animation, forKey: "bounce")
```

**Create Bubble Window:**
```swift
// 3. Create the bubble window positioned below menu bar icon
let bubbleWindow = OTPBubbleWindow(otpCode: otpCode, statusItemButton: button)
self.currentBubbleWindow = bubbleWindow
bubbleWindow.makeKeyAndOrderFront(nil)

// 4. Auto-dismiss after 5.5 seconds
DispatchQueue.main.asyncAfter(deadline: .now() + 5.5) {
    window.contentView = nil
    window.close()
}
```

### 8. **OTP Bubble Display** (OTPNotificationBubble.swift)
The bubble window shows:
- **Tick animation** (Lottie animation plays once)
- **OTP Code** with privacy blur (hover to reveal)
- **"OTP Copied Successfully"** with shimmer effect
- **Glassmorphism design** (frosted glass effect)
- **Auto-dismisses** after 5 seconds

---

## Firebase Database Structure

**Collection:** `notifications`

**Document Fields:**
- `pairingId`: String - Links to your device pair
- `type`: String - "OTP_NOTIFICATION"
- `encryptedOTP`: String - Base64 encoded encrypted OTP
- `timestamp`: Timestamp - When OTP was detected

**Security:**
- OTP is encrypted using AES-GCM with a shared secret key
- Only processes OTPs less than 30 seconds old
- Prevents duplicate notifications

---

## Key Features

✅ **Real-time**: Firebase listener instantly detects new OTPs (no polling)
✅ **Encrypted**: OTP is encrypted in transit using AES-GCM
✅ **Privacy**: OTP code is blurred by default (hover to reveal)
✅ **Auto-dismiss**: Bubble disappears after 5 seconds
✅ **Recent OTP cache**: Can re-show bubble within 60 seconds
✅ **Visual feedback**: Menu bar icon flashes green and bounces
✅ **Audio feedback**: "Tink" sound plays
✅ **macOS notification**: System notification with OTP code
✅ **Resource cleanup**: Properly stops all animations to prevent CPU leak

---

## Performance Notes

- Firebase listener is only active when device is paired
- Listener automatically reconnects if connection drops (retry logic)
- OTP bubble animations stop properly to prevent CPU usage spike
- Window cleanup removes all SwiftUI content views
