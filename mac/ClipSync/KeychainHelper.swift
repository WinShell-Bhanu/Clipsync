// KeychainHelper.swift
// Provides secure storage for the AES-256 encryption key using the macOS Keychain
// instead of UserDefaults. Includes one-time migration from UserDefaults.

import Foundation
import Security

enum KeychainHelper {
    private static let service = "com.clipsync.encryption"

    // MARK: - Legacy API Compatibility

    /// Retrieves the encryption key, migrating from UserDefaults on first access if needed.
    static func getEncryptionKey() -> String? {
        migrateFromUserDefaults(udKey: "encryption_key", keychainAccount: "encryption_key")
        return load(for: "encryption_key")
    }

    /// Stores a new encryption key in the Keychain.
    @discardableResult
    static func setEncryptionKey(_ key: String) -> Bool {
        return save(key, for: "encryption_key")
    }

    // MARK: - Generic Public API

    /// Migrates a value from UserDefaults to the Keychain, deleting it from UserDefaults if successful.
    static func migrateFromUserDefaults(udKey: String, keychainAccount: String) {
        if let legacyValue = UserDefaults.standard.string(forKey: udKey) {
            if save(legacyValue, for: keychainAccount) {
                UserDefaults.standard.removeObject(forKey: udKey)
            }
        }
    }

    /// Retrieves a string value from the Keychain for the given account.
    static func load(for account: String) -> String? {
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

    /// Stores a string value in the Keychain for the given account.
    @discardableResult
    static func save(_ value: String, for account: String) -> Bool {
        delete(for: account)

        guard let data = value.data(using: .utf8) else { return false }

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

    /// Deletes a string value from the Keychain for the given account.
    static func delete(for account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]

        SecItemDelete(query as CFDictionary)
    }
}
