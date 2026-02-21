


import Foundation
import AppKit
import FirebaseFirestore
import Combine
import CryptoKit


// Purpose: Coordinator component that centralizes state, integration calls, and orchestration.
// Responsibilities: Encapsulates clipboard manager behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class ClipboardManager: ObservableObject {
    static let shared = ClipboardManager()

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


    // Purpose: Starts monitoring flow and required listeners.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the toggle sync operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the pull clipboard operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func pullClipboard() {
        stopListening()
        listenForAndroidClipboard()
    }


    // Purpose: Removes clear history data from current storage/context.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func clearHistory() {
        history.removeAll()
    }


    // Purpose: Stops monitoring flow and performs cleanup.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func stopMonitoring() {
        timer?.cancel()
        timer = nil
    }


    // Purpose: Implements the check clipboard operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the upload clipboard operation for this feature.
    // Parameters: text.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the listen for android clipboard operation for this feature.
    // Parameters: retryCount.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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

                let content = self.decrypt(encryptedContent) ?? encryptedContent

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


    // Purpose: Starts listener watchdog flow and required listeners.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Stops listening flow and performs cleanup.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func stopListening() {
        watchdogTimer?.invalidate()
        watchdogTimer = nil
        clipboardListener?.remove()
        clipboardListener = nil
        isListenerActive = false
    }


    // Purpose: Implements the encrypt operation for this feature.
    // Parameters: string.
    // Returns: String?.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the decrypt operation for this feature.
    // Parameters: base64String.
    // Returns: String?.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the hex to data operation for this feature.
    // Parameters: hex.
    // Returns: Data.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


