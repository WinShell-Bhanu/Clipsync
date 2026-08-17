// KeychainHelper.swift
// Provides secure storage for the AES-256 encryption key using the macOS Keychain
// instead of UserDefaults. Includes one-time migration from UserDefaults.

import Foundation
import Security

enum KeychainHelper {
    private static let service = "com.clipsync.encryption"
    private static let account = "encryption_key"

    // MARK: - Public API

    /// Retrieves the encryption key, migrating from UserDefaults on first access if needed.
    static func getEncryptionKey() -> String? {
        if let key = readFromKeychain() {
            return key
        }

        // Migration: move key from UserDefaults to Keychain
        if let legacyKey = UserDefaults.standard.string(forKey: "encryption_key") {
            if saveToKeychain(legacyKey) {
                UserDefaults.standard.removeObject(forKey: "encryption_key")
            }
            return legacyKey
        }

        return nil
    }

    /// Stores a new encryption key in the Keychain.
    @discardableResult
    static func setEncryptionKey(_ key: String) -> Bool {
        // Delete any existing key first, then save
        deleteFromKeychain()
        return saveToKeychain(key)
    }

    // MARK: - Keychain Operations

    private static func readFromKeychain() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess,
              let data = result as? Data,
              let key = String(data: data, encoding: .utf8) else {
            return nil
        }

        return key
    }

    private static func saveToKeychain(_ key: String) -> Bool {
        guard let data = key.data(using: .utf8) else { return false }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    private static func deleteFromKeychain() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]

        SecItemDelete(query as CFDictionary)
    }
}
