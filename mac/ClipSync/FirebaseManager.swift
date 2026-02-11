//
// FirebaseManager.swift
// ClipSync
//

import Foundation
import FirebaseCore
import FirebaseFirestore

class FirebaseManager {
    static let shared = FirebaseManager()
    let db: Firestore
    
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
    
    func collection(_ path: String) -> CollectionReference {
        return db.collection(path)
    }
}
