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
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                if (rel.optBoolean("draft", false) || rel.optBoolean("prerelease", false)) continue
                val tag = rel.optString("tag_name", "").removePrefix("v").removePrefix("android-v")
                val notes = rel.optString("body", "").trim().takeIf { it.isNotEmpty() }
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        val downloadUrl = asset.optString("browser_download_url", "")
                        if (downloadUrl.isNotBlank() && tag.isNotBlank()) {
                            return@runCatching AppRelease(
                                versionName = tag,
                                downloadUrl = downloadUrl,
                                releaseNotes = notes,
                                fileName = name,
                            )
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }
}

class GenericJsonUpdateSource(
    private val feedUrl: String,
) : UpdateSource {
    override suspend fun checkLatestRelease(): AppRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(feedUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            AppRelease(
                versionName = json.getString("versionName"),
                downloadUrl = json.getString("downloadUrl"),
                releaseNotes = json.optString("releaseNotes", "").takeIf { it.isNotEmpty() },
                fileName = json.optString("fileName", "BinderClip-update.apk"),
            )
        }.getOrNull()
    }
}

class AppUpdater(
    private val context: Context,
    private val source: UpdateSource = GitHubReleaseSource(),
) {
    suspend fun checkForUpdate(): AppRelease? = withContext(Dispatchers.IO) {
        val latest = source.checkLatestRelease() ?: return@withContext null
        if (isNewer(latest.versionName, currentVersionName())) {
            latest
        } else {
            null
        }
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
