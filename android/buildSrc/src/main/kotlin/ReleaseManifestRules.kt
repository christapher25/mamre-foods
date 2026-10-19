package com.mamre.build

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Doc 2 s6 (review item 8): the release app opens ONE door, the launcher. The MERGED release manifest must hold no activity but
 * the launcher and no exported provider, service or receiver except the known library entries in [allowed]. A component with an
 * intent filter and no exported attribute counts as exported. Pure: it reads manifest TEXT, so it is tested on samples; the Gradle
 * task verifyReleaseComponents feeds it the merged manifest and fails the build on any problem.
 */
object ReleaseManifestRules {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** One line per door that should not be open; empty when the manifest is as it should be. */
    fun problems(manifestXml: String, allowed: Set<String>): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(manifestXml.byteInputStream())
        val app = document.getElementsByTagName("application").item(0) as Element

        fun children(tag: String, of: Element = app): List<Element> =
            (0 until of.getElementsByTagName(tag).length).map { of.getElementsByTagName(tag).item(it) as Element }
        fun Element.nameAttr() = getAttributeNS(ANDROID_NS, "name")
        fun Element.isLauncher() = children("intent-filter", this).any { f ->
            fun has(tag: String, v: String) = children(tag, f).any { it.nameAttr() == v }
            has("action", "android.intent.action.MAIN") && has("category", "android.intent.category.LAUNCHER")
        }
        fun Element.isExported() = when (getAttributeNS(ANDROID_NS, "exported")) {
            "true" -> true
            "false" -> false
            else -> getElementsByTagName("intent-filter").length > 0
        }

        val problems = mutableListOf<String>()
        for (tag in listOf("activity", "activity-alias")) {
            children(tag).filterNot { it.isLauncher() }.forEach { problems += "$tag ${it.nameAttr()} is not the launcher" }
        }
        val launchers = children("activity").count { it.isLauncher() } + children("activity-alias").count { it.isLauncher() }
        if (launchers != 1) problems += "expected exactly one launcher activity, found $launchers"
        for (tag in listOf("provider", "service", "receiver")) {
            children(tag).filter { it.isExported() && it.nameAttr() !in allowed }
                .forEach { problems += "exported $tag ${it.nameAttr()} is not on the allowlist" }
        }
        return problems
    }
}
