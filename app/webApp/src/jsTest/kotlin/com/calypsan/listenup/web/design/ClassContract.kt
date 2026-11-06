package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.features.match.failed
import io.kotest.assertions.fail
import kotlinx.browser.document
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.css.CSSStyleSheet

internal val CLASS_SELECTOR = Regex("\\.([A-Za-z][A-Za-z0-9_-]*)")

// Every class selector defined anywhere in the loaded sheets, including inside `@media`
// and `@supports` blocks.
//
// Recursion is not a nicety: a grouping rule carries no `selectorText` of its own, so a
// pass that reads only top-level rules skips its whole body. A class styled *only* under
// a media query then looked undefined, and the page rendering it failed this contract —
// a false alarm whose obvious "fix" is to restructure correct CSS until the test stops
// complaining. `.sb-foot` is such a class today; the spec below pins it.
internal fun definedClasses(): Set<String> {
    val defined = mutableSetOf<String>()

    fun collect(rules: dynamic) {
        val length = rules.length as? Int ?: return
        for (j in 0 until length) {
            val rule = rules.item(j)
            val selector = rule?.selectorText as? String
            if (selector != null) {
                CLASS_SELECTOR.findAll(selector).forEach { defined += it.groupValues[1] }
            } else {
                // @media / @supports and friends: the selectors live one level down.
                val nested = rule?.cssRules
                if (nested != null) collect(nested)
            }
        }
    }

    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
        collect(rules.asDynamic())
    }
    return defined
}

internal fun MountRegistry.classesUsedIn(content: @Composable () -> Unit): Set<String> {
    val host = mount { content() }

    // A modal <dialog> holds focus for the whole document and makes everything behind it
    // inert. Left open, it leaks that state into every spec that runs afterwards — which
    // it did, taking the command palette's focus tests with it. Closing leaves the element
    // and its classes exactly where they are, which is all this contract reads.
    val dialogs = host.querySelectorAll("dialog")
    for (i in 0 until dialogs.length) {
        (dialogs.item(i) as? HTMLDialogElement)?.takeIf { it.open }?.close()
    }

    val used = mutableSetOf<String>()
    val all = host.querySelectorAll("*")
    for (i in 0 until all.length) {
        val element = all.item(i) as? HTMLElement ?: continue
        val list = element.classList
        for (j in 0 until list.length) {
            list.item(j)?.let { used += it }
        }
    }
    return used
}

/** One named composable shape: the name is what a failing contract reports. */
internal fun shape(
    name: String,
    content: @Composable () -> Unit,
): Pair<String, @Composable () -> Unit> = name to content

/** A feature's shapes by name. A repeated name would silently drop a shape, so it is an error. */
internal fun shapes(vararg entries: Pair<String, @Composable () -> Unit>): Map<String, @Composable () -> Unit> {
    val byName = entries.toMap()
    require(byName.size == entries.size) { "two shapes share a name" }
    return byName
}

/**
 * Mounts each of [shapes] on its own and asserts every class it renders is defined in the loaded design sheet.
 * Every failing shape is reported as `<shape name>: <undefined classes>`, not just the first.
 */
internal suspend fun assertClassesDefined(shapes: Map<String, @Composable () -> Unit>) {
    val defined = definedClasses()
    val mounts = MountRegistry()
    val failures = mutableListOf<String>()
    try {
        for ((name, content) in shapes) {
            val undefined = mounts.classesUsedIn { WebAppSurface { content() } } - defined
            if (undefined.isNotEmpty()) failures += "$name: ${undefined.sorted()}"
            mounts.disposeAll()
        }
    } finally {
        mounts.disposeAll()
    }
    if (failures.isNotEmpty()) {
        fail("Classes rendered but not defined in the design sheet:\n" + failures.joinToString("\n"))
    }
}
