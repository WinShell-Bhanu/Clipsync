const { onSchedule } = require("firebase-functions/v2/scheduler");
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

/**
 * Recursively delete a document and all its subcollections
 * @param {admin.firestore.DocumentReference} docRef - The document reference
 */
async function deleteDocumentWithSubcollections(docRef) {
  const subcollections = await docRef.listCollections();

  for (const subcollection of subcollections) {
    const subcollectionDocs = await subcollection.get();
    for (const doc of subcollectionDocs.docs) {
      await deleteDocumentWithSubcollections(doc.ref);
    }
  }

  await docRef.delete();
}

/**
 * Delete clipboard items older than 2 hours.
 * M5 fix: reduced from 8 hours to limit the exposure window for clipboard data.
 */
exports.cleanupClipboardItems = onSchedule({
  schedule: "every 60 minutes",
  memory: "512MiB",
  timeoutSeconds: 540,
}, async () => {
  console.log("Cleaning clipboardItems");

  const now = admin.firestore.Timestamp.now();
  const twoHoursAgo = admin.firestore.Timestamp.fromMillis(
    now.toMillis() - 2 * 60 * 60 * 1000,
  );

  const snapshot = await db
    .collection("clipboardItems")
    .where("timestamp", "<", twoHoursAgo)
    .limit(100)
    .get();

  if (snapshot.empty) {
    console.log("No old clipboard items");
    return null;
  }

  // Delete in smaller batches to avoid memory issues
  const batchSize = 10;
  for (let i = 0; i < snapshot.docs.length; i += batchSize) {
    const batch = snapshot.docs.slice(i, i + batchSize);
    const deletePromises = batch.map((doc) =>
      deleteDocumentWithSubcollections(doc.ref),
    );
    await Promise.all(deletePromises);
  }

  console.log(
    `Deleted ${snapshot.size} clipboard items with subcollections`,
  );

  return null;
});

/**
 * Delete notifications older than 30 minutes.
 * OTPs are short-lived credentials — keeping them in Firestore for 8 hours
 * is an unnecessary exposure window. 30 minutes is a generous upper bound
 * for any legitimate OTP lifetime.
 */
exports.cleanupNotifications = onSchedule({
  schedule: "every 30 minutes",
  memory: "512MiB",
  timeoutSeconds: 540,
}, async () => {
  console.log("Cleaning notifications");

  const now = admin.firestore.Timestamp.now();
  const thirtyMinutesAgo = admin.firestore.Timestamp.fromMillis(
    now.toMillis() - 30 * 60 * 1000,
  );

  const snapshot = await db
    .collection("notifications")
    .where("timestamp", "<", thirtyMinutesAgo)
    .limit(100)
    .get();

  if (snapshot.empty) {
    console.log("No old notifications");
    return null;
  }

  // Delete in smaller batches to avoid memory issues
  const batchSize = 10;
  for (let i = 0; i < snapshot.docs.length; i += batchSize) {
    const batch = snapshot.docs.slice(i, i + batchSize);
    const deletePromises = batch.map((doc) =>
      deleteDocumentWithSubcollections(doc.ref),
    );
    await Promise.all(deletePromises);
  }

  console.log(
    `Deleted ${snapshot.size} notifications with subcollections`,
  );

  return null;
});

/**
 * Triggers when a new clipboard item is created in Firestore.
 * This happens when BLE/TCP fails and the device falls back to Cloud Sync.
 * The function looks up the pairing and sends a silent `wake_up` push
 * to the destination device so it knows to fetch the new clipboard item.
 */
exports.notifyPairedDevice = onDocumentCreated("clipboardItems/{docId}", async (event) => {
  const snapshot = event.data;
  if (!snapshot) return;

  const data = snapshot.data();
  const pairingId = data.pairingId;
  const sourceDeviceId = data.sourceDeviceId;

  if (!pairingId || !sourceDeviceId) {
    console.log("Missing pairingId or sourceDeviceId. Skipping push.");
    return;
  }

  // 1. Fetch the pairing document to find the destination device ID
  const pairingDoc = await db.collection("pairings").doc(pairingId).get();
  if (!pairingDoc.exists) {
    console.log(`Pairing document not found for ID: ${pairingId}`);
    return;
  }

  const pairingData = pairingDoc.data();
  // Identify the destination device (the one that didn't send the clip)
  let destinationDeviceId = null;
  
  // Note: pairingData contains macDeviceId and androidDeviceId
  if (sourceDeviceId === pairingData.macDeviceId) {
    destinationDeviceId = pairingData.androidDeviceId;
  } else if (sourceDeviceId === pairingData.androidDeviceId) {
    destinationDeviceId = pairingData.macDeviceId;
  }

  if (!destinationDeviceId) {
    console.log(`Could not determine destination device for source: ${sourceDeviceId}`);
    return;
  }

  // 2. Lookup the destination device's FCM token
  const tokenQuery = await db.collection("fcmTokens")
    .where("deviceId", "==", destinationDeviceId)
    .limit(1)
    .get();

  if (tokenQuery.empty) {
    console.log(`No FCM token found for destination device: ${destinationDeviceId}`);
    return;
  }

  const fcmToken = tokenQuery.docs[0].data().token;
  if (!fcmToken) {
    console.log(`FCM token document found but token string is missing.`);
    return;
  }

  // 3. Send the wake_up silent push via FCM
  const message = {
    token: fcmToken,
    data: {
      type: "wake_up" // Triggers local fetch on the destination device
    },
    // We do NOT include 'notification' block so it remains silent.
    android: {
      priority: "high" // Required to wake up dozing Android devices
    },
    apns: {
      payload: {
        aps: {
          "content-available": 1 // Required to wake up macOS apps in background
        }
      }
    }
  };

  try {
    await admin.messaging().send(message);
    console.log(`Successfully sent wake_up push to device: ${destinationDeviceId}`);
  } catch (error) {
    console.error(`Error sending wake_up push to device: ${destinationDeviceId}`, error);
  }
});
