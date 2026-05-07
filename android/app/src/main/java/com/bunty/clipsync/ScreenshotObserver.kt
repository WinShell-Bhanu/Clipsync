package com.bunty.clipsync

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import android.util.Log

/**
 * Monitors [MediaStore.Images.Media.EXTERNAL_CONTENT_URI] for newly saved screenshots
 * and sends them to the paired Mac via [ImageTransferManager.sendImageToMac].
 *
 * **Why ContentObserver instead of OnPrimaryClipChangedListener?**
 * Starting with Android 12 (API 31), [android.content.ClipboardManager.OnPrimaryClipChangedListener]
 * no longer fires for background services when another app (e.g. Gboard, the system) writes to
 * the clipboard. A ContentObserver on MediaStore has no such restriction and fires as soon as
 * Android saves the screenshot to storage — which happens before Gboard places it in the clipboard.
 *
 * **Permissions required:**
 * - API 33+ : `READ_MEDIA_IMAGES`
 * - API 31–32: `READ_EXTERNAL_STORAGE`
 * Both are declared in the manifest. The caller should check the relevant permission before
 * registering; if not granted the observer is skipped and a warning is logged.
 *
 * Registered and unregistered by [ClipboardAccessibilityService].
 */
class ScreenshotObserver(
    private val context: Context,
    private val handler: Handler
) : ContentObserver(handler) {

    companion object {
        // TAG shared with ImageTransfer so it appears in the same logcat filter.
        private const val TAG = "ImageTransfer"

        // Matches paths like "Pictures/Screenshots/" or "DCIM/Screenshots/" (any casing).
        private val SCREENSHOT_PATH_REGEX = Regex("(?i)(?:^|/)screenshots?(?:/|$)")

        // Ignore the same URI if it was already sent within this window (duplicate guard).
        private const val DEDUP_WINDOW_MS = 5_000L

        // How long to wait for MediaStore to commit the row after onChange fires.
        // onChange can fire while the image is still IS_PENDING (not fully written).
        private const val INITIAL_DELAY_MS = 800L

        // Retry delays if first query finds nothing (MediaStore commit can be slow).
        private val RETRY_DELAYS_MS = longArrayOf(2_000L, 4_000L)
    }

    private var lastSentUri: Uri? = null
    private var lastSentTime = 0L

    // Timestamp when the last onChange fired — used to bound retry windows.
    @Volatile private var lastChangeMs = 0L

    // ── ContentObserver callback ──────────────────────────────────────────────

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        val now = System.currentTimeMillis()
        lastChangeMs = now
        Log.d(TAG, "ScreenshotObserver: MediaStore onChange fired — uri=${uri ?: "null"}, scheduling detection in ${INITIAL_DELAY_MS}ms")
        // Delay before first query: onChange fires as soon as the MediaStore row is
        // *inserted* but the file is often still IS_PENDING (being written). Waiting
        // 800ms gives the system time to flush the image to disk and clear IS_PENDING.
        handler.postDelayed({ detectAndSend(triggerMs = now, attempt = 1) }, INITIAL_DELAY_MS)
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    private fun detectAndSend(triggerMs: Long, attempt: Int) {
        Log.d(TAG, "ScreenshotObserver: detectAndSend() attempt=$attempt")

        if (!DeviceManager.isPaired(context)) {
            Log.d(TAG, "ScreenshotObserver: not paired — skipping")
            return
        }

        if (ImageTransferManager.currentState == ImageTransferManager.TransferState.DISCOVERING ||
            ImageTransferManager.currentState == ImageTransferManager.TransferState.CONNECTING
        ) {
            Log.d(TAG, "ScreenshotObserver: transfer already in progress (state=${ImageTransferManager.currentState}) — skipping")
            return
        }

        val triggerSec = triggerMs / 1000L

        // Build projection
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED
        )

        // On API 29+ filter out items still being written (IS_PENDING = 1).
        // This avoids reading a half-written file.
        val selectionParts = mutableListOf("${MediaStore.Images.Media.DATE_ADDED} >= ?")
        val selectionArgs  = mutableListOf((triggerSec - 8).toString()) // 8s window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selectionParts.add("${MediaStore.Images.Media.IS_PENDING} = 0")
        }

        val selection = selectionParts.joinToString(" AND ")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        Log.d(TAG, "ScreenshotObserver: querying MediaStore (DATE_ADDED >= ${triggerSec - 8}, IS_PENDING=0 on API 29+)")

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs.toTypedArray(),
                sortOrder
            )?.use { cursor ->
                Log.d(TAG, "ScreenshotObserver: query returned ${cursor.count} image(s)")

                if (!cursor.moveToFirst()) {
                    scheduleRetry(triggerMs, attempt)
                    return
                }

                val relativePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                ) ?: run {
                    Log.d(TAG, "ScreenshotObserver: image missing RELATIVE_PATH — skipping")
                    scheduleRetry(triggerMs, attempt)
                    return
                }
                val displayName = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                ) ?: ""

                Log.d(TAG, "ScreenshotObserver: latest candidate: '$relativePath$displayName'")

                if (!SCREENSHOT_PATH_REGEX.containsMatchIn(relativePath)) {
                    Log.d(TAG, "ScreenshotObserver: not a screenshot path ('$relativePath') — ignoring")
                    // Don't retry: non-screenshot images won't become screenshots on retry.
                    return
                }

                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val imageUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )

                val now = System.currentTimeMillis()
                if (imageUri == lastSentUri && (now - lastSentTime) < DEDUP_WINDOW_MS) {
                    Log.d(TAG, "ScreenshotObserver: duplicate URI, already sent ${now - lastSentTime}ms ago — skipping")
                    return
                }

                lastSentUri  = imageUri
                lastSentTime = now

                Log.d(TAG, "ScreenshotObserver: 📸 screenshot matched! Reading bytes from $imageUri")

                val bytes = readBytes(imageUri)
                if (bytes == null || bytes.isEmpty()) {
                    Log.e(TAG, "ScreenshotObserver: ❌ failed to read screenshot bytes — READ_MEDIA_IMAGES granted?")
                    // Clear dedup so a retry can re-read (file might be flushed later).
                    lastSentUri = null
                    scheduleRetry(triggerMs, attempt)
                    return
                }

                Log.d(TAG, "ScreenshotObserver: read ${bytes.size} bytes — handing off to ImageTransferManager")
                ImageTransferManager.sendImageToMac(context, bytes)
            } ?: Log.e(TAG, "ScreenshotObserver: contentResolver.query() returned null")
        } catch (e: Exception) {
            Log.e(TAG, "ScreenshotObserver: unexpected error in detectAndSend()", e)
        }
    }

    /** Schedules a retry if we haven't exhausted the retry delays. */
    private fun scheduleRetry(triggerMs: Long, attempt: Int) {
        val retryIndex = attempt - 1  // attempt 1 → retryIndex 0
        if (retryIndex < RETRY_DELAYS_MS.size) {
            val delay = RETRY_DELAYS_MS[retryIndex]
            Log.d(TAG, "ScreenshotObserver: no screenshot found on attempt $attempt — retrying in ${delay}ms")
            handler.postDelayed({ detectAndSend(triggerMs = triggerMs, attempt = attempt + 1) }, delay)
        } else {
            Log.w(TAG, "ScreenshotObserver: exhausted retries — screenshot not detected")
        }
    }

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: SecurityException) {
            Log.e(TAG, "ScreenshotObserver: SecurityException reading $uri — READ_MEDIA_IMAGES not granted", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "ScreenshotObserver: error reading bytes from $uri", e)
            null
        }
    }
}
