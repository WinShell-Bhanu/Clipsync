package com.bunty.clipsync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An invisible, zero-UI [Activity] that acts as a proxy for clipboard operations on Android 10+.
 *
 * Android 10 (API 29) introduced a hard restriction: only the app whose window is currently
 * in the foreground may read the system clipboard. [ClipboardAccessibilityService] and other
 * background components therefore cannot access clipboard content directly. This Activity
 * sidesteps the restriction by briefly obtaining a foreground window, performing the clipboard
 * read or write, and then immediately dismissing itself — all without ever drawing a visible
 * pixel on screen.
 *
 * Supported operations:
 *  - [ACTION_WRITE]: Writes the string supplied via [EXTRA_CLIP_TEXT] to the system clipboard.
 *    Executed as soon as the Activity is created, then the Activity finishes.
 *  - [ACTION_READ]:  Reads the current primary clip from the system clipboard. The actual read
 *    is intentionally deferred to [onResume] to guarantee the window has acquired foreground
 *    focus before [ClipboardManager.getPrimaryClip] is called. The result is forwarded to
 *    [ClipboardAccessibilityService.onClipboardRead] for deduplication and Firestore upload.
 *
 * A 2-second hard timeout ([SAFETY_TIMEOUT_MS]) ensures the Activity always finishes even if
 * an unexpected error or lifecycle anomaly prevents the normal code path from running.
 * [FLAG_ACTIVITY_SINGLE_TOP] allows an existing instance to be reused rather than stacking
 * multiple invisible Activity instances on top of each other.
 */
class ClipboardGhostActivity : Activity() {

    // Ensures the clipboard is read at most once per Activity instance across onCreate/onResume.
    private var hasReadClipboard = false
    // Guards against calling finish() more than once, which would throw IllegalStateException.
    private var hasFinished = false
    // All posts to this handler run on the main thread; used exclusively for the safety timeout.
    private val safetyHandler = Handler(Looper.getMainLooper())
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /**
     * Dead-man's-switch runnable posted via [safetyHandler] at creation time. If [finishSafely]
     * has not been called within [SAFETY_TIMEOUT_MS] milliseconds, this fires and force-finishes
     * the Activity, preventing it from lingering invisibly in the background indefinitely.
     */
    private val safetyTimeout = Runnable {
        if (!hasFinished) {
            Log.w(TAG, "Safety timeout triggered - force finishing activity")
            finishSafely()
        }
    }

    companion object {
        private const val TAG = "ClipboardGhost"

        /**
         * Maximum lifetime of this Activity in milliseconds. If [finishSafely] has not been
         * called by the time this elapses, [safetyTimeout] fires and force-finishes the Activity.
         */
        private const val SAFETY_TIMEOUT_MS = 2000L

        /** Intent extra key carrying the plain-text string to write to the clipboard. */
        const val EXTRA_CLIP_TEXT = "extra_clip_text"

        /**
         * Intent extra key carrying the absolute path of a staged image file.
         * Used when the payload is a `[IMAGE_PAYLOAD]:` string that exceeds Android's
         * ~1MB Binder IPC limit and cannot be passed directly in an Intent extra.
         */
        const val EXTRA_IMAGE_FILE_PATH = "extra_image_file_path"

        /** Intent action requesting a clipboard read; the actual read is deferred to [onResume]. */
        const val ACTION_READ  = "action_read"

        /** Intent action requesting a clipboard write using the text supplied in [EXTRA_CLIP_TEXT]. */
        const val ACTION_WRITE = "action_write"

        /**
         * Convenience factory that starts a ghost Activity to write [text] to the clipboard.
         *
         * For large [IMAGE_PAYLOAD] strings the bytes are staged to a temp file first so that
         * the path (not the ~multi-MB payload) travels through the Binder IPC. This avoids the
         * hard ~1MB Binder transaction limit that causes a silent TransactionTooLargeException.
         *
         * @param context Any valid [Context] from which the Activity can be started.
         * @param text    Plain-text string or `[IMAGE_PAYLOAD]:base64` image payload.
         */
        fun copyToClipboard(context: Context, text: String) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_WRITE

                    if (text.startsWith("[IMAGE_PAYLOAD]:")) {
                        // Stage bytes to disk; pass only the file path in the Intent to avoid
                        // the ~1MB Binder transaction limit (TransactionTooLargeException).
                        val base64 = text.removePrefix("[IMAGE_PAYLOAD]:")
                        val imageBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                        val stageDir = java.io.File(context.cacheDir, "clipboard_images")
                        if (!stageDir.exists()) stageDir.mkdirs()
                        val stageFile = java.io.File(stageDir, "staged_image_${System.currentTimeMillis()}.jpg")
                        stageFile.writeBytes(imageBytes)
                        putExtra(EXTRA_IMAGE_FILE_PATH, stageFile.absolutePath)
                    } else {
                        putExtra(EXTRA_CLIP_TEXT, text)
                    }
                }
                context.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to launch ghost activity for clipboard write", error)
            }
        }

        /**
         * Convenience factory that starts a ghost Activity to read the current clipboard.
         *
         * The read operation is deferred to [onResume] so the Activity's window has fully
         * acquired foreground focus before [ClipboardManager.getPrimaryClip] is called.
         * The result is forwarded to [ClipboardAccessibilityService.onClipboardRead] for
         * deduplication and Firestore upload to the paired Mac.
         *
         * @param context Any valid [Context] from which the Activity can be started.
         */
        fun readFromClipboard(context: Context) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_READ
                }
                context.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to launch ghost activity for clipboard read", error)
            }
        }

        fun copyImageFileToClipboard(context: Context, filePath: String) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_WRITE
                    putExtra(EXTRA_IMAGE_FILE_PATH, filePath)
                }
                context.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to launch ghost activity for image write", error)
            }
        }

        fun cleanupOldStagedImages(context: Context, maxAgeMs: Long = 60 * 60 * 1000L) {
            val stageDir = java.io.File(context.cacheDir, "clipboard_images")
            val cutoff = System.currentTimeMillis() - maxAgeMs
            stageDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }

    /**
     * Suppresses all open-transition animations so the Activity is invisible to the user,
     * arms the 2-second safety timeout, then immediately dispatches to [handleIntent].
     * For write operations, the work is done and the Activity finishes before [onResume].
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // ── Prevent keyboard flicker ──────────────────────────────────────────
        // FLAG_ALT_FOCUSABLE_IM: this window can acquire focus without the IMM
        // interpreting it as a reason to hide the soft keyboard. Without this flag,
        // the IMM sees a non-input-field window claiming focus and immediately hides
        // the keyboard, then shows it again when we finish — causing the visible flicker.
        // SOFT_INPUT_STATE_UNCHANGED: belt-and-suspenders lock so the IME state
        // is completely frozen for the lifetime of this window.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)

        super.onCreate(savedInstanceState)
        disableOpenAnimation()

        // Post the safety net immediately; finishSafely() will cancel it in the happy path.
        safetyHandler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
        handleIntent(intent)
    }

    /**
     * Invoked when a new Intent arrives while this Activity sits at the top of the task stack
     * (possible because [FLAG_ACTIVITY_SINGLE_TOP] is set). Updates the stored intent and
     * re-dispatches to [handleIntent] to handle the new request without creating a new instance.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * At this point the Activity's window is visible and has foreground focus, satisfying
     * Android 10's prerequisite for clipboard reads. For [ACTION_READ] intents the clipboard
     * access is deferred here (rather than [onCreate]) to guarantee focus is held before
     * [ClipboardManager.getPrimaryClip] is invoked. The read is posted to the view hierarchy
     * for one additional frame to let window focus fully settle.
     */
    override fun onResume() {
        super.onResume()
        // Actual read happens in onWindowFocusChanged to guarantee focus + allow
        // the source app time to finish writing image data to the clipboard.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasFinished) {
            when (intent.action) {
                ACTION_READ -> {
                    if (!hasReadClipboard) {
                        hasReadClipboard = true
                        // Delay 200ms to allow the source app (e.g. Gallery) to finish
                        // committing image data to the clipboard before we read it.
                        scope.launch {
                            delay(200)
                            readClipboardAndFinish()
                        }
                    }
                }
                ACTION_WRITE -> {
                    val imageFilePath = intent.getStringExtra(EXTRA_IMAGE_FILE_PATH)
                    val text = intent.getStringExtra(EXTRA_CLIP_TEXT)
                    when {
                        imageFilePath != null -> writeImageFileToClipboard(imageFilePath)
                        !text.isNullOrEmpty() -> copyTextToClipboard(text)
                    }
                    finishSafely()
                }
            }
        }
    }

    /**
     * Routes the incoming intent to the appropriate clipboard operation:
     *
     * - [ACTION_WRITE]: Does nothing here, defers to [onWindowFocusChanged] where we have focus.
     * - [ACTION_READ]:  Resets [hasReadClipboard] so the deferred read runs in [onWindowFocusChanged].
     * - Unknown action: Finishes immediately without touching the clipboard.
     */
    private fun handleIntent(incomingIntent: Intent?) {
        when (incomingIntent?.action) {
            ACTION_WRITE -> {
                // Focus is required in Android 10+ for clipboard writes. Defers to onWindowFocusChanged.
            }

            ACTION_READ -> {
                // Reset the guard flag so onWindowFocusChanged() triggers the deferred clipboard read.
                hasReadClipboard = false
            }

            else -> {
                finishSafely()
            }
        }
    }

    /**
     * Reads the primary clip from [ClipboardManager] and forwards the text to
     * [ClipboardAccessibilityService.onClipboardRead], which owns deduplication logic and
     * uploads the content to Firestore for the paired Mac to consume.
     * [finishSafely] is always called in the finally block to guarantee the Activity exits.
     */
    private fun readClipboardAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) {
                return
            }

            val clipData = clipboard.primaryClip
            if (clipData == null || clipData.itemCount == 0) {
                return
            }

            val item = clipData.getItemAt(0)
            val text = item.text?.toString() ?: ""
            val uri = item.uri
            val isImage = clipData.description.hasMimeType("image/*")

            // Ignore files (e.g. copied from a file manager) that are not images
            if (uri != null && !isImage) {
                return
            }

            if (isImage && uri != null) {
                // Read the image bytes NOW while we still hold clipboard focus + a valid
                // URI grant. If we pass the URI to LocalSyncManager and wait for BLE
                // IP resolution (up to 8 s), the URI may expire and the stream open fails.
                try {
                    val extension = contentResolver.getType(uri)
                        ?.substringAfterLast('/')?.substringBefore(';') ?: "png"
                    val tmpFile = java.io.File(
                        cacheDir,
                        "clip_img_${System.currentTimeMillis()}.$extension"
                    )
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tmpFile.length() > 0) {
                        LocalSyncManager.onClipboardContent(
                            context = this,
                            content = "",
                            contentType = "image",
                            file = tmpFile
                        )
                    } else {
                        tmpFile.delete()
                        Log.w(TAG, "Image temp file was empty — skipping")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read image from clipboard URI", e)
                }
            } else if (text.isNotBlank()) {
                ClipboardAccessibilityService.onClipboardRead(this, text)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard", e)
        } finally {
            finishSafely()
        }
    }

    /**
     * Writes [text] to the system clipboard as a plain-text [ClipData] item labelled
     * "Copied Text". The label is required by the [ClipData] API but is not shown to users.
     *
     * @param text The string to place on the clipboard.
     */
    private fun copyTextToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Prevent loopback and log to history
            ClipboardAccessibilityService.lastReadClipboardHash = ClipboardAccessibilityService.hashSHA256(text)
            ClipboardAccessibilityService.lastSyncedContent = text
            com.bunty.clipsync.db.HistoryRepository.getInstance(this).addReceived(text, "Text")
            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
        }
    }

    /**
     * Reads an already-decoded JPEG image from [filePath], generates a [FileProvider] URI,
     * and places it on the system clipboard as an image [ClipData].
     * The staged file is deleted after use regardless of success or failure.
     */
    private fun writeImageFileToClipboard(filePath: String) {
        val imageFile = java.io.File(filePath)
        try {
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.e(TAG, "Staged image file missing or empty: $filePath")
                return
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                imageFile
            )
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Prevent loopback
            ClipboardAccessibilityService.lastReadClipboardHash = ClipboardAccessibilityService.hashSHA256(filePath)
            ClipboardAccessibilityService.lastSyncedContent = filePath
            com.bunty.clipsync.db.HistoryRepository.getInstance(this).addReceived("[Image]", "Image")
            clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Copied Image", uri))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write image to clipboard", e)
        }
        // File will be cleaned up lazily; we cannot delete it immediately because
        // the system clipboard holds a reference to the URI until it is cleared.
    }

    /**
     * Idempotent finish that cancels the pending safety timeout and calls [finish] exactly once.
     * Multiple calls — e.g. from both a normal code path and the safety timeout firing
     * simultaneously — are handled safely without risk of a double-finish crash.
     */
    private fun finishSafely() {
        if (!hasFinished) {
            hasFinished = true
            safetyHandler.removeCallbacks(safetyTimeout)
            finish()
        }
    }

    /** Delegates to super then suppresses the close animation so the dismissal is invisible. */
    override fun finish() {
        super.finish()
        disableCloseAnimation()
    }

    /** Cancels any pending handler callbacks on destruction to prevent handler memory leaks. */
    override fun onDestroy() {
        super.onDestroy()
        safetyHandler.removeCallbacks(safetyTimeout)
        scope.cancel()
    }

    /**
     * Removes the Activity open/enter transition animation so the launch is invisible.
     * Uses the API-34+ [overrideActivityTransition] on modern devices and falls back to
     * the deprecated [overridePendingTransition] on older API levels.
     */
    private fun disableOpenAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /**
     * Removes the Activity close/exit transition animation so the dismissal is invisible.
     * Uses the API-34+ [overrideActivityTransition] on modern devices and falls back to
     * the deprecated [overridePendingTransition] on older API levels.
     */
    private fun disableCloseAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
