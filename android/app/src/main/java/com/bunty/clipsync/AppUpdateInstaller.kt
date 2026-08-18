package com.bunty.clipsync

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*

/**
 * Manages the full APK download + install lifecycle:
 *  - Downloads the APK to the public Downloads folder so Android's DownloadManager
 *    shows it in the notification shade with progress.
 *  - After completion posts a "Tap to install" notification.
 *  - Saves the local APK path to SharedPreferences so the app can surface a
 *    floating "ready to install" banner next time it is opened.
 */
object AppUpdateInstaller {
    private const val TAG = "AppUpdateInstaller"
    private const val PREFS_NAME   = "update_install_prefs"
    private const val KEY_APK_PATH = "ready_apk_path"
    private const val KEY_VERSION  = "ready_apk_version"

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val downloadedMb: Float,
            val totalMb: Float,
            val progress: Float
        ) : DownloadState()
        object ReadyToInstall : DownloadState()
        data class Failed(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null
    private val client = OkHttpClient()

    fun downloadAndInstall(context: Context, url: String, version: String, scope: CoroutineScope) {
        downloadJob = scope.launch(Dispatchers.IO) {
            val apkFile = File(context.getExternalFilesDir(null), "update_$version.apk")
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
                val body = response.body ?: throw Exception("Empty response")
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var downloadedBytes = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                            _downloadState.value = DownloadState.Downloading(
                                downloadedMb = downloadedBytes / 1_048_576f,
                                totalMb = totalBytes / 1_048_576f,
                                progress = progress.coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                saveReadyApk(context, version, apkFile.absolutePath)
                _downloadState.value = DownloadState.ReadyToInstall
            } catch (e: CancellationException) {
                _downloadState.value = DownloadState.Idle
                if (apkFile.exists()) apkFile.delete()
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Failed(e.message ?: "Download failed")
                if (apkFile.exists()) apkFile.delete()
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }

    private fun saveReadyApk(context: Context, version: String, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_APK_PATH, path)
            .putString(KEY_VERSION, version)
            .apply()
    }

    /**
     * Directly launch the package installer for an APK that is already on disk.
     */
    fun installReadyApk(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path  = prefs.getString(KEY_APK_PATH, null) ?: return
        val file  = File(path)
        if (!file.exists()) {
            Log.e(TAG, "Cached APK not found: $path — clearing stale entry")
            clearReadyApk(context)
            return
        }
        launchInstaller(context, file)
    }

    fun hasReadyApk(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path  = prefs.getString(KEY_APK_PATH, null) ?: return false
        val version = prefs.getString(KEY_VERSION, null) ?: return false
        val file = File(path)
        
        if (!file.exists()) {
            clearReadyApk(context)
            return false
        }
        
        val currentVersion = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" } catch (e: Exception) { "0.0.0" }
        if (!GithubUpdateChecker.isVersionNewer(currentVersion, version)) {
            file.delete()
            clearReadyApk(context)
            return false
        }
        
        return true
    }

    fun getReadyApkVersion(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_VERSION, null)
    }

    fun clearReadyApk(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_APK_PATH)
            .remove(KEY_VERSION)
            .apply()
    }

    private fun launchInstaller(context: Context, apkFile: File) {
        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }
}
