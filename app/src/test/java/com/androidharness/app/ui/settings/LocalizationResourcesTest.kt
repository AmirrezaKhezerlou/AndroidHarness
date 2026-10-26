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
    @Test fun russianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-ru").keys)
    }

    @Test fun russianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val russian = strings("values-ru")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Russian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val translated = russian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { "d" -> 21; "f" -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("ru"), translated, *args)
            }
        }
    }

    @Test fun russianPluralsCoverEveryCountResourceAndRussianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val russian = plurals("values-ru")
        assertEquals(english.keys, russian.keys)
        russian.forEach { (name, forms) ->
            assertEquals(name, setOf("one", "few", "many", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("ru"), form, 21)
            }
        }
    }

    @Test fun indonesianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-in").keys)
    }

    @Test fun indonesianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val indonesian = strings("values-in")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Indonesian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val translated = indonesian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { "d" -> 21; "f" -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("id"), translated, *args)
            }
        }
    }

    @Test fun indonesianPluralsCoverEveryCountResourceAndIndonesianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val indonesian = plurals("values-in")
        assertEquals(english.keys, indonesian.keys)
        indonesian.forEach { (name, forms) ->
            assertEquals(name, setOf("other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("id"), form, 21)
            }
        }
    }

    @Test fun arabicCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-ar").keys)
    }

    @Test fun arabicTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val arabic = strings("values-ar")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Arabic supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val translated = arabic.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { "d" -> 21; "f" -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("ar"), translated, *args)
            }
        }
    }

    @Test fun arabicPluralsCoverEveryCountResourceAndArabicForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val arabic = plurals("values-ar")
        assertEquals(english.keys, arabic.keys)
        arabic.forEach { (name, forms) ->
            assertEquals(name, setOf("zero", "one", "two", "few", "many", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("ar"), form, 21)
            }
        }
    }

    @Test fun persianCoversEveryTranslatableUiString() {
        assertEquals(strings("values").keys, strings("values-fa").keys)
    }

    @Test fun persianTemplatesPreserveValuesWithoutEnglishGrammarSuffixes() {
        val english = strings("values")
        val persian = strings("values-fa")
        // These arguments contain English words (file/files, reference/references) or the suffix s.
        // Persian supplies its own grammar; every count and user value is still required.
        val grammarOnly = mapOf("ui_in" to setOf(2, 4), "ui_use_workspace" to setOf(2),
            "ui_updated" to setOf(2), "ui_replace_in" to setOf(2),
            "ui_renamed_in_review_the_open_drafts_and" to setOf(2), "ui_rename_in" to setOf(2))
        val argument = Regex("%(\\d+)\\$([sdf])")
        english.forEach { (name, format) ->
            val expected = argument.findAll(format).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val translated = persian.getValue(name)
            val actual = argument.findAll(translated).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals("Format arguments in $name", expected.filterKeys { it !in grammarOnly[name].orEmpty() }, actual)
            if (expected.isNotEmpty()) {
                val args = Array<Any>(expected.keys.max()) { index ->
                    when (expected[index + 1]) { "d" -> 21; "f" -> 2.5; else -> "sample" }
                }
                String.format(Locale.forLanguageTag("fa"), translated, *args)
            }
        }
    }

    @Test fun persianPluralsCoverEveryCountResourceAndPersianForms() {
        fun plurals(locale: String): Map<String, Map<String, String>> = buildMap {
            File("src/main/res/$locale").listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("plurals")
                for (index in 0 until nodes.length) {
                    val node = nodes.item(index) as Element
                    val items = node.getElementsByTagName("item")
                    put(node.getAttribute("name"), (0 until items.length).associate { item ->
                        val entry = items.item(item) as Element
                        entry.getAttribute("quantity") to entry.textContent
                    })
                }
            }
        }
        val english = plurals("values")
        val persian = plurals("values-fa")
        assertEquals(english.keys, persian.keys)
        persian.forEach { (name, forms) ->
            assertEquals(name, setOf("one", "other"), forms.keys)
            forms.values.forEach { form ->
                assertTrue(name, form.contains("%1\$d"))
                String.format(Locale.forLanguageTag("fa"), form, 21)
            }
        }
    }

    @Test fun androidLanguageListDeclaresAllSupportedLanguages() {
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/locales_config.xml")).getElementsByTagName("locale")
        val tags = (0 until locales.length).map { (locales.item(it) as Element).getAttribute("android:name") }
        assertTrue(tags.containsAll(listOf("en", "zh-CN", "ru", "ar", "fa", "id")))
    }

}
