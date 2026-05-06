


// ClipboardManager.swift
// Singleton that polls NSPasteboard every 300 ms for changes, encrypts and uploads
// content to Firestore, and listens for incoming clipboard items from Android.
// Uses AES-GCM with a shared key stored in UserDefaults.

import Foundation
import AppKit
import FirebaseFirestore
import Combine
import CryptoKit

// MARK: - ClipboardManager

class ClipboardManager: ObservableObject {
    static let shared = ClipboardManager()

    // MARK: - Properties

    @Published var history: [ClipboardItem] = []
    @Published var isSyncPaused: Bool = false
    @Published var lastSyncedTime: Date?

    var syncToMac: Bool {
        UserDefaults.standard.bool(forKey: "syncToMac")
    }
    var syncFromMac: Bool {
        UserDefaults.standard.bool(forKey: "syncFromMac")
    }

    private let pasteboard = NSPasteboard.general
    private var timer: DispatchSourceTimer?
    private var watchdogTimer: Timer?
    private var lastChangeCount = 0
    private var lastCopiedText: String = ""
    private var ignoreNextChange = false
    private let db = FirebaseManager.shared.db
    private var clipboardListener: ListenerRegistration?

    private var sharedSecretHex: String {
        return UserDefaults.standard.string(forKey: "encryption_key") ?? Secrets.fallbackEncryptionKey
    }

    private var isListenerActive = false
    private var lastListenerUpdate = Date()

    // MARK: - Monitoring

    /// Starts a 300 ms DispatchSource timer that polls NSPasteboard for changes.
    func startMonitoring() {
        if isSyncPaused { return }
        stopMonitoring()

        lastChangeCount = pasteboard.changeCount

        let queue = DispatchQueue(label: "com.clipsync.clipboard.monitor", qos: .userInitiated)
        let newTimer = DispatchSource.makeTimerSource(queue: queue)

        newTimer.schedule(deadline: .now(), repeating: .milliseconds(300), leeway: .milliseconds(50))

        newTimer.setEventHandler { [weak self] in
            self?.checkClipboard()
        }

        newTimer.resume()
        timer = newTimer
    }


    /// Toggles sync on/off, stopping or resuming both monitoring and the Firestore listener.
    func toggleSync() {
        isSyncPaused.toggle()
        if isSyncPaused {
            stopMonitoring()
            stopListening()
        } else {
            startMonitoring()
            listenForAndroidClipboard()
        }
    }


    /// Re-attaches the Firestore listener to immediately fetch the latest Android clipboard.
    func pullClipboard() {
        stopListening()
        listenForAndroidClipboard()
    }


    func clearHistory() {
        history.removeAll()
    }


    /// Cancels the polling timer.
    func stopMonitoring() {
        timer?.cancel()
        timer = nil
    }


    /// Compares the current pasteboard change count to detect new content, deduplicates,
    /// and calls uploadClipboard if the Mac→Android direction is enabled.
    private func checkClipboard() {
        let currentChangeCount = pasteboard.changeCount
        guard currentChangeCount != lastChangeCount else { return }
        lastChangeCount = currentChangeCount

        if ignoreNextChange {
            ignoreNextChange = false
            return
        }

        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            return
        }

        guard text != lastCopiedText else { return }
        lastCopiedText = text

        guard syncFromMac else { return }

        uploadClipboard(text: text)

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if let lastItem = self.history.first, lastItem.content == text {
                return
            }

            let newItem = ClipboardItem(
                content: text,
                timestamp: Date(),
                deviceName: "Mac",
                direction: .sent
            )
            self.history.insert(newItem, at: 0)
            self.lastSyncedTime = Date()
        }
    }


    // MARK: - Firebase Sync

    /// Encrypts the given text with AES-GCM and writes it to the `clipboardItems` collection.
    private func uploadClipboard(text: String) {
        guard let pairingId = PairingManager.shared.pairingId else { return }
        let macDeviceId = DeviceManager.shared.getDeviceId()

        guard let encryptedContent = encrypt(text) else { return }

        let clipboardData: [String: Any] = [
            "content": encryptedContent,
            "timestamp": FieldValue.serverTimestamp(),
            "pairingId": pairingId,
            "sourceDeviceId": macDeviceId,
            "type": "text"
        ]

        db.collection("clipboardItems").addDocument(data: clipboardData) { error in
            if let error = error {
                print("Error uploading clipboard: \(error)")
            }
        }
    }


    /// Attaches a Firestore snapshot listener for the most recent clipboard item from Android.
    /// Retries up to 5 times if pairingId is not yet available.
    func listenForAndroidClipboard(retryCount: Int = 0) {
        guard let pairingId = PairingManager.shared.pairingId else {
            if retryCount < 5 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                    self?.listenForAndroidClipboard(retryCount: retryCount + 1)
                }
            }
            return
        }

        stopListening()
        let macDeviceId = DeviceManager.shared.getDeviceId()

        isListenerActive = true
        lastListenerUpdate = Date()

        clipboardListener = db.collection("clipboardItems")
            .whereField("pairingId", isEqualTo: pairingId)
            .order(by: "timestamp", descending: true)
            .limit(to: 1)
            .addSnapshotListener(includeMetadataChanges: false) { [weak self] snapshot, error in
                guard let self = self else { return }
                self.lastListenerUpdate = Date()

                if self.isSyncPaused || !self.syncToMac { return }

                if error != nil {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                        self?.listenForAndroidClipboard(retryCount: 0)
                    }
                    return
                }

                guard let documents = snapshot?.documents, !documents.isEmpty else { return }
                let doc = documents[0].data()

                guard let encryptedContent = doc["content"] as? String,
                      let sourceDeviceId = doc["sourceDeviceId"] as? String,
                      sourceDeviceId != macDeviceId else { return }

                guard let content = self.decrypt(encryptedContent) else {
                    print("ClipboardManager: Decryption failed — skipping incoming clipboard item")
                    return
                }

                guard content != self.lastCopiedText else { return }

                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    self.ignoreNextChange = true
                    self.pasteboard.clearContents()
                    self.pasteboard.setString(content, forType: .string)
                    self.lastCopiedText = content

                    if let lastItem = self.history.first, lastItem.content == content { return }

                    let newItem = ClipboardItem(
                        content: content,
                        timestamp: Date(),
                        deviceName: PairingManager.shared.pairedDeviceName,
                        direction: .received
                    )
                    self.history.insert(newItem, at: 0)
                    self.lastSyncedTime = Date()
                }
            }

        startListenerWatchdog()
    }


    /// Schedules a repeating timer that restarts the Firestore listener if no update
    /// has been received in more than 60 seconds, guarding against silent disconnects.
    private func startListenerWatchdog() {
        watchdogTimer?.invalidate()

        watchdogTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }

            if !self.isListenerActive {
                timer.invalidate()
                return
            }

            if Date().timeIntervalSince(self.lastListenerUpdate) > 60 {
                self.listenForAndroidClipboard()
            }
        }
    }


    /// Removes the Firestore listener and invalidates the watchdog timer.
    func stopListening() {
        watchdogTimer?.invalidate()
        watchdogTimer = nil
        clipboardListener?.remove()
        clipboardListener = nil
        isListenerActive = false
    }


    // MARK: - Encryption

    /// AES-GCM encrypts a UTF-8 string and returns a Base64-encoded ciphertext.
    private func encrypt(_ string: String) -> String? {
        guard let data = string.data(using: .utf8) else { return nil }

        do {
            let keyData = hexToData(hex: sharedSecretHex)
            let key = SymmetricKey(data: keyData)
            let sealedBox = try AES.GCM.seal(data, using: key)
            return sealedBox.combined?.base64EncodedString()
        } catch {
            return nil
        }
    }


    /// AES-GCM decrypts a Base64-encoded ciphertext and returns the plain UTF-8 string.
    private func decrypt(_ base64String: String) -> String? {
        guard let data = Data(base64Encoded: base64String) else { return nil }

        do {
            let keyData = hexToData(hex: sharedSecretHex)
            let key = SymmetricKey(data: keyData)
            let sealedBox = try AES.GCM.SealedBox(combined: data)
            let decryptedData = try AES.GCM.open(sealedBox, using: key)
            return String(data: decryptedData, encoding: .utf8)
        } catch {
            return nil
        }
    }


    private func hexToData(hex: String) -> Data {
        var data = Data()
        var temp = ""
        for char in hex {
            temp.append(char)
            if temp.count == 2 {
                if let byte = UInt8(temp, radix: 16) {
                    data.append(byte)
                }
                temp = ""
            }
        }
        return data
    }
}


