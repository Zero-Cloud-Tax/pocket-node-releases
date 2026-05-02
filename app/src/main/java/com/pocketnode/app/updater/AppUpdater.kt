package com.pocketnode.app.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val GITHUB_API_URL = "https://api.github.com/repos/Zero-Cloud-Tax/pocket-node-releases/releases/latest"
    private const val PREFS_NAME = "app_updater"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"

    // Returns a Pair of (Version String, Download URL) if an update is available.
    suspend fun checkForUpdate(context: Context): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                
                var tagName = json.getString("tag_name")
                if (tagName.startsWith("v")) {
                    tagName = tagName.substring(1)
                }

                val currentVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
                } catch (e: Exception) {
                    "0.0.0"
                }
                
                if (isRemoteVersionNewer(tagName, currentVersion)) {
                    val dismissedVersion = context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_DISMISSED_VERSION, null)
                    if (dismissedVersion == tagName) {
                        return@withContext null
                    }

                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.getString("name")
                        if (name.endsWith(".apk")) {
                            val downloadUrl = asset.getString("browser_download_url")
                            return@withContext Pair(tagName, downloadUrl)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates", e)
        }
        return@withContext null
    }

    private fun isRemoteVersionNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remoteParts = versionParts(remoteVersion) ?: return false
        val currentParts = versionParts(currentVersion) ?: return false
        val maxSize = maxOf(remoteParts.size, currentParts.size)

        for (i in 0 until maxSize) {
            val remotePart = remoteParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (remotePart > currentPart) return true
            if (remotePart < currentPart) return false
        }

        return false
    }

    private fun versionParts(version: String): List<Int>? {
        val parts = Regex("""\d+""")
            .findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

        return parts.ifEmpty { null }
    }

    fun dismissVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply()
    }

    fun downloadAndInstall(context: Context, url: String, version: String) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri).apply {
            setTitle("Pocket Node Update")
            setDescription("Downloading version $version")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            
            // Download to the app's external files directory so we don't need storage permissions
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "pocketnode_update_$version.apk")
        }

        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, version)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context, version: String) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "pocketnode_update_$version.apk")
            if (!file.exists()) return

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }
}
