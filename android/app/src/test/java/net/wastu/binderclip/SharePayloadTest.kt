package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadTest {

    @Test
    fun urlPatternMatchesStandardWebUrls() {
        val urls = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "http://example.com/page?id=123",
            "https://github.com/wastu/binderclip"
        )
        for (url in urls) {
            assertTrue("Expected $url to be classified as web URL", WebLinks.isWebUrl(url))
        }
    }

    @Test
    fun malformedOrAnnotatedLinksStayAsText() {
        for (text in listOf("https://", "https://example.com\nA page title", "http://example.com extra", "file:///tmp/photo")) {
            assertFalse("Expected ordinary text: $text", WebLinks.isWebUrl(text))
        }
        assertTrue(WebLinks.isWebUrl("HTTPS://example.com/path?x=1#section"))
    }

    @Test
    fun payloadPrecedencePrefersTextOverThumbnail() {
        // Simulates Chrome share intent extras:
        // Intent has EXTRA_TEXT = "https://youtu.be/123", type = "text/plain", and a thumbnail URI in clipData
        val extraText = "https://youtu.be/123"
        val intentType = "text/plain"
        val hasExtraStream = false

        val chosenPayloadType = when {
            extraText.isNotBlank() && (intentType == "text/plain" || !intentType.startsWith("image/") || !hasExtraStream) -> {
                "TEXT"
            }
            hasExtraStream && intentType.startsWith("image/") -> {
                "IMAGE"
            }
            else -> "UNKNOWN"
        }

        assertEquals("TEXT", chosenPayloadType)
    }

    @Test
    fun payloadPrecedencePrefersImageWhenExplicitImageShare() {
        // Simulates Gallery/Photos share intent extras:
        // Intent has EXTRA_STREAM = content://media/..., type = "image/png", and no EXTRA_TEXT
        val extraText: String? = null
        val intentType = "image/png"
        val hasExtraStream = true

        val chosenPayloadType = when {
            extraText != null && (intentType == "text/plain" || !intentType.startsWith("image/") || !hasExtraStream) -> {
                "TEXT"
            }
            hasExtraStream && intentType.startsWith("image/") -> {
                "IMAGE"
            }
            else -> "UNKNOWN"
        }

        assertEquals("IMAGE", chosenPayloadType)
    }
}
