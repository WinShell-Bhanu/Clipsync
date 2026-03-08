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

        /** Intent action requesting a clipboard read; the actual read is deferred to [onResume]. */
        const val ACTION_READ  = "action_read"

        /** Intent action requesting a clipboard write using the text supplied in [EXTRA_CLIP_TEXT]. */
        const val ACTION_WRITE = "action_write"

        /**
         * Convenience factory that starts a ghost Activity to write [text] to the clipboard.
         *
         * The Intent is built with [FLAG_ACTIVITY_NO_ANIMATION] (invisible launch),
         * [FLAG_ACTIVITY_SINGLE_TOP] (reuse an existing instance if already on the stack), and
         * [FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS] (keep it off the recent-apps screen).
         * Any failure to start the Activity is caught and logged so the caller does not need
         * exception handling.
         *
         * @param context Any valid [Context] from which the Activity can be started.
         * @param text    Plain-text string to place on the system clipboard.
         */
        fun copyToClipboard(context: Context, text: String) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    action = ACTION_WRITE
                    putExtra(EXTRA_CLIP_TEXT, text)
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
    }

    /**
     * Suppresses all open-transition animations so the Activity is invisible to the user,
     * arms the 2-second safety timeout, then immediately dispatches to [handleIntent].
     * For write operations, the work is done and the Activity finishes before [onResume].
     */
    override fun onCreate(savedInstanceState: Bundle?) {
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
        if (intent.action == ACTION_READ && !hasReadClipboard && !hasFinished) {
            hasReadClipboard = true
            window.decorView.post {
                readClipboardAndFinish()
            }
        }
    }

    /**
     * Routes the incoming intent to the appropriate clipboard operation:
     *
     * - [ACTION_WRITE]: Extracts [EXTRA_CLIP_TEXT], writes it to the clipboard, finishes.
     * - [ACTION_READ]:  Resets [hasReadClipboard] so the deferred read runs in [onResume].
     * - Unknown action: Finishes immediately without touching the clipboard.
     */
    private fun handleIntent(incomingIntent: Intent?) {
        when (incomingIntent?.action) {
            ACTION_WRITE -> {
                val text = incomingIntent.getStringExtra(EXTRA_CLIP_TEXT)
                if (!text.isNullOrEmpty()) {
                    copyTextToClipboard(text)
                }
                finishSafely()
            }

            ACTION_READ -> {
                // Reset the guard flag so onResume() triggers the deferred clipboard read.
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

            if (!clipboard.hasPrimaryClip()) return

            val clipData = clipboard.primaryClip
            if (clipData == null || clipData.itemCount == 0) return

            val text = clipData.getItemAt(0).text?.toString() ?: ""

            if (text.isNotBlank()) {
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
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
        }
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
