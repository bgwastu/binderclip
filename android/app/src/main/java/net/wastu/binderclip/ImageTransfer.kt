package net.wastu.binderclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ImagePayload(val id: String = UUID.randomUUID().toString(), val mimeType: String, val data: ByteArray) {
    val sha256: String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        .also { EnergyCounters.add("image.hash.bytes", data.size.toLong()) }

    init {
        require(mimeType in ALLOWED_MIME_TYPES && data.isNotEmpty() && data.size <= MAXIMUM_BYTES) { "Unsupported image" }
    }

    companion object {
        const val MAXIMUM_BYTES = 30 * 1_024 * 1_024
        // This stays comfortably below the encrypted frame limit once JSON and
        // base64 overhead are included, while avoiding a round trip per 60 KiB.
        const val CHUNK_BYTES = 192 * 1_024
        val ALLOWED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/heic")
    }
}

object ImageClipboard {
    fun readUri(context: Context, uri: Uri, declaredMime: String? = null): ImagePayload? = runCatching {
        val mime = declaredMime?.lowercase()?.takeIf { it in ImagePayload.ALLOWED_MIME_TYPES }
            ?: context.contentResolver.getType(uri)?.lowercase()?.takeIf { it in ImagePayload.ALLOWED_MIME_TYPES }
            ?: return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(ImagePayload.MAXIMUM_BYTES) } ?: return null
        ImagePayload(mimeType = mime, data = bytes)
    }.onFailure {
        android.util.Log.w("BinderClip", "Could not read shared image", it)
        DiagnosticLog.error("Could not read shared image: ${it.message ?: "unsupported content"}")
    }.getOrNull()

    fun read(context: Context, clipboard: ClipboardManager): ImagePayload? = runCatching {
        val clip = clipboard.primaryClip ?: return null
        val mime = clip.description.filterMimeTypes("image/*")
            ?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES } ?: return null
        val uri = clip.getItemAt(0).uri ?: return null
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBounded(ImagePayload.MAXIMUM_BYTES)
        } ?: return null
        ImagePayload(mimeType = mime, data = bytes)
    }.onFailure {
        android.util.Log.w("BinderClip", "Could not read clipboard image", it)
        DiagnosticLog.warning("Could not read clipboard image: ${it.message ?: "unsupported content"}")
    }.getOrNull()

    fun write(context: Context, clipboard: ClipboardManager, image: ImagePayload) {
        val directory = File(context.cacheDir, "clipboard-images").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        val extension = when (image.mimeType) {
            "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "heic"
        }
        val file = File(directory, "${image.sha256}.$extension")
        file.writeBytes(image.data)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
        val description = android.content.ClipDescription(
            "BinderClip image",
            arrayOf(image.mimeType, "image/*", android.content.ClipDescription.MIMETYPE_TEXT_URILIST)
        )
        clipboard.setPrimaryClip(ClipData(description, ClipData.Item(uri)))
    }

    fun clearStale(context: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
        File(context.cacheDir, "clipboard-images").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Image exceeds ${limit / (1_024 * 1_024)} MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
