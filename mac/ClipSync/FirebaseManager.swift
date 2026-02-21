


import Foundation
import FirebaseCore
import FirebaseFirestore


// Purpose: Coordinator component that centralizes state, integration calls, and orchestration.
// Responsibilities: Encapsulates firebase manager behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class FirebaseManager {
    static let shared = FirebaseManager()
    let db: Firestore


    // Purpose: Initializes the type with required runtime state.
    // Parameters: No parameters.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
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


    // Purpose: Implements the test network connection operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the collection operation for this feature.
    // Parameters: path.
    // Returns: CollectionReference.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func collection(_ path: String) -> CollectionReference {
        return db.collection(path)
    }
}
