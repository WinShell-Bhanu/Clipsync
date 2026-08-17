package com.bunty.clipsync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GithubUpdateChecker {
    private const val TAG = "GithubUpdateChecker"
    private const val REPO_URL = "https://api.github.com/repos/WinShell-Bhanu/Clipsync/releases/latest"
    
    private const val PREFS_NAME = "github_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val KEY_CACHED_URL = "cached_url"
    private const val KEY_CACHED_NOTES = "cached_notes"
    
    // Cache duration: 6 hours
    private const val CACHE_DURATION_MS = 6 * 60 * 60 * 1000L

    data class GithubRelease(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    /**
     * Checks for the latest release on GitHub. 
     * Uses a 6-hour cache to prevent rate-limiting by the GitHub API.
     */
    suspend fun checkForUpdate(context: Context): GithubRelease? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheckTime = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
        
        
        // Return cached result if within the cache duration
        if (System.currentTimeMillis() - lastCheckTime < CACHE_DURATION_MS) {
            val cachedVersion = prefs.getString(KEY_CACHED_VERSION, null)
            val cachedUrl = prefs.getString(KEY_CACHED_URL, null)
            if (cachedVersion != null && cachedUrl != null) {
                val cachedNotes = prefs.getString(KEY_CACHED_NOTES, "New update available!") ?: "New update available!"
                
                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get local version for cache check", e)
                    "0.0.0"
                }
                
                if (isVersionNewer(currentVersion, cachedVersion)) {
                    return@withContext GithubRelease(cachedVersion, cachedUrl, cachedNotes)
                }
                return@withContext null
            } else {
            }
        }

        // Fetch fresh data from GitHub API
        var connection: HttpURLConnection? = null
        try {
            val url = URL(REPO_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            // GitHub API often requires a valid User-Agent header or it returns 403 Forbidden
            connection.setRequestProperty("User-Agent", "ClipSync-Android-App")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                // version tag (e.g., "v1.2.0" or "1.2.0")
                var tagName = json.optString("tag_name", "")
                if (tagName.startsWith("v", ignoreCase = true)) {
                    tagName = tagName.substring(1)
                }
                
                val releaseNotes = json.optString("body", "New update available from GitHub!")
                
                val assets = json.optJSONArray("assets")
                var downloadUrl = ""
                
                if (assets != null && assets.length() > 0) {
                    // Try to find an APK asset
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val assetName = asset.optString("name", "")
                        if (assetName.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                    
                    // Fallback to first asset if no APK found (unlikely, but safe)
                    if (downloadUrl.isEmpty()) {
                        downloadUrl = assets.getJSONObject(0).optString("browser_download_url", "")
                    }
                }

                if (tagName.isNotEmpty() && downloadUrl.isNotEmpty()) {
                    // Cache the successful response
                    prefs.edit().apply {
                        putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                        putString(KEY_CACHED_VERSION, tagName)
                        putString(KEY_CACHED_URL, downloadUrl)
                        putString(KEY_CACHED_NOTES, releaseNotes)
                        apply()
                    }

                    val currentVersion = try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get local version for fresh check", e)
                        "0.0.0"
                    }

                    if (isVersionNewer(currentVersion, tagName)) {
                        return@withContext GithubRelease(tagName, downloadUrl, releaseNotes)
                    } else {
                    }
                } else {
                }
            } else {
                Log.e(TAG, "GitHub API returned ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching GitHub release", e)
        } finally {
            connection?.disconnect()
        }
        
        return@withContext null
    }

    /**
     * Semantically compares two version strings.
     * Prevents the classic "1.9.0" > "1.10.0" string comparison footgun.
     * e.g., "1.2.0" vs "1.3.0" -> returns true
     * e.g., "1.10.0" vs "1.9.0" -> returns false
     */
    fun isVersionNewer(currentVersion: String, newVersion: String): Boolean {
        try {
            // Strip any non-numeric prefixes/suffixes like "v" or "-beta" (simplified)
            val currentClean = currentVersion.replace(Regex("[^0-9.]"), "")
            val newClean = newVersion.replace(Regex("[^0-9.]"), "")

            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }
            val newParts = newClean.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLength = maxOf(currentParts.size, newParts.size)
            
            for (i in 0 until maxLength) {
                val curPart = if (i < currentParts.size) currentParts[i] else 0
                val newPart = if (i < newParts.size) newParts[i] else 0
                
                if (newPart > curPart) {
                    return true
                }
                if (newPart < curPart) {
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
        }
        return false // If equal or error, it's not newer
    }
    
    /**
     * Forces a fresh check by clearing the last check time.
     */
    fun clearCache(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_CHECK_TIME)
            .apply()
    }
}
