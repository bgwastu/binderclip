package net.wastu.binderclip

import android.content.ClipboardManager
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
        val original = context.resources.openRawResource(R.mipmap.ic_launcher_foreground).use { ImagePayload(mimeType = "image/png", data = it.readBytes()) }
        val clipboard = context.getSystemService(ClipboardManager::class.java)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ImageClipboard.write(activity, clipboard, original)
                val restored = ImageClipboard.read(activity, clipboard)

                assertNotNull(restored)
                assertEquals(original.sha256, restored?.sha256)
                assertEquals("image/png", restored?.mimeType)
            }
        }
    }
}
