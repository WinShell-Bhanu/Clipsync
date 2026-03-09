// KeychainHelper.swift
// Thin wrapper around SecItem* for storing the AES session key in the macOS Keychain
// instead of UserDefaults (which is a plain plist readable by any process running as
// the same user).
//
// Usage:
//   KeychainHelper.save("deadbeef...", for: "encryption_key")
//   let key = KeychainHelper.load(for: "encryption_key")
//   KeychainHelper.delete(for: "encryption_key")

import Foundation

enum KeychainHelper {

    private static let service = "com.OP.ClipSync.keys"

    // MARK: - Write

    /// Stores a UTF-8 string in the Keychain under the given account key.
    /// Silently overwrites any existing value for the same key.
    @discardableResult
    static func save(_ value: String, for account: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }

        // Delete any existing item first so SecItemAdd never returns errSecDuplicateItem.
        delete(for: account)

        let query: [String: Any] = [
            kSecClass        as String: kSecClassGenericPassword,
            kSecAttrService  as String: service,
            kSecAttrAccount  as String: account,
            kSecValueData    as String: data
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    // MARK: - Read

    /// Returns the value stored for `account`, or `nil` if nothing is stored yet.
    static func load(for account: String) -> String? {
        let query: [String: Any] = [
            kSecClass        as String: kSecClassGenericPassword,
            kSecAttrService  as String: service,
            kSecAttrAccount  as String: account,
            kSecReturnData   as String: true,
            kSecMatchLimit   as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - Delete

    /// Removes the Keychain item for `account`. Silently succeeds if nothing was stored.
    @discardableResult
    static func delete(for account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass       as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let status = SecItemDelete(query as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    // MARK: - Migration helper

    /// Migrates a value stored in UserDefaults to the Keychain.
    /// M1 fix: only removes from UserDefaults if the Keychain save succeeds,
    /// so the key is never lost if save() fails.
    static func migrateFromUserDefaults(udKey: String, keychainAccount: String) {
        guard let value = UserDefaults.standard.string(forKey: udKey) else { return }
        if load(for: keychainAccount) == nil {
            guard save(value, for: keychainAccount) else {
                // Save failed — leave the value in UserDefaults so it isn't lost.
                print("⚠️ KeychainHelper: migration of '\(udKey)' failed — keeping in UserDefaults")
                return
            }
        }
        UserDefaults.standard.removeObject(forKey: udKey)
    }
}
