package com.bunty.clipsync

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.firestore.ListenerRegistration

/**
 * ClipboardAccessibilityService is the heart of ClipSync's clipboard-detection engine.
 *
 * It runs as a background [AccessibilityService] and detects when the user copies text
 * by monitoring accessibility events emitted by the Android UI framework, then syncs
 * the clipboard content to the paired Mac via Firestore.
 *
 * **Why an Accessibility Service?**
 * Since Android 10, apps can no longer read the clipboard in the background. The only
 * reliable way to detect a copy action without foreground focus is to observe accessibility
 * events (click events on "Copy" buttons, "copied" toast notifications, etc.) and then
 * launch [ClipboardGhostActivity] — a zero-UI Activity that briefly comes to the foreground
 * to read the clipboard, then immediately finishes.
 *
 * **Copy detection strategy (in priority order):**
 * 1. `TYPE_NOTIFICATION_STATE_CHANGED` with class `Toast` containing "copied".
 * 2. `TYPE_VIEW_CLICKED` with content description or text containing "copy".
 * 3. `TYPE_WINDOW_STATE_CHANGED` with content description or text containing "copied".
 * 4. Deep DFS ([dfsFindCopy]) of the accessibility node tree for any node with "copy".
 *
 * **Inbound sync (Mac → Android):**
 * A Firestore real-time listener ([startFirestoreListener]) watches the `clipboardItems`
 * collection. When a new item from the Mac arrives, it is decrypted and written to the
 * Android clipboard via [ClipboardGhostActivity.copyToClipboard], while [ignoreNextChange]
 * is set to prevent the just-received content from being immediately re-uploaded to Firestore.
 *
 * Declared in `accessibility_service_config.xml` and registered in AndroidManifest.xml.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    /** Runs delayed callbacks (ghost activity launches, ignore-flag reset) on the main thread. */
    private val handler = Handler(Looper.getMainLooper())

    /**
     * When `true`, the next clipboard change event from the accessibility stream is ignored.
     * Set to `true` when we write content to the clipboard ourselves (from Mac sync) so we
     * don't echo it back to Firestore.
     */
    private var ignoreNextChange = false

    /** Tracks the last clipboard text we processed to avoid duplicate uploads. */
    private var lastClipboardContent: String = ""

    /** Timestamp of the last accessibility event we processed (for debouncing). */
    private var lastEventTime = 0L

    /** Timestamp of the last time we launched [ClipboardGhostActivity] (for debouncing). */
    private var lastGhostLaunchTime = 0L

    /** Handle to the active Firestore listener so it can be removed on destroy. */
    private var firestoreListener: ListenerRegistration? = null

    /** Runnable that resets [ignoreNextChange] to `false` after [IGNORE_LOCAL_CHANGE_MS]. */
    private val clearIgnoreRunnable = Runnable { ignoreNextChange = false }

    companion object {
        private const val TAG = "ClipSync_Service"

        /** Minimum ms between two consecutive accessibility-event triggers. */
        private const val EVENT_DEBOUNCE_MS = 1000L

        /** Minimum ms between two consecutive [ClipboardGhostActivity] launches. */
        private const val GHOST_LAUNCH_DEBOUNCE_MS = 700L

        /**
         * How long (ms) after writing Mac content to the clipboard to keep [ignoreNextChange]
         * set to `true`, preventing an echo back to Firestore.
         */
        private const val IGNORE_LOCAL_CHANGE_MS = 2000L

        /** `true` while the service is actively running (used for status checks in UI). */
        var isRunning = false

        /**
         * The last piece of text we uploaded to Firestore.
         * Used for deduplication — avoids re-uploading the same content twice.
         * `@Volatile` because it can be read/written from both the main and background threads.
         */
        @Volatile
        var lastSyncedContent: String = ""

        /**
         * Hash of the last clipboard text we read via [ClipboardGhostActivity].
         * Compared against the hash of new reads to skip unchanged content.
         */
        @Volatile
        var lastReadClipboardHash: String = ""

        /**
         * Called by [ClipboardGhostActivity] after it successfully reads the system clipboard.
         *
         * Deduplication checks:
         * - Blank text is ignored.
         * - Text whose hash matches [lastReadClipboardHash] is a duplicate — ignored.
         * - Text that matches [lastSyncedContent] was written by us (from Mac sync) — ignored.
         *
         * @param context Application context.
         * @param text    The plain-text clipboard content just read.
         */
        fun onClipboardRead(context: Context, text: String) {
            if (text.isBlank()) return

            val currentHash = text.hashCode().toString()

            // Skip if we've already processed this exact content
            if (currentHash == lastReadClipboardHash) return

            // Skip if this is content we wrote ourselves from Mac sync
            if (text == lastSyncedContent) {
                lastReadClipboardHash = currentHash
                return
            }

            lastSyncedContent     = text
            lastReadClipboardHash = currentHash
            uploadToFirestoreStatic(context.applicationContext, text)
        }

        /**
         * Uploads [text] to the `clipboardItems` Firestore collection.
         *
         * On failure, [lastSyncedContent] is cleared so the user can retry by copying again.
         *
         * @param context Application context.
         * @param text    The plain-text clipboard content to upload.
         */
        private fun uploadToFirestoreStatic(context: Context, text: String) {
            try {
                FirestoreManager.sendClipboard(
                    context   = context,
                    text      = text,
                    onSuccess = {
                        Log.d(TAG, "Clipboard synced to Firestore")
                    },
                    onFailure = { e: Exception ->
                        Log.e(TAG, "Clipboard sync failed: ${e.message}")
                        // Allow a retry on the next copy event
                        if (lastSyncedContent == text) lastSyncedContent = ""
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in uploadToFirestoreStatic", e)
            }
        }
    }

    /**
     * Returns `true` if [packageName] belongs to a game app.
     * Games frequently generate spurious accessibility events, so we skip them.
     */
    private fun isGameApp(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0).category == ApplicationInfo.CATEGORY_GAME
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Called by the system when the service is successfully connected.
     * Starts the Firestore real-time listener so Mac → Android sync begins immediately.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        try {
            startFirestoreListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected", e)
        }
    }

    /**
     * Receives every accessibility event fired by the UI framework.
     *
     * Filters applied (in order):
     * 1. Null / [ignoreNextChange] guard.
     * 2. Skip game apps (too noisy).
     * 3. Debounce — ignore events that arrive within [EVENT_DEBOUNCE_MS] of the last.
     * 4. Skip events from our own package (avoids feedback loops).
     *
     * Then dispatches to the appropriate copy-detection logic based on event type.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ignoreNextChange) return

        try {
            val pkg = event.packageName?.toString()
            if (pkg != null && isGameApp(pkg)) return

            val eventTime = event.eventTime
            if (eventTime - lastEventTime < EVENT_DEBOUNCE_MS) return

            // Ignore events fired by ClipSync itself
            if (event.packageName == packageName) return

            when (event.eventType) {

                // Strategy 1: Toast notification containing the word "copied"
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (event.className == "android.widget.Toast") {
                        val text = event.text.joinToString(" ")
                        if (text.contains("copied", ignoreCase = true)) {
                            lastEventTime = eventTime
                            handler.postDelayed({ handleClipboardChange("Toast Notification") }, 50)
                        }
                    }
                }

                // Strategy 2 & 3: Click or window-change events with "copy"/"copied" text
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val contentDesc = event.contentDescription?.toString() ?: ""
                    val eventText   = event.text.joinToString(" ")
                    val isClick     = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED

                    var triggerType: String? = null

                    val hasCopy     = contentDesc.contains("copy", ignoreCase = true) || eventText.contains("copy", ignoreCase = true)
                    val hasCopied   = contentDesc.contains("copied", ignoreCase = true) || eventText.contains("copied", ignoreCase = true)
                    val isCopyright = contentDesc.contains("copyright", ignoreCase = true) || eventText.contains("copyright", ignoreCase = true)

                    if (!isCopyright) {
                        if (isClick && hasCopy)    triggerType = "Click (Copy Button)"
                        else if (hasCopied)        triggerType = "Passive (Content Copied)"
                    }

                    // Strategy 4: DFS through the node tree as a fallback
                    if (triggerType == null) {
                        val source = event.source
                        if (source != null && dfsFindCopy(source, isClick = isClick)) {
                            triggerType = if (isClick) "Deep Search (Click)" else "Deep Search (Window)"
                        }
                    }

                    if (triggerType != null) {
                        lastEventTime = eventTime
                        Log.d(TAG, "Copy detected: $triggerType")
                        handler.postDelayed({ handleClipboardChange(triggerType) }, 50)
                    }
                }

                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    /**
     * Depth-first search of the accessibility node tree looking for a "Copy" action node.
     *
     * Stops at depth 5 and skips invisible nodes to keep the search fast.
     * "Copyright" nodes are explicitly excluded to avoid false positives.
     *
     * @param node    Root node to start the search from.
     * @param depth   Current recursion depth (max [5]).
     * @param isClick `true` when triggered by a click event; changes what we look for.
     * @return `true` if a matching "Copy" node was found anywhere in the subtree.
     */
    private fun dfsFindCopy(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int = 0,
        isClick: Boolean = true
    ): Boolean {
        if (node == null || depth > 5 || !node.isVisibleToUser) return false

        val text        = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId      = node.viewIdResourceName ?: ""

        // Skip nodes with very long text or content descriptions (unlikely to be copy buttons)
        if (!((text.length > 30 || contentDesc.length > 30) && viewId.isEmpty())) {
            val combined = "$text $contentDesc $viewId".trim()

            if (combined.contains("copyright", ignoreCase = true)) return false

            val hasCopy   = combined.contains("copy", ignoreCase = true)
            val hasCopied = combined.contains("copied", ignoreCase = true)

            if (isClick && hasCopy) return true
            if (!isClick && (hasCopied || viewId.contains("copy", ignoreCase = true))) return true
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && dfsFindCopy(child, depth + 1, isClick)) return true
        }
        return false
    }

    /**
     * Launches [ClipboardGhostActivity] to read the current clipboard.
     *
     * Guards:
     * - Skips if [ignoreNextChange] is currently `true`.
     * - Applies a [GHOST_LAUNCH_DEBOUNCE_MS] debounce to avoid rapid successive launches.
     *
     * @param trigger Human-readable string describing why the launch was triggered (for logs).
     */
    private fun handleClipboardChange(trigger: String = "Unknown") {
        if (ignoreNextChange) return

        val now = System.currentTimeMillis()
        if (now - lastGhostLaunchTime < GHOST_LAUNCH_DEBOUNCE_MS) return

        lastGhostLaunchTime = now
        try {
            Log.d(TAG, "Launching ghost activity | trigger=$trigger")
            ClipboardGhostActivity.readFromClipboard(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ghost activity", e)
        }
    }

    /**
     * Attaches a Firestore real-time snapshot listener that fires whenever the Mac
     * writes new clipboard content.
     *
     * When content arrives:
     * 1. [ignoreNextChange] is set to `true` to prevent the incoming content from being
     *    re-uploaded when [ClipboardGhostActivity] detects the clipboard change we're about
     *    to make.
     * 2. The content is written to the Android clipboard via [ClipboardGhostActivity].
     * 3. [clearIgnoreRunnable] is scheduled to reset the ignore flag after [IGNORE_LOCAL_CHANGE_MS].
     *
     * Duplicate / blank / already-seen content is silently skipped.
     */
    private fun startFirestoreListener() {
        try {
            firestoreListener?.remove()  // remove any stale listener before creating a new one

            firestoreListener = FirestoreManager.listenToClipboard(this) { content: String ->
                try {
                    // Skip blank, duplicate, or already-processed content
                    if (content.isBlank() ||
                        content == lastSyncedContent ||
                        content == lastClipboardContent) return@listenToClipboard

                    // Set the ignore flag BEFORE writing to clipboard to suppress the echo
                    ignoreNextChange = true
                    handler.removeCallbacks(clearIgnoreRunnable)

                    lastSyncedContent     = content
                    lastClipboardContent  = content
                    lastReadClipboardHash = content.hashCode().toString()

                    // Write the Mac's clipboard to Android (invisible to the user)
                    ClipboardGhostActivity.copyToClipboard(this@ClipboardAccessibilityService, content)

                    // Reset the ignore flag after a safe window to allow future local copies
                    handler.postDelayed(clearIgnoreRunnable, IGNORE_LOCAL_CHANGE_MS)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in Firestore listener", e)
                    ignoreNextChange = false  // safety reset if something went wrong
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Firestore listener", e)
        }
    }

    /** Called by the system when the service is interrupted. Nothing to clean up here. */
    override fun onInterrupt() {}

    /**
     * Cleans up all resources when the service is stopped or the user disables it
     * in Accessibility Settings.
     */
    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()       // stop the Firestore real-time listener
        firestoreListener = null
        handler.removeCallbacksAndMessages(null)  // cancel all pending callbacks
        ignoreNextChange = false
        isRunning        = false
    }
}
