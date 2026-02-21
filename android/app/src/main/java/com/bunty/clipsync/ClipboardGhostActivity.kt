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
 * ClipboardGhostActivity is a completely invisible [Activity] used as a workaround
 * for Android's clipboard access restrictions.
 *
 * **Why this exists:**
 * Since Android 10, apps can only read the clipboard when they have an active foreground window.
 * Background services (including [ClipboardAccessibilityService]) cannot access the clipboard
 * directly. This Activity is launched with no visible UI and no transition animation,
 * performs the clipboard operation in the foreground, then immediately finishes.
 *
 * **Two operations are supported:**
 * - [ACTION_WRITE]: Sets the system clipboard to the text passed via [EXTRA_CLIP_TEXT].
 * - [ACTION_READ]:  Reads the current system clipboard and forwards the text to
 *   [ClipboardAccessibilityService.onClipboardRead] for upload to Firestore.
 *
 * A [safetyTimeout] of 2 seconds ensures the Activity always finishes even if something goes wrong.
 */
class ClipboardGhostActivity : Activity() {

    // Prevents multiple clipboard reads in one lifecycle (onCreate → onResume)
    private var hasReadClipboard = false
    // Prevents calling finish() more than once
    private var hasFinished = false
    // Posts the safetyTimeout runnable; runs on the main thread
    private val safetyHandler = Handler(Looper.getMainLooper())

    /** Forces the Activity to finish if it hasn't done so within [SAFETY_TIMEOUT_MS]. */
    private val safetyTimeout = Runnable {
        if (!hasFinished) {
            Log.w(TAG, "Safety timeout triggered - force finishing activity")
            finishSafely()
        }
    }

    companion object {
        private const val TAG = "ClipboardGhost"

        /** Maximum time (ms) the Activity is allowed to live before being force-finished. */
        private const val SAFETY_TIMEOUT_MS = 2000L

        /** Intent extra key for the text to write to the clipboard. */
        const val EXTRA_CLIP_TEXT = "extra_clip_text"

        /** Action value that tells the Activity to write text to the clipboard. */
        const val ACTION_READ  = "action_read"

        /** Action value that tells the Activity to read text from the clipboard. */
        const val ACTION_WRITE = "action_write"

        /**
         * Launches a ghost Activity instance to write [text] to the system clipboard.
         *
         * Uses [FLAG_ACTIVITY_NO_ANIMATION] so the Activity is invisible to the user.
         * Uses [FLAG_ACTIVITY_SINGLE_TOP] + [FLAG_ACTIVITY_CLEAR_TOP] to reuse an
         * existing instance if one is already running.
         *
         * @param context Any valid [Context].
         * @param text    The plain-text string to place on the clipboard.
         */
        fun copyToClipboard(context: Context, text: String) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = ACTION_WRITE
                    putExtra(EXTRA_CLIP_TEXT, text)
                }
                context.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to launch ghost activity for clipboard write", error)
            }
        }

        /**
         * Launches a ghost Activity instance to read the current system clipboard.
         *
         * The read happens in [onResume] (when the Activity has a foreground window),
         * then the result is passed to [ClipboardAccessibilityService.onClipboardRead].
         *
         * @param context Any valid [Context].
         */
        fun readFromClipboard(context: Context) {
            runCatching {
                val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = ACTION_READ
                }
                context.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to launch ghost activity for clipboard read", error)
            }
        }
    }

    /**
     * Entry point. Removes all window animations, arms the safety timeout, then
     * dispatches to [handleIntent] immediately.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableOpenAnimation()

        // Arm the safety net — guarantees this Activity finishes within 2 seconds no matter what
        safetyHandler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
        handleIntent(intent)
    }

    /**
     * Called when a new Intent arrives while the Activity is already at the top of the stack
     * (due to [FLAG_ACTIVITY_SINGLE_TOP]). Updates the current intent and re-dispatches.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The Activity now has a foreground window — safe to access the clipboard.
     * For [ACTION_READ] intents the actual read is deferred to this callback
     * so the window is guaranteed to be visible.
     */
    override fun onResume() {
        super.onResume()
        if (intent.action == ACTION_READ && !hasReadClipboard && !hasFinished) {
            hasReadClipboard = true
            // Post to the view hierarchy so the window focus is fully established first
            window.decorView.post {
                readClipboardAndFinish()
            }
        }
    }

    /**
     * Routes the incoming intent to the correct clipboard operation.
     *
     * - [ACTION_WRITE]: Immediately writes the extra text to the clipboard and finishes.
     * - [ACTION_READ]:  Marks the pending read flag; actual read happens in [onResume].
     * - Unknown:        Finishes immediately without doing anything.
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
                // Actual read is deferred to onResume() so the window is in the foreground
                hasReadClipboard = false
            }

            else -> {
                finishSafely()
            }
        }
    }

    /**
     * Reads the current primary clip from the system [ClipboardManager] and forwards
     * the text to [ClipboardAccessibilityService.onClipboardRead] for Firestore upload.
     * Always calls [finishSafely] in the `finally` block.
     */
    private fun readClipboardAndFinish() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (!clipboard.hasPrimaryClip()) return

            val clipData = clipboard.primaryClip
            if (clipData == null || clipData.itemCount == 0) return

            val text = clipData.getItemAt(0).text?.toString() ?: ""

            if (text.isNotBlank()) {
                // Hand the text off to the service which handles deduplication and Firestore upload
                ClipboardAccessibilityService.onClipboardRead(this, text)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read clipboard", e)
        } finally {
            finishSafely()
        }
    }

    /**
     * Writes [text] to the system clipboard as a plain-text clip labelled "Copied Text".
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
     * Idempotent finish — cancels the safety timeout and calls [finish] at most once.
     * Guards against double-finish crashes that can occur in edge-case lifecycle flows.
     */
    private fun finishSafely() {
        if (!hasFinished) {
            hasFinished = true
            safetyHandler.removeCallbacks(safetyTimeout)
            finish()
        }
    }

    /** Overridden to suppress the close window transition animation. */
    override fun finish() {
        super.finish()
        disableCloseAnimation()
    }

    /** Removes the safety timeout handler to avoid leaks when the Activity is destroyed. */
    override fun onDestroy() {
        super.onDestroy()
        safetyHandler.removeCallbacks(safetyTimeout)
    }

    /** Suppresses the Activity open/enter animation so it appears completely invisible. */
    private fun disableOpenAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** Suppresses the Activity close/exit animation so it disappears completely invisibly. */
    private fun disableCloseAnimation() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
