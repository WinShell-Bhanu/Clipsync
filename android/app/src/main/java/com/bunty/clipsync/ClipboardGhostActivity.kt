package com.bunty.clipsync

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * --- Background Clipboard Hack ---
 * Android 10+ blocks background services from reading the clipboard.
 * This transparent Activity launches briefly to gain 'Foreground' status, 
 * reads/writes simple text, and then instantly closes.
 */
class ClipboardGhostActivity : Activity() {

    private var hasReadClipboard = false

    companion object {
        const val EXTRA_CLIP_TEXT = "extra_clip_text"
        
        // --- Intent Actions ---
        const val ACTION_READ = "action_read"
        const val ACTION_WRITE = "action_write"

        fun copyToClipboard(context: Context, text: String) {
            val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_WRITE
                putExtra(EXTRA_CLIP_TEXT, text)
            }
            context.startActivity(intent)
        }

        fun readFromClipboard(context: Context) {
            val intent = Intent(context, ClipboardGhostActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_READ
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }

        super.onCreate(savedInstanceState)

        if (intent.action == ACTION_WRITE) {
            val text = intent.getStringExtra(EXTRA_CLIP_TEXT)
            if (!text.isNullOrEmpty()) {
                copyTextToClipboard(text)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        
        if (hasFocus && intent.action == ACTION_READ && !hasReadClipboard) {
            hasReadClipboard = true
            window.decorView.post {
                readClipboardAndFinish()
            }
        }
    }

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

            if (text.isNotBlank()) {
                ClipboardAccessibilityService.onClipboardRead(this, text)
            }

        } catch (e: Exception) {
            Log.e("ClipboardGhost", "Failed to read clipboard", e)
        } finally {
            finish()
        }
    }

    private fun copyTextToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Text", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("ClipboardGhost", "Failed to set clipboard", e)
        }
    }

    override fun finish() {
        super.finish()
        // Disable closing animations for all finish() calls
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}