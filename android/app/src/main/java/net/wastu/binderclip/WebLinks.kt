package net.wastu.binderclip

import java.net.URI

object WebLinks {
    fun isWebUrl(value: String): Boolean {
        if (value.isEmpty() || value.any(Char::isWhitespace)) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }
}
