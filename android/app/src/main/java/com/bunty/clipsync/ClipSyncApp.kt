package com.bunty.clipsync

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/**
 * ClipSyncApp is the custom [Application] class for the entire app.
 *
 * Responsibilities:
 * - Initialises the secondary Firebase project for the US Firestore region ("ClipSyncUS")
 *   at startup so it is ready before any screen is shown.
 * - Signs in the user anonymously so Firestore security rules (which require auth) are satisfied
 *   without asking the user to create an account.
 * - Seeds the device ID via [DeviceManager.getDeviceId] so it is always available.
 *
 * Declared in AndroidManifest.xml as `android:name=".ClipSyncApp"`.
 */
class ClipSyncApp : Application() {

    /**
     * Called once when the process starts, before any Activity, Service, or BroadcastReceiver.
     * Sets up Firebase and anonymous auth as early as possible.
     */
    override fun onCreate() {
        super.onCreate()

        try {
            // Initialise the US Firebase project as a named secondary app ("ClipSyncUS").
            // The default Firebase app (for the IN region) is initialised automatically via google-services.json.
            val usOptions = RegionConfig.getOptionsForRegion(this, RegionConfig.REGION_US)
            if (usOptions != null) {
                FirebaseApp.initializeApp(this, usOptions, "ClipSyncUS")
            }

            // Sign in anonymously so Firestore security rules are satisfied
            signInAnonymously()

        } catch (e: Exception) {
             Log.e("ClipSync", "Failed to initialize Firebase: ${e.message}")
        }

        // Generate and persist the unique device ID on first launch
        DeviceManager.getDeviceId(this)
    }

    /**
     * Signs the user into Firebase anonymously if they are not already signed in.
     *
     * ClipSync uses Firebase Anonymous Auth purely to satisfy Firestore security rules —
     * no personal data is collected and no account is shown to the user.
     */
    private fun signInAnonymously() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnFailureListener {
                    Log.e("ClipSync", "Anonymous Auth Failed", it)
                }
        }
    }
}
