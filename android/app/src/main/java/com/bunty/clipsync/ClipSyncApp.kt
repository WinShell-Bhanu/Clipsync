package com.bunty.clipsync

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class ClipSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            val usOptions = RegionConfig.getOptionsForRegion(this, RegionConfig.REGION_US)
            if (usOptions != null) {
                FirebaseApp.initializeApp(this, usOptions, "ClipSyncUS")
            }
            
            signInAnonymously()
            
        } catch (e: Exception) {
             Log.e("ClipSync", "Failed to initialize Firebase: ${e.message}")
        }
        DeviceManager.getDeviceId(this)
    }

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
