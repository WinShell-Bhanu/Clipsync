🔴 CRITICAL — Fixed

1. Plaintext fallback on encryption failure

Both FirestoreManager.encryptData() and OTPNotificationService.encryptOTP() had a comment saying "last-resort fallback" and silently sent the raw plaintext to Firestore when encryption failed. That means a bug in key parsing would have leaked every clipboard item and every OTP to Firebase unencrypted, with no indication anything was wrong.

Fixed in FirestoreManager.kt: encryptData now rethrows; sendClipboard catches and calls onFailure — the upload is aborted.

Fixed in OTPNotificationService.kt: encryptOTP returns String?; notifyOTPDetected returns early on null instead of sending plaintext.

🔴 HIGH — Fixed

2. Toast exposes OTP on screen

OTPListeningService and EmailOTPListenerService both showed the extracted OTP in a Toast:

Edited

index.js+8-5

Toasts are readable by:

Any app with SYSTEM_ALERT_WINDOW (screen overlay)

The accessibility tree (screen readers)

Screen capture when the notification shade is expanded

Fixed in OTPListeningService.kt: Toast removed, unused import android.widget.Toast removed.

Fixed in EmailOTPListenerService.kt: Same.

🟠 MEDIUM — Fixed

3. AES key backed up via ADB / cloud backup

backup_rules.xml and data_extraction_rules.xml were empty templates. With android:allowBackup="true", an attacker with temporary physical access + USB could run adb backup and extract clipsync_prefs.xml, which contains the 256-bit AES session key.

Fixed: both files now explicitly exclude clipsync_prefs.xml from cloud backup and device transfers.

4. android:usesCleartextTraffic="true" in Manifest

Fixed in AndroidManifest.xml: line removed. Firebase always uses TLS anyway; this attribute was only opening a door for misconfigured SDKs or debug HTTP calls to bypass TLS silently.

5. OTP records persist in Firestore for 8 hours

The cleanupNotifications Cloud Function was set to run every 60 minutes and delete items older than 8 hours. OTPs are valid for at most 10 minutes — keeping encrypted OTP ciphertexts in Firebase for 480 minutes is an unnecessary exposure window if the session key is ever compromised.

Fixed in index.js: schedule changed to every 30 minutes, TTL changed to 30 minutes.

🟠 MEDIUM — Manual fix needed (not auto-fixable)

6. AES session key stored in plain SharedPreferences (Android)

DeviceManager stores the 64-char hex key under clipsync_prefs using Context.MODE_PRIVATE. This is not hardware-protected — on a rooted device, the file is directly readable at /data/data/com.bunty.clipsync/shared_prefs/clipsync_prefs.xml.

Recommended fix: Migrate to EncryptedSharedPreferences backed by the Android Keystore. The key itself is then wrapped by a hardware-bound key that never leaves the secure element.

















7. AES session key stored in plain UserDefaults (Mac)

ClipboardManager.sharedSecretHex and OTPNotificationManager.sharedSecretHex both do:

















UserDefaults is a plain plist at ~/Library/Preferences/com.OP.ClipSync.plist — readable by any process running as the same user.

Recommended fix: Store in the macOS Keychain using SecItemAdd / SecItemCopyMatching. The KeychainHelper pattern is well-known in Swift.

🟡 LOW — Informational

8. FALLBACK_ENCRYPTION_KEY used before pairing

If a user copies text before completing a QR scan, DeviceManager.getEncryptionKey() falls back to Secrets.FALLBACK_ENCRYPTION_KEY. That key is in your source code. The data is "encrypted" but with a publicly known key.

Fix: Guard sendClipboard with a isPaired() check — refuse to upload if not yet paired: