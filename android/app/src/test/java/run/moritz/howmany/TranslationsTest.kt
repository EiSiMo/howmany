package run.moritz.howmany

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every language the app knows translates every text, rather than falling back to English. */
class TranslationsTest {
    private val resources = File("src/main/res")

    /** Texts that rightly read the same as in English, by language. */
    private val sameAsDefault = mapOf("de" to setOf("version"))

    @Test
    fun everyLanguageTranslatesEveryText() {
        val default = texts(resources.resolve("values/strings.xml"))
        val translations =
            resources
                .listFiles { dir -> dir.name.startsWith("values-") }!!
                .map { it.resolve("strings.xml") }
                .filter { it.exists() }
        assertTrue("no translations found in $resources", translations.isNotEmpty())

        val problems = translations.flatMap { file ->
            val language = file.parentFile.name.removePrefix("values-")
            val translated = texts(file)
            val allowed = sameAsDefault[language].orEmpty()
            default.mapNotNull { (name, text) ->
                when {
                    name !in translated -> "$language: $name is missing"
                    translated[name] == text && name !in allowed ->
                        "$language: $name is not translated"
                    else -> null
                }
            }
        }
        assertEquals("", problems.joinToString("\n"))
    }

    /** The translatable strings and plurals in [file], by name. */
    private fun texts(file: File): Map<String, String> {
        val root =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val elements =
            root.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
        return elements
            .filterIsInstance<Element>()
            .filter { it.tagName in setOf("string", "plurals") }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }
}
