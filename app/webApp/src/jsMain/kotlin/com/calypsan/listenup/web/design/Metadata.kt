package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * One row of the details list.
 *
 * [machine] marks values the server produced rather than a person: durations, byte sizes, codecs,
 * ASINs, filesystem paths. Those take the mono face — the same "attributable, not uniform" signal
 * the rest of the app uses, and the reason a column of sizes is scannable.
 */
class MetaEntry(
    val label: String,
    val value: String,
    val machine: Boolean = false,
)

/**
 * The description list behind Book Detail's "Details" panel.
 *
 * A real `<dl>` rather than a stack of divs: these are term/definition pairs, and screen readers
 * announce them as such.
 */
@Composable
fun MetaList(entries: List<MetaEntry>) {
    Dl(attrs = { style { property("margin", "0") } }) {
        entries.forEach { entry ->
            Div(attrs = { classes("meta-r") }) {
                Dt { Text(entry.label) }
                Dd(attrs = { if (entry.machine) classes("mono") }) { Text(entry.value) }
            }
        }
    }
}

/**
 * The breadcrumb trail.
 *
 * The last entry is the current page and is not a link — making it one invites a click that goes
 * nowhere, which is the small lie breadcrumbs usually tell.
 */
@Composable
fun Breadcrumb(
    trail: List<String>,
    onNavigate: ((Int) -> Unit)? = null,
) {
    Div(attrs = { classes("crumb") }) {
        trail.forEachIndexed { index, entry ->
            if (index > 0) {
                Span(attrs = { style { property("opacity", "0.5") } }) { Text("/") }
            }
            if (index == trail.lastIndex) {
                Span(attrs = { classes("cur") }) { Text(entry) }
            } else {
                A(attrs = { onNavigate?.let { navigate -> onClick { navigate(index) } } }) { Text(entry) }
            }
        }
    }
}
