package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * A titled card.
 *
 * Book Detail is almost entirely panels — description, up-next, files, details, tags, readers —
 * so this is the layout unit of the overview tab.
 *
 * [flush] drops the body padding, which is what a panel wrapping a [DataTable] needs: the table
 * draws its own edges, and padding around it would leave the rows floating inside a border.
 *
 * [spokenCount] is what a count drawn in [trailing] means, said inside the heading for a screen
 * reader only ("Needs a match, 3 books"); the drawn count is then hidden from it. A bare "3" read
 * after the heading says nothing about what was counted.
 */
@Composable
fun Panel(
    title: String? = null,
    flush: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    spokenCount: String? = null,
    content: @Composable () -> Unit,
) {
    Section(attrs = {
        style {
            property("background", "var(--surface)")
            property("border", "1px solid var(--line)")
            property("border-radius", "var(--rad)")
            property("box-shadow", "var(--shadow-card)")
            // `clip`, not `hidden`: it clips the same, but does not make the panel a scroll
            // container — so a sticky heading inside one (a series page's group headings) sticks
            // to the page as it scrolls, rather than to a box that never scrolls.
            property("overflow", "clip")
        }
    }) {
        if (title != null) {
            Header(attrs = {
                style {
                    property("display", "flex")
                    property("align-items", "center")
                    property("gap", "12px")
                    property("padding", "0 16px")
                    property("height", "46px")
                    property("border-bottom", "1px solid var(--line-2)")
                }
            }) {
                H2(attrs = {
                    style {
                        property("margin", "0")
                        property("font-size", "0.875rem")
                        property("font-weight", "700")
                        property("letter-spacing", "-0.01em")
                        property("color", "var(--ink)")
                    }
                }) {
                    Text(title)
                    spokenCount?.let { Span(attrs = { classes("sr-only") }) { Text(", $it") } }
                }
                Span(attrs = { style { property("flex", "1") } }) {}
                if (spokenCount != null && trailing != null) {
                    Span(attrs = { attr("aria-hidden", "true") }) { trailing() }
                } else {
                    trailing?.invoke()
                }
            }
        }
        Div(attrs = {
            style { property("padding", if (flush) "0" else "18px") }
        }) {
            // Anything headed inside a titled panel sits under its H2.
            if (title != null) UnderHeading(level = PANEL_HEADING_LEVEL, content = content) else content()
        }
    }
}

private const val PANEL_HEADING_LEVEL = 2
