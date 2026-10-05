package net.wastu.binderclip

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageClipboardDeviceTest {
    @Test fun writesAnImageUriThatTheAppCanReadBack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val original = ImagePayload(mimeType = "image/png", data = bytes)
        val clipboard = AppClipboard(context)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previous = clipboard.primaryClip
                val previousImage = ImageClipboard.read(activity, clipboard)
                try {
                    ImageClipboard.write(activity, clipboard, original)
                    val restored = ImageClipboard.read(activity, clipboard)
                    assertNotNull(restored)
                    assertEquals(original.sha256, restored?.sha256)
                    assertEquals("image/png", restored?.mimeType)
                } finally {
                    if (previousImage != null) ImageClipboard.write(activity, clipboard, previousImage)
                    else if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
                }
            }
        }
    }
}
