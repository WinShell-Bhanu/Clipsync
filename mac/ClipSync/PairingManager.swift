


// PairingManager.swift
// Manages the full device pairing lifecycle: listening for a new QR-scan pairing
// from Android, persisting state to UserDefaults, watching for remote unpairs,
// and restoring a valid pairing on relaunch (within the same boot session).

import Foundation
import FirebaseFirestore
import Combine

// MARK: - PairingManager

class PairingManager: ObservableObject {
    static let shared = PairingManager()

    // MARK: - Published State

    @Published var isPaired: Bool = UserDefaults.standard.string(forKey: "current_pairing_id") != nil
    @Published var pairedDeviceName: String = UserDefaults.standard.string(forKey: "paired_device_name") ?? ""
    @Published var pairingId: String? = UserDefaults.standard.string(forKey: "current_pairing_id")
    @Published var isSetupComplete: Bool = UserDefaults.standard.bool(forKey: "is_setup_complete")
    @Published var pairingError: String? = nil

    private var pairingListener: ListenerRegistration?
    private var unpairingListener: ListenerRegistration?
    private let db = FirebaseManager.shared.db
    private var listenStartTime: Date?

    // MARK: - Pairing Listener

    /// Begins listening for a new pairing document in Firestore that matches this Mac's device ID.
    func listenForPairing(macDeviceId: String) {
        guard !isPaired else { return }

        listenStartTime = Date().addingTimeInterval(-3600)
        DispatchQueue.main.async { self.pairingError = nil }

        self.startFirestoreListener(macDeviceId: macDeviceId)
    }


    /// Creates (or replaces) the Firestore snapshot listener for pairing documents.
    private func startFirestoreListener(macDeviceId: String) {
        // Bug fix: always tear down the previous listener before creating a new one
        // so we never leak a dangling Firestore snapshot listener.
        pairingListener?.remove()
        pairingListener = nil

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


    /// Filters the snapshot documents to find a valid pairing created after listenStartTime.
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


    /// Extracts pairing fields from a Firestore document and updates published state + UserDefaults.
    private func processPairingData(_ doc: QueryDocumentSnapshot) {
        let data = doc.data()
        guard let androidDeviceName = data["androidDeviceName"] as? String else { return }
        let pairingId = doc.documentID

        DispatchQueue.main.async {
            self.pairingId = pairingId
            self.pairedDeviceName = androidDeviceName
            self.isPaired = true
            self.pairingError = nil
            
            ClipboardManager.shared.listenForAndroidClipboard()
        }

        UserDefaults.standard.set(pairingId, forKey: "current_pairing_id")
        UserDefaults.standard.set(androidDeviceName, forKey: "paired_device_name")

        self.startMonitoringPairingStatus(pairingId: pairingId)

        self.pairingListener?.remove()
        self.pairingListener = nil
    }


    // MARK: - Unpairing

    /// Watches the pairing document for deletion so the Mac can react to a remote unpair from Android.
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


    /// Tears down the active pairing Firestore listener.
    func stopListening() {
        pairingListener?.remove()
        pairingListener = nil
        listenStartTime = nil
    }


    /// Deletes the pairing document from Firestore, then calls unpair() regardless
    /// of whether the delete succeeded, so local state is always cleaned up.
    func clearPairing(onSuccess: @escaping () -> Void = {}, onFailure: @escaping (Error) -> Void = { _ in }) {
        guard let pairingId = self.pairingId else {
            unpair()
            DispatchQueue.main.async { onSuccess() }
            return
        }

        db.collection("pairings")
            .document(pairingId)
            .delete { [weak self] error in
                // Bug fix: always dispatch callbacks on main thread — callers set
                // @State / navigate, which must happen on main.
                DispatchQueue.main.async {
                    if let error = error {
                        // Bug fix: unpair() regardless of Firestore failure so local
                        // state is always cleaned up — no stale paired state left behind.
                        self?.unpair()
                        onFailure(error)
                    } else {
                        self?.unpair()
                        onSuccess()
                    }
                }
            }
    }


    /// Resets all in-memory paired state, wipes UserDefaults keys, and stops clipboard sync.
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
        ImageTransferManagerMac.shared.stop()

    // MARK: - Launch Restoration

    /// U1 fix: On launch, restores the saved pairing and validates it against Firestore.
    /// No longer compares boot times — a reboot should NOT unpair the user.
    func restorePairing() {
        if let savedPairingId = UserDefaults.standard.string(forKey: "current_pairing_id"),
           let savedDeviceName = UserDefaults.standard.string(forKey: "paired_device_name") {

            self.pairingId = savedPairingId
            self.pairedDeviceName = savedDeviceName
            self.isPaired = true
            self.isSetupComplete = UserDefaults.standard.bool(forKey: "is_setup_complete")

            // Validate the pairing still exists in Firestore; if the Android side
            // deleted it while the Mac was turned off, unpair gracefully.
            db.collection("pairings").document(savedPairingId).getDocument { [weak self] snapshot, error in
                guard let self = self else { return }
                if let snapshot = snapshot, snapshot.exists {
                    // Pairing is valid — start monitoring for remote unpairs
                    self.startMonitoringPairingStatus(pairingId: savedPairingId)
                } else {
                    // Pairing document was deleted remotely → clean up local state
                    self.unpair()
                }
            }
        }
    }


    /// Marks onboarding as done.
    func completeSetup() {
        DispatchQueue.main.async {
            self.isSetupComplete = true
        }
        UserDefaults.standard.set(true, forKey: "is_setup_complete")
    }
}
