package com.bunty.clipsync

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data.isNotEmpty()) {
            val clipboardContent = remoteMessage.data["content"]
            if (!clipboardContent.isNullOrEmpty()) {
                val isOtp = HelperUtils.isOTP(clipboardContent)
                NotificationHelper(applicationContext).showClipboardNotification(clipboardContent, isOtp)
            }
        }
    }
}
