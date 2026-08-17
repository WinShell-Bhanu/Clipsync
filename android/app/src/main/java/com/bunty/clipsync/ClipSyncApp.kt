package com.bunty.clipsync

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

/**
 * ClipSyncApp is the custom [Application] subclass that serves as the process-wide entry
 * point for the Android app. The Android runtime instantiates this class exactly once,
 * before any Activity, Service, or BroadcastReceiver is created, making it the canonical
 * place for initialisation work that must complete before any component runs.
 *
 * This class performs three startup tasks:
 *
 * 1. **Secondary Firebase initialisation** — ClipSync maintains two independent Firebase
 *    projects to minimise Firestore latency: one in India and one in the US. The India
 *    project is wired up automatically via `google-services.json`, but the US project
 *    must be registered manually as a named app (`"ClipSyncUS"`) using [FirebaseApp.initializeApp]
 *    before any component attempts a Firestore read or write.
 *
 * 2. **Anonymous authentication** — Firestore security rules require every incoming request
 *    to carry a valid Firebase Auth token. Because ClipSync has no user accounts, it
 *    obtains a token silently via Firebase Anonymous Auth. The user is never shown a
 *    sign-in prompt. The auth state persists across restarts, so the network round-trip
 *    only occurs once per app installation.
 *
 * 3. **Device ID seeding** — [DeviceManager.getDeviceId] generates and stores a stable UUID
 *    for this device on the very first launch. Calling it here guarantees the ID exists
 *    and is cached in SharedPreferences before any other component needs it.
 *
 * Declare this class in `AndroidManifest.xml` with `android:name=".ClipSyncApp"`.
 */
class ClipSyncApp : Application() {

    /**
     * Invoked by the Android runtime at process start, before any UI component is shown.
     * All three startup tasks — Firebase init, anonymous auth, and device ID seeding — are
     * triggered here. The Firebase work is wrapped in a try-catch so that a transient failure
     * (e.g. missing config, no network) does not crash the process at launch; the app will
     * simply degrade gracefully until the next opportunity to retry.
     */
    override fun onCreate() {
        super.onCreate()

        try {
            // Build FirebaseOptions for the US project. The India project is already handled
            // automatically by the Firebase SDK reading google-services.json, so only the
            // US secondary project needs an explicit call to FirebaseApp.initializeApp.
            val usOptions = RegionConfig.getOptionsForRegion(this, RegionConfig.REGION_US)
            if (usOptions != null) {
                // Register the US project under the well-known alias "ClipSyncUS" so any
                // component can retrieve it later via FirebaseApp.getInstance("ClipSyncUS").
                FirebaseApp.initializeApp(this, usOptions, "ClipSyncUS")
            }

            // Acquire a Firebase Auth token silently so Firestore security rules are met
            // from the very first database operation attempted anywhere in the app.
            signInAnonymously()

        } catch (e: Exception) {
            // Log and swallow: a Firebase init failure should not prevent the app from
            // launching. Features that depend on Firestore will fail individually instead.
             Log.e("ClipSync", "Failed to initialize Firebase: ${e.message}")
        }

        // Trigger device ID generation on first launch. The call is cheap on subsequent
        // launches (just a SharedPreferences read) so there is no cost to calling it here.
        DeviceManager.getDeviceId(this)
    }

    /**
     * Performs a silent Firebase Anonymous Auth sign-in if no authenticated session exists.
     *
     * Anonymous auth is used solely to satisfy Firestore security rules that require a valid
     * [FirebaseAuth] token on every request. No personal information is associated with the
     * anonymous account, no UI is shown, and the authenticated state persists across app
     * restarts — meaning the actual network call to Firebase is only made once per device
     * installation unless the app data is cleared.
     *
     * Failures are logged but not propagated. If auth fails, subsequent Firestore writes
     * will be rejected by security rules; the app continues running and can retry later.
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
