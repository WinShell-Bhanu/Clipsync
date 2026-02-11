package com.bunty.clipsync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    // --- API Configuration ---
    // Fetches latest release tag from GitHub public API
    private const val TAG = "UpdateChecker"
    private const val REPO_OWNER = "WinShell-Bhanu"
    private const val REPO_NAME = "Clipsync"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                // GitHub requires a User-Agent header
                connection.setRequestProperty("User-Agent", "ClipSync-Android-App")

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    
                    val latestTag = json.getString("tag_name") // e.g., "v1.0"
                    val htmlUrl = json.getString("html_url")
                    val body = json.optString("body", "New update available!")

                    // Remove 'v' prefix for comparison if present
                    val cleanLatest = latestTag.removePrefix("v")
                    val cleanCurrent = currentVersion.removePrefix("v")

                    if (isVersionNewer(cleanCurrent, cleanLatest)) {
                        return@withContext UpdateInfo(latestTag, htmlUrl, body)
                    }
                } else {
                    Log.e(TAG, "GitHub API returned code: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates", e)
            }
            return@withContext null
        }
    }

    // --- Version Logic ---
    // Compares semantic versioning strings (e.g. 1.0.0 vs 1.0.1)
    private fun isVersionNewer(current: String, latest: String): Boolean {
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            val length = maxOf(currentParts.size, latestParts.size)
            
            for (i in 0 until length) {
                val c = if (i < currentParts.size) currentParts[i] else 0
                val l = if (i < latestParts.size) latestParts[i] else 0
                
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Version parsing failed", e)
        }
        return false
    }
}
