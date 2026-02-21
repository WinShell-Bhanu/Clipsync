package com.bunty.clipsync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * UpdateChecker polls the GitHub Releases API to see if a newer version of ClipSync
 * is available and returns the release metadata so the UI can show an update prompt.
 *
 * Uses the public GitHub REST API (no auth token required for public repos).
 * All network I/O runs on [Dispatchers.IO].
 *
 * **Usage (from a coroutine):**
 * ```kotlin
 * val info = UpdateChecker.checkForUpdates("v1.0.0")
 * if (info != null) showUpdateDialog(info)
 * ```
 */
object UpdateChecker {

    private const val TAG        = "UpdateChecker"
    private const val REPO_OWNER = "WinShell-Bhanu"
    private const val REPO_NAME  = "Clipsync"

    /** Full GitHub API URL for the latest release of the ClipSync repo. */
    private const val GITHUB_API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /**
     * Metadata for a GitHub release that is newer than the currently installed version.
     *
     * @param version      The release tag (e.g. `"v1.2.0"`).
     * @param downloadUrl  The HTML URL of the GitHub release page (used to open in browser).
     * @param releaseNotes The release body text (markdown), shown in the update dialog.
     */
    data class UpdateInfo(
        val version:      String,
        val downloadUrl:  String,
        val releaseNotes: String
    )

    /**
     * Checks whether a newer release exists on GitHub.
     *
     * Makes an HTTP GET to [GITHUB_API_URL], parses the JSON response, and compares
     * the `tag_name` to [currentVersion] using [isVersionNewer].
     *
     * @param currentVersion The version string of the installed build (e.g. `"v1.0.0"`
     *                       or `"1.0.0"`; the leading `"v"` is stripped automatically).
     * @return An [UpdateInfo] instance if a newer version is available, or `null` if the
     *         app is up to date or the check fails.
     */
    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod  = "GET"
                connection.connectTimeout = 5000   // 5-second connection timeout
                connection.readTimeout    = 5000   // 5-second read timeout

                // GitHub API requires a User-Agent header; use the app name
                connection.setRequestProperty("User-Agent", "ClipSync-Android-App")

                if (connection.responseCode == 200) {
                    val response  = connection.inputStream.bufferedReader().use { it.readText() }
                    val json      = JSONObject(response)
                    val latestTag = json.getString("tag_name")   // e.g. "v1.2.0"
                    val htmlUrl   = json.getString("html_url")   // release page URL
                    val body      = json.optString("body", "New update available!")

                    // Strip the leading "v" so we can compare numeric version parts
                    val cleanLatest  = latestTag.removePrefix("v")
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
            return@withContext null  // up to date or request failed
        }
    }

    /**
     * Returns `true` if [latest] is a higher semantic version than [current].
     *
     * Both strings should be dot-separated integers (e.g. `"1.2.0"`). Missing
     * parts are treated as `0` (e.g. `"1.2"` == `"1.2.0"`).
     *
     * @param current The installed version string (no `"v"` prefix).
     * @param latest  The GitHub release version string (no `"v"` prefix).
     */
    private fun isVersionNewer(current: String, latest: String): Boolean {
        return try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts  = latest.split(".").map  { it.toIntOrNull() ?: 0 }

            val length = maxOf(currentParts.size, latestParts.size)

            for (i in 0 until length) {
                val c = if (i < currentParts.size) currentParts[i] else 0
                val l = if (i < latestParts.size)  latestParts[i]  else 0

                if (l > c) return true   // latest has a higher part → newer
                if (l < c) return false  // current has a higher part → already newer
            }
            false  // all parts equal → same version
        } catch (e: Exception) {
            Log.e(TAG, "Version parsing failed", e)
            false
        }
    }
}
