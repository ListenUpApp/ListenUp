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
internal fun XmlElement.getElementsByTagName(tag: String): List<XmlElement> = buildList { collectByTag(tag, this) }

/**
 * Pre-order walk over an explicit stack rather than the call stack, so traversal depth costs heap
 * and never risks a `StackOverflowError` — an Error the sidecar parsers' `catch (Exception)` would
 * not contain. Children are pushed in reverse so the first is popped first: pre-order with
 * left-to-right siblings *is* document order, which [getElementsByTagName] promises and
 * [firstText] depends on.
 */
private fun XmlElement.collectByTag(
    tag: String,
    out: MutableList<XmlElement>,
) {
    val pending = ArrayDeque<XmlElement>()
    pushElementChildrenReversed(pending)
    while (pending.isNotEmpty()) {
        val element = pending.removeLast()
        if (element.tag == tag) out.add(element)
        element.pushElementChildrenReversed(pending)
    }
}

private fun XmlElement.pushElementChildrenReversed(pending: ArrayDeque<XmlElement>) {
    for (index in children.indices.reversed()) {
        val child = children[index]
        if (child is XmlElement) pending.addLast(child)
    }
}

/** Concatenated text of every descendant text node (DOM `textContent`). */
internal val XmlElement.textContent: String
    get() = buildString { appendDescendantText(this) }

/** Same explicit-stack pre-order walk as [collectByTag], so the text arrives in document order. */
private fun XmlElement.appendDescendantText(sb: StringBuilder) {
    val pending = ArrayDeque<XmlNode>()
    pushChildrenReversed(pending)
    while (pending.isNotEmpty()) {
        when (val node = pending.removeLast()) {
            is XmlText -> sb.append(node.value)
            is XmlElement -> node.pushChildrenReversed(pending)
        }
    }
}

private fun XmlElement.pushChildrenReversed(pending: ArrayDeque<XmlNode>) {
    for (index in children.indices.reversed()) pending.addLast(children[index])
}

/** Attribute value for [name], or `""` when absent (matches DOM `getAttribute`). */
internal fun XmlElement.getAttribute(name: String): String = attributes[name] ?: ""

/** Concatenated text of the DIRECT text-node children only (not nested elements). */
internal fun XmlElement.directText(): String = children.filterIsInstance<XmlText>().joinToString("") { it.value }

/** Trimmed text of the first `<tag>` descendant, or null when absent or blank. */
internal fun XmlElement.firstText(tag: String): String? =
    getElementsByTagName(tag)
        .firstOrNull()
        ?.textContent
        ?.trim()
        ?.ifBlank { null }

/** Trimmed text of every `<tag>` descendant, blanks dropped. */
internal fun XmlElement.allText(tag: String): List<String> =
    getElementsByTagName(tag).mapNotNull { it.textContent.trim().ifBlank { null } }
