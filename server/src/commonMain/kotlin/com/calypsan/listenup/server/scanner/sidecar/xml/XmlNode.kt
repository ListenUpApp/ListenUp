package com.calypsan.listenup.server.scanner.sidecar.xml

/** A node in a parsed XML document — either an [XmlElement] or a run of [XmlText]. */
internal sealed interface XmlNode

/** A run of character data (element text or CDATA), already entity-decoded. */
internal class XmlText(
    val value: String,
) : XmlNode

/** An XML element: its (literal, prefix-included) [tag], its [attributes], and ordered [children]. */
internal class XmlElement(
    val tag: String,
    val attributes: Map<String, String>,
    val children: List<XmlNode>,
) : XmlNode

/** All descendant elements named [tag], in document order (excludes the receiver). DOM `getElementsByTagName`. */
internal fun XmlElement.getElementsByTagName(tag: String): List<XmlElement> =
    buildList { collectByTag(tag, this) }

private fun XmlElement.collectByTag(
    tag: String,
    out: MutableList<XmlElement>,
) {
    for (child in children) {
        if (child is XmlElement) {
            if (child.tag == tag) out.add(child)
            child.collectByTag(tag, out)
        }
    }
}

/** Concatenated text of every descendant text node (DOM `textContent`). */
internal val XmlElement.textContent: String
    get() = buildString { appendDescendantText(this) }

private fun XmlElement.appendDescendantText(sb: StringBuilder) {
    for (child in children) {
        when (child) {
            is XmlText -> sb.append(child.value)
            is XmlElement -> child.appendDescendantText(sb)
        }
    }
}

/** Attribute value for [name], or `""` when absent (matches DOM `getAttribute`). */
internal fun XmlElement.getAttribute(name: String): String = attributes[name] ?: ""

/** Concatenated text of the DIRECT text-node children only (not nested elements). */
internal fun XmlElement.directText(): String =
    children.filterIsInstance<XmlText>().joinToString("") { it.value }

/** Trimmed text of the first `<tag>` descendant, or null when absent or blank. */
internal fun XmlElement.firstText(tag: String): String? =
    getElementsByTagName(tag).firstOrNull()?.textContent?.trim()?.ifBlank { null }

/** Trimmed text of every `<tag>` descendant, blanks dropped. */
internal fun XmlElement.allText(tag: String): List<String> =
    getElementsByTagName(tag).mapNotNull { it.textContent.trim().ifBlank { null } }
