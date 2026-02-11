//
// OTPNotificationManager.swift
// ClipSync - OTP Menu Bar Ping
//

import Foundation
import FirebaseFirestore
import AppKit
import Combine
import CryptoKit

class OTPNotificationManager: ObservableObject {
    static let shared = OTPNotificationManager()
    private var listener: ListenerRegistration?
    @Published var lastOTPCode: String? = nil
    @Published var showOTPIndicator = false
    private var lastOTPTime: Date? // Track when the last OTP was received
    
    // Direct reference to AppDelegate to avoid casting issues
    weak var delegate: AppDelegate?
    
    // Keep strong reference to bubble window to prevent premature deallocation
    private var currentBubbleWindow: OTPBubbleWindow?
    
    // Encryption key (same as clipboard)
    private var sharedSecretHex: String {
        return UserDefaults.standard.string(forKey: "encryption_key") ?? Secrets.fallbackEncryptionKey
    }
    
    // Computed property to check if we have a recent OTP (within 60 seconds)
    var hasRecentOTP: Bool {
        guard let lastTime = lastOTPTime, lastOTPCode != nil else { return false }
        return Date().timeIntervalSince(lastTime) < 60
    }
    
    private init() {}
    
    func startListening(retryCount: Int = 0) {
        guard let pairingId = PairingManager.shared.pairingId else {
            if retryCount < 5 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                    self?.startListening(retryCount: retryCount + 1)
                }
            }
            return
        }
        
        listener?.remove()
        
        listener = FirebaseManager.shared.db
            .collection("notifications")
            .whereField("pairingId", isEqualTo: pairingId)
            .whereField("type", isEqualTo: "OTP_NOTIFICATION")
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self = self else { return }
                
                if let error = error {
                    print("OTP listener error: \(error)")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
                        self?.startListening(retryCount: 0)
                    }
                    return
                }
                
                guard let documents = snapshot?.documents else { return }
                
                for document in documents {
                    let data = document.data()
                    
                    if let timestamp = data["timestamp"] as? Timestamp {
                        let age = Date().timeIntervalSince(timestamp.dateValue())
                        
                        if age < 30 {
                            if let encryptedOTP = data["encryptedOTP"] as? String,
                               let decryptedOTP = self.decrypt(encryptedOTP),
                               self.lastOTPCode != decryptedOTP {
                                self.handleOTPDetected(otpCode: decryptedOTP)
                            }
                        }
                    }
                }
            }
    }
    
    private func handleOTPDetected(otpCode: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            let pasteboard = NSPasteboard.general
            pasteboard.clearContents()
            pasteboard.setString(otpCode, forType: .string)
            
            self.lastOTPCode = otpCode
            self.lastOTPTime = Date()
            self.showOTPIndicator = true
            
            self.pingMenuBar(with: otpCode)
            NSSound(named: "Tink")?.play()
            self.showNotification(otpCode: otpCode)
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
                self?.showOTPIndicator = false
            }
        }
    }
    
    func reshowLastBubble() {
        guard let otpCode = lastOTPCode, hasRecentOTP else { return }
        pingMenuBar(with: otpCode)
    }
    
    private func pingMenuBar(with otpCode: String) {
        guard let appDelegate = self.delegate,
              let button = appDelegate.statusItem?.button else {
            return
        }
        
        button.contentTintColor = .systemGreen
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak button] in
            button?.contentTintColor = nil
        }
        
        if let existingWindow = currentBubbleWindow {
            existingWindow.contentView = nil
            existingWindow.close()
            currentBubbleWindow = nil
        }
        
        let bubbleWindow = OTPBubbleWindow(otpCode: otpCode, statusItemButton: button)
        self.currentBubbleWindow = bubbleWindow
        bubbleWindow.makeKeyAndOrderFront(nil)
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.5) { [weak self] in
            if let window = self?.currentBubbleWindow {
                window.contentView = nil
                window.close()
            }
            self?.currentBubbleWindow = nil
        }
        
        animateMenuBarIcon(button: button)
    }
    
    private func animateMenuBarIcon(button: NSStatusBarButton) {
        let animation = CAKeyframeAnimation(keyPath: "transform.scale")
        animation.values = [1.0, 1.2, 0.9, 1.1, 1.0]
        animation.keyTimes = [0, 0.2, 0.4, 0.6, 0.8]
        animation.duration = 0.5
        animation.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        
        button.layer?.add(animation, forKey: "bounce")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak button] in
            button?.layer?.removeAnimation(forKey: "bounce")
        }
    }
    
    private func showNotification(otpCode: String) {
        let notification = NSUserNotification()
        notification.title = "OTP Copied"
        notification.informativeText = "Code \(otpCode) copied from Android"
        notification.soundName = NSUserNotificationDefaultSoundName
        
        NSUserNotificationCenter.default.deliver(notification)
    }
    
    func stopListening() {
        listener?.remove()
        listener = nil
    }
    
    deinit {
        stopListening()
    }
    
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
