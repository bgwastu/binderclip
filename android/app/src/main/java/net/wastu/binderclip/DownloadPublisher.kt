package net.wastu.binderclip

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File

/** Publish before acknowledging the transfer. Retain the target URI across a reconnect or restart. */
internal class DownloadPublisher(private val resolver: ContentResolver) {
    fun publish(file: ReceivedFile): String {
        val uriRecord = AtomicFile(File(file.file.parentFile, "download"))
        val previous = runCatching { Uri.parse(uriRecord.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        val pending = previous?.let(::isPending)
        if (pending == false) return previous.toString()

        val uri = if (pending == true) checkNotNull(previous) else {
            val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.metadata.name)
                put(MediaStore.MediaColumns.MIME_TYPE, file.metadata.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("Could not create download")
            try {
                val output = uriRecord.startWrite()
                try {
                    output.write(newUri.toString().toByteArray(Charsets.UTF_8))
                    uriRecord.finishWrite(output)
                } catch (e: Exception) {
                    uriRecord.failWrite(output)
                    throw e
                }
            } catch (e: Exception) {
                resolver.delete(newUri, null, null)
                throw e
            }
            newUri
        }
        resolver.openOutputStream(uri, "wt")?.use { output ->
            file.file.inputStream().use { it.copyTo(output, 64 * 1024) }
        } ?: error("Could not write download")
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        return uri.toString()
    }

    private fun isPending(uri: Uri): Boolean? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) != 0 else null
        }
}
