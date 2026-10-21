package com.androidharness.app.ui.settings

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {
    private fun strings(locale: String): Map<String, String> = buildMap {
        File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as Element
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                assertFalse("Duplicate resource $name", containsKey(name))
                put(name, node.textContent)
            }
        }
    }

    @Test fun chineseCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-zh-rCN").keys)
    }

    @Test fun translatedTemplatesKeepValidArgumentTypesAndFormatSuccessfully() {
        val english = strings("values")
        val chinese = strings("values-zh-rCN")
        val argument = Regex("%(\\d+)\\$([sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val translated = chinese.getValue(name)
            argument.findAll(translated).forEach {
                assertEquals("Unexpected format argument in $name", expected[it.groupValues[1].toInt()], it.groupValues[2])
            }
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { "d" -> 2; "f" -> 2.5; else -> "sample" }
                }
                String.format(Locale.ENGLISH, format, *args)
                String.format(Locale.SIMPLIFIED_CHINESE, translated, *args)
            }
        }
    }
}
