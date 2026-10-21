package com.androidharness.app.ui.settings

import com.androidharness.app.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Resolve the shipped resources without depending on Android's stubbed JVM Context. */
internal fun settingsTestStrings(locale: String = "values"): (Int) -> String {
    val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/$locale/strings.xml"))
    val strings = document.getElementsByTagName("string")
    val byName = (0 until strings.length).associate { index ->
        val element = strings.item(index) as org.w3c.dom.Element
        element.getAttribute("name") to element.textContent.replace("\\'", "'")
    }
    val byId = R.string::class.java.fields.mapNotNull { field ->
        byName[field.name]?.let { field.getInt(null) to it }
    }.toMap()
    return { id -> requireNotNull(byId[id]) { "Missing string resource $id in $locale" } }
}
