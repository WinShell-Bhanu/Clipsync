package com.bunty.clipsync

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.firebase.firestore.ListenerRegistration
import java.util.concurrent.Executors

/**
 * ClipboardAccessibilityService is the core clipboard-detection engine for ClipSync.
 *
 * It runs as an Android Accessibility Service and listens to system-wide accessibility
 * events to detect when the user copies text in any app. Detected clipboard content is
 * encrypted and synced to the paired Mac via Firestore.
 *
 * **Copy-detection strategy (evaluated in priority order):**
 * 1. [AccessibilityNodeInfo.ACTION_COPY] action ID on the event source node — a numeric
 *    constant that is completely language-independent and always preferred.
 * 2. Toast notification whose text contains a localised "copied" word from [COPIED_WORDS].
 * 3. Click or window-change event whose text/contentDescription contains a word from
 *    [COPY_WORDS] or [COPIED_WORDS].
 * 4. DFS traversal of the accessibility node tree via [dfsFindCopy] as a last resort.
 *
 * **Multilingual support:**
 * [COPY_WORDS] and [COPIED_WORDS] cover 22 languages including English, all major Indian
 * languages, and several global languages. All words are hardcoded in the APK — no network
 * calls or translation APIs are required.
 *
 * **Inbound sync (Mac → Android):**
 * A Firestore real-time listener watches the `clipboardItems` collection. When new content
 * arrives from the Mac it is written to the Android clipboard via
 * [ClipboardGhostActivity.copyToClipboard]. The [ignoreNextChange] flag is set immediately
 * after to prevent the service from echoing that content back to Firestore.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    /**
     * When `true`, the next clipboard-change event detected by this service is silently
     * discarded. This prevents a Mac→Android inbound write from being immediately
     * re-uploaded back to the Mac (echo loop prevention).
     */
    private var ignoreNextChange = false

    private var lastClipboardContent: String = ""
    private var lastEventTime       = 0L
    private var lastGhostLaunchTime = 0L
    private var firestoreListener: ListenerRegistration? = null
    private val clearIgnoreRunnable = Runnable { ignoreNextChange = false }

    companion object {
        private const val TAG = "ClipSync_Service"

        /** Minimum milliseconds between two consecutive accessibility events being processed. */
        private const val EVENT_DEBOUNCE_MS        = 1000L
        /** Minimum milliseconds between two consecutive [ClipboardGhostActivity] launches. */
        private const val GHOST_LAUNCH_DEBOUNCE_MS = 700L
        /** How long [ignoreNextChange] stays `true` after writing a Mac→Android clipboard item. */
        private const val IGNORE_LOCAL_CHANGE_MS   = 2000L

        /** `true` while the service is bound and running; used by the UI to show service status. */
        var isRunning = false

        // ── Multilingual word sets ────────────────────────────────────────────
        // COPY_WORDS and COPIED_WORDS have been migrated to res/values/strings.xml.
        // They are loaded dynamically in onServiceConnected() to avoid allocating
        // two large static Sets that would live in the companion object for the
        // entire lifetime of the process.

        /**
         * Words and symbols related to "copyright" notices across several languages.
         *
         * Apps such as document viewers and web browsers often display copyright notices
         * (e.g. "© 2024 Acme Corp") whose text contains words like "copy". Nodes whose
         * combined text matches any entry in this set are excluded from copy-detection to
         * avoid spurious clipboard reads triggered by copyright labels.
         */
        private val COPYRIGHT_WORDS = setOf(
            "copyright", "©",
            "कॉपीराइट",
            "авторские права"
        )

        /**
         * Tracks the content most recently uploaded to Firestore so that duplicate writes
         * are suppressed even across multiple rapid accessibility events.
         */
        @Volatile var lastSyncedContent:     String = ""
        /**
         * Hash of the clipboard text most recently read by [ClipboardGhostActivity].
         * Used by [onClipboardRead] to skip redundant uploads when the clipboard has not
         * changed between two successive ghost-activity reads.
         */
        @Volatile var lastReadClipboardHash: String = ""

        /**
         * Callback invoked by [ClipboardGhostActivity] after it successfully reads the
         * system clipboard on behalf of this service.
         *
         * Performs three deduplication checks before uploading to Firestore:
         * 1. Rejects blank text.
         * 2. Rejects content whose hash matches [lastReadClipboardHash] (same read twice).
         * 3. Rejects content already equal to [lastSyncedContent] (already uploaded).
         *
         * @param context The calling context (used to reach Firestore).
         * @param text    The plain-text string read from the clipboard.
         */
        fun onClipboardRead(context: Context, text: String) {
            if (!DeviceManager.isSyncToMacEnabled(context)) {
                return
            }
            if (text.isBlank()) return
            val currentHash = hashSHA256(text)
            if (currentHash == lastReadClipboardHash) return
            if (text == lastSyncedContent) {
                lastReadClipboardHash = currentHash
                return
            }
            lastSyncedContent     = text
            lastReadClipboardHash = currentHash
            // Upload to Firestore if hybrid sync is enabled
            val syncMode = DeviceManager.getSyncMode(context)
            if (syncMode == "hybrid") {
                uploadToFirestoreStatic(context.applicationContext, text)
            }
            
            // Add minimum logic for LocalSyncManager support
            if (syncMode != "hybrid") {
                LocalSyncManager.onClipboardContent(context, text)
            }
        }

        /**
         * Calls [FirestoreManager.sendClipboard] to persist [text] to the cloud.
         *
         * On failure the [lastSyncedContent] cache is cleared so that the next identical
         * clipboard content will be retried rather than silently dropped.
         *
         * @param context Application context forwarded to [FirestoreManager].
         * @param text    The clipboard text to upload.
         */
        private fun uploadToFirestoreStatic(context: Context, text: String) {
            try {
                FirestoreManager.sendClipboard(
                    context   = context,
                    text      = text,
                    onSuccess = { },
                    onFailure = { e: Exception ->
                        Log.e(TAG, "Clipboard sync failed: ${e.message}")
                        if (lastSyncedContent == text) lastSyncedContent = ""
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in uploadToFirestoreStatic", e)
            }
        }
        /**
         * Returns the SHA-256 hex digest of [text].
         *
         * Used by [onClipboardRead], [ClipboardGhostActivity], and [MacPushReceiver] to
         * fingerprint clipboard content for loopback-detection deduplication. SHA-256 is
         * chosen over `hashCode()` to make accidental hash collisions cryptographically
         * negligible for typical clipboard text lengths.
         */
        fun hashSHA256(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes  = digest.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        copyWordsCache = resources.getStringArray(R.array.copy_words).toSet()
        copiedWordsCache = resources.getStringArray(R.array.copied_words).toSet()
        try {
            ClipboardGhostActivity.cleanupOldStagedImages(this)
            startFirestoreListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected", e)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        firestoreListener?.remove()
        firestoreListener = null
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreListener?.remove()
        firestoreListener = null
        handler.removeCallbacksAndMessages(null)
        ignoreNextChange = false
        isRunning        = false
    }

    // ── Accessibility event handling ──────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || ignoreNextChange) return
        try {
            val pkg = event.packageName?.toString()
            if (pkg != null && isGameApp(pkg)) return

            val eventTime = event.eventTime
            if (eventTime - lastEventTime < EVENT_DEBOUNCE_MS) return
            if (event.packageName == packageName) return

            when (event.eventType) {

                // Strategy 1 — "Copied" confirmation toast.
                // Android (and many custom launchers) briefly shows a toast such as
                // "Copied to clipboard" after a successful copy. Matching against
                // COPIED_WORDS catches this signal across all 22 supported languages.
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (event.className == "android.widget.Toast") {
                        val text = event.text.joinToString(" ")
                        if (containsCopiedWord(text)) {
                            lastEventTime = eventTime
                            handler.postDelayed({ handleClipboardChange("Toast") }, 50)
                        }
                    }
                }

                // Strategy 2 & 3 — Click events and window state changes.
                // For click events the source node is inspected first for the standard
                // ACTION_COPY action ID (language-independent). If that is absent, the
                // event text and contentDescription are checked against COPY_WORDS and
                // COPIED_WORDS. As a final fallback, dfsFindCopy walks the node tree.
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val contentDesc = event.contentDescription?.toString() ?: ""
                    val eventText   = event.text.joinToString(" ")
                    val isClick     = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    var triggerType: String? = null

                    // Primary check: the numeric ACTION_COPY ID is reliable across all locales
                    // and is always checked first before any word-based heuristics.
                    val source = event.source
                    if (isClick && source?.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_COPY } == true) {
                        triggerType = "ACTION_COPY"
                    }

                    // Secondary check: scan event text and contentDescription for copy words,
                    // first filtering out copyright notices to avoid false positives.
                    if (triggerType == null) {
                        val isCopyright = containsCopyrightWord(contentDesc) || containsCopyrightWord(eventText)
                        if (!isCopyright) {
                            val hasCopy   = containsCopyWord(contentDesc)   || containsCopyWord(eventText)
                            val hasCopied = containsCopiedWord(contentDesc) || containsCopiedWord(eventText)
                            if (isClick && hasCopy)  triggerType = "Click (Copy Button)"
                            else if (hasCopied)      triggerType = "Passive (Copied)"
                        }
                    }

                    // Tertiary check: walk the full accessibility node tree as a last resort.
                    if (triggerType == null && source != null) {
                        backgroundExecutor.execute {
                            try {
                                val found = dfsFindCopy(source, isClick = isClick)
                                if (found) {
                                    val finalType = if (isClick) "Deep Search (Click)" else "Deep Search (Window)"
                                    handler.post {
                                        lastEventTime = eventTime
                                        handleClipboardChange(finalType)
                                    }
                                }
                            } finally {
                                if (Build.VERSION.SDK_INT < 34) {
                                    @Suppress("DEPRECATION")
                                    source.recycle()
                                }
                            }
                        }
                    } else {
                        // Release the native accessibility node reference on API < 34.
                        if (Build.VERSION.SDK_INT < 34) {
                            @Suppress("DEPRECATION")
                            source?.recycle()
                        }

                        if (triggerType != null) {
                            lastEventTime = eventTime
                            handler.postDelayed({ handleClipboardChange(triggerType) }, 50)
                        }
                    }
                }

                else -> Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    // ── DFS copy node search ──────────────────────────────────────────────────

    /**
     * Performs a depth-first search of the accessibility node tree rooted at [node],
     * looking for evidence that a "Copy" action was performed.
     *
     * Each node is inspected in this order:
     * 1. [AccessibilityNodeInfo.ACTION_COPY] present in its action list — the most
     *    reliable signal, independent of language or label text.
     * 2. Combined text / contentDescription / viewId matching [COPY_WORDS] (for click
     *    events) or [COPIED_WORDS] (for passive window events).
     *
     * Nodes whose combined text matches [COPYRIGHT_WORDS] are skipped immediately to
     * prevent false positives from copyright notices. Invisible nodes are also skipped.
     * The search is bounded to a maximum depth of 5 to keep it fast.
     *
     * @param node    The root node to search from (may be `null`).
     * @param depth   Current recursion depth; starts at 0.
     * @param isClick `true` when called from a [AccessibilityEvent.TYPE_VIEW_CLICKED]
     *                event, which enables more aggressive matching via [COPY_WORDS].
     * @return `true` if a copy-related node was found anywhere in the subtree.
     */
    private fun dfsFindCopy(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        isClick: Boolean = true
    ): Boolean {
        if (node == null || depth > 5 || !node.isVisibleToUser) return false

        val text        = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId      = node.viewIdResourceName ?: ""

        if (!((text.length > 30 || contentDesc.length > 30) && viewId.isEmpty())) {
            val combined = "$text $contentDesc $viewId".trim()

            if (containsCopyrightWord(combined)) return false

            // Language-independent check: prefer the numeric action ID over any text heuristic.
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_COPY }) return true

            val hasCopy   = containsCopyWord(combined)
            val hasCopied = containsCopiedWord(combined)
            if (isClick && hasCopy)  return true
            if (!isClick && (hasCopied || viewId.contains("copy", ignoreCase = true))) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = dfsFindCopy(child, depth + 1, isClick)
            // Release the child node's native reference on API < 34 to prevent memory leaks.
            // Above API 33 the framework handles this automatically.
            if (Build.VERSION.SDK_INT < 34) {
                @Suppress("DEPRECATION")
                child.recycle()
            }
            if (found) return true
        }
        return false
    }

    // ── Multilingual word matchers ────────────────────────────────────────────

    private var copyWordsCache = emptySet<String>()
    private var copiedWordsCache = emptySet<String>()

    /** Returns `true` if [text] contains at least one word from copyWordsCache (case-insensitive). */
    private fun containsCopyWord(text: String): Boolean {
        val lower = text.lowercase()
        return copyWordsCache.any { lower.contains(it.lowercase()) }
    }

    /** Returns `true` if [text] contains at least one word from copiedWordsCache (case-insensitive). */
    private fun containsCopiedWord(text: String): Boolean {
        val lower = text.lowercase()
        return copiedWordsCache.any { lower.contains(it.lowercase()) }
    }

    /** Returns `true` if [text] contains at least one word from [COPYRIGHT_WORDS] (case-insensitive). */
    private fun containsCopyrightWord(text: String): Boolean {
        val lower = text.lowercase()
        return COPYRIGHT_WORDS.any { lower.contains(it.lowercase()) }
    }

    // ── Ghost activity launch ─────────────────────────────────────────────────

    /**
     * Responds to a detected copy event by launching [ClipboardGhostActivity] to read
     * the current clipboard content on behalf of this service.
     *
     * Two guards prevent redundant launches:
     * - [ignoreNextChange] — set when an inbound Mac clipboard write is in progress.
     * - [lastGhostLaunchTime] debounce — enforces a [GHOST_LAUNCH_DEBOUNCE_MS] gap
     *   between consecutive launches to avoid hammering the clipboard API.
     *
     * @param trigger A short human-readable label describing which detection strategy
     *                fired, used only for debug logging.
     */
    private fun handleClipboardChange(trigger: String = "Unknown") {
        if (ignoreNextChange) return
        val now = System.currentTimeMillis()
        if (now - lastGhostLaunchTime < GHOST_LAUNCH_DEBOUNCE_MS) return
        lastGhostLaunchTime = now
        try {
            ClipboardGhostActivity.readFromClipboard(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ghost activity", e)
        }
    }

    // ── Firestore inbound listener (Mac → Android) ────────────────────────────

    /**
     * Attaches a Firestore real-time listener (via [FirestoreManager.listenToClipboard])
     * that watches for new clipboard items sent by the paired Mac.
     *
     * When a new item arrives the following steps occur atomically on the main thread:
     * 1. Duplicate content (already written or already synced) is discarded.
     * 2. [ignoreNextChange] is set to `true` so the accessibility event triggered by
     *    the clipboard write below is not echoed back to Firestore.
     * 3. The decrypted content is written to the Android clipboard via
     *    [ClipboardGhostActivity.copyToClipboard].
     * 4. A delayed runnable clears [ignoreNextChange] after [IGNORE_LOCAL_CHANGE_MS].
     *
     * Any previously registered listener is removed before attaching the new one to
     * avoid duplicate callbacks after re-pairing.
     */
    private fun startFirestoreListener() {
        firestoreListener?.remove()
        firestoreListener = FirestoreManager.listenToClipboard(this) { content: String ->
            try {
                if (content.isBlank() ||
                    content == lastSyncedContent ||
                    content == lastClipboardContent) return@listenToClipboard

                ignoreNextChange = true
                handler.removeCallbacks(clearIgnoreRunnable)

                lastSyncedContent     = content
                lastClipboardContent  = content
                lastReadClipboardHash = hashSHA256(content)

                ClipboardGhostActivity.copyToClipboard(this@ClipboardAccessibilityService, content)
                handler.postDelayed(clearIgnoreRunnable, IGNORE_LOCAL_CHANGE_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Error in Firestore listener", e)
                ignoreNextChange = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns `true` if the app identified by [packageName] is categorised as a game
     * by the system's [ApplicationInfo.CATEGORY_GAME] flag.
     *
     * Game apps are excluded from copy-detection entirely because they generate high
     * volumes of accessibility events that are unlikely to represent clipboard operations,
     * and processing them would waste battery and CPU unnecessarily.
     *
     * Returns `false` if the package cannot be resolved (e.g. the app was just uninstalled).
     */
    private fun isGameApp(packageName: String): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0).category == ApplicationInfo.CATEGORY_GAME
        } catch (_: Exception) {
            false
        }
    }
}
