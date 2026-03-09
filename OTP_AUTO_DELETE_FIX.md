# OTP Notifications Auto-Delete Issue - FIXED ✅

## Problem Found

Your OTP notifications were being **automatically deleted after 10 seconds** by the **Android app**, not by Firebase Cloud Functions!

### Location of Bug:
**File:** `/android/app/src/main/java/com/bunty/clipsync/OTPNotificationService.kt`
**Lines:** 51-54

### The Problematic Code:
```kotlin
FirestoreManager.getDb(context).collection("notifications")
    .add(notificationData)
    .addOnSuccessListener { documentReference ->
        GlobalScope.launch(Dispatchers.IO) {
            delay(10000)  // Wait 10 seconds
            documentReference.delete()  // ← DELETES THE NOTIFICATION!
        }
    }
```

**What was happening:**
1. Android detects OTP and creates notification document in Firestore
2. Mac app receives notification instantly via Firebase listener
3. Mac app shows bubble for 5 seconds
4. **After 10 seconds**, Android app deletes the notification document
5. This caused the entire "notifications" collection to appear empty

---

## The Fix ✅

**Removed the auto-delete logic** from the Android app. The notification now stays in Firestore until the Firebase Cloud Function cleans it up (when you deploy it).

### New Code:
```kotlin
FirestoreManager.getDb(context).collection("notifications")
    .add(notificationData)
    .addOnSuccessListener { documentReference ->
        Log.d(TAG, "OTP notification sent successfully: ${documentReference.id}")
    }
    .addOnFailureListener { exception ->
        Log.e(TAG, "Failed to send OTP notification", exception)
    }
```

---

## How It Works Now

### Timeline:
1. **Android detects OTP** → Creates document in "notifications" collection
2. **Mac receives immediately** → Shows bubble for 5 seconds
3. **Notification stays in Firestore** → Available for debugging/logging
4. **Cloud Function cleans up** → Deletes after 5 minutes (when you deploy)

### Benefits:
✅ Notifications persist long enough for the Mac to receive them
✅ No premature deletion (was deleting after 10 seconds)
✅ Cloud Function will handle cleanup when deployed
✅ You can now see OTP notifications in Firebase Console for debugging
✅ More reliable delivery (no race conditions)

---

## Why Was It Deleting?

The original code was trying to "clean up after itself" to avoid database bloat. However:
- **10 seconds is too short** - caused issues with slower networks
- **Better to let Cloud Functions handle cleanup** - centralized, scheduled
- **Mac needs time to receive** - especially with network latency

---

## What to Do Next

### Option 1: Keep Notifications Forever (For Debugging)
- Do nothing - notifications will stay in Firestore
- Good for testing and seeing OTP history
- Not recommended for production (costs + privacy)

### Option 2: Deploy Cloud Function (Recommended)
When you're ready, deploy the Cloud Function to auto-cleanup:

```bash
cd /Users/bunty/Documents/Clipsync
firebase deploy --only functions
```

This will delete:
- **OTP notifications** after 5 minutes
- **Other notifications** after 8 hours

---

## Testing

1. **Send OTP from Android** → Should appear in Firebase Console under "notifications" collection
2. **Check Mac** → Should show OTP bubble within 1-2 seconds
3. **Wait 15 seconds** → Notification should STILL be in Firestore (not deleted)
4. **Check Firebase Console** → You should see the notification document with:
   - `type: "OTP_NOTIFICATION"`
   - `encryptedOTP: "..."`
   - `timestamp: ...`
   - `pairingId: "..."`

---

## Summary

**Root Cause:** Android app was deleting notifications after 10 seconds
**Fix Applied:** Removed auto-delete logic from Android app
**Result:** Notifications now persist in Firestore (until Cloud Function cleans them up)

The issue was NOT in the Firebase Cloud Functions (since you haven't deployed them yet). It was the Android app cleaning up too aggressively!
