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
 * The one classification policy shared by the foreground reader, the root reader, and the
 * Shizuku UserService, so the three capture paths cannot drift apart. It fails closed: URI
 * items are never coerced to text, because Android would turn a copied file into its name.
 */
object ClipboardRules {
    enum class Plan { DirectText, Image, WebUri, Coerce, Unsupported }

    fun isWebUrl(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    /** @param imageMime the first allowed image MIME type the clip declares, if any. */
    fun plan(directText: String?, uriScheme: String?, hasUri: Boolean, imageMime: String?): Plan = when {
        directText != null && (isWebUrl(directText) || imageMime == null) -> Plan.DirectText
        hasUri && (uriScheme == "content" || uriScheme == "file" || imageMime != null) -> Plan.Image
        hasUri && (uriScheme == "http" || uriScheme == "https") -> Plan.WebUri
        directText != null -> Plan.DirectText
        !hasUri -> Plan.Coerce
        else -> Plan.Unsupported
    }

    /** An image URI that could not be read only falls back to text the clip carried directly. */
    fun afterImageFailure(directText: String?): Plan =
        if (directText != null) Plan.DirectText else Plan.Unsupported
}

object ClipboardClassifier {
    fun read(context: Context, clipboard: AppClipboard): LocalClipboardContent {
        val clip = clipboard.primaryClip ?: return LocalClipboardContent.Unsupported
        val item = clip.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return LocalClipboardContent.Unsupported
        val imageMime = clip.description?.filterMimeTypes("image/*")?.firstOrNull { it in ImagePayload.ALLOWED_MIME_TYPES }
        val directText = item.text?.toString()?.takeIf { it.isNotBlank() }
        val uri = item.uri
        var plan = ClipboardRules.plan(directText, uri?.scheme?.lowercase(), uri != null, imageMime)
        if (plan == ClipboardRules.Plan.Image) {
            ImageClipboard.readUri(context, uri!!, imageMime)?.let { return LocalClipboardContent.Image(it) }
            plan = ClipboardRules.afterImageFailure(directText)
        }
        return when (plan) {
            ClipboardRules.Plan.DirectText -> LocalClipboardContent.Text(directText!!)
            ClipboardRules.Plan.WebUri -> LocalClipboardContent.Text(uri.toString())
            ClipboardRules.Plan.Coerce -> runCatching { item.coerceToText(context)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let(LocalClipboardContent::Text) ?: LocalClipboardContent.Unsupported
            else -> LocalClipboardContent.Unsupported
        }
    }
}
