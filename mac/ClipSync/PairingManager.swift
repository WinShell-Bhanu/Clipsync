


import Foundation
import FirebaseFirestore
import Combine


// Purpose: Coordinator component that centralizes state, integration calls, and orchestration.
// Responsibilities: Encapsulates pairing manager behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class PairingManager: ObservableObject {
    static let shared = PairingManager()

    @Published var isPaired: Bool = UserDefaults.standard.string(forKey: "current_pairing_id") != nil
    @Published var pairedDeviceName: String = UserDefaults.standard.string(forKey: "paired_device_name") ?? ""
    @Published var pairingId: String? = UserDefaults.standard.string(forKey: "current_pairing_id")
    @Published var isSetupComplete: Bool = UserDefaults.standard.bool(forKey: "is_setup_complete")
    @Published var pairingError: String? = nil

    private var pairingListener: ListenerRegistration?
    private var unpairingListener: ListenerRegistration?
    private let db = FirebaseManager.shared.db
    private var listenStartTime: Date?


    // Purpose: Implements the listen for pairing operation for this feature.
    // Parameters: macDeviceId.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func listenForPairing(macDeviceId: String) {
        guard !isPaired else { return }

        listenStartTime = Date().addingTimeInterval(-3600)
        DispatchQueue.main.async { self.pairingError = nil }

        self.startFirestoreListener(macDeviceId: macDeviceId)
    }


    // Purpose: Starts firestore listener flow and required listeners.
    // Parameters: macDeviceId.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func startFirestoreListener(macDeviceId: String) {
        pairingListener = db.collection("pairings")
            .whereField("macId", isEqualTo: macDeviceId)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self = self else { return }

                if let error = error {
                    let nsError = error as NSError
                    if nsError.code == 7 {
                        DispatchQueue.main.async {
                            self.pairingError = "Permission denied. Check Firestore rules."
                        }
                    } else if nsError.code == 14 {
                        DispatchQueue.main.async {
                            self.pairingError = "Network error. Check connection."
                        }
                    }
                    return
                }

                guard let documents = snapshot?.documents else { return }

                if documents.isEmpty { return }

                self.processDocuments(documents)
            }
    }


    // Purpose: Implements the process documents operation for this feature.
    // Parameters: documents.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func processDocuments(_ documents: [QueryDocumentSnapshot]) {
        var validPairing: QueryDocumentSnapshot?

        for doc in documents {
            let data = doc.data()

            guard let timestamp = data["timestamp"] as? Timestamp else {
                continue
            }

            let pairingDate = timestamp.dateValue()

            if let startTime = self.listenStartTime {
                if pairingDate > startTime {
                    validPairing = doc
                    break
                }
            }
        }

        guard let pairingDoc = validPairing else { return }

        self.processPairingData(pairingDoc)
    }


    // Purpose: Implements the process pairing data operation for this feature.
    // Parameters: doc.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func processPairingData(_ doc: QueryDocumentSnapshot) {
        let data = doc.data()
        guard let androidDeviceName = data["androidDeviceName"] as? String else { return }
        let pairingId = doc.documentID

        DispatchQueue.main.async {
            self.pairingId = pairingId
            self.pairedDeviceName = androidDeviceName
            self.isPaired = true
            self.pairingError = nil
        }

        UserDefaults.standard.set(pairingId, forKey: "current_pairing_id")
        UserDefaults.standard.set(androidDeviceName, forKey: "paired_device_name")

        self.startMonitoringPairingStatus(pairingId: pairingId)

        self.pairingListener?.remove()
        self.pairingListener = nil
    }


    // Purpose: Starts monitoring pairing status flow and required listeners.
    // Parameters: pairingId.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func startMonitoringPairingStatus(pairingId: String) {
        unpairingListener?.remove()

        unpairingListener = db.collection("pairings").document(pairingId)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self = self else { return }

                if error != nil { return }

                if let snapshot = snapshot, !snapshot.exists {
                    self.unpair()
                }
            }
    }


    // Purpose: Stops listening flow and performs cleanup.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func stopListening() {
        pairingListener?.remove()
        pairingListener = nil
        listenStartTime = nil
    }


    // Purpose: Removes clear pairing data from current storage/context.
    // Parameters: onSuccess, onFailure.
    // Returns: Void =.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func clearPairing(onSuccess: @escaping () -> Void = {}, onFailure: @escaping (Error) -> Void = { _ in }) {
        guard let pairingId = self.pairingId else {
            unpair()
            onSuccess()
            return
        }

        db.collection("pairings")
            .document(pairingId)
            .delete { [weak self] error in
                if let error = error {
                    onFailure(error)
                } else {
                    self?.unpair()
                    onSuccess()
                }
            }
    }


    // Purpose: Implements the unpair operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func unpair() {
        unpairingListener?.remove()
        unpairingListener = nil

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.isPaired = false
            self.pairedDeviceName = ""
            self.pairingId = nil
            self.isSetupComplete = false
            self.pairingError = nil
        }

        UserDefaults.standard.removeObject(forKey: "current_pairing_id")
        UserDefaults.standard.removeObject(forKey: "paired_device_name")
        UserDefaults.standard.removeObject(forKey: "is_setup_complete")

        ClipboardManager.shared.clearHistory()
        ClipboardManager.shared.stopMonitoring()
        ClipboardManager.shared.stopListening()
    }


    // Purpose: Implements the restore pairing operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func restorePairing() {
        if let savedPairingId = UserDefaults.standard.string(forKey: "current_pairing_id"),
           let savedDeviceName = UserDefaults.standard.string(forKey: "paired_device_name") {

            let currentBootTime = getCurrentBootTime()
            let savedBootTime = UserDefaults.standard.double(forKey: "last_boot_time")

            if abs(currentBootTime - savedBootTime) > 120 {
                unpair()
                return
            }

            self.pairingId = savedPairingId
            self.pairedDeviceName = savedDeviceName
            self.isPaired = true

            self.startMonitoringPairingStatus(pairingId: savedPairingId)
            self.isSetupComplete = UserDefaults.standard.bool(forKey: "is_setup_complete")
        }
    }


    // Purpose: Implements the complete setup operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func completeSetup() {
        DispatchQueue.main.async {
            self.isSetupComplete = true
        }
        UserDefaults.standard.set(true, forKey: "is_setup_complete")
        UserDefaults.standard.set(getCurrentBootTime(), forKey: "last_boot_time")
    }


    // Purpose: Returns computed or stored current boot time.
    // Parameters: No parameters.
    // Returns: TimeInterval.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func getCurrentBootTime() -> TimeInterval {
        return Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime
    }
}
