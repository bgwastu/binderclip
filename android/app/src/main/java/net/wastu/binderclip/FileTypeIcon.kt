package net.wastu.binderclip

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

/** Prefer the provider's MIME type; generic/unknown types use the filename extension. */
internal fun fileTypeIcon(mimeType: String?, fileName: String): ImageVector {
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    when {
        mime?.startsWith("image/") == true -> return Icons.Rounded.Image
        mime?.startsWith("video/") == true -> return Icons.Rounded.VideoFile
        mime?.startsWith("audio/") == true -> return Icons.Rounded.Audiotrack
        mime == "application/pdf" -> return Icons.Rounded.Description
        mime == "application/vnd.android.package-archive" -> return Icons.Rounded.Android
        mime == "text/csv" || mime?.contains("spreadsheet") == true || mime?.contains("excel") == true -> return Icons.Rounded.TableChart
        mime?.contains("presentation") == true || mime?.contains("powerpoint") == true -> return Icons.Rounded.Slideshow
        mime?.contains("zip") == true || mime?.contains("compressed") == true || mime in setOf("application/x-tar", "application/vnd.rar", "application/x-rar") -> return Icons.Rounded.Archive
        mime in setOf("application/json", "application/xml", "text/html", "text/xml", "text/css", "text/javascript", "application/javascript") -> return Icons.Rounded.Code
        mime?.startsWith("text/") == true || mime?.contains("word") == true || mime?.contains("opendocument.text") == true || mime == "application/rtf" -> return Icons.Rounded.Article
    }
    if (!mime.isNullOrBlank() && mime != "application/octet-stream") return Icons.Rounded.InsertDriveFile
    return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "svg", "bmp", "tif", "tiff" -> Icons.Rounded.Image
        "mp4", "mkv", "mov", "webm", "avi", "m4v" -> Icons.Rounded.VideoFile
        "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus" -> Icons.Rounded.Audiotrack
        "pdf" -> Icons.Rounded.Description
        "apk" -> Icons.Rounded.Android
        "csv", "xls", "xlsx", "ods" -> Icons.Rounded.TableChart
        "ppt", "pptx", "odp" -> Icons.Rounded.Slideshow
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz" -> Icons.Rounded.Archive
        "json", "xml", "html", "css", "js", "ts", "py", "kt", "swift", "java", "sh" -> Icons.Rounded.Code
        "txt", "md", "rtf", "doc", "docx", "odt" -> Icons.Rounded.Article
        else -> Icons.Rounded.InsertDriveFile
    }
}
