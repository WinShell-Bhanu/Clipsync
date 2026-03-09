const {onSchedule} = require("firebase-functions/v2/scheduler");
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
 * Delete clipboard items older than 8 hours
 */
exports.cleanupClipboardItems = onSchedule({
  schedule: "every 60 minutes",
  memory: "512MiB",
  timeoutSeconds: 540,
}, async () => {
  console.log("Cleaning clipboardItems");

  const now = admin.firestore.Timestamp.now();
  const eightHoursAgo = admin.firestore.Timestamp.fromMillis(
      now.toMillis() - 8 * 60 * 60 * 1000,
  );

  const snapshot = await db
      .collection("clipboardItems")
      .where("timestamp", "<", eightHoursAgo)
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
 * Delete notifications older than 8 hours
 */
exports.cleanupNotifications = onSchedule({
  schedule: "every 60 minutes",
  memory: "512MiB",
  timeoutSeconds: 540,
}, async () => {
  console.log("Cleaning notifications");

  const now = admin.firestore.Timestamp.now();
  const eightHoursAgo = admin.firestore.Timestamp.fromMillis(
      now.toMillis() - 8 * 60 * 60 * 1000,
  );

  const snapshot = await db
      .collection("notifications")
      .where("timestamp", "<", eightHoursAgo)
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

