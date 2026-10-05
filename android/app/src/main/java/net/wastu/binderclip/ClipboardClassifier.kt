package net.wastu.binderclip

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context

sealed interface LocalClipboardContent {
    data class Text(val value: String) : LocalClipboardContent
    data class Image(val value: ImagePayload) : LocalClipboardContent
    data object Unsupported : LocalClipboardContent
}

/**
 * Classifies only text and supported image media. URI items are never coerced
 * to text: Android would turn a copied file into its display name.
 */
object ClipboardClassifier {
    fun read(context: Context, clipboard: AppClipboard): LocalClipboardContent {
        val clip = clipboard.primaryClip ?: return LocalClipboardContent.Unsupported
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return LocalClipboardContent.Unsupported

        val hasImageMime = clip.description?.filterMimeTypes("image/*")?.isNotEmpty() == true
        val directText = item.text?.toString()?.takeIf { it.isNotBlank() }

        // 1. Direct text / URL precedence: If plain text contains an HTTP/HTTPS URL or description is text-only, prioritize text
        if (directText != null) {
            val trimmed = directText.trim().lowercase()
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || !hasImageMime) {
                return LocalClipboardContent.Text(directText)
            }
        }

        // 2. Image content (via image MIME description, file URI, or image content URI)
        if (item.uri != null) {
            val scheme = item.uri.scheme?.lowercase()
            if (scheme == "content" || scheme == "file" || hasImageMime) {
                ImageClipboard.readUri(context, item.uri, clip.description?.getMimeType(0))?.let {
                    return LocalClipboardContent.Image(it)
                }
                // If it's a file scheme and not a supported image, fail-closed
                if (scheme == "file") return LocalClipboardContent.Unsupported
            } else if (scheme == "http" || scheme == "https") {
                return LocalClipboardContent.Text(item.uri.toString())
            }
        }

        // 3. Fallback to direct plain text
        if (directText != null) {
            return LocalClipboardContent.Text(directText)
        }

        // 4. Coerced text (HTML or formatted text)
        val coerced = runCatching { item.coerceToText(context)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (coerced != null) {
            return LocalClipboardContent.Text(coerced)
        }

        return LocalClipboardContent.Unsupported
    }
}
