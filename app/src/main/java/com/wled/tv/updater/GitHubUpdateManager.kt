package com.wled.tv.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.wled.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkName: String = ""
)

class GitHubUpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiUrl = "https://api.github.com/repos/leonida92/wled-tv/releases/latest"

    suspend fun checkForUpdates(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "WLED-TV-App")
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub API returned code ${response.code}")
                    return@withContext UpdateInfo(false, BuildConfig.VERSION_NAME, "", "")
                }

                val body = response.body?.string() ?: return@withContext UpdateInfo(false, BuildConfig.VERSION_NAME, "", "")
                val json = JSONObject(body)

                val tagName = json.optString("tag_name", "").trim()
                val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
                val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

                val releaseNotes = json.optString("body", "").trim()

                var apkDownloadUrl = ""
                var selectedApkName = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    val candidateAssets = mutableListOf<Pair<String, String>>()
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (name.isNotBlank() && downloadUrl.isNotBlank()) {
                            candidateAssets.add(name to downloadUrl)
                        }
                    }

                    val selectedApk = selectReleaseApkAsset(candidateAssets)
                    if (selectedApk != null) {
                        selectedApkName = selectedApk.first
                        apkDownloadUrl = selectedApk.second
                        Log.i(TAG, "Selected release APK asset: $selectedApkName ($apkDownloadUrl)")
                    } else {
                        Log.w(TAG, "No suitable release APK found in release $tagName (candidates: ${candidateAssets.map { it.first }})")
                    }
                }

                val isNewer = isVersionNewer(cleanRemoteVersion, currentVersion)
                UpdateInfo(
                    hasUpdate = isNewer && apkDownloadUrl.isNotBlank(),
                    latestVersion = cleanRemoteVersion,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkDownloadUrl,
                    apkName = selectedApkName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for GitHub updates", e)
            UpdateInfo(false, BuildConfig.VERSION_NAME, "", "")
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (downloadUrl.isBlank()) return@withContext false

        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "wled-tv-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "WLED-TV-App")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val responseBody = response.body ?: return@withContext false
                val totalLength = responseBody.contentLength()
                var downloadedBytes = 0L

                responseBody.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloadedBytes * 100) / totalLength).toInt()
                                onProgress(progress)
                            }
                        }
                        output.flush()
                    }
                }
            }

            // Launch package installer on Main Thread
            withContext(Dispatchers.Main) {
                installApk(context, apkFile)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download update APK", e)
            false
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
        }
    }

    fun isVersionNewer(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        if (remote == current) return false

        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * Selects the release APK asset and explicitly rejects debug and unsigned APKs.
     * Prioritization:
     * 1. Assets ending with .apk that explicitly contain "release" (case-insensitive)
     *    and do not contain "debug" or "unsigned".
     * 2. Assets ending with .apk that do not contain "debug" or "unsigned" (e.g. wled-tv-v1.2.0.apk).
     * Debug and unsigned APKs are strictly rejected to avoid installation failures.
     */
    fun selectReleaseApkAsset(assets: List<Pair<String, String>>): Pair<String, String>? {
        val apkAssets = assets.filter { (name, url) ->
            name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()
        }

        // 1. Explicit release APK (e.g. wled-tv-v1.2.0-release.apk)
        val explicitRelease = apkAssets.firstOrNull { (name, _) ->
            name.contains("release", ignoreCase = true) &&
                !name.contains("debug", ignoreCase = true) &&
                !name.contains("unsigned", ignoreCase = true)
        }
        if (explicitRelease != null) return explicitRelease

        // 2. Generic APK without "debug" or "unsigned"
        val genericClean = apkAssets.firstOrNull { (name, _) ->
            !name.contains("debug", ignoreCase = true) &&
                !name.contains("unsigned", ignoreCase = true)
        }
        if (genericClean != null) return genericClean

        return null
    }

    companion object {
        private const val TAG = "GitHubUpdateManager"
    }
}
