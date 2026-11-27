package net.wastu.binderclip

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun selectsPublishedAndroidApkMatchingItsTag() {
        val releases = JSONArray(
            """
            [
              {"tag_name":"macos-v1.24.0","assets":[]},
              {"tag_name":"v1.23.0","assets":[
                {"name":"BinderClip-1.22.2.apk","state":"uploaded","browser_download_url":"https://example.com/old.apk"},
                {"name":"BinderClip-1.23.0.apk","state":"starter","browser_download_url":"https://example.com/incomplete.apk"}
              ]},
              {"tag_name":"android-v1.22.2","body":" Android update ","assets":[
                {"name":"BinderClip-1.22.2.apk","state":"uploaded","browser_download_url":"https://example.com/android.apk"}
              ]}
            ]
            """.trimIndent()
        )

        assertEquals(
            AppRelease("1.22.2", "https://example.com/android.apk", "Android update", "BinderClip-1.22.2.apk"),
            findAndroidRelease(releases),
        )
    }

    @Test
    fun ignoresDraftAndPrerelease() {
        val releases = JSONArray(
            """
            [
              {"tag_name":"v1.23.0","draft":true,"assets":[
                {"name":"BinderClip-1.23.0.apk","state":"uploaded","browser_download_url":"https://example.com/draft.apk"}
              ]},
              {"tag_name":"v1.22.2","prerelease":true,"assets":[
                {"name":"BinderClip-1.22.2.apk","state":"uploaded","browser_download_url":"https://example.com/preview.apk"}
              ]}
            ]
            """.trimIndent()
        )

        assertNull(findAndroidRelease(releases))
    }
}
