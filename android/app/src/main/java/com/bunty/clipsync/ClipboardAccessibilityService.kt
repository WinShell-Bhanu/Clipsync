package com.bunty.clipsync

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.View
import android.provider.Settings

class ClipboardAccessibilityService : AccessibilityService() {

    private var ignoreNextChange = false
    private lateinit var clipboardManager: ClipboardManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastUploadedContent: String = ""
    private var lastClipboardContent: String = ""
    private var lastEventTime = 0L

    private var lastRootScanTime = 0L
    private var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null

    // --- Service Description ---
    // This service listens for 'Copy' events system-wide using Accessibility APIs.
    // It is the workaround for Android 10+ restrictions on background clipboard access.

    companion object {
        private const val TAG = "ClipSync_Service"
        var isRunning = false

        @Volatile
        var lastSyncedContent: String = ""

        @Volatile
        var lastReadClipboardHash: String = "" // Track clipboard hash to prevent duplicate reads

        fun onClipboardRead(context: Context, text: String) {
            if (text.isBlank()) {
                return
            }

            val currentHash = text.hashCode().toString()

            if (currentHash == lastReadClipboardHash) {
                return
            }

            if (text == lastSyncedContent) {
                lastReadClipboardHash = currentHash
                return
            }

            lastSyncedContent = text
            lastReadClipboardHash = currentHash
            uploadToFirestoreStatic(context.applicationContext, text)
        }

        private fun uploadToFirestoreStatic(context: Context, text: String) {
            try {
                FirestoreManager.sendClipboard(
                    context = context,
                    text = text,
                    onSuccess = {
                        // Upload successful
                    },
                    onFailure = { e: Exception ->
                        Log.e(TAG, "Upload Failed", e)
                        if (lastSyncedContent == text) {
                            lastSyncedContent = ""
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in uploadToFirestoreStatic", e)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true

        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            startFirestoreListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            val eventTime = event.eventTime
            // Debounce: Ignore events if they happen too close to the last one (1 second)
            // This prevents double-triggering if an app shows a Toast AND a Snackbar.
            if (eventTime - lastEventTime < 1000) {
                return
            }

            // Ignore events from our own app to prevent loops (e.g. reading our own "Copied" toast)
            if (event.packageName == packageName) {
                return
            }

            when (event.eventType) {
                // Detect "Copied" Toast messages
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (event.className == "android.widget.Toast") {
                        val text = event.text.toString()
                        if (text.contains("copied", ignoreCase = true)) {
                            lastEventTime = eventTime
                            handler.postDelayed({
                                handleClipboardChange("Toast Notification")
                            }, 50)
                        }
                    }
                }

                // Detect "Copy" interaction
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val contentDesc = event.contentDescription?.toString() ?: ""
                    val eventText = event.text.joinToString(" ")
                    val isClick = (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)

                    var triggerType: String? = null

                    // --- Heuristic Detection Logic ---
                    // 1. CLICK Events: We accept "Copy" or "Copied" text.
                    // 2. PASSIVE Events: We REJECT "Copy" (imperative) to avoid false positives (menus).
                    //    We only accept "Copied" (past tense) or specific View ID matches.

                    val hasCopy = (contentDesc.contains(
                        "copy",
                        ignoreCase = true
                    ) || eventText.contains("copy", ignoreCase = true))
                    val hasCopied = (contentDesc.contains(
                        "copied",
                        ignoreCase = true
                    ) || eventText.contains("copied", ignoreCase = true))
                    val isCopyright =
                        (contentDesc.contains("copyright", ignoreCase = true) || eventText.contains(
                            "copyright",
                            ignoreCase = true
                        ))

                    if (!isCopyright) {
                        if (isClick && hasCopy) {
                            triggerType = "Click (Copy Button)"
                        } else if (hasCopied) {
                            triggerType = "Passive (Content Copied)"
                        } else if (hasCopy && !isClick) {
                            // It has "Copy" but it wasn't a click. It's likely a menu opening.
                            // We IGNORE this specific text match.
                        }
                    }

                    // --- Strategy 2: Deep Search (DFS) ---
                    // If simple event text match failed, traverse the Node Tree. 
                    // IMPORTANT: We pass 'isClick' to dfsFindCopy to apply the same strict rules depths-wise.
                    var source = event.source
                    if (triggerType == null && source != null) {
                        try {
                            if (dfsFindCopy(source, isClick = isClick)) {
                                triggerType = "Deep Search (Source)"
                            }
                        } finally {
                            source.recycle()
                        }
                    }

                    // --- Strategy 3: Full Screen Scan (Fallback) ---
                    // Expensive operation. Only triggered on Window State Changes or throttled (2s).
                    
                    val now = System.currentTimeMillis()
                    val isWindowStateChange = (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    val timeSinceLastRootScan = now - lastRootScanTime
                    
                    if (triggerType == null) {
                         // Only scan if it's a new window OR we haven't scanned in a while
                         if (isWindowStateChange || timeSinceLastRootScan > 2000) {
                            val rootNode = rootInActiveWindow
                            if (rootNode != null) {
                                try {
                                    lastRootScanTime = now
                                    // For root scan (always passive), we treat isClick = false
                                    if (dfsFindCopy(rootNode, isClick = false)) {
                                        triggerType = "Root Window Scan"
                                    }
                                } finally {
                                    rootNode.recycle()
                                }
                            }
                         }
                    }

                    if (triggerType != null) {
                        lastEventTime = eventTime
                        handler.postDelayed({
                            handleClipboardChange(triggerType)
                        }, 50)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent", e)
        }
    }

    private fun dfsFindCopy(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int = 0,
        isClick: Boolean = false
    ): Boolean {
        if (node == null) return false
        if (depth > 5) return false
        if (!node.isVisibleToUser) return false // Optimization: Skip invisible nodes

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        
        // Optimisation: A "Copy" button or "Copied" toast is rarely a paragraph of text.
        // If the text is huge, it's likely content, not a control. 
        // We skip string matching on huge text to save CPU, unless it's an ID match we are looking for.
        // "Copy to clipboard" is 17 chars. 30 is a safe upper limit.
        if ((text.length > 30 || contentDesc.length > 30) && viewId.isEmpty()) {
             // If ID is present, we still check it (e.g. smart_reply_action might have no text but a valid ID)
             // But if just text, skip.
             // Continuing directly to children might be safer than returning false?
             // Actually, if the container has long text but children are buttons, we MUST recurse.
             // So we just skip the *local* string check, but continue to loop children.
        } else {
            val combined = "$text $contentDesc $viewId".trim()

            if (combined.contains("copyright", ignoreCase = true)) return false

            val hasCopy = combined.contains("copy", ignoreCase = true)
            val hasCopied = combined.contains("copied", ignoreCase = true)
            val hasIdMatch = viewId.contains("copy", ignoreCase = true)

            if (isClick && hasCopy) {
                return true
            }

            if (!isClick) {
                if (hasCopied) {
                    return true
                }
                if (hasIdMatch) {
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    if (dfsFindCopy(child, depth + 1, isClick)) {
                        return true
                    }
                } finally {
                    child.recycle()
                }
            }
        }
        return false
    }

    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "NOTIFICATION_STATE_CHANGED"
            else -> "UNKNOWN($eventType)"
        }
    }

    private fun handleClipboardChange(trigger: String = "Unknown") {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay Permission Missing! Cannot launch Ghost Activity.")
            return
        }

        try {
            ClipboardGhostActivity.readFromClipboard(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Ghost Activity", e)
        }
    }



    private fun startFirestoreListener() {
        try {
            // Remove existing listener to prevent duplicates
            firestoreListener?.remove()
            
            firestoreListener = FirestoreManager.listenToClipboard(this) { content: String ->
                try {
                    if (content == lastSyncedContent || content == lastClipboardContent) {
                        return@listenToClipboard
                    }

                    ignoreNextChange = true
                    lastSyncedContent = content
                    lastClipboardContent = content

                    ClipboardGhostActivity.copyToClipboard(
                        this@ClipboardAccessibilityService,
                        content
                    )

                    handler.postDelayed({
                        ignoreNextChange = false
                    }, 2000)

                } catch (e: Exception) {
                    Log.e(TAG, "Error in Firestore listener", e)
                    ignoreNextChange = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Firestore listener", e)
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (::clipboardManager.isInitialized) {
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing clipboard listener", e)
        }

        firestoreListener?.remove()
        firestoreListener = null
        handler.removeCallbacksAndMessages(null)
        isRunning = false
    }
}




