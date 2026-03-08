


// FirebaseManager.swift
// Singleton that configures FirebaseApp on first access and exposes a shared
// Firestore instance. Reads the server region from UserDefaults to select the
// correct Firebase project via RegionConfig.

import Foundation
import FirebaseCore
import FirebaseFirestore

// MARK: - FirebaseManager

class FirebaseManager {
    static let shared = FirebaseManager()
    let db: Firestore

    /// Configures Firebase with the region-appropriate options (or default if none),
    /// then creates a Firestore instance with in-memory caching.
    private init() {
        if FirebaseApp.app() == nil {
            let region = UserDefaults.standard.string(forKey: "server_region") ?? "IN"

            if let options = RegionConfig.getOptions(for: region) {
                FirebaseApp.configure(options: options)
            } else {
                FirebaseApp.configure()
            }
        }

        db = Firestore.firestore()

        let settings = FirestoreSettings()
        settings.cacheSettings = MemoryCacheSettings()
        db.settings = settings

        testNetworkConnection()
    }


    private func testNetworkConnection() {
        guard let url = URL(string: "https://www.google.com") else { return }

        let task = URLSession.shared.dataTask(with: url) { _, _, _ in }
        task.resume()
    }

    var isReady: Bool {
        return FirebaseApp.app() != nil
    }

    var isAuthenticated: Bool {
        return true
    }


    /// Convenience accessor that returns a typed Firestore CollectionReference.
    func collection(_ path: String) -> CollectionReference {
        return db.collection(path)
    }
}
