package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourceParityTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val locales = listOf("id", "es", "zh", "ja", "de", "fr", "pt", "ru")
    private val placeholder = Regex("%(\\d+\\$)?[sd]")

    /** Key → placeholders, for strings and the "other" form of plurals. */
    private fun load(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = mutableMapOf<String, List<String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
            out[node.attributes.getNamedItem("name").nodeValue] =
                placeholder.findAll(node.textContent).map { it.value }.sorted().toList()
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) out["plurals/" + plurals.item(i).attributes.getNamedItem("name").nodeValue] = emptyList()
        return out
    }

    @Test
    fun everyLocaleHasTheSameKeysAndPlaceholders() {
        val english = load("values")
        assertTrue(english.isNotEmpty())
        for (locale in locales) {
            val other = load("values-$locale")
            assertEquals("values-$locale keys differ", english.keys.sorted(), other.keys.sorted())
            for ((key, args) in english) assertEquals("values-$locale $key placeholders", args, other[key])
        }
    }

    @Test
    fun noStaleLocaleFolders() {
        val folders = res.listFiles().orEmpty().map { it.name }
            .filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }.sorted()
        assertEquals(locales.map { "values-$it" }.sorted(), folders)
    }
}
