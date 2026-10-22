package vibe.tube.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringsParityTest {

    private fun keys(path: String, tag: String): Set<String> {
        val text = File(path).readText()
        return Regex("<$tag name=\"([a-z0-9_]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
    }

    private val en = "src/main/res/values/strings.xml"
    private val vi = "src/main/res/values-vi/strings.xml"

    @Test
    fun englishAndVietnameseDefineTheSameStringsAndPlurals() {
        val enStrings = keys(en, "string") - "app_name"
        val viStrings = keys(vi, "string")
        assertEquals(enStrings, viStrings)
        assertEquals(keys(en, "plurals"), keys(vi, "plurals"))
        assertTrue(enStrings.size > 250)
    }

    @Test
    fun placeholdersMatchBetweenLanguages() {
        fun placeholders(path: String) = Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>").findAll(File(path).readText())
            .associate { it.groupValues[1] to Regex("%\\d\\$[sd]").findAll(it.groupValues[2]).map { m -> m.value }.sorted().toList() }
        val a = placeholders(en)
        val b = placeholders(vi)
        a.forEach { (k, v) -> if (k != "app_name") assertEquals("placeholders of $k", v, b[k]) }
    }

    @Test
    fun everyReferencedStringExists() {
        val defined = keys(en, "string")
        val used = File("src/main").walkTopDown().filter { it.extension == "kt" }
            .flatMap { Regex("R\\.string\\.([a-z0-9_]+)").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertEquals(emptySet<String>(), used - defined)
    }
}
