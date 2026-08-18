package com.bunty.clipsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A transparent activity that intercepts ACTION_SEND intents from other apps,
 * forwards them to LocalSyncManager for background transfer, and immediately finishes.
 * This prevents the full ClipSync UI from launching when sharing a file.
 *
 * WHY WE USE PARCEL FILE DESCRIPTORS (PFDs):
 * When another app (e.g. Google Files) shares a URI via the share sheet, Android grants the
 * URI permission ONLY to this Activity. Once the Activity finishes, that grant is revoked.
 * If we pass the URI directly to LocalSyncManager, its background coroutine workers run on a
 * different thread that never held the URI grant → SecurityException: requires grantUriPermission().
 * 
 * Instead of making a slow, bloated copy of the file in our cache, we simply open a
 * ParcelFileDescriptor while the Activity is alive. The Linux kernel keeps the underlying
 * file descriptor valid indefinitely, allowing LocalSyncManager to stream the file 
 * zero-copy without any temporary files!
 */
class ShareActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        when {
            Intent.ACTION_SEND == intent.action && intent.type != null -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

                when {
                    text != null -> {
                        // Plain text — no URI permission issue, safe to pass directly
                        LocalSyncManager.onClipboardContent(this, text, "text")
                        Toast.makeText(this, "Sharing text to Mac…", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    uri != null -> {
                        startTransferService(intent, single = true)
                    }
                    else -> finish()
                }
            }

            Intent.ACTION_SEND_MULTIPLE == intent.action && intent.type != null -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    startTransferService(intent, single = false)
                } else {
                    finish()
                }
            }

            else -> finish()
        }
    }

    private fun startTransferService(originalIntent: Intent, single: Boolean) {
        val serviceIntent = Intent(this, ShareTransferService::class.java).apply {
            // Re-attach the clipData containing the URIs to preserve the permission grant
            clipData = originalIntent.clipData ?: createClipData(originalIntent)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra("single", single)
        }

        try {
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
            // Immediately finish so the user goes back to their sharing app!
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start background transfer", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun createClipData(intent: Intent): android.content.ClipData? {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        
        if (uris.isEmpty()) return null
        
        val clipData = android.content.ClipData.newUri(contentResolver, "shared_file", uris.first())
        for (i in 1 until uris.size) {
            clipData.addItem(android.content.ClipData.Item(uris[i]))
        }
        return clipData
    }

    /**
     * Resolves a display name for the URI using OpenableColumns.
     * Must be called while the Activity is alive so the URI grant is still valid.
     */
    private fun resolveFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
