package net.wastu.binderclip

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
    val fileName: String,
)

private val androidReleaseTag = Regex("(?:android-)?v([0-9]+\\.[0-9]+\\.[0-9]+)")

internal fun findAndroidRelease(releases: JSONArray): AppRelease? {
    for (i in 0 until releases.length()) {
        val release = releases.getJSONObject(i)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val version = androidReleaseTag.matchEntire(release.optString("tag_name"))
            ?.groupValues?.get(1) ?: continue
        val assets = release.optJSONArray("assets") ?: continue
        val fileName = "BinderClip-$version.apk"
        for (j in 0 until assets.length()) {
            val asset = assets.getJSONObject(j)
            if (asset.optString("name") != fileName || asset.optString("state") != "uploaded") continue
            val downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: continue
            return AppRelease(
                versionName = version,
                downloadUrl = downloadUrl,
                releaseNotes = release.optString("body").trim().takeIf { it.isNotEmpty() },
                fileName = fileName,
            )
        }
    }
    return null
}

interface UpdateSource {
    suspend fun checkLatestRelease(): AppRelease?
}

class GitHubReleaseSource(
    private val owner: String = "bgwastu",
    private val repo: String = "BinderClip",
) : UpdateSource {
    override suspend fun checkLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL("https://api.github.com/repos/$owner/$repo/releases")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "BinderClip-Android/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                findAndroidRelease(JSONArray(body))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

class AppUpdater(
    private val context: Context,
    private val source: UpdateSource = GitHubReleaseSource(),
) {
    /** Automatic checks reuse a recent result so reopening the app does not hit GitHub each time. */
    suspend fun checkForUpdate(force: Boolean = true): AppRelease? = withContext(Dispatchers.IO) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force) synchronized(Companion) {
            if (lastCheckMs != 0L && now - lastCheckMs < AUTO_CHECK_INTERVAL_MS) return@withContext lastResult
        }
        val latest = source.checkLatestRelease()?.takeIf { isNewer(it.versionName, currentVersionName()) }
        synchronized(Companion) { lastCheckMs = now; lastResult = latest }
        latest
    }

    suspend fun downloadApk(release: AppRelease, onProgress: ((Int) -> Unit)? = null): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destFile = File(dir, release.fileName)
            if (destFile.exists()) destFile.delete()

            var actualConn = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            var redirects = 0
            while (actualConn.responseCode in setOf(301, 302, 303, 307, 308) && redirects < 5) {
                val loc = actualConn.getHeaderField("Location") ?: break
                actualConn.disconnect()
                actualConn = (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                }
                redirects++
            }

            val totalBytes = actualConn.contentLengthLong
            actualConn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var copiedBytes = 0L
                    var lastPercent = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                        if (totalBytes > 0) {
                            val percent = ((copiedBytes * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent)
                            }
                        }
                    }
                }
            }
            destFile
        }.getOrNull()
    }

    fun installApk(file: File) {
        val authority = "${context.packageName}.images"
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            null
        }
    }

    private fun currentVersionName(): String {
        return BuildConfig.VERSION_NAME.removeSuffix("-debug")
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private var lastCheckMs = 0L
        private var lastResult: AppRelease? = null

        fun isNewer(latest: String, current: String): Boolean {
            val v1 = latest.split('.').mapNotNull { it.toIntOrNull() }
            val v2 = current.split('.').mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(v1.size, v2.size)
            for (i in 0 until maxLen) {
                val n1 = v1.getOrElse(i) { 0 }
                val n2 = v2.getOrElse(i) { 0 }
                if (n1 > n2) return true
                if (n1 < n2) return false
            }
            return false
        }
    }
}
