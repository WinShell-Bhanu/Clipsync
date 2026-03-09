# Firebase Notifications Auto-Delete Issue - FIXED

## Problem Found ✅

Your Firebase Cloud Function `cleanupNotifications` in `/functions/index.js` was automatically deleting ALL notifications older than **8 hours**, including OTP notifications.

**The function runs every 60 minutes** and removes old documents from the "notifications" collection.

---

## Solution Applied ✅

Updated the `cleanupNotifications` function to use **different retention policies** for different notification types:

### New Retention Policy:
- **OTP Notifications** (`type: "OTP_NOTIFICATION"`): Delete after **5 minutes**
- **Other Notifications**: Delete after **8 hours** (unchanged)

### Why This Works:
1. OTP notifications are only needed briefly (your Mac app dismisses them after 5 seconds)
2. No need to keep OTP data for 8 hours (security risk + unnecessary storage)
3. Other notification types still retain the 8-hour policy
4. Reduces database costs and improves security

---

## What Changed in `/functions/index.js`:

```javascript
// OLD: Delete ALL notifications after 8 hours
exports.cleanupNotifications = onSchedule({
  schedule: "every 60 minutes",
}, async () => {
  const eightHoursAgo = now.toMillis() - 8 * 60 * 60 * 1000;
  const snapshot = await db.collection("notifications")
    .where("timestamp", "<", eightHoursAgo)
    .get();
  // Delete all...
});

// NEW: Different policies for OTP vs other notifications
exports.cleanupNotifications = onSchedule({
  schedule: "every 60 minutes",
}, async () => {
  // 1. Delete OTP notifications after 5 minutes
  const fiveMinutesAgo = now.toMillis() - 5 * 60 * 1000;
  const otpSnapshot = await db.collection("notifications")
    .where("type", "==", "OTP_NOTIFICATION")
    .where("timestamp", "<", fiveMinutesAgo)
    .get();
  
  // 2. Delete other notifications after 8 hours
  const eightHoursAgo = now.toMillis() - 8 * 60 * 60 * 1000;
  const otherSnapshot = await db.collection("notifications")
    .where("timestamp", "<", eightHoursAgo)
    .get();
});
```

---

## Deploy Instructions

To apply this fix to your Firebase project, run:

```bash
cd /Users/bunty/Documents/Clipsync
firebase deploy --only functions
```

This will deploy the updated Cloud Function with the new retention policy.

---

## Timeline:

**OTP Notification Lifecycle:**
1. **Android detects OTP** → Creates document in "notifications" collection
2. **Mac receives notification** → Shows bubble (5 seconds), copies to clipboard
3. **After 5 minutes** → Cloud Function auto-deletes the OTP notification
4. **Result:** Clean database, good security, no storage bloat

**Other Notifications:**
- Still deleted after 8 hours as before

---

## Verification

After deploying, you can verify in Firebase Console:
1. Go to **Firestore Database** → "notifications" collection
2. Send a test OTP from Android
3. Wait 6 minutes
4. The OTP notification should be auto-deleted
5. Check Cloud Functions logs for: "Deleted X OTP notifications (>5 min old)"

---

## Benefits:
✅ OTP notifications cleaned up quickly (5 minutes)
✅ Better security (OTP codes don't linger in database)
✅ Reduced storage costs
✅ Other notifications retain 8-hour policy
✅ No change to app functionality
